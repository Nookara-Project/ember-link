package net.ikelene.bonfire.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CoreTest {

    @Test
    void identityVectors() throws Exception {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) 1);
        Identity id = Identity.fromSeed(seed);
        byte[] spki = Base64.getDecoder().decode(id.publicKeyB64());
        assertEquals(44, spki.length);
        assertEquals(60, id.publicKeyB64().length());
        assertTrue(id.iid().matches("g[0-9a-f]{16}"));
        assertTrue(id.pairingTag().matches("[0-9A-HJKMNP-TV-Z]{3}-[0-9A-HJKMNP-TV-Z]{3}"));

        String nonce = "test-nonce";
        String sig = id.signHandshake(nonce);
        String input = "bonfire-gw-v1|" + nonce + "|" + id.iid();
        assertTrue(id.verify(input.getBytes(StandardCharsets.UTF_8), Base64.getDecoder().decode(sig)));

        String vp = System.getProperty("bonfire.vectors");
        if (vp == null) return;
        Path p = Path.of(vp);
        if (Files.exists(p)) {
            JsonObject o = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            if (o.has("spki_b64")) assertEquals(o.get("spki_b64").getAsString(), id.publicKeyB64(), "spki");
            if (o.has("iid")) assertEquals(o.get("iid").getAsString(), id.iid(), "iid");
            if (o.has("pairing_tag")) assertEquals(o.get("pairing_tag").getAsString(), id.pairingTag(), "pairing tag");
            if (o.has("sig_b64")) assertEquals(o.get("sig_b64").getAsString(), sig, "signature");
        } else {
            JsonObject o = new JsonObject();
            o.addProperty("comment", "Ember Link shared test vectors (spec 8.1). Written by the Java core test.");
            o.addProperty("seed_hex", BonfireCore.hex(seed));
            o.addProperty("pkcs8_b64", id.privateKeyB64());
            o.addProperty("spki_b64", id.publicKeyB64());
            o.addProperty("iid", id.iid());
            o.addProperty("pairing_tag", id.pairingTag());
            o.addProperty("nonce", nonce);
            o.addProperty("signed_string", input);
            o.addProperty("sig_b64", sig);
            Files.writeString(p, new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(o) + "\n");
        }
    }

    @Test
    void tagFromKnownHash() {
        byte[] h = new byte[32];
        h[8] = h[9] = h[10] = h[11] = (byte) 0xFF;
        assertEquals("ZZZ-ZZZ", Identity.computeTag(h));
        h[8] = h[9] = h[10] = h[11] = 0;
        assertEquals("000-000", Identity.computeTag(h));
        h[8] = 0x04;
        assertEquals("0G0-000", Identity.computeTag(h));
    }

    @Test
    void claimCodes() {
        assertEquals("7KQ2M9XA", Identity.normalizeClaimCode("7kq2-m9xa"));
        assertEquals("00110000", Identity.normalizeClaimCode("OOIL-0000"));
        assertNull(Identity.normalizeClaimCode("7KQ2-M9X"));
        assertNull(Identity.normalizeClaimCode("7KQ2-M9XU"));
    }

    @Test
    void codec() {
        String json = "{\"v\":1,\"kind\":\"presence\",\"x\":\"" + "a".repeat(300) + "\"}";
        byte[] enc = PluginMessageCodec.encode(json);
        assertNotNull(enc);
        assertTrue((enc[0] & 0x80) != 0);
        assertEquals(json, PluginMessageCodec.decode(enc));
        assertNull(PluginMessageCodec.decode(json.getBytes(StandardCharsets.UTF_8)));
        assertNull(PluginMessageCodec.encode("x".repeat(9000)));
        byte[] small = PluginMessageCodec.encode("{}");
        assertArrayEquals(new byte[]{2, '{', '}'}, small);
    }

    @Test
    void legacyAndFormat() {
        List<Segment> segs = Segments.fromLegacy("§aHello §lWorld§r!");
        assertEquals(3, segs.size());
        assertEquals("#55ff55", segs.get(0).color);
        assertTrue(segs.get(1).bold());
        assertNull(segs.get(2).color);
        List<Segment> hex = Segments.fromLegacy("§x§2§2§f§f§8§4gamble");
        assertEquals("#22ff84", hex.get(0).color);
        assertEquals("gamble", hex.get(0).text);

        BonfireChat m = new BonfireChat();
        m.channel = "mc-chat";
        m.author = new BonfireChat.Author();
        m.author.username = "Ikelene";
        m.author.color = "#ff7ac6";
        Segment a = new Segment("hey §cred ");
        Segment b = Segment.of("@Nat", "#8ab4ff");
        Segment c = new Segment("[image]");
        c.link = "https://example.com/x.png";
        Segment evil = new Segment("click");
        evil.link = "javascript:alert(1)";
        m.segments = List.of(a, b, c, evil);
        List<List<Segment>> lines = ChatFormatter.format("&8[&6Bonfire&8] &f{name}&7: &f{message}", m);
        assertEquals(1, lines.size());
        String plain = Segments.plain(lines.get(0));
        assertEquals("[Ember] Ikelene: hey red @Nat[image]click", plain);
        Segment name = lines.get(0).stream().filter(s -> s.text.equals("Ikelene")).findFirst().orElseThrow();
        assertEquals("#ff7ac6", name.color);
        Segment link = lines.get(0).stream().filter(s -> s.text.equals("[image]")).findFirst().orElseThrow();
        assertEquals("https://example.com/x.png", link.link);
        Segment bad = lines.get(0).stream().filter(s -> s.text.equals("click")).findFirst().orElseThrow();
        assertNull(bad.link);
        Segment hey = lines.get(0).stream().filter(s -> s.text.startsWith("hey")).findFirst().orElseThrow();
        assertEquals("#ffffff", hey.color);

        m.reply = new BonfireChat.Reply();
        m.reply.author = "Nat";
        m.reply.text = "hi";
        m.author.bot = true;
        lines = ChatFormatter.format("{name}: {message}", m);
        assertEquals(2, lines.size());
        assertEquals("> Nat: \"hi\"", Segments.plain(lines.get(0)));
        assertTrue(Segments.plain(lines.get(1)).startsWith("[BOT] Ikelene: "));

        List<Segment> filled = ChatFormatter.fill("&8[&7{server}&8] &f<{name}> {message}",
                Map.of("{server}", List.of(new Segment("survival")), "{name}", List.of(new Segment("Nat")), "{message}", List.of(new Segment("&chi"))));
        assertEquals("[survival] <Nat> &chi", Segments.plain(filled));
    }

    @Test
    void prefixes() {
        assertEquals("[Admin]", Prefixes.toPlain("&c[Admin] "));
        assertEquals("[Owner]", Prefixes.toPlain("<gradient:#ff0000:#00ff00>[Owner]</gradient>"));
        assertEquals("VIP", Prefixes.toPlain("&#ff00ffVIP"));
        assertEquals("Mod", Prefixes.toPlain("§x§f§f§0§0§f§fMod"));
        assertEquals("X", Prefixes.toPlain("{#123456}X"));
        assertNull(Prefixes.toPlain("&c &l "));
        assertNull(Prefixes.toPlain(null));
        assertEquals(32, Prefixes.toPlain("a".repeat(50)).length());
    }

    @Test
    void textHelpers() {
        assertEquals("Nat[/[ip]] logged in", Text.redactIps("Nat[/1.2.3.4:5678] logged in"));
        assertEquals("at 12:34:56 ok", Text.redactIps("at 12:34:56 ok"));
        assertEquals("from [ip] x", Text.redactIps("from 2001:db8::1 x"));
        assertEquals("[ip]", Text.redactIps("[0:0:0:0:0:0:0:1]:25565"));
        assertEquals("hi", Text.cleanLogLine("\u001B[32mhi\u001B[0m"));
        assertEquals("abc", Text.stripSection("§aabc"));
    }

    @Test
    void icons() {
        byte[] png = new byte[40];
        byte[] magic = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, png, 0, 8);
        png[19] = 64;
        png[23] = 64;
        assertTrue(BonfireCore.validIcon(png));
        png[19] = (byte) 128;
        assertFalse(BonfireCore.validIcon(png));
    }

    @Test
    void selfHostedServerUrls() {
        assertNull(Config.gatewayFor(""));
        assertNull(Config.gatewayFor("velora"));
        assertNull(Config.gatewayFor("https://velora.ikelene.net/chat"));
        assertEquals("wss://chat.example.com/game-gateway", Config.gatewayFor("https://chat.example.com"));
        assertEquals("wss://chat.example.com/game-gateway", Config.gatewayFor("chat.example.com/"));
        assertEquals("wss://chat.example.com/game-gateway", Config.gatewayFor("wss://Chat.Example.com/game-gateway"));
        assertEquals("ws://192.168.1.5:4100/game-gateway", Config.gatewayFor("http://192.168.1.5:4100"));
        assertEquals("wss://example.com/bonfire/game-gateway", Config.gatewayFor("https://example.com/bonfire/"));
        assertThrows(IllegalArgumentException.class, () -> Config.gatewayFor("ftp://example.com"));
        assertThrows(IllegalArgumentException.class, () -> Config.gatewayFor("https://example.com/?x=1"));
        assertThrows(IllegalArgumentException.class, () -> Config.gatewayFor("https://user:pw@example.com"));
        assertThrows(IllegalArgumentException.class, () -> Config.gatewayFor("https:///nohost"));
    }

    @Test
    void saveServerKeepsTheRestOfTheConfig() throws Exception {
        Path dir = Files.createTempDirectory("bonfire-cfg");
        Log log = new Log() {
            public void info(String m) {}
            public void warn(String m) {}
            public void error(String m, Throwable t) {}
        };
        Config c = new Config();
        c.load(dir, log);
        assertEquals(Config.VELORA_GATEWAY + "?v=1", c.gatewayUrl());
        assertFalse(c.selfHosted());

        Path f = dir.resolve(Config.FILE);
        Files.writeString(f, Files.readString(f).replace("listing=public", "listing=private"));
        assertEquals("https://chat.example.com", c.saveServer(dir, "chat.example.com", log));
        assertEquals("wss://chat.example.com/game-gateway?v=1", c.gatewayUrl());
        assertEquals("chat.example.com", c.serverLabel());
        assertEquals("private", c.listing());
        assertTrue(c.selfHosted());

        Files.writeString(f, "gateway-url=wss://velora.ikelene.net/chat/game-gateway\nlisting=private\n");
        c.load(dir, log);
        c.saveServer(dir, "http://localhost:4100", log);
        assertEquals("ws://localhost:4100/game-gateway?v=1", c.gatewayUrl());
        assertEquals(1, Files.readAllLines(f).stream().filter(l -> l.startsWith("server=")).count());

        assertNull(c.saveServer(dir, "velora", log));
        assertEquals(Config.VELORA_GATEWAY + "?v=1", c.gatewayUrl());
        assertEquals("private", c.listing());

        Files.writeString(f, "server=ftp://nope\n");
        c.load(dir, log);
        assertThrows(IllegalArgumentException.class, c::gatewayUrl);
        assertTrue(c.selfHosted());
    }

    @Test
    void skinTextures() {
        String url = "https://textures.minecraft.net/texture/0a1b2c3d4e5f60718293a4b5c6d7e8f9";
        String prop = Base64.getEncoder().encodeToString(("{\"timestamp\":1,\"profileId\":\"x\",\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}").getBytes(StandardCharsets.UTF_8));
        assertEquals(url, Skins.fromTexturesProperty(prop));
        assertNull(Skins.fromTexturesProperty(null));
        assertNull(Skins.fromTexturesProperty("not base64!"));
        assertNull(Skins.fromTexturesProperty(Base64.getEncoder().encodeToString("{\"textures\":{}}".getBytes(StandardCharsets.UTF_8))));
        String evil = Base64.getEncoder().encodeToString("{\"textures\":{\"SKIN\":{\"url\":\"https://evil.example/texture/0a1b2c3d4e5f60718293a4b5c6d7e8f9\"}}}".getBytes(StandardCharsets.UTF_8));
        assertNull(Skins.fromTexturesProperty(evil));
        Skins s = new Skins();
        java.util.UUID id = java.util.UUID.randomUUID();
        s.note(id, url);
        assertEquals(url, s.of(id));
        assertEquals(url, Skins.fromTexturesProperty(Base64.getEncoder().encodeToString(("{\"textures\":{\"SKIN\":{\"url\":\"" + url.replace("https", "http") + "\"}}}").getBytes(StandardCharsets.UTF_8))));
        s.note(id, "https://evil.example/texture/0a1b2c3d4e5f60718293a4b5c6d7e8f9");
        assertNull(s.of(id));
    }
}
