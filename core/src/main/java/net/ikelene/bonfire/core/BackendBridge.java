package net.ikelene.bonfire.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BackendBridge {
    public interface Hooks {
        void sendEvents(UUID via, byte[] data);

        Set<UUID> onlinePlayers();

        boolean vanished(UUID uuid);

        String prefix(UUID uuid);
    }

    private final BonfireCore core;
    private final Set<UUID> helloSeen = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> lastPresence = new ConcurrentHashMap<>();
    private volatile boolean proxyConfirmed;
    private long playersSince;
    private boolean warned;
    private volatile String warnedGateway;

    BackendBridge(BonfireCore core) {
        this.core = core;
    }

    public boolean proxyConfirmed() {
        return proxyConfirmed;
    }

    public boolean helloSeen(UUID uuid) {
        return helloSeen.contains(uuid);
    }

    public void onHello(Hooks h, UUID uuid, byte[] data) {
        String json = PluginMessageCodec.decode(data);
        if (json == null) return;
        try {
            JsonElement e = JsonParser.parseString(json);
            if (!e.isJsonObject()) return;
            JsonObject o = e.getAsJsonObject();
            if (!"bonfire-link".equals(BonfireCore.str(o, "proxy"))) return;
            checkProxyGateway(BonfireCore.str(o, "gateway"));
        } catch (Exception ex) {
            return;
        }
        if (!proxyConfirmed) core.log().info("[Ember Link] Proxy confirmed: Ember Link is running on the Velocity proxy.");
        proxyConfirmed = true;
        helloSeen.add(uuid);
        lastPresence.remove(uuid);
        sendPresence(h, uuid, true);
    }

    private void checkProxyGateway(String proxyGateway) {
        if (proxyGateway == null || proxyGateway.equals(warnedGateway)) return;
        String mine;
        try {
            mine = core.config().gatewayUrl();
        } catch (IllegalArgumentException e) {
            return;
        }
        if (stripQuery(mine).equalsIgnoreCase(stripQuery(proxyGateway))) return;
        warnedGateway = proxyGateway;
        core.log().warn("[Ember Link] The Velocity proxy connects to " + stripQuery(proxyGateway) + " but this backend connects to "
                + stripQuery(mine) + ". Run `bonfire server <url>` here with the proxy's Ember address.");
    }

    private static String stripQuery(String u) {
        int q = u.indexOf('?');
        return q < 0 ? u : u.substring(0, q);
    }

    public void onQuit(UUID uuid) {
        helloSeen.remove(uuid);
        lastPresence.remove(uuid);
    }

    public void tick(Hooks h) {
        Set<UUID> online = h.onlinePlayers();
        helloSeen.retainAll(online);
        lastPresence.keySet().retainAll(online);
        for (UUID u : helloSeen) sendPresence(h, u, false);
        long now = System.currentTimeMillis();
        if (online.isEmpty() || proxyConfirmed) {
            playersSince = 0;
        } else {
            if (playersSince == 0) playersSince = now;
            if (!warned && now - playersSince > 5 * 60_000) {
                warned = true;
                core.log().warn("[Ember Link] Players have been online for 5 minutes but the proxy never said hello. Is Ember Link installed on the proxy?");
            }
        }
    }

    private void sendPresence(Hooks h, UUID uuid, boolean force) {
        boolean vanished = h.vanished(uuid);
        String prefix = core.config().useLuckPermsPrefix() ? h.prefix(uuid) : null;
        String key = vanished + "|" + prefix;
        if (!force && Objects.equals(lastPresence.get(uuid), key)) return;
        lastPresence.put(uuid, key);
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("kind", "presence");
        o.addProperty("uuid", uuid.toString());
        o.addProperty("vanished", vanished);
        if (prefix != null) o.addProperty("prefix", prefix);
        else o.add("prefix", com.google.gson.JsonNull.INSTANCE);
        send(h, uuid, o);
    }

    public void death(Hooks h, UUID uuid, String name, String text) {
        if (text == null || text.isBlank() || h.vanished(uuid)) return;
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("kind", "death");
        o.addProperty("uuid", uuid.toString());
        o.addProperty("name", name);
        o.addProperty("text", Text.clamp(Text.stripSection(text), 512));
        send(h, uuid, o);
    }

    public void advancement(Hooks h, UUID uuid, String name, JsonObject advancement) {
        if (h.vanished(uuid)) return;
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("kind", "advancement");
        o.addProperty("uuid", uuid.toString());
        o.addProperty("name", name);
        o.add("advancement", advancement);
        send(h, uuid, o);
    }

    private void send(Hooks h, UUID via, JsonObject o) {
        if (!helloSeen.contains(via)) return;
        byte[] data = PluginMessageCodec.encode(BonfireCore.GSON.toJson(o));
        if (data == null) return;
        try {
            h.sendEvents(via, data);
        } catch (Throwable t) {
            core.log().warn("[Ember Link] Could not send to the proxy: " + t);
        }
    }
}
