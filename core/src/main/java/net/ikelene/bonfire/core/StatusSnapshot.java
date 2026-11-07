package net.ikelene.bonfire.core;

import java.util.ArrayList;
import java.util.List;

public final class StatusSnapshot {
    public boolean online = true;
    public String version;
    public List<Segment> motd;
    public Players players = new Players();
    public Double tps;
    public long uptime_s;
    public List<Backend> backends;

    public static final class Players {
        public int online;
        public int max;
        public List<PlayerEntry> list = new ArrayList<>();
    }

    public static final class PlayerEntry {
        public String uuid;
        public String name;
        public String display;
        public String server;

        public PlayerEntry() {
        }

        public PlayerEntry(String uuid, String name, String display, String server) {
            this.uuid = uuid;
            this.name = name;
            this.display = display;
            this.server = server;
        }
    }

    public static final class Backend {
        public String name;
        public boolean online;
        public String version;
        public List<Segment> motd;
        public Players players;
    }
}
