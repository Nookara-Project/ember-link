package net.ikelene.bonfire.core;

import java.util.ArrayList;
import java.util.List;

public final class CommandResult {
    public final boolean ok;
    public final List<String> output;
    public final String error;

    public CommandResult(boolean ok, List<String> output, String error) {
        this.ok = ok;
        this.output = output == null ? new ArrayList<>() : output;
        this.error = error;
    }

    public static CommandResult ok(List<String> output) {
        return new CommandResult(true, output, null);
    }

    public static CommandResult fail(String error, List<String> output) {
        return new CommandResult(false, output, error);
    }
}
