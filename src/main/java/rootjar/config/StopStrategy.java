package rootjar.config;

import java.util.Locale;

/** How the root asks a child to stop. SIGKILL is always the final fallback. */
public enum StopStrategy {
    /** Write stop_command to the child's stdin. */
    STDIN,
    /** Send SIGTERM (Process.destroy()). */
    SIGTERM,
    /** stdin first; if still alive after half of stop_timeout, SIGTERM. */
    BOTH;

    public static StopStrategy parse(String s) {
        switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "stdin": return STDIN;
            case "sigterm": return SIGTERM;
            case "both": return BOTH;
            default: return null;
        }
    }
}
