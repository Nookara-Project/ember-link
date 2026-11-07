package net.ikelene.bonfire.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class BonfireCore {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Platform platform;
    private final Config config = new Config();
    private final ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor(threads("bonfire-gw-sched"));
    private final ConsoleCapture capture = new ConsoleCapture(this);
    private final Commands commands = new Commands(this);
    private final Skins skins = new Skins();
    private final long startedAt = System.currentTimeMillis();

    private Identity identity;
    private GatewayClient gateway;
    private BackendBridge backend;

    private volatile boolean relayChat = true;
    private volatile boolean relayEvents = true;
    private volatile boolean consoleStream;
    private volatile JsonObject linked;
    private volatile String pairingTag;
    private volatile boolean everReady;
    private volatile boolean bannerShown;

    private volatile List<Segment> motd = new ArrayList<>();
    private volatile byte[] iconPng;
    private volatile String iconSha;
    private volatile int maxPlayers;
    private volatile boolean onlineMode = true;

    private ScheduledFuture<?> statusDebounce;
    private final Object statusLock = new Object();

    public BonfireCore(Platform platform) {
        this.platform = platform;
    }

    static ThreadFactory threads(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    public Platform platform() {
        return platform;
    }

    public Config config() {
        return config;
    }

    public Log log() {
        return platform.log();
    }

    public void noteSkin(UUID uuid, String textureUrl) {
        skins.note(uuid, textureUrl);
    }

    public Commands commands() {
        return commands;
    }

    public Identity identity() {
        return identity;
    }

    public BackendBridge backend() {
        return backend;
    }

    public ScheduledExecutorService scheduler() {
        return sched;
    }

    public boolean gatewayReady() {
        return gateway != null && gateway.isReady();
    }

    GatewayClient gateway() {
        return gateway;
    }

    public JsonObject linked() {
        return linked;
    }

    public String pairingTag() {
        return pairingTag != null ? pairingTag : identity != null ? identity.pairingTag() : "?";
    }

    boolean relayChat() {
        return relayChat;
    }

    boolean relayEvents() {
        return relayEvents;
    }

    boolean consoleStream() {
        return consoleStream;
    }

    public void init() {
        config.load(platform.dataDir(), log());
        try {
            identity = Identity.loadOrCreate(platform.dataDir());
        } catch (Exception e) {
            log().error("[Ember Link] Could not load or create identity.properties", e);
        }
        if (platform.mode() == Mode.PROXIED) backend = new BackendBridge(this);
    }

    public void start() {
        if (platform.mode() == Mode.PROXIED) {
            log().info("[Ember Link] Proxied mode: player events go through Velocity; this backend connects separately for its console.");
        }
        if (identity == null) {
            log().error("[Ember Link] No identity, not connecting to Ember.");
            return;
        }
        refreshStatic();
        if (config.consoleEnabled()) capture.attach(sched, platform.serverClassLoader());
        gateway = new GatewayClient(this, sched);
        log().info("[Ember Link] Connecting to Ember at " + config.serverLabel() + "... Pairing tag: " + identity.pairingTag());
        gateway.connect();
        sched.scheduleAtFixedRate(() -> collectStatus(false), 60, 60, TimeUnit.SECONDS);
    }

    public void stop() {
        if (gateway != null) {
            if (gateway.isReady() && platform.mode() != Mode.PROXIED) {
                JsonObject d = new JsonObject();
                d.addProperty("kind", "server_stop");
                gateway.send("event", d);
            }
            gateway.stop();
        }
        capture.detach();
        sched.shutdownNow();
    }

    public void reload() {
        config.load(platform.dataDir(), log());
        if (gateway != null) {
            platform.runSync(() -> {
                refreshStatic();
                gateway.reconnectNow();
            });
        }
    }

    String switchServer(String value) throws java.io.IOException {
        String base = config.saveServer(platform.dataDir(), value, log());
        linked = null;
        everReady = false;
        bannerShown = false;
        pairingTag = null;
        log().info("[Ember Link] Ember server set to " + config.serverLabel() + ".");
        if (gateway != null) {
            platform.runSync(() -> {
                refreshStatic();
                gateway.reconnectNow();
            });
        }
        return base;
    }

    void regenerateIdentity() {
        try {
            Identity.delete(platform.dataDir());
            identity = Identity.loadOrCreate(platform.dataDir());
            pairingTag = identity.pairingTag();
            linked = null;
            log().info("[Ember Link] New identity created. Pairing tag: " + identity.pairingTag());
            if (gateway != null) gateway.reconnectNow();
        } catch (Exception e) {
            log().error("[Ember Link] Could not create a new identity", e);
        }
    }

    private void refreshStatic() {
        try {
            motd = Segments.clampMotd(platform.motd());
            maxPlayers = platform.maxPlayers();
            onlineMode = platform.onlineMode();
        } catch (Throwable t) {
            log().warn("[Ember Link] Could not read MOTD: " + t);
        }
        try {
            byte[] png = platform.serverIconPng();
            if (png != null && validIcon(png)) {
                iconPng = png;
                iconSha = hex(MessageDigest.getInstance("SHA-256").digest(png));
            } else {
                iconPng = null;
                iconSha = null;
            }
        } catch (Throwable t) {
            iconPng = null;
            iconSha = null;
        }
    }

    static boolean validIcon(byte[] png) {
        if (png.length < 24 || png.length > 36 * 1024) return false;
        byte[] magic = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        for (int i = 0; i < 8; i++) if (png[i] != magic[i]) return false;
        int w = ((png[16] & 0xff) << 24) | ((png[17] & 0xff) << 16) | ((png[18] & 0xff) << 8) | (png[19] & 0xff);
        int h = ((png[20] & 0xff) << 24) | ((png[21] & 0xff) << 16) | ((png[22] & 0xff) << 8) | (png[23] & 0xff);
        return w > 0 && h > 0 && w <= 64 && h <= 64;
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    public static byte[] readIcon(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
        } catch (Exception e) {
            return null;
        }
    }

    JsonObject buildIdentify(String nonce) {
        JsonObject d = new JsonObject();
        d.addProperty("v", 1);
        d.addProperty("key", identity.publicKeyB64());
        d.addProperty("sig", identity.signHandshake(nonce));
        d.addProperty("iid", identity.iid());
        d.addProperty("platform", platform.platformId());
        d.addProperty("software", Text.clamp(platform.software(), 80));
        d.addProperty("mc_version", Text.clamp(platform.mcVersion(), 40));
        d.addProperty("mod_version", Text.clamp(platform.modVersion(), 20));
        d.addProperty("listing", config.listing());
        d.add("motd", GSON.toJsonTree(motd));
        if (iconSha != null) d.addProperty("icon_sha256", iconSha);
        else d.add("icon_sha256", com.google.gson.JsonNull.INSTANCE);
        d.addProperty("max_players", maxPlayers);
        d.addProperty("online_mode", onlineMode);
        d.addProperty("public_address", config.publicAddress());
        JsonObject f = new JsonObject();
        f.addProperty("chat", platform.mode() != Mode.PROXIED && config.chatEnabled());
        f.addProperty("events", platform.mode() != Mode.PROXIED && config.eventsEnabled());
        f.addProperty("console", config.consoleEnabled() && capture.available());
        f.addProperty("console_exec", config.consoleExecEnabled());
        d.add("features", f);
        return d;
    }

    void onReady(JsonObject d) {
        String iid = str(d, "iid");
        if (iid != null && identity != null && !iid.equals(identity.iid())) {
            log().warn("[Ember Link] Ember computed a different instance id (" + iid + ") than this server (" + identity.iid() + ")");
        }
        String tag = str(d, "pairing_tag");
        if (tag != null) pairingTag = tag;
        JsonElement l = d.get("linked");
        JsonObject newLinked = l != null && l.isJsonObject() ? l.getAsJsonObject() : null;
        boolean changed = !everReady || (linked == null) != (newLinked == null);
        linked = newLinked;
        everReady = true;
        applyConfig(d);
        if (d.has("icon_needed") && d.get("icon_needed").getAsBoolean() && iconPng != null) {
            JsonObject icon = new JsonObject();
            icon.addProperty("png", Base64.getEncoder().encodeToString(iconPng));
            gateway.send("icon", icon);
        }
        if (changed || !bannerShown) {
            bannerShown = true;
            printBanner();
        } else {
            log().info("[Ember Link] Reconnected to Ember.");
        }
        collectStatus(true);
    }

    void printBanner() {
        JsonObject l = linked;
        String what = platform.mode() == Mode.PROXIED ? "backend console" : "server";
        if (l == null) {
            log().info("[Ember Link] Connected to " + config.serverLabel() + ". Pairing tag: " + pairingTag());
            if (!config.selfHosted()) {
                log().info("[Ember Link] Not linked yet. In Ember, add a \"Game server\" channel and pick this " + what + ".");
            } else {
                log().info("[Ember Link] Waiting for an admin to pick this " + what + " under Connected game servers"
                        + " (or add a \"Game server\" channel), or run `bonfire link <code>`.");
            }
        } else {
            log().info("[Ember Link] Linked to " + str(l, "server_name") + " on " + config.serverLabel() + ".");
        }
    }

    void onConfig(JsonObject d) {
        applyConfig(d);
    }

    private void applyConfig(JsonObject d) {
        JsonElement r = d.get("relay");
        if (r != null && r.isJsonObject()) {
            JsonObject ro = r.getAsJsonObject();
            if (ro.has("chat")) relayChat = ro.get("chat").getAsBoolean();
            if (ro.has("events")) relayEvents = ro.get("events").getAsBoolean();
        }
        if (d.has("console_stream")) {
            consoleStream = d.get("console_stream").getAsBoolean();
            capture.setActive(consoleStream && config.consoleEnabled());
        }
    }

    void onDisconnected() {
        consoleStream = false;
        capture.setActive(false);
        log().warn("[Ember Link] Lost the connection to Ember, reconnecting...");
    }

    void onLinked(JsonObject d) {
        linked = d;
        log().info("[Ember Link] Linked to " + str(d, "server_name") + (str(d, "linked_by") != null ? " by " + str(d, "linked_by") : "") + ".");
        collectStatus(true);
    }

    void onUnlinked(JsonObject d) {
        linked = null;
        String by = str(d, "by");
        String user = str(d, "username");
        log().info("[Ember Link] Unlinked from Ember" + (user != null ? " by " + user : "")
                + ("console".equals(by) ? " (from the console)" : "bonfire".equals(by) ? " (from Ember)" : "")
                + ". Still running as unclaimed. Pairing tag: " + pairingTag());
    }

    void onOrphanLinkResult(JsonObject d) {
        commands.printLinkResult(d, log()::info);
    }

    void onBonfireChat(JsonObject d) {
        if (platform.mode() == Mode.PROXIED || !config.chatEnabled()) return;
        BonfireChat m;
        try {
            m = GSON.fromJson(d, BonfireChat.class);
        } catch (Exception e) {
            return;
        }
        List<List<Segment>> lines = ChatFormatter.format(config.chatFormat(), m);
        platform.runSync(() -> {
            try {
                platform.broadcast(lines);
            } catch (Throwable t) {
                log().error("[Ember Link] Could not show an Ember message", t);
            }
        });
    }

    void onConsoleExec(JsonObject d) {
        String req = str(d, "req");
        String cmd = str(d, "command");
        if (req == null) return;
        if (!config.consoleExecEnabled()) {
            sendResult(req, CommandResult.fail("console_disabled", List.of("Running commands from Ember is turned off in Ember Link's config.")));
            return;
        }
        if (cmd == null || cmd.isBlank()) {
            sendResult(req, CommandResult.fail("error", List.of("Empty command")));
            return;
        }
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        String by = str(d, "by");
        log().info("[Ember Link] " + (by != null ? by : "Ember") + " ran: " + cmd);
        CompletableFuture<CommandResult> f;
        try {
            f = platform.runConsoleCommand(cmd);
        } catch (Throwable t) {
            f = CompletableFuture.completedFuture(CommandResult.fail("error", List.of(String.valueOf(t))));
        }
        f.orTimeout(10, TimeUnit.SECONDS).whenComplete((res, ex) -> {
            if (ex != null) {
                Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                if (c instanceof java.util.concurrent.TimeoutException) sendResult(req, CommandResult.fail("timeout", List.of()));
                else sendResult(req, CommandResult.fail("error", List.of(String.valueOf(c))));
            } else {
                sendResult(req, res);
            }
        });
    }

    private void sendResult(String req, CommandResult r) {
        JsonObject d = new JsonObject();
        d.addProperty("req", req);
        d.addProperty("ok", r.ok);
        JsonArray out = new JsonArray();
        boolean truncated = false;
        int n = 0;
        for (String raw : r.output) {
            for (String line : (raw == null ? "" : raw).split("\n", -1)) {
                if (n >= 100) {
                    truncated = true;
                    break;
                }
                String clean = Text.cleanLogLine(line);
                if (config.redactIps()) clean = Text.redactIps(clean);
                if (clean.length() > 2000) truncated = true;
                out.add(Text.clamp(clean, 2000));
                n++;
            }
        }
        d.add("output", out);
        d.addProperty("truncated", truncated);
        if (!r.ok && r.error != null) d.addProperty("error", r.error);
        sendFrame("console.result", d);
    }

    void sendFrame(String op, JsonObject d) {
        if (gateway != null) gateway.send(op, d);
    }

    void request(String op, JsonObject d, java.util.function.Consumer<JsonObject> reply) {
        if (gateway != null) gateway.request(op, d, reply);
    }

    public void chat(UUID uuid, String name, String display, String text, String server, boolean bedrock) {
        if (platform.mode() == Mode.PROXIED || gateway == null || !config.chatEnabled() || (everReady && !relayChat)) return;
        String clean = Text.cleanChat(text);
        if (clean.isBlank()) return;
        JsonObject d = new JsonObject();
        d.addProperty("uuid", uuid.toString());
        d.addProperty("name", Text.clamp(name, 32));
        if (display != null) d.addProperty("display", Text.clamp(display, 80));
        d.addProperty("text", clean);
        if (server != null) d.addProperty("server", Text.clamp(server, 40));
        d.addProperty("bedrock", bedrock);
        String skin = skins.of(uuid);
        if (skin != null) d.addProperty("skin", skin);
        gateway.send("chat", d);
    }

    public void playerEvent(String kind, UUID uuid, String name, String display, String server, String from, String text, JsonObject advancement) {
        if (platform.mode() == Mode.PROXIED || gateway == null || !config.eventsEnabled() || (everReady && !relayEvents)) return;
        JsonObject d = new JsonObject();
        d.addProperty("kind", kind);
        if (uuid != null) d.addProperty("uuid", uuid.toString());
        if (name != null) d.addProperty("name", Text.clamp(name, 32));
        if (display != null) d.addProperty("display", Text.clamp(display, 80));
        if (server != null) d.addProperty("server", Text.clamp(server, 40));
        if (from != null) d.addProperty("from", Text.clamp(from, 40));
        if (text != null) d.addProperty("text", Text.clamp(Text.stripSection(text), 512));
        if (advancement != null) d.add("advancement", advancement);
        String skin = skins.of(uuid);
        if (skin != null) d.addProperty("skin", skin);
        gateway.send("event", d);
        if (kind.equals("join") || kind.equals("leave") || kind.equals("switch")) requestStatus();
    }

    public void serverEvent(String kind, String server) {
        if (platform.mode() == Mode.PROXIED || gateway == null || !config.eventsEnabled()) return;
        JsonObject d = new JsonObject();
        d.addProperty("kind", kind);
        if (server != null) d.addProperty("server", Text.clamp(server, 40));
        gateway.send("event", d);
        if (kind.startsWith("backend_")) requestStatus();
    }

    public static JsonObject advancementJson(String id, String title, String description, String frame) {
        JsonObject a = new JsonObject();
        a.addProperty("id", id);
        a.addProperty("title", Text.clamp(Text.stripSection(title), 120));
        a.addProperty("description", Text.clamp(Text.stripSection(description == null ? "" : description), 240));
        a.addProperty("frame", frame == null ? "task" : frame);
        return a;
    }

    public void localCommand(String source, String by, String command) {
        if (gateway == null || !config.reportLocalCommands() || command == null) return;
        String cmd = command.startsWith("/") ? command.substring(1) : command;
        cmd = cmd.trim();
        if (cmd.isEmpty()) return;
        String low = cmd.toLowerCase(java.util.Locale.ROOT);
        if (low.startsWith("bonfire link ") || low.startsWith("bonfire:bonfire link ")) cmd = cmd.substring(0, low.indexOf("link ") + 5) + "[code]";
        JsonObject d = new JsonObject();
        d.addProperty("t", System.currentTimeMillis());
        d.addProperty("source", source);
        d.addProperty("by", Text.clamp(by, 40));
        d.addProperty("command", Text.clamp(cmd, 1000));
        gateway.send("console.command", d);
    }

    public void requestStatus() {
        synchronized (statusLock) {
            if (statusDebounce != null && !statusDebounce.isDone()) return;
            statusDebounce = sched.schedule(() -> collectStatus(false), 2, TimeUnit.SECONDS);
        }
    }

    private void collectStatus(boolean force) {
        if (gateway == null || (!gateway.isReady() && !force)) return;
        platform.runSync(() -> {
            StatusSnapshot s;
            try {
                refreshStatic();
                s = platform.status();
            } catch (Throwable t) {
                log().error("[Ember Link] Could not build status", t);
                return;
            }
            s.uptime_s = (System.currentTimeMillis() - startedAt) / 1000;
            if (s.motd == null) s.motd = motd;
            else s.motd = Segments.clampMotd(s.motd);
            if (s.players.list != null && s.players.list.size() > 100) s.players.list = new ArrayList<>(s.players.list.subList(0, 100));
            JsonObject d = GSON.toJsonTree(s).getAsJsonObject();
            sched.execute(() -> {
                if (gateway.isReady()) gateway.send("status", d);
            });
        });
    }

    static String str(JsonObject o, String k) {
        if (o == null) return null;
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    List<String> statusLines() {
        List<String> out = new ArrayList<>();
        out.add("Ember Link " + platform.modVersion() + " (" + platform.platformId() + ", mode " + platform.mode().name().toLowerCase() + ")");
        if (platform.mode() == Mode.PROXIED) {
            out.add("Proxied: events go through the proxy.");
            out.add("Proxy confirmed: " + (backend != null && backend.proxyConfirmed() ? "yes" : "not yet (hello arrives when a player connects through Velocity)"));
        }
        if (gateway == null) {
            out.add("Connection: not started");
        } else if (gateway.isReady()) {
            out.add("Connection: connected");
        } else {
            out.add("Connection: " + (gateway.isConnecting() ? "connecting" : "disconnected") + (gateway.lastError().isEmpty() ? "" : " (last error: " + gateway.lastError() + ")"));
        }
        out.add("Ember: " + config.serverLabel());
        JsonObject l = linked;
        out.add("Linked: " + (l == null ? (everReady ? (config.selfHosted() ? "no (waiting for an admin to pick this server)" : "no") : "unknown (not connected yet)") : str(l, "server_name") + (str(l, "linked_by") != null ? " (by " + str(l, "linked_by") + ")" : "")));
        if (identity != null) out.add("Instance: " + identity.iid() + "   Pairing tag: " + pairingTag());
        out.add("Listing: " + config.listing() + "   Relay chat: " + relayChat + "   Relay events: " + relayEvents);
        out.add("Console stream: " + consoleStream + "   Console exec: " + (config.consoleExecEnabled() ? "enabled" : "disabled"));
        return out;
    }
}
