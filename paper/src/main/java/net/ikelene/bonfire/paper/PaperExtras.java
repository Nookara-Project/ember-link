package net.ikelene.bonfire.paper;

import io.papermc.paper.advancement.AdvancementDisplay;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.ikelene.bonfire.core.CommandResult;
import net.ikelene.bonfire.core.Segment;
import net.ikelene.bonfire.core.adventure.AdventureSegments;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

final class PaperExtras {
    private PaperExtras() {
    }

    private static String plain(Component c) {
        return c == null ? "" : PlainTextComponentSerializer.plainText().serialize(c);
    }

    static void registerChat(Plugin plugin, BiConsumer<Player, String> sink) {
        plugin.getServer().getPluginManager().registerEvent(AsyncChatEvent.class, new Listener() {
        }, EventPriority.MONITOR, (l, e) -> {
            if (e instanceof AsyncChatEvent) {
                AsyncChatEvent ce = (AsyncChatEvent) e;
                sink.accept(ce.getPlayer(), plain(ce.message()));
            }
        }, plugin, true);
    }

    static void broadcast(List<List<Segment>> lines) {
        for (List<Segment> l : lines) Bukkit.broadcast(AdventureSegments.toComponent(l));
    }

    static String[] advancement(PlayerAdvancementDoneEvent e) {
        if (e.message() == null) return null;
        AdvancementDisplay d = e.getAdvancement().getDisplay();
        if (d == null || !d.doesAnnounceToChat()) return null;
        return new String[]{plain(d.title()), plain(d.description()), d.frame().name().toLowerCase(Locale.ROOT)};
    }

    static double tps() {
        return Bukkit.getTPS()[0];
    }

    static CompletableFuture<CommandResult> runCaptured(Plugin plugin, String cmd) {
        CompletableFuture<CommandResult> f = new CompletableFuture<>();
        List<String> out = Collections.synchronizedList(new ArrayList<>());
        Runnable task = () -> {
            try {
                CommandSender s = Bukkit.createCommandSender(c -> out.add(plain(c)));
                boolean found = Bukkit.dispatchCommand(s, cmd);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    List<String> copy;
                    synchronized (out) {
                        copy = new ArrayList<>(out);
                    }
                    f.complete(found ? CommandResult.ok(copy) : CommandResult.fail("unknown_command", copy.isEmpty() ? List.of("Unknown command.") : copy));
                }, 2L);
            } catch (Throwable t) {
                f.complete(CommandResult.fail("error", List.of(String.valueOf(t))));
            }
        };
        if (Bukkit.isPrimaryThread()) task.run();
        else Bukkit.getScheduler().runTask(plugin, task);
        return f;
    }
}
