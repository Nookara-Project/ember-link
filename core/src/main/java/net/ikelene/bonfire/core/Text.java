package net.ikelene.bonfire.core;

import java.util.regex.Pattern;

public final class Text {
    private Text() {
    }

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]");
    private static final Pattern SECTION = Pattern.compile("(?i)§[0-9a-fk-orx]");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\t]]");
    private static final Pattern IPV4 = Pattern.compile("(?<![\\d.])(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?(?![\\d.])");
    private static final Pattern IPV6_FULL = Pattern.compile("\\[?(?<![\\w:])(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}(?![\\w:])\\]?(?::\\d{1,5})?");
    private static final Pattern IPV6_SHORT = Pattern.compile("\\[?(?<![\\w:])(?:[0-9a-fA-F]{1,4}:){1,6}:(?:[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{1,4}){0,5})?(?![\\w:])\\]?(?::\\d{1,5})?|\\[::1?\\](?::\\d{1,5})?");

    public static String stripAnsi(String s) {
        return s == null ? null : ANSI.matcher(s).replaceAll("");
    }

    public static String stripSection(String s) {
        if (s == null) return null;
        return SECTION.matcher(s).replaceAll("").replace("§", "");
    }

    public static String cleanLogLine(String s) {
        if (s == null) return "";
        return CONTROL.matcher(stripSection(stripAnsi(s))).replaceAll("");
    }

    public static String redactIps(String s) {
        if (s == null) return null;
        s = IPV4.matcher(s).replaceAll("[ip]");
        s = IPV6_FULL.matcher(s).replaceAll("[ip]");
        s = IPV6_SHORT.matcher(s).replaceAll("[ip]");
        return s;
    }

    public static String clamp(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        int end = max;
        if (Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

    public static String cleanChat(String s) {
        if (s == null) return "";
        return clamp(CONTROL.matcher(stripSection(s)).replaceAll(""), 512);
    }
}
