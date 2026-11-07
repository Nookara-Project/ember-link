package net.ikelene.bonfire.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.ikelene.bonfire.core.BackendBridge;
import net.ikelene.bonfire.core.BonfireCore;
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
import net.ikelene.bonfire.fabric.mixin.CommandSourceStackAccessor;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.rcon.RconConsoleSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.gamerules.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class BonfireFabric implements ModInitializer, Platform, BackendBridge.Hooks {
    private static final Logger LOGGER = LoggerFactory.getLogger("BonfireLink");
    private static volatile BonfireFabric INSTANCE;

    private volatile MinecraftServer server;
    private BonfireCore core;
    private Mode mode;
    private String version = "0";
    private boolean advancementFallback;
    private long ticks;
    private Method vanishMethod;
    private boolean vanishChecked;

    private final List<Object[]> pendingJoins = new ArrayList<>();
    private final Set<UUID> joinChecked = ConcurrentHashMap.newKeySet();

    private final Log log = new Log() {
        @Override
        public void info(String msg) {
            LOGGER.info(msg);
        }

        @Override
        public void warn(String msg) {
            LOGGER.warn(msg);
        }

        @Override
        public void error(String msg, Throwable t) {
            if (t == null) LOGGER.error(msg);
            else LOGGER.error(msg, t);
        }
    };

    @Override
    public void onInitialize() {
        INSTANCE = this;
        version = FabricLoader.getInstance().getModContainer("bonfire-link").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("0");
        advancementFallback = !AdvancementHookMarker.class.isAssignableFrom(PlayerAdvancements.class);

        PayloadTypeRegistry.clientboundPlay().register(Payloads.Events.TYPE, Payloads.Events.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Payloads.Hello.TYPE, Payloads.Hello.CODEC);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommand(dispatcher));

        ServerLifecycleEvents.SERVER_STARTING.register(s -> {
            server = s;
            core = new BonfireCore(this);
            core.init();
            if (mode() == Mode.PROXIED) {
                ServerPlayNetworking.registerGlobalReceiver(Payloads.Hello.TYPE, (payload, context) -> {
                    byte[] data = PluginMessageCodec.encode(payload.json());
                    if (data != null) core.backend().onHello(this, context.player().getUUID(), data);
                });
            }
        });
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            if (advancementFallback) log.warn("[Ember Link] Advancement mixin did not apply; using the chat announcement fallback.");
            core.start();
            if (mode() != Mode.PROXIED) core.serverEvent("server_start", null);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            if (core != null) core.stop();
        });
        ServerTickEvents.END_SERVER_TICK.register(this::tick);

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            if (core == null || mode() == Mode.PROXIED) return;
            core.chat(sender.getUUID(), name(sender), display(sender), message.signedContent(), null, isBedrock(sender.getUUID()));
        });
        ServerMessageEvents.GAME_MESSAGE.register((srv, message, overlay) -> {
            if (advancementFallback && core != null) advancementFromMessage(message);
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            if (core == null) return;
            core.noteSkin(handler.player.getUUID(), skinOf(handler.player));
            synchronized (pendingJoins) {
                pendingJoins.add(new Object[]{handler.player.getUUID(), ticks + 2});
            }
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (core == null || !(entity instanceof ServerPlayer p)) return;
            String msg;
            try {
                msg = p.getCombatTracker().getDeathMessage().getString();
            } catch (Throwable t) {
                return;
            }
            if (mode() == Mode.PROXIED) {
                core.backend().death(this, p.getUUID(), name(p), msg);
            } else if (!vanished(p) && msg != null && !msg.isBlank()) {
                core.playerEvent("death", p.getUUID(), name(p), display(p), null, null, msg, null);
            }
        });
    }

    private static String skinOf(ServerPlayer p) {
        try {
            Object props = call(call(p, "getGameProfile", "gameProfile"), "properties", "getProperties");
            Object found = callGet(props, "textures");
            if (!(found instanceof java.util.Collection<?> c)) return null;
            for (Object prop : c) {
                Object value = call(prop, "value", "getValue");
                if (value instanceof String v) return net.ikelene.bonfire.core.Skins.fromTexturesProperty(v);
            }
        } catch (Throwable t) {
        }
        return null;
    }

    private static Object call(Object o, String name, String altName) throws Exception {
        if (o == null) return null;
        for (String n : new String[]{name, altName}) {
            if (n == null) continue;
            try {
                return o.getClass().getMethod(n).invoke(o);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static Object callGet(Object o, String key) throws Exception {
        return o == null ? null : o.getClass().getMethod("get", Object.class).invoke(o, key);
    }

    private void tick(MinecraftServer s) {
        ticks++;
        if (core == null) return;
        if (mode() == Mode.PROXIED) {
            if (ticks % 100 == 0) core.backend().tick(this);
            return;
        }
        synchronized (pendingJoins) {
            Iterator<Object[]> it = pendingJoins.iterator();
            while (it.hasNext()) {
                Object[] pj = it.next();
                if ((long) pj[1] > ticks) continue;
                it.remove();
                ServerPlayer p = s.getPlayerList().getPlayer((UUID) pj[0]);
                if (p == null) continue;
                joinChecked.add(p.getUUID());
                if (!vanished(p)) core.playerEvent("join", p.getUUID(), name(p), display(p), null, null, null, null);
                else core.requestStatus();
            }
        }
    }

    public static void onPlayerRemoved(ServerPlayer p) {
        BonfireFabric self = INSTANCE;
        if (self == null || self.core == null || p == null) return;
        try {
            UUID id = p.getUUID();
            if (self.mode() == Mode.PROXIED) {
                self.core.backend().onQuit(id);
                return;
            }
            boolean checked = self.joinChecked.remove(id);
            if (checked && !self.vanished(p)) self.core.playerEvent("leave", id, name(p), self.display(p), null, null, null, null);
            else self.core.requestStatus();
        } catch (Throwable t) {
            self.log.warn("[Ember Link] leave hook failed: " + t);
        }
    }

    public static void onPrefixedCommand(CommandSourceStack stack, String command) {
        BonfireFabric self = INSTANCE;
        if (self == null || self.core == null || self.mode() == Mode.PROXIED) return;
        try {
            CommandSource src = ((CommandSourceStackAccessor) stack).bonfire$getSource();
            if (src instanceof RconConsoleSource) self.core.localCommand("rcon", "RCON", command);
            else if (src instanceof MinecraftServer) self.core.localCommand("console", "CONSOLE", command);
        } catch (Throwable ignored) {
        }
    }

    public static void onAdvancementDone(ServerPlayer player, AdvancementHolder holder) {
        BonfireFabric self = INSTANCE;
        if (self == null || self.core == null || player == null) return;
        try {
            DisplayInfo d = holder.value().display().orElse(null);
            if (d == null || !d.shouldAnnounceChat()) return;
            if (!player.level().getGameRules().get(GameRules.SHOW_ADVANCEMENT_MESSAGES)) return;
            String frame = d.getType().getSerializedName();
            self.advancement(player, holder.id().toString(), d.getTitle().getString(), d.getDescription().getString(), frame);
        } catch (Throwable t) {
            self.log.warn("[Ember Link] advancement hook failed: " + t);
        }
    }

    private void advancementFromMessage(Component message) {
        if (!(message.getContents() instanceof TranslatableContents tc)) return;
        String key = tc.getKey();
        if (!key.startsWith("chat.type.advancement.")) return;
        Object[] args = tc.getArgs();
        if (args.length < 2) return;
        String who = args[0] instanceof Component c ? c.getString() : String.valueOf(args[0]);
        String title = args[1] instanceof Component c ? c.getString() : String.valueOf(args[1]);
        if (title.startsWith("[") && title.endsWith("]")) title = title.substring(1, title.length() - 1);
        ServerPlayer p = null;
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (sp.getDisplayName().getString().equals(who) || name(sp).equals(who)) {
                p = sp;
                break;
            }
        }
        if (p == null) return;
        advancement(p, "", title, "", key.substring("chat.type.advancement.".length()));
    }

    private void advancement(ServerPlayer p, String id, String title, String desc, String frame) {
        var json = BonfireCore.advancementJson(id, title, desc, frame);
        if (mode() == Mode.PROXIED) core.backend().advancement(this, p.getUUID(), name(p), json);
        else if (!vanished(p)) core.playerEvent("advancement", p.getUUID(), name(p), display(p), null, null, null, json);
    }

    private static String name(ServerPlayer p) {
        return p.getGameProfile().name();
    }

    private String display(ServerPlayer p) {
        return Prefixes.display(prefix(p.getUUID()), name(p));
    }

    boolean vanished(ServerPlayer p) {
        if (core.config().isAlwaysHidden(p.getUUID(), name(p))) return true;
        if (!vanishChecked) {
            vanishChecked = true;
            if (FabricLoader.getInstance().isModLoaded("vanish")) {
                try {
                    vanishMethod = Class.forName("me.drex.vanish.api.VanishAPI").getMethod("isVanished", Entity.class);
                } catch (Throwable t) {
                    log.warn("[Ember Link] The vanish mod is loaded but its API was not found: " + t);
                }
            }
        }
        if (vanishMethod == null) return false;
        try {
            return (Boolean) vanishMethod.invoke(null, p);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isBedrock(UUID uuid) {
        if (uuid.toString().startsWith("00000000-0000-0000-")) return true;
        if (!FabricLoader.getInstance().isModLoaded("floodgate")) return false;
        try {
            Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object inst = api.getMethod("getInstance").invoke(null);
            return (Boolean) api.getMethod("isFloodgatePlayer", UUID.class).invoke(inst, uuid);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isConsole(CommandSourceStack src) {
        if (src.getEntity() != null || src.isPlayer()) return false;
        if (!net.minecraft.commands.Commands.LEVEL_OWNERS.check(src.permissions())) return false;
        CommandSource s = ((CommandSourceStackAccessor) src).bonfire$getSource();
        return s instanceof MinecraftServer || s instanceof RconConsoleSource;
    }

    private void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String name : new String[] {"bonfire", "ember"}) dispatcher.register(net.minecraft.commands.Commands.literal(name)
                .requires(src -> isConsole(src) || net.minecraft.commands.Commands.LEVEL_GAMEMASTERS.check(src.permissions()))
                .executes(ctx -> runCommand(ctx.getSource(), new String[0]))
                .then(net.minecraft.commands.Commands.argument("args", StringArgumentType.greedyString())
                        .executes(ctx -> runCommand(ctx.getSource(), StringArgumentType.getString(ctx, "args").trim().split("\\s+")))));
    }

    private int runCommand(CommandSourceStack src, String[] args) {
        if (core == null) return 0;
        core.commands().execute(new Commands.Sender() {
            @Override
            public boolean isConsole() {
                return BonfireFabric.isConsole(src);
            }

            @Override
            public boolean mayStatus() {
                return net.minecraft.commands.Commands.LEVEL_GAMEMASTERS.check(src.permissions());
            }

            @Override
            public void send(String line) {
                MinecraftServer s = server;
                if (s != null && !s.isSameThread()) s.execute(() -> src.sendSystemMessage(Component.literal(line)));
                else src.sendSystemMessage(Component.literal(line));
            }
        }, args);
        return 1;
    }

    @Override
    public void sendEvents(UUID via, byte[] data) {
        ServerPlayer p = server.getPlayerList().getPlayer(via);
        String json = PluginMessageCodec.decode(data);
        if (p != null && json != null) ServerPlayNetworking.send(p, new Payloads.Events(json));
    }

    @Override
    public Set<UUID> onlinePlayers() {
        Set<UUID> s = new HashSet<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) s.add(p.getUUID());
        return s;
    }

    @Override
    public boolean vanished(UUID uuid) {
        ServerPlayer p = server.getPlayerList().getPlayer(uuid);
        return p != null && vanished(p);
    }

    @Override
    public String prefix(UUID uuid) {
        if (!core.config().useLuckPermsPrefix() || !FabricLoader.getInstance().isModLoaded("luckperms")) return null;
        return Prefixes.luckPermsPrefix(uuid);
    }

    @Override
    public String platformId() {
        return "fabric";
    }

    @Override
    public String software() {
        String loader = FabricLoader.getInstance().getModContainer("fabricloader").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        return "Fabric Loader " + loader + " (Minecraft " + mcVersion() + ")";
    }

    @Override
    public String mcVersion() {
        return FabricLoader.getInstance().getModContainer("minecraft").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }

    @Override
    public String modVersion() {
        return version;
    }

    @Override
    public Path dataDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("bonfire-link");
    }

    @Override
    public Log log() {
        return log;
    }

    @Override
    public StatusSnapshot status() {
        StatusSnapshot s = new StatusSnapshot();
        s.version = "Fabric " + mcVersion();
        s.motd = motd();
        s.players.max = server.getPlayerList().getMaxPlayers();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (vanished(p)) continue;
            s.players.online++;
            if (s.players.list.size() < 100) {
                s.players.list.add(new StatusSnapshot.PlayerEntry(p.getUUID().toString(), name(p), display(p), null));
            }
        }
        long nanos = server.getAverageTickTimeNanos();
        if (nanos > 0) s.tps = Math.round(Math.min(20.0, 1_000_000_000.0 / nanos) * 100.0) / 100.0;
        return s;
    }

    @Override
    public byte[] serverIconPng() {
        return BonfireCore.readIcon(server.getServerDirectory().resolve("server-icon.png"));
    }

    @Override
    public List<Segment> motd() {
        return Segments.fromLegacy(server.getMotd());
    }

    @Override
    public int maxPlayers() {
        return server.getPlayerList().getMaxPlayers();
    }

    @Override
    public boolean onlineMode() {
        return server.usesAuthentication();
    }

    @Override
    public void broadcast(List<List<Segment>> lines) {
        for (List<Segment> l : lines) server.getPlayerList().broadcastSystemMessage(toComponent(l), false);
    }

    static Component toComponent(List<Segment> segs) {
        MutableComponent root = Component.empty();
        for (Segment s : segs) {
            if (s == null || s.text == null || s.text.isEmpty()) continue;
            Style st = Style.EMPTY;
            String c = s.effectiveColor();
            st = st.withColor(Segments.validColor(c) ? TextColor.fromRgb(Integer.parseInt(c.substring(1), 16)) : TextColor.fromRgb(0xFFFFFF));
            st = st.withBold(s.bold()).withItalic(s.italic()).withUnderlined(s.underlined()).withStrikethrough(s.strike()).withObfuscated(s.obfuscated());
            String hover = s.hover;
            if (s.link != null && Segments.validLink(s.link)) {
                try {
                    st = st.withClickEvent(new ClickEvent.OpenUrl(URI.create(s.link)));
                    if (hover == null) hover = s.link;
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (hover != null) st = st.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover)));
            root.append(Component.literal(s.text).withStyle(st));
        }
        return root;
    }

    @Override
    public CompletableFuture<CommandResult> runConsoleCommand(String cmd) {
        CompletableFuture<CommandResult> f = new CompletableFuture<>();
        server.execute(() -> {
            List<String> out = new ArrayList<>();
            CommandSource capture = new CommandSource() {
                @Override
                public void sendSystemMessage(Component message) {
                    synchronized (out) {
                        out.add(message.getString());
                    }
                }

                @Override
                public boolean acceptsSuccess() {
                    return true;
                }

                @Override
                public boolean acceptsFailure() {
                    return true;
                }

                @Override
                public boolean shouldInformAdmins() {
                    return false;
                }
            };
            try {
                CommandSourceStack stack = server.createCommandSourceStack().withSource(capture);
                server.getCommands().performPrefixedCommand(stack, cmd);
            } catch (Throwable t) {
                f.complete(CommandResult.fail("error", List.of(String.valueOf(t))));
                return;
            }
            server.execute(() -> {
                List<String> copy;
                synchronized (out) {
                    copy = new ArrayList<>(out);
                }
                boolean unknown = !copy.isEmpty() && copy.get(0).toLowerCase(Locale.ROOT).startsWith("unknown or incomplete command");
                f.complete(unknown ? CommandResult.fail("unknown_command", copy) : CommandResult.ok(copy));
            });
        });
        return f;
    }

    @Override
    public void runSync(Runnable r) {
        MinecraftServer s = server;
        if (s == null) return;
        if (s.isSameThread()) r.run();
        else s.execute(r);
    }

    @Override
    public Mode mode() {
        if (mode == null) {
            String m = core.config().mode();
            if (m.equals("standalone")) mode = Mode.STANDALONE;
            else if (m.equals("proxied")) mode = Mode.PROXIED;
            else if (FabricLoader.getInstance().isModLoaded("fabricproxy-lite")) {
                log.info("[Ember Link] FabricProxy-Lite is loaded: running in proxied mode.");
                mode = Mode.PROXIED;
            } else mode = Mode.STANDALONE;
        }
        return mode;
    }

    @Override
    public ClassLoader serverClassLoader() {
        return MinecraftServer.class.getClassLoader();
    }

    static String clean(String s) {
        return Text.stripSection(s);
    }
}
