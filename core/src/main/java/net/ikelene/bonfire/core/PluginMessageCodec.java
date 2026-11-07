package net.ikelene.bonfire.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class PluginMessageCodec {
    public static final String CHANNEL_EVENTS = "bonfire:events";
    public static final String CHANNEL_HELLO = "bonfire:hello";
    public static final int MAX_PAYLOAD = 8192;

    private PluginMessageCodec() {
    }

    public static byte[] encode(String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + 5);
        int v = body.length;
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
        out.write(body, 0, body.length);
        byte[] r = out.toByteArray();
        return r.length > MAX_PAYLOAD ? null : r;
    }

    public static String decode(byte[] data) {
        if (data == null || data.length == 0 || data.length > MAX_PAYLOAD) return null;
        int len = 0;
        int shift = 0;
        int pos = 0;
        while (true) {
            if (pos >= data.length || shift > 28) return null;
            int b = data[pos++] & 0xFF;
            len |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        if (len < 0 || pos + len != data.length) return null;
        return new String(data, pos, len, StandardCharsets.UTF_8);
    }
}
