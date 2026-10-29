package rootjar.console;

import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Decides what a line typed into the Pterodactyl console means. Pure parsing, no side effects.
 *
 * Order of precedence:
 *   1. status / help / stop / list       (reserved words, case-insensitive)
 *   2. {id}.start | {id}.stop | {id}.restart | {id}.mute | {id}.unmute
 *   3. {id} {command}                    forward the command to that child's stdin
 *
 * Server names are matched without regard to case. The canonical configured name is returned.
 *   4. anything else                     forward to the first console-enabled child
 */
public final class StdinRouter {
    public enum Kind { NONE, STATUS, HELP, STOP_ALL, LIST, LIFECYCLE, SEND, FALLBACK }

    /** childId = target child (LIFECYCLE/SEND); text = verb (LIFECYCLE) or the command text (SEND/FALLBACK). */
    public record Command(Kind kind, String childId, String text) {
        static Command of(Kind k) { return new Command(k, null, null); }
    }

    private final Supplier<Set<String>> childIds;

    /** Snapshot of the child IDs, captured once. */
    public StdinRouter(Set<String> childIds) {
        this(() -> childIds);
    }

    /** Live view: the IDs are re-read on every parsed line, so children added at runtime are routable too. */
    public StdinRouter(Supplier<Set<String>> childIds) {
        this.childIds = childIds;
    }

    public Command parse(String raw) {
        if (raw == null) return Command.of(Kind.NONE);
        String line = raw.strip(); // also drops a trailing \r
        if (line.isEmpty()) return Command.of(Kind.NONE);

        switch (line.toLowerCase(Locale.ROOT)) {
            case "status": return Command.of(Kind.STATUS);
            case "help": return Command.of(Kind.HELP);
            case "stop": return Command.of(Kind.STOP_ALL);
            case "list": return Command.of(Kind.LIST);
            default: break;
        }

        Set<String> ids = childIds.get();
        int firstSpace = indexOfWhitespace(line);
        if (firstSpace < 0) {
            int dot = line.indexOf('.');
            if (dot > 0) {
                String id = matchId(ids, line.substring(0, dot));
                String verb = line.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (id != null && (verb.equals("start") || verb.equals("stop") || verb.equals("restart")
                        || verb.equals("mute") || verb.equals("unmute"))) {
                    return new Command(Kind.LIFECYCLE, id, verb);
                }
            }
        } else {
            String id = matchId(ids, line.substring(0, firstSpace));
            if (id != null) {
                return new Command(Kind.SEND, id, line.substring(firstSpace).strip());
            }
        }
        return new Command(Kind.FALLBACK, null, line);
    }

    /** Exact name first; otherwise the only name that matches ignoring case. */
    private static String matchId(Set<String> ids, String typed) {
        if (ids == null || typed == null || typed.isEmpty()) return null;
        if (ids.contains(typed)) return typed;
        String found = null;
        for (String id : ids) {
            if (!id.equalsIgnoreCase(typed)) continue;
            if (found != null) return null;
            found = id;
        }
        return found;
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isWhitespace(s.charAt(i))) return i;
        return -1;
    }
}
