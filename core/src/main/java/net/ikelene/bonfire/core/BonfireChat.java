package net.ikelene.bonfire.core;

import java.util.List;

public final class BonfireChat {
    public String id;
    public String channel;
    public Author author;
    public List<Segment> segments;
    public Reply reply;

    public static final class Author {
        public String username;
        public String color;
        public Boolean bot;
        public Boolean webhook;
        public String bridge;
    }

    public static final class Reply {
        public String author;
        public String text;
    }
}
