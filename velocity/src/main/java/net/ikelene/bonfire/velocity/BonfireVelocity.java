package net.ikelene.bonfire.velocity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import net.ikelene.bonfire.core.BonfireCore;
import net.ikelene.bonfire.core.ChatFormatter;
import net.ikelene.bonfire.core.CommandResult;
import net.ikelene.bonfire.core.Commands;
import net.ikelene.bonfire.core.Log;
import net.ikelene.bonfire.core.Mode;
import net.ikelene.bonfire.core.Platform;
import net.ikelene.bonfire.core.PluginMessageCodec;
import net.ikelene.bonfire.core.Prefixes;
import net.ikelene.bonfire.core.Segment;
import net.ikelene.bonfire.core.Segments;
import net.ikelene.bonfire.core.StatusSnapshot;
import net.ikelene.bonfire.core.Text;
import net.ikelene.bonfire.core.adventure.AdventureSegments;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Plugin(id = "bonfire-link", name = "Ember Link", version = BuildInfo.VERSION,
        description = "Connects this Velocity network to Ember (Velora or a self-hosted Ember instance).",
        url = "https://velora.ikelene.net/chat", authors = {"Ikelene"},
        dependencies = {@Dependency(id = "luckperms", optional = true), @Dependency(id = "floodgate", optional = true)})
public final class BonfireVelocity implements Platform {
    static final MinecraftChannelIdentifier EVENTS = MinecraftChannelIdentifier.create("bonfire", "events");
    static final MinecraftChannelIdentifier HELLO = MinecraftChannelIdentifier.create("bonfire", "hello");

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDir;
    private final BonfireCore core;
    private final Map<UUID, PlayerState> players = new ConcurrentHashMap<>();
    private final Map<String, BackendState> backends = new ConcurrentHashMap<>();
    private final Log log;
    private volatile byte[] helloBytes;

    static final class PlayerState {
        final UUID uuid;
        final String name;
        volatile String server;
        volatile boolean vanished;
        volatile String prefix;
        volatile boolean presenceSeen;
        volatile boolean joinSettled;
        volatile boolean joinAnnounced;
        volatile String pendingFrom;
        volatile int connectSeq;

        PlayerState(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }
    }

    static final class BackendState {
        volatile boolean online = true;
        volatile boolean known;
        volatile int failures;
        volatile String version;
        volatile List<Segment> motd;
        volatile int max;
    }

