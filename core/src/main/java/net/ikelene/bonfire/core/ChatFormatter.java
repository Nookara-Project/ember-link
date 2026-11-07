package net.ikelene.bonfire.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ChatFormatter {
    private ChatFormatter() {
    }

    public static List<List<Segment>> format(String chatFormat, BonfireChat m) {
        List<List<Segment>> lines = new ArrayList<>();
        BonfireChat.Author a = m.author != null ? m.author : new BonfireChat.Author();
        if (m.reply != null && m.reply.author != null) {
            String t = Text.clamp(Text.stripSection(m.reply.text == null ? "" : m.reply.text), 80);
            List<Segment> reply = new ArrayList<>();
            reply.add(Segment.of("> " + Text.stripSection(m.reply.author) + ": \"" + t + "\"", "#aaaaaa"));
            lines.add(reply);
        }

        List<Segment> name = new ArrayList<>();
        String tag = null;
        if (Boolean.TRUE.equals(a.bot)) tag = "[BOT]";
        else if (a.bridge != null && a.bridge.equalsIgnoreCase("discord")) tag = "[Discord]";
        else if (a.bridge != null && a.bridge.equalsIgnoreCase("fluxer")) tag = "[Fluxer]";
        else if (Boolean.TRUE.equals(a.webhook) || a.bridge != null) tag = "[APP]";
        if (tag != null) name.add(Segment.of(tag + " ", "#aaaaaa"));
        Segment n = new Segment(Text.clamp(Text.stripSection(a.username == null ? "?" : a.username), 64));
        if (Segments.validColor(a.color)) n.color = a.color.toLowerCase();
        name.add(n);

        List<Segment> message = Segments.sanitize(m.segments, 200);
        List<Segment> channel = new ArrayList<>();
        channel.add(new Segment(m.channel == null ? "" : Text.stripSection(m.channel)));

        lines.add(fill(chatFormat, Map.of("{name}", name, "{message}", message, "{channel}", channel)));
        return lines;
    }

    public static List<Segment> fill(String format, Map<String, List<Segment>> placeholders) {
        List<Segment> base = Segments.fromLegacy(Segments.translateAmp(format));
        List<Segment> out = new ArrayList<>();
        for (Segment seg : base) {
            String t = seg.text;
            int pos = 0;
            while (pos < t.length()) {
                int best = -1;
                String key = null;
                for (String k : placeholders.keySet()) {
                    int at = t.indexOf(k, pos);
                    if (at >= 0 && (best < 0 || at < best)) {
                        best = at;
                        key = k;
                    }
                }
                if (best < 0) {
                    out.add(seg.withText(t.substring(pos)));
                    break;
                }
                if (best > pos) out.add(seg.withText(t.substring(pos, best)));
                for (Segment p : placeholders.get(key)) out.add(p.inherit(seg));
                pos = best + key.length();
            }
        }
        out.removeIf(s -> s.text == null || s.text.isEmpty());
        return out;
    }
}
