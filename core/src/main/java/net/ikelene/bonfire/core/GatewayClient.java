package net.ikelene.bonfire.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class GatewayClient {
    public static final int MAX_FRAME = 64 * 1024;
    private static final int QUEUE_CAP = 500;
    private static final int COMMAND_CAP = 200;
    private static final long STALE_MS = 120_000;
    private static final long[] BACKOFF_S = {1, 2, 4, 8, 16, 32, 60};

    private static final class Frame {
        final String op;
        final String json;
        final long createdAt;

        Frame(String op, String json) {
            this.op = op;
            this.json = json;
            this.createdAt = System.currentTimeMillis();
        }
    }

    private final BonfireCore core;
    private final ScheduledExecutorService sched;
    private final ExecutorService sender;
    private final ExecutorService io;
    private final HttpClient http;

    private final Object lock = new Object();
    private final ArrayDeque<Frame> pending = new ArrayDeque<>();
    private final Map<Integer, Consumer<JsonObject>> replies = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    private volatile WebSocket ws;
    private volatile int generation;
    private volatile boolean ready;
    private volatile boolean stopped;
    private volatile boolean connecting;
    private volatile long sessionStart;
    private volatile long heartbeatMs = 30_000;
    private volatile long lastAckAt;
    private volatile String lastError = "";
    private volatile long connectedSince;
    private int attempt;
    private long hbSeq;
    private ScheduledFuture<?> hbTask;
    private ScheduledFuture<?> reconnectTask;

    private double tokens = 60;
    private long tokensAt = System.nanoTime();

    GatewayClient(BonfireCore core, ScheduledExecutorService sched) {
        this.core = core;
        this.sched = sched;
        this.sender = Executors.newSingleThreadExecutor(BonfireCore.threads("bonfire-gw-send"));
        this.io = Executors.newCachedThreadPool(BonfireCore.threads("bonfire-gw-io"));
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .executor(io)
                .build();
    }

    public boolean isReady() {
        return ready;
    }

    public boolean isConnecting() {
        return connecting || (ws != null && !ready);
    }

    public String lastError() {
        return lastError;
    }

    public long connectedSince() {
        return connectedSince;
    }

    public void connect() {
        if (stopped) return;
        synchronized (lock) {
            if (connecting || ws != null) return;
            connecting = true;
        }
        final int gen = ++generation;
        String url = "server=" + core.config().get("server");
        try {
            url = core.config().gatewayUrl();
            http.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .header("User-Agent", "BonfireLink/" + core.platform().modVersion() + " (" + core.platform().platformId() + ")")
                    .buildAsync(URI.create(url), new Listener(gen))
                    .whenComplete((w, ex) -> {
                        connecting = false;
                        if (ex != null) {
                            Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                            lastError = "connect failed: " + c.getClass().getSimpleName() + (c.getMessage() != null ? " " + c.getMessage() : "");
                            if (gen == generation) scheduleReconnect(1006);
                            return;
                        }
                        if (gen != generation || stopped) {
                            w.abort();
                            return;
                        }
                        ws = w;
                    });
        } catch (Exception e) {
            connecting = false;
            lastError = "bad gateway-url: " + e.getMessage();
            core.log().error("[Ember Link] Bad gateway-url " + url + ": " + e.getMessage());
            scheduleReconnect(4008);
        }
    }

    public void reconnectNow() {
        WebSocket w = ws;
        generation++;
        teardown();
        if (w != null) {
            try {
                w.sendClose(1000, "reconnect");
            } catch (Exception ignored) {
            }
            w.abort();
        }
        attempt = 0;
        cancelReconnect();
        connecting = false;
        if (!stopped) sched.schedule(this::connect, 200, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        stopped = true;
        cancelReconnect();
        WebSocket w = ws;
        if (w != null && ready) {
            try {
                sender.submit(() -> {
                }).get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            try {
                w.sendClose(1000, "server shutdown").get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
        generation++;
        teardown();
        if (w != null) w.abort();
        sender.shutdownNow();
        io.shutdownNow();
    }

    private void teardown() {
        ready = false;
        ws = null;
        synchronized (lock) {
            if (hbTask != null) hbTask.cancel(false);
            hbTask = null;
        }
    }

    private void cancelReconnect() {
        synchronized (lock) {
            if (reconnectTask != null) reconnectTask.cancel(false);
            reconnectTask = null;
        }
    }

    private void onClosed(int gen, int code, String reason) {
        if (gen != generation) return;
        boolean wasReady = ready;
        generation++;
        teardown();
        connecting = false;
        lastError = "closed " + code + (reason != null && !reason.isEmpty() ? " (" + reason + ")" : "");
        if (wasReady) core.onDisconnected();
        if (!stopped) scheduleReconnect(code);
    }

    private void scheduleReconnect(int code) {
        if (stopped) return;
        long delayMs;
        switch (code) {
            case 4003:
                core.log().error("[Ember Link] Ember rejected this server's signature (4003). Retrying in 10 minutes.");
                delayMs = TimeUnit.MINUTES.toMillis(10);
                break;
            case 4004:
                core.log().error("[Ember Link] This server is banned from Ember (4004). Retrying in 6 hours.");
                delayMs = TimeUnit.HOURS.toMillis(6);
                break;
            case 4007:
                core.log().error("[Ember Link] Ember no longer supports this version of the gateway protocol. Please update Ember Link. Retrying in 6 hours.");
                delayMs = TimeUnit.HOURS.toMillis(6);
                break;
            case 4008:
                core.log().warn("[Ember Link] Ember refused the connection (IP limits, 4008). Retrying in 10 minutes.");
                delayMs = TimeUnit.MINUTES.toMillis(10);
                break;
            default: {
                long now = System.currentTimeMillis();
                if (sessionStart > 0 && now - sessionStart >= 60_000) attempt = 0;
                sessionStart = 0;
                long base = BACKOFF_S[Math.min(attempt, BACKOFF_S.length - 1)] * 1000L;
                attempt++;
                double jitter = 1.0 + (ThreadLocalRandom.current().nextDouble() * 0.4 - 0.2);
                delayMs = (long) (base * jitter);
            }
        }
        synchronized (lock) {
            if (reconnectTask != null) reconnectTask.cancel(false);
            reconnectTask = sched.schedule(this::connect, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    static String frameJson(String op, JsonObject d, Integer id) {
        JsonObject o = new JsonObject();
        o.addProperty("op", op);
        o.add("d", d == null ? new JsonObject() : d);
        if (id != null) o.addProperty("id", id);
        return BonfireCore.GSON.toJson(o);
    }

    public void send(String op, JsonObject d) {
        enqueue(new Frame(op, frameJson(op, d, null)));
    }

    public void request(String op, JsonObject d, Consumer<JsonObject> onReply) {
        int id = nextId.getAndIncrement();
        replies.put(id, onReply);
        enqueue(new Frame(op, frameJson(op, d, id)));
    }

    private void enqueue(Frame f) {
        if (f.json.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME) {
            core.log().warn("[Ember Link] Dropped an oversized " + f.op + " frame");
            return;
        }
        synchronized (lock) {
            if (!ready) {
                addPending(f);
                return;
            }
        }
        sender.execute(() -> write(f, true));
    }

    private void addPending(Frame f) {
        if (f.op.equals("heartbeat") || f.op.equals("identify") || f.op.equals("console.log")) return;
        if (f.op.equals("status")) {
            pending.removeIf(p -> p.op.equals("status"));
        }
        if (f.op.equals("console.command")) {
            int n = 0;
            for (Frame p : pending) if (p.op.equals("console.command")) n++;
            if (n >= COMMAND_CAP) removeFirst("console.command");
        }
        while (pending.size() >= QUEUE_CAP) {
            if (removeFirst("console.log")) continue;
            if (removeFirst("status")) continue;
            if (removeFirst("chat") || removeFirst("event")) continue;
            if (removeFirst("console.command") || removeFirst("console.result") || removeFirst("icon")) continue;
            break;
        }
        pending.addLast(f);
    }

    private boolean removeFirst(String op) {
        Iterator<Frame> it = pending.iterator();
        while (it.hasNext()) {
            if (it.next().op.equals(op)) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    private void flushPending() {
        List<Frame> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        synchronized (lock) {
            for (Frame f : pending) {
                if (f.op.equals("status")) continue;
                if ((f.op.equals("chat") || f.op.equals("event")) && now - f.createdAt > STALE_MS) continue;
                out.add(f);
            }
            pending.clear();
        }
        for (Frame f : out) write(f, true);
    }

    private void write(Frame f, boolean requeueOnFail) {
        WebSocket w = ws;
        if (w == null || (!ready && !f.op.equals("identify"))) {
            if (requeueOnFail) synchronized (lock) {
                addPending(f);
            }
            return;
        }
        takeToken();
        try {
            w.sendText(f.json, true).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            lastError = "send failed: " + e.getClass().getSimpleName();
            if (requeueOnFail && !f.op.equals("heartbeat")) synchronized (lock) {
                addPending(f);
            }
            int gen = generation;
            w.abort();
            onClosed(gen, 1006, "send failed");
        }
    }

    private void takeToken() {
        while (true) {
            long now = System.nanoTime();
            tokens = Math.min(60, tokens + (now - tokensAt) / 1e9 * 30);
            tokensAt = now;
            if (tokens >= 1) {
                tokens -= 1;
                return;
            }
            try {
                Thread.sleep(35);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private final class Listener implements WebSocket.Listener {
        private final int gen;
        private final StringBuilder buf = new StringBuilder();

        Listener(int gen) {
            this.gen = gen;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            connectedSince = System.currentTimeMillis();
            webSocket.request(1);
            sched.schedule(() -> {
                if (gen == generation && !ready) {
                    lastError = "no handshake from the gateway within 20 s";
                    webSocket.abort();
                    onClosed(gen, 1006, "handshake timeout");
                }
            }, 20, TimeUnit.SECONDS);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buf.append(data);
            if (buf.length() > MAX_FRAME * 2) {
                buf.setLength(0);
                webSocket.sendClose(1009, "frame too big");
                return null;
            }
            if (last) {
                String s = buf.toString();
                buf.setLength(0);
                if (gen == generation) {
                    try {
                        handle(webSocket, s);
                    } catch (Exception e) {
                        core.log().warn("[Ember Link] Bad frame from Ember: " + e);
                    }
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            onClosed(gen, statusCode, reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            lastError = "error: " + error.getClass().getSimpleName() + (error.getMessage() != null ? " " + error.getMessage() : "");
            onClosed(gen, 1006, "error");
        }
    }

    private void handle(WebSocket w, String text) {
        JsonElement el = JsonParser.parseString(text);
        if (!el.isJsonObject()) return;
        JsonObject o = el.getAsJsonObject();
        String op = o.has("op") ? o.get("op").getAsString() : "";
        JsonObject d = o.has("d") && o.get("d").isJsonObject() ? o.getAsJsonObject("d") : new JsonObject();
        switch (op) {
            case "hello": {
                if (d.has("heartbeat_ms")) heartbeatMs = Math.max(5_000, d.get("heartbeat_ms").getAsLong());
                String nonce = d.has("nonce") ? d.get("nonce").getAsString() : "";
                JsonObject identify = core.buildIdentify(nonce);
                Frame f = new Frame("identify", frameJson("identify", identify, null));
                sender.execute(() -> write(f, false));
                break;
            }
            case "ready": {
                synchronized (lock) {
                    ready = true;
                }
                sessionStart = System.currentTimeMillis();
                lastAckAt = System.currentTimeMillis();
                lastError = "";
                startHeartbeat();
                sender.execute(this::flushPending);
                core.onReady(d);
                break;
            }
            case "heartbeat_ack":
                lastAckAt = System.currentTimeMillis();
                break;
            case "config":
                core.onConfig(d);
                break;
            case "chat":
                core.onBonfireChat(d);
                break;
            case "console.exec":
                core.onConsoleExec(d);
                break;
            case "link_result": {
                int id = o.has("id") && o.get("id").isJsonPrimitive() ? o.get("id").getAsInt() : -1;
                Consumer<JsonObject> cb = replies.remove(id);
                if (cb != null) cb.accept(d);
                else core.onOrphanLinkResult(d);
                break;
            }
            case "linked":
                core.onLinked(d);
                break;
            case "unlinked":
                core.onUnlinked(d);
                break;
            case "error": {
                String code = d.has("code") ? d.get("code").getAsString() : "?";
                String msg = d.has("message") ? d.get("message").getAsString() : "";
                boolean fatal = d.has("fatal") && d.get("fatal").getAsBoolean();
                if (fatal) core.log().error("[Ember Link] Ember error " + code + ": " + msg);
                else if (!code.equals("rate_limited")) core.log().warn("[Ember Link] Ember says " + code + ": " + msg);
                break;
            }
            default:
                break;
        }
    }

    private void startHeartbeat() {
        synchronized (lock) {
            if (hbTask != null) hbTask.cancel(false);
            final int gen = generation;
            hbTask = sched.scheduleAtFixedRate(() -> {
                if (gen != generation || !ready) return;
                long now = System.currentTimeMillis();
                if (now - lastAckAt > heartbeatMs * 2 + 2_000) {
                    core.log().warn("[Ember Link] No heartbeat answer from Ember, reconnecting");
                    WebSocket w = ws;
                    if (w != null) w.abort();
                    onClosed(gen, 1006, "heartbeat timeout");
                    return;
                }
                JsonObject d = new JsonObject();
                d.addProperty("seq", ++hbSeq);
                Frame f = new Frame("heartbeat", frameJson("heartbeat", d, null));
                sender.execute(() -> write(f, false));
            }, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
        }
    }

    CompletableFuture<Void> onSenderThread(Runnable r) {
        return CompletableFuture.runAsync(r, sender);
    }
}
