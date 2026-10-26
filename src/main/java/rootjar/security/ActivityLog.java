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
        Path file = serverDir.resolve("activity.log");
        if (!Files.isRegularFile(file)) return "[]";
        java.util.List<String> lines = Files.readAllLines(file);
        int start = Math.max(0, lines.size() - limit);
        return "[" + String.join(",", lines.subList(start, lines.size())) + "]";
    }

    private static String json(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "");
    }
}
