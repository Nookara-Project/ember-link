package net.ikelene.bonfire.core;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

public final class Config {
    public static final String FILE = "config.properties";

    private static final String TEMPLATE = String.join("\n",
            "# Ember Link - connects this server to an Ember server (on Velora, or a self-hosted Ember instance).",
            "# This file is safe to share. The server's identity lives in identity.properties (keep that one private).",
            "# The Ember instance to connect to, e.g. https://chat.example.com. Empty = Velora (velora.ikelene.net).",
            "# Easiest to set with `bonfire server <url>` in the console.",
            "server=",
            "# Advanced: full gateway URL, only used when server= is empty.",
            "gateway-url=wss://velora.ikelene.net/chat/game-gateway",
            "# auto | standalone | proxied   (proxy = Velocity, always)",
            "mode=auto",
            "# public  = shows in Ember's global \"Searching for servers\" list (MOTD, icon, version, player count only)",
            "# private = hidden unless someone types this server's pairing tag",
            "listing=public",
            "# Shown on the join-info card (players connect here). Empty = set it in Ember instead.",
            "public-address=",
            "chat.enabled=true",
            "chat.use-luckperms-prefix=true",
            "# Format of Ember messages in game. {name} {message} {channel}",
            "chat.format=&8[&6Bonfire&8] &f{name}&7: &f{message}",
            "events.enabled=true",
            "console.enabled=true",
            "console.exec-enabled=true",
            "console.redact-ips=true",
            "console.report-local-commands=true",
            "# always treated as vanished (names or UUIDs, comma-separated)",
            "events.hidden-players=",
            "# Velocity only",
            "proxy.cross-server-chat=true",
            "proxy.cross-server-format=&8[&7{server}&8] &f<{name}> {message}",
            "proxy.network-messages=true",
            "proxy.excluded-servers=",
            "# Names shown in Ember for backends, e.g. survival=Survival,creative=Creative",
            "proxy.server-names=",
            "");

    private final Properties props = new Properties();
    private final Properties defaults = new Properties();

    private Set<String> hiddenNames = Set.of();
    private Set<UUID> hiddenUuids = Set.of();
    private Set<String> excludedServers = Set.of();
    private Map<String, String> serverNames = Map.of();

