package net.ikelene.bonfire.core;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface Platform {
    String platformId();

    String software();

    String mcVersion();

    String modVersion();

    Path dataDir();

    Log log();

    StatusSnapshot status();

    byte[] serverIconPng();

    List<Segment> motd();

    int maxPlayers();

    boolean onlineMode();

    void broadcast(List<List<Segment>> lines);

    CompletableFuture<CommandResult> runConsoleCommand(String cmd);

    void runSync(Runnable r);

    Mode mode();

    default ClassLoader serverClassLoader() {
        return Platform.class.getClassLoader();
    }
}
