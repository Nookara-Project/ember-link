package net.ikelene.bonfire.paper;

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
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.advancement.Advancement;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.RemoteServerCommandEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class BonfirePaper extends JavaPlugin implements Platform, BackendBridge.Hooks {
    private BonfireCore core;
    private Mode mode;
    private boolean paper;
    private final Set<UUID> joinChecked = ConcurrentHashMap.newKeySet();

    private final Log log = new Log() {
        @Override
        public void info(String msg) {
            getLogger().info(strip(msg));
        }

        @Override
        public void warn(String msg) {
            getLogger().warning(strip(msg));
        }

        @Override
        public void error(String msg, Throwable t) {
            getLogger().log(Level.SEVERE, strip(msg), t);
        }

        private String strip(String m) {
            return m.startsWith("[Ember Link] ") ? m.substring(15) : m;
        }
    };

    @Override
    public void onEnable() {
        paper = classExists("io.papermc.paper.event.player.AsyncChatEvent");
        core = new BonfireCore(this);
        core.init();

        PluginCommand cmd = getCommand("bonfire");
        if (cmd != null) {
            cmd.setExecutor((sender, command, label, args) -> {
                core.commands().execute(sender(sender), args);
                return true;
            });
        }

        if (mode() == Mode.PROXIED) {
            getServer().getMessenger().registerOutgoingPluginChannel(this, PluginMessageCodec.CHANNEL_EVENTS);
            getServer().getMessenger().registerIncomingPluginChannel(this, PluginMessageCodec.CHANNEL_HELLO,
                    (channel, player, message) -> core.backend().onHello(this, player.getUniqueId(), message));
            getServer().getPluginManager().registerEvents(new ProxiedListener(), this);
            getServer().getScheduler().runTaskTimer(this, () -> core.backend().tick(this), 100L, 100L);
        } else {
            getServer().getPluginManager().registerEvents(new StandaloneListener(), this);
            if (paper) {
                PaperExtras.registerChat(this, this::onChat);
            } else {
                getServer().getPluginManager().registerEvent(AsyncPlayerChatEvent.class, new Listener() {
                }, EventPriority.MONITOR, (l, e) -> {
                    if (e instanceof AsyncPlayerChatEvent) {
                        AsyncPlayerChatEvent ce = (AsyncPlayerChatEvent) e;
                        onChat(ce.getPlayer(), ce.getMessage());
                    }
                }, this, true);
            }
        }

        getServer().getScheduler().runTask(this, () -> {
            core.start();
            for (Player online : getServer().getOnlinePlayers()) core.noteSkin(online.getUniqueId(), skinOf(online));
            if (mode() != Mode.PROXIED) core.serverEvent("server_start", null);
        });
    }

    @Override
    public void onDisable() {
        if (core != null) core.stop();
    }

    private static boolean classExists(String n) {
        try {
            Class.forName(n);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private Commands.Sender sender(CommandSender s) {
        return new Commands.Sender() {
            @Override
            public boolean isConsole() {
                return s instanceof ConsoleCommandSender || s instanceof RemoteConsoleCommandSender;
            }

            @Override
            public boolean mayStatus() {
                return s.hasPermission("bonfire.status");
            }

            @Override
            public void send(String line) {
                if (Bukkit.isPrimaryThread()) s.sendMessage(line);
                else runSync(() -> s.sendMessage(line));
            }
        };
    }

    boolean vanished(Player p) {
        if (core.config().isAlwaysHidden(p.getUniqueId(), p.getName())) return true;
        try {
            for (MetadataValue v : p.getMetadata("vanished")) if (v.asBoolean()) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private String prefixOf(UUID uuid) {
        if (!core.config().useLuckPermsPrefix()) return null;
        if (!getServer().getPluginManager().isPluginEnabled("LuckPerms")) return null;
        return Prefixes.luckPermsPrefix(uuid);
    }

    private static String skinOf(Player p) {
        try {
            java.net.URL u = p.getPlayerProfile().getTextures().getSkin();
            return u == null ? null : u.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private void onChat(Player p, String text) {
        core.chat(p.getUniqueId(), p.getName(), Prefixes.display(prefixOf(p.getUniqueId()), p.getName()), text, null, isBedrock(p.getUniqueId()));
    }

    private static boolean isBedrock(UUID uuid) {
        return uuid.toString().startsWith("00000000-0000-0000-");
    }

    private String[] advancementInfo(PlayerAdvancementDoneEvent e) {
        if (paper) return PaperExtras.advancement(e);
        try {
            Advancement adv = e.getAdvancement();
            Method gd = adv.getClass().getMethod("getDisplay");
            Object d = gd.invoke(adv);
            if (d == null) return null;
            boolean announce = (Boolean) d.getClass().getMethod("shouldAnnounceChat").invoke(d);
            if (!announce) return null;
            try {
                Boolean rule = e.getPlayer().getWorld().getGameRuleValue(GameRule.ANNOUNCE_ADVANCEMENTS);
                if (Boolean.FALSE.equals(rule)) return null;
            } catch (Throwable ignored) {
            }
            String title = String.valueOf(d.getClass().getMethod("getTitle").invoke(d));
            String desc = String.valueOf(d.getClass().getMethod("getDescription").invoke(d));
            String frame = String.valueOf(d.getClass().getMethod("getType").invoke(d)).toLowerCase(Locale.ROOT);
            return new String[]{Text.stripSection(title), Text.stripSection(desc), frame};
        } catch (Throwable t) {
            return null;
        }
    }

    private final class StandaloneListener implements Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        public void onJoin(PlayerJoinEvent e) {
            Player p = e.getPlayer();
            UUID id = p.getUniqueId();
            core.noteSkin(id, skinOf(p));
            getServer().getScheduler().runTaskLater(BonfirePaper.this, () -> {
                if (!p.isOnline()) return;
                joinChecked.add(id);
                if (!vanished(p)) {
                    core.playerEvent("join", id, p.getName(), Prefixes.display(prefixOf(id), p.getName()), null, null, null, null);
                } else {
                    core.requestStatus();
                }
            }, 2L);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(PlayerQuitEvent e) {
            Player p = e.getPlayer();
            UUID id = p.getUniqueId();
            boolean checked = joinChecked.remove(id);
            if (checked && !vanished(p)) {
                core.playerEvent("leave", id, p.getName(), Prefixes.display(prefixOf(id), p.getName()), null, null, null, null);
            } else {
                core.requestStatus();
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onDeath(PlayerDeathEvent e) {
            Player p = e.getEntity();
            @SuppressWarnings("deprecation") String msg = e.getDeathMessage();
            if (msg == null || msg.isEmpty() || vanished(p)) return;
            core.playerEvent("death", p.getUniqueId(), p.getName(), Prefixes.display(prefixOf(p.getUniqueId()), p.getName()), null, null, Text.stripSection(msg), null);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onAdvancement(PlayerAdvancementDoneEvent e) {
            Player p = e.getPlayer();
            if (vanished(p)) return;
            String[] a = advancementInfo(e);
            if (a == null) return;
            core.playerEvent("advancement", p.getUniqueId(), p.getName(), Prefixes.display(prefixOf(p.getUniqueId()), p.getName()), null, null, null,
                    BonfireCore.advancementJson(e.getAdvancement().getKey().toString(), a[0], a[1], a[2]));
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onConsoleCommand(ServerCommandEvent e) {
            reportLocal(e.getSender(), e.getCommand());
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onRconCommand(RemoteServerCommandEvent e) {
            reportLocal(e.getSender(), e.getCommand());
        }
    }

    private void reportLocal(CommandSender s, String command) {
        if (s instanceof RemoteConsoleCommandSender) {
            String by = "RCON";
            if (!core.config().redactIps()) {
                try {
                    Object addr = s.getClass().getMethod("getAddress").invoke(s);
                    if (addr instanceof InetSocketAddress) by = "RCON " + ((InetSocketAddress) addr).getAddress().getHostAddress();
                } catch (Throwable ignored) {
                }
            }
            core.localCommand("rcon", by, command);
        } else if (s instanceof ConsoleCommandSender) {
            core.localCommand("console", "CONSOLE", command);
        }
    }

    private final class ProxiedListener implements Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(PlayerQuitEvent e) {
            core.backend().onQuit(e.getPlayer().getUniqueId());
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onDeath(PlayerDeathEvent e) {
            Player p = e.getEntity();
            @SuppressWarnings("deprecation") String msg = e.getDeathMessage();
            core.backend().death(BonfirePaper.this, p.getUniqueId(), p.getName(), msg);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onAdvancement(PlayerAdvancementDoneEvent e) {
            String[] a = advancementInfo(e);
            if (a == null) return;
            Player p = e.getPlayer();
            core.backend().advancement(BonfirePaper.this, p.getUniqueId(), p.getName(),
                    BonfireCore.advancementJson(e.getAdvancement().getKey().toString(), a[0], a[1], a[2]));
        }
    }

    @Override
    public void sendEvents(UUID via, byte[] data) {
        Player p = Bukkit.getPlayer(via);
        if (p != null) p.sendPluginMessage(this, PluginMessageCodec.CHANNEL_EVENTS, data);
    }

    @Override
    public Set<UUID> onlinePlayers() {
        Set<UUID> s = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) s.add(p.getUniqueId());
        return s;
    }

    @Override
    public boolean vanished(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        return p != null && vanished(p);
    }

    @Override
    public String prefix(UUID uuid) {
        return prefixOf(uuid);
    }

    @Override
    public String platformId() {
        return paper ? "paper" : "spigot";
    }

    @Override
    public String software() {
        return getServer().getName() + " " + getServer().getVersion();
    }

    @Override
    public String mcVersion() {
        String v = getServer().getBukkitVersion();
        int dash = v.indexOf('-');
        return dash > 0 ? v.substring(0, dash) : v;
    }

    @Override
    @SuppressWarnings("deprecation")
    public String modVersion() {
        return getDescription().getVersion();
    }

    @Override
    public Path dataDir() {
        return getDataFolder().toPath();
    }

    @Override
    public Log log() {
        return log;
    }

    @Override
    public StatusSnapshot status() {
        StatusSnapshot s = new StatusSnapshot();
        s.version = getServer().getName() + " " + mcVersion();
        s.motd = motd();
        s.players.max = getServer().getMaxPlayers();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (vanished(p)) continue;
            s.players.online++;
            if (s.players.list.size() < 100) {
                s.players.list.add(new StatusSnapshot.PlayerEntry(p.getUniqueId().toString(), p.getName(), Prefixes.display(prefixOf(p.getUniqueId()), p.getName()), null));
            }
        }
        if (paper) {
            try {
                s.tps = Math.round(Math.min(20.0, PaperExtras.tps()) * 100.0) / 100.0;
            } catch (Throwable ignored) {
            }
        }
        return s;
    }

    @Override
    public byte[] serverIconPng() {
        return BonfireCore.readIcon(Path.of("server-icon.png"));
    }

    @Override
    @SuppressWarnings("deprecation")
    public List<Segment> motd() {
        return Segments.fromLegacy(getServer().getMotd());
    }

    @Override
    public int maxPlayers() {
        return getServer().getMaxPlayers();
    }

    @Override
    public boolean onlineMode() {
        return getServer().getOnlineMode();
    }

    @Override
    public void broadcast(List<List<Segment>> lines) {
        if (paper) {
            PaperExtras.broadcast(lines);
        } else {
            for (List<Segment> l : lines) Bukkit.broadcastMessage(Segments.toLegacy(l));
        }
    }

    @Override
    public CompletableFuture<CommandResult> runConsoleCommand(String cmd) {
        if (paper) return PaperExtras.runCaptured(this, cmd);
        CompletableFuture<CommandResult> f = new CompletableFuture<>();
        runSync(() -> {
            try {
                boolean found = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                List<String> out = new ArrayList<>();
                out.add(found ? "(output went to the console log)" : "Unknown command.");
                f.complete(found ? CommandResult.ok(out) : CommandResult.fail("unknown_command", out));
            } catch (Throwable t) {
                f.complete(CommandResult.fail("error", List.of(String.valueOf(t))));
            }
        });
        return f;
    }

    @Override
    public void runSync(Runnable r) {
        if (!isEnabled()) return;
        if (Bukkit.isPrimaryThread()) r.run();
        else getServer().getScheduler().runTask(this, r);
    }

    @Override
    public Mode mode() {
        if (mode == null) mode = detectMode();
        return mode;
    }

    @Override
    public ClassLoader serverClassLoader() {
        return Bukkit.getServer().getClass().getClassLoader();
    }

    private Mode detectMode() {
        String m = core.config().mode();
        if (m.equals("standalone")) return Mode.STANDALONE;
        if (m.equals("proxied")) return Mode.PROXIED;
        try {
            File pg = new File("config", "paper-global.yml");
            if (pg.isFile() && YamlConfiguration.loadConfiguration(pg).getBoolean("proxies.velocity.enabled", false)) {
                log.info("[Ember Link] Velocity forwarding is on in paper-global.yml: running in proxied mode.");
                return Mode.PROXIED;
            }
            File sp = new File("spigot.yml");
            if (sp.isFile() && YamlConfiguration.loadConfiguration(sp).getBoolean("settings.bungeecord", false)) {
                log.info("[Ember Link] bungeecord is on in spigot.yml: running in proxied mode.");
                return Mode.PROXIED;
            }
        } catch (Throwable t) {
            log.warn("[Ember Link] Could not read proxy settings, assuming standalone: " + t);
        }
        return Mode.STANDALONE;
    }
}
