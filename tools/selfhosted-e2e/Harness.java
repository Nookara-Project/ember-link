import net.ikelene.bonfire.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class Harness {
    public static void main(String[] a) throws Exception {
        Path dir = Path.of(a[0]);
        Log log = new Log() {
            public void info(String m) { System.out.println("LOG " + m); }
            public void warn(String m) { System.out.println("WARN " + m); }
            public void error(String m, Throwable t) { System.out.println("ERR " + m + (t == null ? "" : " " + t)); }
        };
        Platform p = new Platform() {
            public String platformId() { return "paper"; }
            public String software() { return "Harness"; }
            public String mcVersion() { return "1.21.11"; }
            public String modVersion() { return "0.2.0"; }
            public Path dataDir() { return dir; }
            public Log log() { return log; }
            public StatusSnapshot status() { StatusSnapshot s = new StatusSnapshot(); s.version = "1.21.11"; s.motd = motd(); s.players.max = 20; return s; }
            public byte[] serverIconPng() { return null; }
            public List<Segment> motd() { return List.of(new Segment("Harness MOTD")); }
            public int maxPlayers() { return 20; }
            public boolean onlineMode() { return true; }
            public void broadcast(List<List<Segment>> lines) { StringBuilder b = new StringBuilder(); for (List<Segment> l : lines) for (Segment s : l) b.append(s.text); System.out.println("BROADCAST " + b); }
            public CompletableFuture<CommandResult> runConsoleCommand(String cmd) { return CompletableFuture.completedFuture(CommandResult.ok(List.of("ran: " + cmd))); }
            public void runSync(Runnable r) { r.run(); }
            public Mode mode() { return Mode.STANDALONE; }
        };
        BonfireCore core = new BonfireCore(p);
        core.init();
        Commands.Sender console = new Commands.Sender() {
            public boolean isConsole() { return true; }
            public boolean mayStatus() { return true; }
            public void send(String line) { System.out.println("CMD " + line); }
        };
        core.start();
        System.out.println("TAG " + core.identity().pairingTag() + " IID " + core.identity().iid());
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        for (String line; (line = in.readLine()) != null; ) {
            if (line.startsWith("chat ")) core.chat(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), "Notch", "Notch", line.substring(5), null, false);
            else core.commands().execute(console, line.trim().split("\\s+"));
            System.out.println("DONE");
        }
        core.stop();
    }
}
