import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Stand-in for Velocity / NanoLimbo, used only by the local acceptance tests (never shipped in server.jar).
 * The root starts it with the fixed command line "... -jar X.jar nogui", so its behaviour comes from a file
 * called mode.txt in its working directory:  "<mode> [stopword]"
 *   obey     (default) exits cleanly when it reads the stop word on stdin (default "stop")
 *   term     ignores stdin, exits promptly on SIGTERM
 *   stubborn ignores stdin AND hangs on SIGTERM, so only SIGKILL stops it
 *   crash    exits with code 1 two seconds after starting
 * It answers "plist" with "plist: ok" and echoes any other line as "echo: <line>".
 */
public class DummyServer {
    public static void main(String[] args) throws Exception {
        String mode = "obey";
        String stopWord = "stop";
        Path f = Paths.get("mode.txt");
        if (Files.exists(f)) {
            String[] t = Files.readString(f).trim().split("\\s+");
            mode = t[0];
            if (t.length > 1) stopWord = t[1];
        }
        final String m = mode;
        String name = Paths.get("").toAbsolutePath().getFileName().toString();
        System.out.println("started name=" + name + " mode=" + m + " pid=" + ProcessHandle.current().pid()
                + " t=" + System.currentTimeMillis());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (m.equals("stubborn")) {
                System.out.println("SIGTERM received, ignoring it");
                try { Thread.sleep(3_600_000); } catch (InterruptedException ignored) { }
            } else {
                System.out.println("shutdown hook: exiting");
            }
        }));

        if (m.equals("crash")) {
            Thread.sleep(2_000);
            System.out.println("crashing now");
            Runtime.getRuntime().halt(1);
        }
        if (m.equals("obey")) {
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) {
                if (line.equals(stopWord)) {
                    System.out.println("stop command received, shutting down");
                    System.exit(0);
                } else if (line.equals("plist")) {
                    System.out.println("plist: ok");
                } else {
                    System.out.println("echo: " + line);
                }
            }
        }
        Thread.sleep(Long.MAX_VALUE); // stdin ignored or closed: just stay alive
    }
}
