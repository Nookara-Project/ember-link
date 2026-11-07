package net.ikelene.bonfire.core;

import com.google.gson.JsonObject;

import java.util.function.Consumer;

public final class Commands {
    public static final String CONSOLE_ONLY = "This command only works from the server console.";

    public interface Sender {
        boolean isConsole();

        boolean mayStatus();

        void send(String line);
    }

    private final BonfireCore core;
    private volatile long unlinkPendingUntil;

    Commands(BonfireCore core) {
        this.core = core;
    }

    public void execute(Sender s, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(java.util.Locale.ROOT);
        boolean proxied = core.platform().mode() == Mode.PROXIED;
        switch (sub) {
            case "status":
                if (!s.isConsole() && !s.mayStatus()) {
                    s.send("You don't have permission to use this command.");
                    return;
                }
                for (String l : core.statusLines()) s.send(l);
                return;
            case "link":
                if (!s.isConsole()) {
                    s.send(CONSOLE_ONLY);
                    return;
                }
                if (proxied) s.send("Linking this backend's console; player events stay on Velocity.");
                link(s, args);
                return;
            case "unlink":
                if (!s.isConsole()) {
                    s.send(CONSOLE_ONLY);
                    return;
                }
                if (proxied) s.send("Unlinking this backend's console; the Velocity link stays active.");
                unlink(s, args);
                return;
            case "server":
                if (!s.isConsole()) {
                    s.send(CONSOLE_ONLY);
                    return;
                }
                server(s, args);
                return;
            case "reload":
                if (!s.isConsole()) {
                    s.send(CONSOLE_ONLY);
                    return;
                }
                core.reload();
                s.send("Ember Link config reloaded" + (proxied ? "." : ", reconnecting."));
                return;
            default:
                s.send(s.isConsole() ? "Usage: bonfire <status|server <url>|link <code>|unlink|reload>" : "Usage: /bonfire status");
        }
    }

    private void server(Sender s, String[] args) {
        if (args.length < 2) {
            s.send("Ember server: " + core.config().serverLabel());
            s.send("Usage: bonfire server <https://your.instance>   (or `bonfire server velora` to go back to Velora)");
            return;
        }
        String value = args[1];
        if (value.equalsIgnoreCase("reset")) value = "velora";
        String base;
        try {
            base = core.switchServer(value);
        } catch (IllegalArgumentException e) {
            s.send("That doesn't look like an Ember address (" + e.getMessage() + "). Example: bonfire server https://chat.example.com");
            return;
        } catch (java.io.IOException e) {
            s.send("Could not save " + Config.FILE + ": " + e.getMessage());
            return;
        }
        s.send("[Ember Link] Now connecting to " + core.config().serverLabel() + ". Pairing tag: " + core.pairingTag());
        if (base != null) {
            s.send("In Ember, open Server settings > Connected game servers and pick this server (or add a \"Game server\" channel),"
                    + " or make a link code there and run `bonfire link <code>`.");
        }
        if (core.platform().mode() == Mode.PROXIED) {
            s.send("This is a proxied backend: run the same command on the Velocity proxy too.");
        }
    }

    private void link(Sender s, String[] args) {
        if (args.length < 2) {
            s.send("Usage: bonfire link <code>   (get the code in Ember: add a \"Game server\" channel and pick this server)");
            return;
        }
        String code = Identity.normalizeClaimCode(args[1]);
        if (code == null) {
            s.send("That doesn't look like a link code. Codes look like 7KQ2-M9XA.");
            return;
        }
        if (!core.gatewayReady()) {
            s.send("Not connected to Ember right now. The code will be sent as soon as the connection is back.");
        } else {
            s.send("Sending the link code to Ember...");
        }
        JsonObject d = new JsonObject();
        d.addProperty("code", code);
        core.request("link", d, r -> printLinkResult(r, s::send));
    }

    void printLinkResult(JsonObject r, Consumer<String> out) {
        boolean ok = r.has("ok") && r.get("ok").getAsBoolean();
        if (ok) {
            out.accept("[Ember Link] Linked to " + BonfireCore.str(r, "server_name") + ".");
            return;
        }
        String err = BonfireCore.str(r, "error");
        String msg;
        switch (err == null ? "" : err) {
            case "invalid_code": msg = "That code isn't valid. Check it, or get a new one in Ember."; break;
            case "expired": msg = "That code has expired. Get a new one in Ember."; break;
            case "already_linked": {
                String name = BonfireCore.str(r, "server_name");
                msg = "This server is already linked" + (name != null ? " to " + name : " to an Ember server") + ". Run `bonfire unlink` first.";
                break;
            }
            case "rate_limited": msg = "Too many link attempts. Wait a few minutes and try again."; break;
            case "not_allowed": msg = "The person who made this code can no longer manage that Ember server."; break;
            case "too_many_instances": msg = "That Ember server already has the maximum number of linked game servers (10)."; break;
            default: msg = "Linking failed" + (err != null ? " (" + err + ")" : "") + ".";
        }
        out.accept("[Ember Link] " + msg);
    }

    private void unlink(Sender s, String[] args) {
        JsonObject l = core.linked();
        boolean confirm = args.length >= 2 && args[1].equalsIgnoreCase("confirm");
        boolean newIdentity = false;
        for (int i = 2; i < args.length; i++) if (args[i].equalsIgnoreCase("--new-identity")) newIdentity = true;
        if (!confirm) {
            if (l == null) {
                s.send(core.gatewayReady() ? "This server isn't linked to an Ember server." : "Not connected to Ember, so the link state is unknown. Try again once connected.");
                if (!core.gatewayReady()) return;
                s.send("To start over with a brand new identity anyway, run `bonfire unlink confirm --new-identity` within 30 s.");
                unlinkPendingUntil = System.currentTimeMillis() + 30_000;
                return;
            }
            unlinkPendingUntil = System.currentTimeMillis() + 30_000;
            s.send("Run `bonfire unlink confirm` within 30 s to unlink from " + BonfireCore.str(l, "server_name")
                    + ". Add --new-identity to also throw away this server's identity.");
            return;
        }
        if (System.currentTimeMillis() > unlinkPendingUntil) {
            s.send("Run `bonfire unlink` first, then `bonfire unlink confirm` within 30 s.");
            return;
        }
        unlinkPendingUntil = 0;
        if (l != null) {
            core.sendFrame("unlink", new JsonObject());
            s.send("Unlinking from " + BonfireCore.str(l, "server_name") + "...");
        }
        if (newIdentity) {
            s.send("Creating a new server identity. Ember will see this as a brand new server.");
            core.scheduler().schedule(core::regenerateIdentity, 1500, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }
}
