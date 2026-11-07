package net.ikelene.bonfire.core.adventure;

import net.ikelene.bonfire.core.Segment;
import net.ikelene.bonfire.core.Segments;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.flattener.ComponentFlattener;
import net.kyori.adventure.text.flattener.FlattenerListener;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class AdventureSegments {
    private AdventureSegments() {
    }

    public static Component toComponent(List<Segment> segs) {
        TextComponent.Builder b = Component.text();
        for (Segment s : segs) {
            if (s == null || s.text == null || s.text.isEmpty()) continue;
            Style.Builder st = Style.style();
            String c = s.effectiveColor();
            if (Segments.validColor(c)) st.color(TextColor.fromHexString(c));
            else st.color(TextColor.color(0xFFFFFF));
            st.decoration(TextDecoration.BOLD, s.bold());
            st.decoration(TextDecoration.ITALIC, s.italic());
            st.decoration(TextDecoration.UNDERLINED, s.underlined());
            st.decoration(TextDecoration.STRIKETHROUGH, s.strike());
            st.decoration(TextDecoration.OBFUSCATED, s.obfuscated());
            if (s.link != null && Segments.validLink(s.link)) st.clickEvent(ClickEvent.openUrl(s.link));
            String hover = s.hover;
            if (hover == null && s.link != null && Segments.validLink(s.link)) hover = s.link;
            if (hover != null) st.hoverEvent(HoverEvent.showText(Component.text(hover)));
            b.append(Component.text(s.text, st.build()));
        }
        return b.build();
    }

    public static List<Segment> fromComponent(Component c) {
        List<Segment> out = new ArrayList<>();
        if (c == null) return out;
        Deque<Style> stack = new ArrayDeque<>();
        stack.push(Style.empty());
        ComponentFlattener.basic().flatten(c, new FlattenerListener() {
            @Override
            public void pushStyle(Style style) {
                stack.push(stack.peek().merge(style));
            }

            @Override
            public void component(String text) {
                if (text == null || text.isEmpty()) return;
                Style st = stack.peek();
                Segment s = new Segment(text);
                if (st.color() != null) s.color = st.color().asHexString().toLowerCase(java.util.Locale.ROOT);
                if (st.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE) s.b = true;
                if (st.decoration(TextDecoration.ITALIC) == TextDecoration.State.TRUE) s.i = true;
                if (st.decoration(TextDecoration.UNDERLINED) == TextDecoration.State.TRUE) s.u = true;
                if (st.decoration(TextDecoration.STRIKETHROUGH) == TextDecoration.State.TRUE) s.s = true;
                if (st.decoration(TextDecoration.OBFUSCATED) == TextDecoration.State.TRUE) s.obf = true;
                out.add(s);
            }

            @Override
            public void popStyle(Style style) {
                if (stack.size() > 1) stack.pop();
            }
        });
        return out;
    }

    public static String plain(Component c) {
        StringBuilder sb = new StringBuilder();
        for (Segment s : fromComponent(c)) sb.append(s.text);
        return sb.toString();
    }
}
