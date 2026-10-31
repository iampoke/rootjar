package rootjar.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** Append-only activity record stored inside one server directory. */
public final class ActivityLog {
    private ActivityLog() {}

    public static void append(Path serverDir, String user, String action, String details) throws IOException {
        Files.createDirectories(serverDir);
        String line = "{\"time\":\"" + Instant.now() + "\",\"user\":\"" + json(user) + "\",\"action\":\"" + json(action) + "\",\"details\":\"" + json(details) + "\"}\n";
        Path file = serverDir.resolve("activity.log");
        Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (Files.size(file) > 4_194_304) Files.move(file, serverDir.resolve("activity.log.1"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public static String recent(Path serverDir, int limit) throws IOException {
        return recent(java.util.List.of(serverDir.resolve("activity.log")), limit, "");
    }

    public static String recent(Path serverDir, int limit, String query) throws IOException {
        return recent(java.util.List.of(serverDir.resolve("activity.log")), limit, query);
    }

    /** Returns the newest records from one or more append-only logs, oldest first. */
    public static String recent(java.util.List<Path> files, int limit) throws IOException {
        return recent(files, limit, "");
    }

    public static String recent(java.util.List<Path> files, int limit, String query) throws IOException {
        java.util.List<String> lines = new java.util.ArrayList<>();
        String needle = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT);
        for (Path file : files) {
            if (!Files.isRegularFile(file)) continue;
            for (String line : Files.readAllLines(file)) {
                if (needle.isBlank() || line.toLowerCase(java.util.Locale.ROOT).contains(needle)) lines.add(line);
            }
        }
        return page(dedupe(lines), limit);
    }

    /**
     * Everything one server did: its own log plus every central audit record whose action is prefixed with
     * that server's id. The prefix is stripped so the panel shows {@code file.write}, not {@code Event.file.write}.
     * Records written to both logs by one action collapse into a single entry.
     */
    public static String recentForServer(Path serverDir, Path centralLog, String serverId, int limit, String query) throws IOException {
        java.util.List<String> lines = new java.util.ArrayList<>();
        String needle = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT);
        Path own = serverDir.resolve("activity.log");
        if (Files.isRegularFile(own)) {
            for (String line : Files.readAllLines(own)) if (matches(line, needle)) lines.add(line);
        }
        String marker = "\"action\":\"" + json(serverId) + ".";
        if (centralLog != null && Files.isRegularFile(centralLog)) {
            for (String line : Files.readAllLines(centralLog)) {
                if (!line.contains(marker)) continue;
                String trimmed = line.replace(marker, "\"action\":\"");
                if (matches(trimmed, needle)) lines.add(trimmed);
            }
        }
        return page(dedupe(lines), limit);
    }

    private static boolean matches(String line, String needle) {
        return needle.isBlank() || line.toLowerCase(java.util.Locale.ROOT).contains(needle);
    }

    /** One action can be recorded in both logs; keep the first copy of each identical event. */
    private static java.util.List<String> dedupe(java.util.List<String> lines) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.List<String> unique = new java.util.ArrayList<>();
        for (String line : lines) if (seen.add(keyOf(line))) unique.add(line);
        return unique;
    }

    private static String page(java.util.List<String> lines, int limit) {
        lines.sort(java.util.Comparator.comparing(ActivityLog::timeOf));
        int start = Math.max(0, lines.size() - limit);
        return "[" + String.join(",", lines.subList(start, lines.size())) + "]";
    }

    /** Identity of an event to the nearest second, so a double write is not shown twice. */
    private static String keyOf(String line) {
        String time = timeOf(line);
        int dot = time.indexOf('.');
        return (dot > 0 ? time.substring(0, dot) : time) + "|" + field(line, "action") + "|" + field(line, "details") + "|" + field(line, "user");
    }

    private static String field(String line, String name) {
        String marker = "\"" + name + "\":\"";
        int start = line.indexOf(marker);
        if (start < 0) return "";
        start += marker.length();
        int end = line.indexOf('"', start);
        return end < 0 ? "" : line.substring(start, end);
    }

    private static String timeOf(String line) {
        int start = line.indexOf("\"time\":\"");
        if (start < 0) return "";
        start += 8;
        int end = line.indexOf('"', start);
        return end < 0 ? "" : line.substring(start, end);
    }

    private static String json(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "");
    }
}
