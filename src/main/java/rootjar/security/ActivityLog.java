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
        if (Files.size(file) > 1_048_576) Files.move(file, serverDir.resolve("activity.log.1"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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
        lines.sort(java.util.Comparator.comparing(ActivityLog::timeOf));
        int start = Math.max(0, lines.size() - limit);
        return "[" + String.join(",", lines.subList(start, lines.size())) + "]";
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