    @Inject
    public BonfireVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDir = dataDir;
        this.log = new Log() {
            @Override
            public void info(String msg) {
                logger.info(strip(msg));
            }

            @Override
            public void warn(String msg) {
                logger.warn(strip(msg));
            }

            @Override
            public void error(String msg, Throwable t) {
                if (t == null) logger.error(strip(msg));
                else logger.error(strip(msg), t);
            }

            private String strip(String m) {
                return m.startsWith("[Ember Link] ") ? m.substring(15) : m;
            }
        };
        this.core = new BonfireCore(this);
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent e) {
        core.init();
        helloBytes = buildHello();

        proxy.getChannelRegistrar().register(EVENTS, HELLO);
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("bonfire").aliases("ember").plugin(this).build(), new BonfireCommand());
        proxy.getScheduler().buildTask(this, this::pingBackends).delay(5, TimeUnit.SECONDS).repeat(30, TimeUnit.SECONDS).schedule();

        if (proxy.getPluginManager().getPlugin("simpleproxychat").isPresent()) {
            log.warn("[Ember Link] SimpleProxyChat is installed too. Ember Link replaces it (cross-server chat + network messages); remove one of them to avoid duplicate messages.");
        }
        core.start();
        core.serverEvent("server_start", null);
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent e) {
        core.stop();
    }

    private String backendName(String raw) {
        return core.config().serverDisplayName(raw);
    }

    private static String serverOf(Player p) {
        return p.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse(null);
    }

    boolean hidden(Player p) {
        PlayerState st = players.get(p.getUniqueId());
        if (st != null && st.vanished) return true;
        if (core.config().isAlwaysHidden(p.getUniqueId(), p.getUsername())) return true;
        try {
            return p.getPermissionValue("bonfire.hidden") == Tristate.TRUE;
        } catch (Throwable t) {
            return false;
        }
    }

    private String prefixOf(Player p) {
        if (!core.config().useLuckPermsPrefix()) return null;
        if (proxy.getPluginManager().getPlugin("luckperms").isPresent()) {
            String lp = Prefixes.luckPermsPrefix(p.getUniqueId());
            if (lp != null) return lp;
        }
        PlayerState st = players.get(p.getUniqueId());
        return st != null ? st.prefix : null;
    }

    private String displayOf(Player p) {
        return Prefixes.display(prefixOf(p), p.getUsername());
    }

    private boolean isBedrock(UUID uuid) {
        if (uuid.toString().startsWith("00000000-0000-0000-")) return true;
        if (!proxy.getPluginManager().getPlugin("floodgate").isPresent()) return false;
        try {
            Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object inst = api.getMethod("getInstance").invoke(null);
            return (Boolean) api.getMethod("isFloodgatePlayer", UUID.class).invoke(inst, uuid);
        } catch (Throwable t) {
            return false;
        }
    }

    private void sendToPlayers(Component c, String skipServer) {
        for (Player p : proxy.getAllPlayers()) {
            String s = serverOf(p);
            if (s != null && core.config().isExcludedServer(s)) continue;
            if (skipServer != null && skipServer.equals(s)) continue;
            p.sendMessage(c);
        }
    }

    private void networkMessage(String text) {
        if (!core.config().networkMessages()) return;
        sendToPlayers(Component.text(text, NamedTextColor.YELLOW), null);
    }

    @Subscribe(order = PostOrder.LAST)
    public void onChat(PlayerChatEvent e) {
        Player p = e.getPlayer();
        String raw = serverOf(p);
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty()) return;
        core.chat(p.getUniqueId(), p.getUsername(), displayOf(p), msg, raw == null ? null : backendName(raw), isBedrock(p.getUniqueId()));

        if (raw == null || !core.config().crossServerChat() || core.config().isExcludedServer(raw)) return;
        List<Segment> line = ChatFormatter.fill(core.config().crossServerFormat(), Map.of(
                "{server}", List.of(new Segment(backendName(raw))),
                "{name}", List.of(new Segment(p.getUsername())),
                "{message}", List.of(new Segment(Text.stripSection(msg)))));
        sendToPlayers(AdventureSegments.toComponent(line), raw);
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent e) {
        Player p = e.getPlayer();
        players.put(p.getUniqueId(), new PlayerState(p.getUniqueId(), p.getUsername()));
        try {
            for (com.velocitypowered.api.util.GameProfile.Property prop : p.getGameProfileProperties()) {
                if ("textures".equals(prop.getName())) {
                    core.noteSkin(p.getUniqueId(), net.ikelene.bonfire.core.Skins.fromTexturesProperty(prop.getValue()));
                    break;
                }
            }
        } catch (Throwable t) {
        }
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent e) {
        Player p = e.getPlayer();
        PlayerState st = players.computeIfAbsent(p.getUniqueId(), u -> new PlayerState(u, p.getUsername()));
        RegisteredServer prev = e.getPreviousServer();
        String now = serverOf(p);
        if (prev != null && st.joinSettled) st.pendingFrom = prev.getServerInfo().getName();
        st.server = now;
        st.presenceSeen = false;
        int seq = ++st.connectSeq;
        Optional<ServerConnection> conn = p.getCurrentServer();
        helloBytes = buildHello();
        if (conn.isPresent() && helloBytes != null) {
            try {
                conn.get().sendPluginMessage(HELLO, helloBytes);
            } catch (Throwable t) {
                log.warn("[Ember Link] Could not send hello to " + now + ": " + t);
            }
        }
        proxy.getScheduler().buildTask(this, () -> {
            if (st.connectSeq == seq) settle(p, st);
        }).delay(3, TimeUnit.SECONDS).schedule();
        core.requestStatus();
    }

    private synchronized void settle(Player p, PlayerState st) {
        if (!p.isActive()) return;
        boolean hide = hidden(p);
        String server = st.server;
        if (!st.joinSettled) {
            st.joinSettled = true;
            if (!hide) {
                st.joinAnnounced = true;
                core.playerEvent("join", st.uuid, st.name, displayOf(p), server == null ? null : backendName(server), null, null, null);
                networkMessage(st.name + " joined the network");
            }
            core.requestStatus();
            return;
        }
        String from = st.pendingFrom;
        if (from != null) {
            st.pendingFrom = null;
            if (!hide && server != null && !from.equals(server)) {
                core.playerEvent("switch", st.uuid, st.name, displayOf(p), backendName(server), backendName(from), null, null);
                networkMessage(st.name + " moved to " + backendName(server));
            }
            core.requestStatus();
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent e) {
        Player p = e.getPlayer();
        PlayerState st = players.remove(p.getUniqueId());
        if (e.getLoginStatus() != DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN) {
            core.requestStatus();
            return;
        }
        boolean hide = (st != null && st.vanished) || core.config().isAlwaysHidden(p.getUniqueId(), p.getUsername());
        if (!hide) {
            try {
                hide = p.getPermissionValue("bonfire.hidden") == Tristate.TRUE;
            } catch (Throwable ignored) {
            }
        }
        if (st != null && st.joinAnnounced && !hide) {
            String server = st.server;
            core.playerEvent("leave", p.getUniqueId(), p.getUsername(), Prefixes.display(st.prefix, p.getUsername()), server == null ? null : backendName(server), null, null, null);
            networkMessage(p.getUsername() + " left the network");
        } else {
            core.requestStatus();
        }
    }

    private byte[] buildHello() {
        JsonObject hello = new JsonObject();
        hello.addProperty("v", 1);
        hello.addProperty("proxy", "bonfire-link");
        hello.addProperty("version", BuildInfo.VERSION);
        try {
            hello.addProperty("gateway", core.config().gatewayUrl());
        } catch (IllegalArgumentException ignored) {
        }
        return PluginMessageCodec.encode(BonfireCore.GSON.toJson(hello));
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onPluginMessage(PluginMessageEvent e) {
        String ns = e.getIdentifier().getId();
        if (!ns.startsWith("bonfire:")) return;
        e.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(e.getSource() instanceof ServerConnection)) return;
        if (!EVENTS.getId().equals(ns)) return;
        ServerConnection sc = (ServerConnection) e.getSource();
        String backend = sc.getServerInfo().getName();
        String json = PluginMessageCodec.decode(e.getData());
        if (json == null) return;
        JsonObject o;
        try {
            JsonElement el = JsonParser.parseString(json);
            if (!el.isJsonObject()) return;
            o = el.getAsJsonObject();
        } catch (Exception ex) {
            return;
        }
        String kind = str(o, "kind");
        UUID uuid;
        try {
            uuid = UUID.fromString(str(o, "uuid"));
        } catch (Exception ex) {
            return;
        }
        Optional<Player> op = proxy.getPlayer(uuid);
        if (op.isEmpty() || !backend.equals(serverOf(op.get()))) return;
        Player p = op.get();
        PlayerState st = players.get(uuid);
        if (st == null) return;
        switch (kind == null ? "" : kind) {
            case "presence": {
                boolean wasVanished = st.vanished;
                st.vanished = o.has("vanished") && o.get("vanished").getAsBoolean();
                JsonElement pe = o.get("prefix");
                st.prefix = pe != null && pe.isJsonPrimitive() ? Prefixes.toPlain(pe.getAsString()) : null;
                boolean first = !st.presenceSeen;
                st.presenceSeen = true;
                if (first) settle(p, st);
                else if (wasVanished != st.vanished) core.requestStatus();
                break;
            }
            case "death": {
                if (hidden(p)) return;
                core.playerEvent("death", uuid, p.getUsername(), displayOf(p), backendName(backend), null, str(o, "text"), null);
                break;
            }
            case "advancement": {
                if (hidden(p)) return;
                JsonElement a = o.get("advancement");
                if (a == null || !a.isJsonObject()) return;
                JsonObject ao = a.getAsJsonObject();
                core.playerEvent("advancement", uuid, p.getUsername(), displayOf(p), backendName(backend), null, null,
                        BonfireCore.advancementJson(str(ao, "id"), str(ao, "title"), str(ao, "description"), str(ao, "frame")));
                break;
            }
            default:
                break;
        }
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    @Subscribe(order = PostOrder.LAST)
    public void onCommand(CommandExecuteEvent e) {
        if (!e.getResult().isAllowed()) return;
        if (e.getCommandSource() instanceof ConsoleCommandSource) {
            core.localCommand("console", "CONSOLE", e.getCommand());
        }
    }

    private void pingBackends() {
        for (RegisteredServer rs : proxy.getAllServers()) {
            String name = rs.getServerInfo().getName();
            BackendState bs = backends.computeIfAbsent(name, n -> new BackendState());
            rs.ping().orTimeout(3, TimeUnit.SECONDS).whenComplete((ping, ex) -> {
                if (ex == null && ping != null) {
                    bs.failures = 0;
                    bs.version = ping.getVersion() != null ? ping.getVersion().getName() : null;
                    bs.motd = Segments.clampMotd(AdventureSegments.fromComponent(ping.getDescriptionComponent()));
                    bs.max = ping.getPlayers().map(ServerPing.Players::getMax).orElse(0);
                    if (!bs.online && bs.known) core.serverEvent("backend_up", backendName(name));
                    if (!bs.online || !bs.known) core.requestStatus();
                    bs.online = true;
                    bs.known = true;
                } else {
                    bs.failures++;
                    if (!bs.known && bs.failures >= 2) {
                        bs.known = true;
                        bs.online = false;
                        core.requestStatus();
                    } else if (bs.online && bs.known && bs.failures >= 2) {
                        bs.online = false;
                        core.serverEvent("backend_down", backendName(name));
                    }
                }
            });
        }
    }

    @Override
    public String platformId() {
        return "velocity";
    }

    @Override
    public String software() {
        return proxy.getVersion().getName() + " " + proxy.getVersion().getVersion();
    }

    @Override
    public String mcVersion() {
        return ProtocolVersion.MAXIMUM_VERSION.getMostRecentSupportedVersion();
    }

    @Override
    public String modVersion() {
        return BuildInfo.VERSION;
    }

    @Override
    public Path dataDir() {
        return dataDir;
    }

    @Override
    public Log log() {
        return log;
    }

    @Override
    public StatusSnapshot status() {
        StatusSnapshot s = new StatusSnapshot();
        s.version = proxy.getVersion().getName() + " " + proxy.getVersion().getVersion();
        s.motd = motd();
        s.players.max = maxPlayers();
        Map<String, Integer> perBackend = new java.util.HashMap<>();
        for (Player p : proxy.getAllPlayers()) {
            if (hidden(p)) continue;
            String srv = serverOf(p);
            s.players.online++;
            if (srv != null) perBackend.merge(srv, 1, Integer::sum);
            if (s.players.list.size() < 100) {
                s.players.list.add(new StatusSnapshot.PlayerEntry(p.getUniqueId().toString(), p.getUsername(), displayOf(p), srv == null ? null : backendName(srv)));
            }
        }
        s.backends = new ArrayList<>();
        for (RegisteredServer rs : proxy.getAllServers()) {
            String name = rs.getServerInfo().getName();
            BackendState bs = backends.get(name);
            StatusSnapshot.Backend b = new StatusSnapshot.Backend();
            b.name = Text.clamp(backendName(name), 40);
            b.online = bs != null && bs.known ? bs.online : !rs.getPlayersConnected().isEmpty();
            if (b.online) {
                b.version = bs != null ? bs.version : null;
                b.motd = bs != null ? bs.motd : null;
                StatusSnapshot.Players pl = new StatusSnapshot.Players();
                pl.list = null;
                pl.online = perBackend.getOrDefault(name, 0);
                pl.max = bs != null ? bs.max : 0;
                b.players = pl;
            }
            s.backends.add(b);
        }
        return s;
    }

    @Override
    public byte[] serverIconPng() {
        return BonfireCore.readIcon(Path.of("server-icon.png"));
    }

    @Override
    public List<Segment> motd() {
        return AdventureSegments.fromComponent(proxy.getConfiguration().getMotd());
    }

    @Override
    public int maxPlayers() {
        return proxy.getConfiguration().getShowMaxPlayers();
    }

    @Override
    public boolean onlineMode() {
        return proxy.getConfiguration().isOnlineMode();
    }

    @Override
    public void broadcast(List<List<Segment>> lines) {
        for (List<Segment> l : lines) {
            Component c = AdventureSegments.toComponent(l);
            sendToPlayers(c, null);
            logger.info(Segments.plain(l));
        }
    }

    @Override
    public CompletableFuture<CommandResult> runConsoleCommand(String cmd) {
        CaptureSource src = new CaptureSource();
        return proxy.getCommandManager().executeAsync(src, cmd).handle((found, ex) -> {
            if (ex != null) return CommandResult.fail("error", List.of(String.valueOf(ex)));
            List<String> out = src.lines();
            if (Boolean.FALSE.equals(found)) {
                return CommandResult.fail("unknown_command", out.isEmpty() ? List.of("Unknown proxy command. (Ember runs commands on the Velocity proxy console.)") : out);
            }
            return CommandResult.ok(out);
        });
    }

    @Override
    public void runSync(Runnable r) {
        r.run();
    }

    @Override
    public Mode mode() {
        return Mode.PROXY;
    }

    @Override
    public ClassLoader serverClassLoader() {
        return proxy.getClass().getClassLoader();
    }

    private final class BonfireCommand implements SimpleCommand {
        @Override
        public void execute(Invocation inv) {
            CommandSource src = inv.source();
            core.commands().execute(new Commands.Sender() {
                @Override
                public boolean isConsole() {
                    return src instanceof ConsoleCommandSource;
                }

                @Override
                public boolean mayStatus() {
                    return src.hasPermission("bonfire.status");
                }

                @Override
                public void send(String line) {
                    src.sendMessage(Component.text(line));
                }
            }, inv.arguments());
        }

        @Override
        public List<String> suggest(Invocation inv) {
            if (!(inv.source() instanceof ConsoleCommandSource)) return List.of("status");
            if (inv.arguments().length <= 1) return List.of("status", "server", "link", "unlink", "reload");
            return List.of();
        }
    }
}
