package net.ikelene.bonfire.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class Segments {
    private Segments() {
    }

    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final String[] LEGACY_HEX = {
            "#000000", "#0000aa", "#00aa00", "#00aaaa", "#aa0000", "#aa00aa", "#ffaa00", "#aaaaaa",
            "#555555", "#5555ff", "#55ff55", "#55ffff", "#ff5555", "#ff55ff", "#ffff55", "#ffffff"};

    public static boolean validColor(String c) {
        return c != null && HEX.matcher(c).matches();
    }

    public static boolean validLink(String l) {
        if (l == null || l.length() > 2048) return false;
        String low = l.toLowerCase(Locale.ROOT);
        return low.startsWith("https://") || low.startsWith("http://");
    }

    public static String translateAmp(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == '#' && i + 8 <= s.length() && HEX.matcher(s.substring(i + 1, i + 8)).matches()) {
                    out.append('§').append('x');
                    for (int k = i + 2; k < i + 8; k++) out.append('§').append(Character.toLowerCase(s.charAt(k)));
                    i += 7;
                    continue;
                }
                if ("0123456789abcdefklmnorxABCDEFKLMNORX".indexOf(n) >= 0) {
                    out.append('§').append(Character.toLowerCase(n));
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /** Parse section-sign legacy text (incl. the {@code §x§r§r§g§g§b§b} hex form) into segments. */
    public static List<Segment> fromLegacy(String s) {
        List<Segment> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        Segment cur = new Segment("");
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) {
                char code = Character.toLowerCase(s.charAt(i + 1));
                if (code == 'x' && i + 13 < s.length() + 0 && i + 14 <= s.length()) {
                    StringBuilder hex = new StringBuilder("#");
                    boolean ok = true;
                    for (int k = 0; k < 6; k++) {
                        int p = i + 2 + k * 2;
                        if (s.charAt(p) != '§' || "0123456789abcdefABCDEF".indexOf(s.charAt(p + 1)) < 0) {
                            ok = false;
                            break;
                        }
                        hex.append(Character.toLowerCase(s.charAt(p + 1)));
                    }
                    if (ok) {
                        flush(out, cur, buf);
                        cur = new Segment("");
                        cur.color = hex.toString();
                        i += 13;
                        continue;
                    }
                }
                int idx = "0123456789abcdef".indexOf(code);
                if (idx >= 0) {
                    flush(out, cur, buf);
                    cur = new Segment("");
                    cur.color = LEGACY_HEX[idx];
                    i++;
                    continue;
                }
                if ("klmnor".indexOf(code) >= 0) {
                    flush(out, cur, buf);
                    Segment n = code == 'r' ? new Segment("") : cur.copy();
                    n.text = "";
                    switch (code) {
                        case 'k': n.obf = true; break;
                        case 'l': n.b = true; break;
                        case 'm': n.s = true; break;
                        case 'n': n.u = true; break;
                        case 'o': n.i = true; break;
                        default: break;
                    }
                    cur = n;
                    i++;
                    continue;
                }
                i++;
                continue;
            }
            if (c == '§') continue;
            buf.append(c);
        }
        flush(out, cur, buf);
        return out;
    }

    private static void flush(List<Segment> out, Segment style, StringBuilder buf) {
        if (buf.length() == 0) return;
        out.add(style.withText(buf.toString()));
        buf.setLength(0);
    }

    public static String plain(List<Segment> segs) {
        StringBuilder sb = new StringBuilder();
        if (segs != null) for (Segment s : segs) if (s != null && s.text != null) sb.append(s.text);
        return sb.toString();
    }

    public static String toLegacy(List<Segment> segs) {
        StringBuilder sb = new StringBuilder();
        for (Segment s : segs) {
            if (s == null || s.text == null || s.text.isEmpty()) continue;
            sb.append("§r");
            String c = s.effectiveColor();
            if (validColor(c)) {
                sb.append("§x");
                for (char ch : c.substring(1).toCharArray()) sb.append('§').append(ch);
            }
            if (s.obfuscated()) sb.append("§k");
            if (s.bold()) sb.append("§l");
            if (s.strike()) sb.append("§m");
            if (s.underlined()) sb.append("§n");
            if (s.italic()) sb.append("§o");
            sb.append(s.text);
            if (s.link != null && validLink(s.link) && !s.text.contains(s.link)) {
                sb.append(" (").append(s.link).append(")");
            }
        }
        return sb.toString();
    }

    public static List<Segment> sanitize(List<Segment> in, int maxSegments) {
        List<Segment> out = new ArrayList<>();
        if (in == null) return out;
        for (Segment s : in) {
            if (s == null || s.text == null) continue;
            if (out.size() >= maxSegments) break;
            Segment n = s.copy();
            n.text = Text.stripSection(n.text).replace('\n', ' ').replace('\r', ' ');
            if (n.color != null && !validColor(n.color)) n.color = null;
            if (n.link != null && !validLink(n.link)) n.link = null;
            if (n.hover != null) n.hover = Text.clamp(Text.stripSection(n.hover), 500);
            out.add(n);
        }
        return out;
    }

    public static List<Segment> clampMotd(List<Segment> in) {
        List<Segment> out = new ArrayList<>();
        int total = 0;
        if (in == null) return out;
        for (Segment s : in) {
            if (s == null || s.text == null || s.text.isEmpty()) continue;
            if (out.size() >= 20 || total >= 256) break;
            Segment n = s.copy();
            n.link = null;
            n.hover = null;
            n.code = null;
            if (n.color != null && !validColor(n.color)) n.color = null;
            n.text = Text.clamp(n.text, 256 - total);
            total += n.text.length();
            out.add(n);
        }
        return out;
    }
}
