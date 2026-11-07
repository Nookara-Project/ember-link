package net.ikelene.bonfire.core;

public interface Log {
    void info(String msg);

    void warn(String msg);

    void error(String msg, Throwable t);

    default void error(String msg) {
        error(msg, null);
    }
}
