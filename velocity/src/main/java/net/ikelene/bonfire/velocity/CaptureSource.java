package net.ikelene.bonfire.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.permission.Tristate;
import net.ikelene.bonfire.core.adventure.AdventureSegments;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

final class CaptureSource implements CommandSource {
    private final List<String> lines = new ArrayList<>();

    @Override
    public void sendMessage(@NotNull Component message) {
        synchronized (lines) {
            Component rendered = message;
            try {
                rendered = net.kyori.adventure.translation.GlobalTranslator.render(message, java.util.Locale.US);
            } catch (Throwable ignored) {
            }
            lines.add(AdventureSegments.plain(rendered));
        }
    }

    List<String> lines() {
        synchronized (lines) {
            return new ArrayList<>(lines);
        }
    }

    @Override
    public Tristate getPermissionValue(String permission) {
        return Tristate.TRUE;
    }
}