    public Config() {
        try {
            defaults.load(new java.io.StringReader(TEMPLATE));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public void load(Path dir, Log log) {
        Path f = dir.resolve(FILE);
        props.clear();
        try {
            Files.createDirectories(dir);
            if (!Files.exists(f)) {
                Files.writeString(f, TEMPLATE, StandardCharsets.UTF_8);
            }
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                props.load(r);
            }
        } catch (IOException e) {
            log.error("[Ember Link] Could not read " + f + ", using defaults", e);
        }
        hiddenNames = new HashSet<>();
        hiddenUuids = new HashSet<>();
        for (String s : list("events.hidden-players")) {
            try {
                hiddenUuids.add(UUID.fromString(s));
            } catch (IllegalArgumentException e) {
                hiddenNames.add(s.toLowerCase(Locale.ROOT));
            }
        }
        try {
            gatewayFor(get("server"));
        } catch (IllegalArgumentException e) {
            log.error("[Ember Link] server=" + get("server") + " in " + f + " is not usable: " + e.getMessage()
                    + ". Fix it or run `bonfire server <url>`.");
        }
        excludedServers = new HashSet<>();
        for (String s : list("proxy.excluded-servers")) excludedServers.add(s.toLowerCase(Locale.ROOT));
        serverNames = new LinkedHashMap<>();
        for (String s : list("proxy.server-names")) {
            int eq = s.indexOf('=');
            if (eq > 0) serverNames.put(s.substring(0, eq).trim().toLowerCase(Locale.ROOT), s.substring(eq + 1).trim());
        }
    }

    public String get(String key) {
        String v = props.getProperty(key);
        if (v == null) v = defaults.getProperty(key, "");
        return v.trim();
    }

    public boolean bool(String key) {
        String v = get(key);
        if (v.isEmpty()) v = defaults.getProperty(key, "false");
        return v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes") || v.equals("1");
    }

    private java.util.List<String> list(String key) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String s : get(key).split(",")) {
            s = s.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    public String gatewayUrl() {
        String u = gatewayFor(get("server"));
        if (u == null) u = get("gateway-url");
        if (u.isEmpty()) u = VELORA_GATEWAY;
        if (!u.contains("?")) u = u + "?v=1";
        else if (!u.contains("v=")) u = u + "&v=1";
        return u;
    }

    public String server() {
        String g = gatewayFor(get("server"));
        return g == null ? null : baseOf(g);
    }

    public boolean selfHosted() {
        try {
            return server() != null;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    public String serverLabel() {
        String s;
        try {
            s = server();
        } catch (IllegalArgumentException e) {
            return "invalid server= in " + FILE + " (" + e.getMessage() + ")";
        }
        if (s != null) return s.replaceFirst("^https?://", "");
        String g = get("gateway-url");
        if (g.isEmpty() || g.startsWith(VELORA_GATEWAY)) return "Velora (velora.ikelene.net)";
        return g;
    }

    public static final String VELORA_GATEWAY = "wss://velora.ikelene.net/chat/game-gateway";

    public static String gatewayFor(String server) {
        if (server == null) return null;
        String s = server.trim();
        if (s.isEmpty() || s.equalsIgnoreCase("velora") || s.equalsIgnoreCase("default")) return null;
        if (!s.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) s = "https://" + s;
        java.net.URI u;
        try {
            u = new java.net.URI(s);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("not a valid URL");
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        String ws;
        switch (scheme) {
            case "https": case "wss": ws = "wss"; break;
            case "http": case "ws": ws = "ws"; break;
            default: throw new IllegalArgumentException("use an https:// address");
        }
        String host = u.getHost();
        if (host == null || host.isEmpty()) throw new IllegalArgumentException("the address needs a host name");
        if (u.getRawQuery() != null || u.getRawFragment() != null || u.getRawUserInfo() != null) {
            throw new IllegalArgumentException("leave out ?query, #fragment and user:password@");
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.equals("velora.ikelene.net")) return null;
        String path = u.getRawPath() == null ? "" : u.getRawPath();
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.endsWith("/game-gateway")) path = path.substring(0, path.length() - "/game-gateway".length());
        String port = u.getPort() == -1 ? "" : ":" + u.getPort();
        return ws + "://" + host + port + path + "/game-gateway";
    }

    static String baseOf(String gateway) {
        String b = gateway.replaceFirst("^wss://", "https://").replaceFirst("^ws://", "http://");
        return b.substring(0, b.length() - "/game-gateway".length());
    }

    public String saveServer(Path dir, String value, Log log) throws IOException {
        String g = gatewayFor(value);
        String base = g == null ? "" : baseOf(g);
        Path f = dir.resolve(FILE);
        Files.createDirectories(dir);
        java.util.List<String> lines = Files.exists(f)
                ? new java.util.ArrayList<>(Files.readAllLines(f, StandardCharsets.UTF_8))
                : new java.util.ArrayList<>(java.util.Arrays.asList(TEMPLATE.split("\n")));
        boolean done = false;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).matches("^\\s*server\\s*[=:].*")) {
                if (done) {
                    lines.remove(i--);
                } else {
                    lines.set(i, "server=" + base);
                    done = true;
                }
            }
        }
        if (!done) {
            int at = 0;
            while (at < lines.size() && lines.get(at).trim().startsWith("#")) at++;
            lines.add(at, "server=" + base);
            lines.add(at, "# The Ember instance to connect to (set with `bonfire server <url>`). Empty = Velora.");
        }
        Path tmp = dir.resolve(FILE + ".tmp");
        Files.writeString(tmp, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        try {
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        load(dir, log);
        return g == null ? null : base;
    }

    public String mode() {
        return get("mode").toLowerCase(Locale.ROOT);
    }

    public String listing() {
        return get("listing").equalsIgnoreCase("private") ? "private" : "public";
    }

    public String publicAddress() {
        String a = get("public-address");
        return a.length() > 120 ? a.substring(0, 120) : a;
    }

    public boolean chatEnabled() {
        return bool("chat.enabled");
    }

    public boolean useLuckPermsPrefix() {
        return bool("chat.use-luckperms-prefix");
    }

    public String chatFormat() {
        return get("chat.format");
    }

    public boolean eventsEnabled() {
        return bool("events.enabled");
    }

    public boolean consoleEnabled() {
        return bool("console.enabled");
    }

    public boolean consoleExecEnabled() {
        return consoleEnabled() && bool("console.exec-enabled");
    }

    public boolean redactIps() {
        return bool("console.redact-ips");
    }

    public boolean reportLocalCommands() {
        return bool("console.report-local-commands");
    }

    public boolean crossServerChat() {
        return bool("proxy.cross-server-chat");
    }

    public String crossServerFormat() {
        return get("proxy.cross-server-format");
    }

    public boolean networkMessages() {
        return bool("proxy.network-messages");
    }

    public boolean isExcludedServer(String backend) {
        return backend != null && excludedServers.contains(backend.toLowerCase(Locale.ROOT));
    }

    public String serverDisplayName(String backend) {
        if (backend == null) return null;
        String n = serverNames.get(backend.toLowerCase(Locale.ROOT));
        return n == null || n.isEmpty() ? backend : n;
    }

    public boolean isAlwaysHidden(UUID uuid, String name) {
        if (uuid != null && hiddenUuids.contains(uuid)) return true;
        return name != null && hiddenNames.contains(name.toLowerCase(Locale.ROOT));
    }
}
