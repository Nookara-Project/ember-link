package net.ikelene.bonfire.core;

import java.util.UUID;
import java.util.regex.Pattern;

public final class Prefixes {
    private Prefixes() {
    }

    private static final Pattern AMP_HEX = Pattern.compile("[&§]#[0-9a-fA-F]{6}");
    private static final Pattern BRACE_HEX = Pattern.compile("\\{#[0-9a-fA-F]{6}\\}");
    private static final Pattern SECTION_HEX = Pattern.compile("(?i)[&§]x([&§][0-9a-f]){6}");
    private static final Pattern LEGACY = Pattern.compile("(?i)[&§][0-9a-fk-or]");
    private static final Pattern MINIMESSAGE = Pattern.compile("</?[a-zA-Z#!_][^<>]{0,120}>");
    private static final Pattern WS = Pattern.compile("\\s+");

    private static volatile Boolean available;

    public static String toPlain(String raw) {
        if (raw == null) return null;
        String s = SECTION_HEX.matcher(raw).replaceAll("");
        s = AMP_HEX.matcher(s).replaceAll("");
        s = BRACE_HEX.matcher(s).replaceAll("");
        s = LEGACY.matcher(s).replaceAll("");
        s = MINIMESSAGE.matcher(s).replaceAll("");
        s = s.replace("§", "");
        s = s.replaceAll("[\\p{Cntrl}\\p{Cf}]", "");
        s = WS.matcher(s).replaceAll(" ").trim();
        s = Text.clamp(s, 32).trim();
        boolean printable = false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) {
                printable = true;
                break;
            }
        }
        return printable ? s : null;
    }

    public static String display(String prefix, String name) {
        if (prefix == null || prefix.isEmpty()) return null;
        return prefix + " " + name;
    }

    public static boolean luckPermsPresent() {
        Boolean a = available;
        if (a == null) {
            try {
                Class.forName("net.luckperms.api.LuckPermsProvider");
                a = true;
            } catch (Throwable t) {
                a = false;
            }
            available = a;
        }
        return a;
    }

    public static String luckPermsPrefix(UUID uuid) {
        if (uuid == null || !luckPermsPresent()) return null;
        try {
            return toPlain(LuckPermsHook.prefix(uuid));
        } catch (Throwable t) {
            return null;
        }
    }

    private static final class LuckPermsHook {
        static String prefix(UUID uuid) {
            net.luckperms.api.model.user.User u = net.luckperms.api.LuckPermsProvider.get().getUserManager().getUser(uuid);
            if (u == null) return null;
            return u.getCachedData().getMetaData().getPrefix();
        }
    }
}
