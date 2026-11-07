package net.ikelene.bonfire.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public final class Skins {
    private static final Pattern TEXTURE = Pattern.compile("^https://textures\\.minecraft\\.net/texture/[0-9a-f]{16,80}$");

    private final Map<UUID, String> known = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, String> e) {
            return size() > 500;
        }
    });

    public static String clean(String url) {
        if (url == null) return null;
        if (url.startsWith("http://textures.minecraft.net/")) url = "https://" + url.substring(7);
        return TEXTURE.matcher(url).matches() ? url : null;
    }

    public void note(UUID uuid, String url) {
        if (uuid == null) return;
        String u = clean(url);
        if (u != null) known.put(uuid, u);
        else known.remove(uuid);
    }

    public String of(UUID uuid) {
        return uuid == null ? null : known.get(uuid);
    }

    public static String fromTexturesProperty(String base64) {
        if (base64 == null || base64.isEmpty() || base64.length() > 8192) return null;
        try {
            JsonObject o = JsonParser.parseString(new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonElement skin = o.getAsJsonObject("textures").getAsJsonObject("SKIN").get("url");
            return skin == null ? null : clean(skin.getAsString());
        } catch (Exception e) {
            return null;
        }
    }
}
