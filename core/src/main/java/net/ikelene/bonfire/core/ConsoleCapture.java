package net.ikelene.bonfire.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ConsoleCapture {
    private static final int MAX_LINES_PER_BATCH = 50;
    private static final int MAX_BATCH_CHARS = 48_000;

    private final BonfireCore core;
    private final ConcurrentLinkedQueue<JsonObject> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private volatile boolean active;
    private Object hook;

    ConsoleCapture(BonfireCore core) {
        this.core = core;
    }

    void attach(ScheduledExecutorService sched, ClassLoader serverLoader) {
        try {
            hook = Log4jHook.install(this, serverLoader);
        } catch (Throwable t) {
            core.log().warn("[Ember Link] Console capture is not available on this server (" + t + ")");
            return;
        }
        sched.scheduleAtFixedRate(this::flush, 250, 250, TimeUnit.MILLISECONDS);
    }

    void detach() {
        active = false;
        if (hook != null) {
            try {
                Log4jHook.uninstall(hook);
            } catch (Throwable ignored) {
            }
            hook = null;
        }
    }

    public boolean available() {
        return hook != null;
    }

    void setActive(boolean on) {
        active = on;
        if (!on) {
            queue.clear();
            size.set(0);
        }
    }

    void offer(long t, String level, String logger, String msg, String threadName) {
        if (!active) return;
        if (threadName != null && threadName.startsWith("bonfire-gw")) return;
        if (logger != null && logger.startsWith("net.ikelene.bonfire.gateway")) return;
        String clean = Text.cleanLogLine(msg);
        if (core.config().redactIps()) clean = Text.redactIps(clean);
        String shortLogger = logger == null ? "" : logger;
        int dot = shortLogger.lastIndexOf('.');
        if (dot >= 0 && dot < shortLogger.length() - 1) shortLogger = shortLogger.substring(dot + 1);
        JsonObject line = new JsonObject();
        line.addProperty("t", t);
        line.addProperty("level", level);
        line.addProperty("logger", Text.clamp(shortLogger, 60));
        line.addProperty("msg", Text.clamp(clean, 2000));
        if (size.get() > 2000) return;
        queue.add(line);
        size.incrementAndGet();
    }

    private void flush() {
        try {
            if (!active || queue.isEmpty()) return;
            if (!core.gatewayReady()) {
                queue.clear();
                size.set(0);
                return;
            }
            while (!queue.isEmpty()) {
                JsonArray lines = new JsonArray();
                int chars = 0;
                JsonObject l;
                while (lines.size() < MAX_LINES_PER_BATCH && (l = queue.peek()) != null) {
                    int len = l.get("msg").getAsString().length() + 80;
                    if (lines.size() > 0 && chars + len > MAX_BATCH_CHARS) break;
                    queue.poll();
                    size.decrementAndGet();
                    lines.add(l);
                    chars += len;
                }
                JsonObject d = new JsonObject();
                d.add("lines", lines);
                core.sendFrame("console.log", d);
            }
        } catch (Throwable ignored) {
        }
    }

    private static final class Log4jHook {
        static Object install(ConsoleCapture cap, ClassLoader loader) {
            org.apache.logging.log4j.spi.LoggerContext any = org.apache.logging.log4j.LogManager.getContext(loader, false);
            org.apache.logging.log4j.core.LoggerContext ctx = (org.apache.logging.log4j.core.LoggerContext) any;
            org.apache.logging.log4j.core.appender.AbstractAppender app =
                    new org.apache.logging.log4j.core.appender.AbstractAppender("BonfireLinkCapture", null, null, true,
                            org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
                        @Override
                        public void append(org.apache.logging.log4j.core.LogEvent e) {
                            try {
                                String msg = e.getMessage() == null ? "" : e.getMessage().getFormattedMessage();
                                Throwable thrown = e.getThrown();
                                if (thrown != null) msg = msg + " " + thrown;
                                cap.offer(e.getTimeMillis(), e.getLevel().name(), e.getLoggerName(), msg, e.getThreadName());
                            } catch (Throwable ignored) {
                            }
                        }
                    };
            app.start();
            ctx.getConfiguration().getRootLogger().addAppender(app, null, null);
            ctx.updateLoggers();
            return new Object[]{ctx, app};
        }

        static void uninstall(Object hook) {
            Object[] h = (Object[]) hook;
            org.apache.logging.log4j.core.LoggerContext ctx = (org.apache.logging.log4j.core.LoggerContext) h[0];
            org.apache.logging.log4j.core.appender.AbstractAppender app = (org.apache.logging.log4j.core.appender.AbstractAppender) h[1];
            ctx.getConfiguration().getRootLogger().removeAppender(app.getName());
            ctx.updateLoggers();
            app.stop();
        }
    }
}
