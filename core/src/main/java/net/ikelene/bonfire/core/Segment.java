package net.ikelene.bonfire.core;

public final class Segment {
    public String text;
    public String color;
    public Boolean b;
    public Boolean i;
    public Boolean u;
    public Boolean s;
    public Boolean obf;
    public Boolean code;
    public String link;
    public String hover;

    public Segment() {
    }

    public Segment(String text) {
        this.text = text;
    }

    public static Segment of(String text, String color) {
        Segment s = new Segment(text);
        s.color = color;
        return s;
    }

    public boolean bold() {
        return Boolean.TRUE.equals(b);
    }

    public boolean italic() {
        return Boolean.TRUE.equals(i);
    }

    public boolean underlined() {
        return Boolean.TRUE.equals(u) || link != null;
    }

    public boolean strike() {
        return Boolean.TRUE.equals(s);
    }

    public boolean obfuscated() {
        return Boolean.TRUE.equals(obf);
    }

    public boolean isCode() {
        return Boolean.TRUE.equals(code);
    }

    public String effectiveColor() {
        if (isCode()) return "#aaaaaa";
        return color;
    }

    public Segment withText(String t) {
        Segment n = copy();
        n.text = t;
        return n;
    }

    public Segment copy() {
        Segment n = new Segment(text);
        n.color = color;
        n.b = b;
        n.i = i;
        n.u = u;
        n.s = s;
        n.obf = obf;
        n.code = code;
        n.link = link;
        n.hover = hover;
        return n;
    }

    public Segment inherit(Segment base) {
        if (base == null) return this;
        Segment n = copy();
        if (n.color == null) n.color = base.color;
        if (n.b == null) n.b = base.b;
        if (n.i == null) n.i = base.i;
        if (n.u == null) n.u = base.u;
        if (n.s == null) n.s = base.s;
        if (n.obf == null) n.obf = base.obf;
        return n;
    }
}
