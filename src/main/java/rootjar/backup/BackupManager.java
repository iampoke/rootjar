package rootjar.backup;

import rootjar.config.ChildConfig;
import rootjar.files.FileOps;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Creates, lists, restores, and retains full server backup archives. */
public final class BackupManager {
    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]+\\.zip");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private BackupManager() {}

    public record BackupInfo(String name, long bytes, long createdAt) {}

    public static Path directory(ChildConfig child) throws IOException {
        Path dir = child.dir().resolve("backups");
        Files.createDirectories(dir);
        return dir;
    }

    public static BackupInfo create(ChildConfig child, String label, boolean includeLogs, int retention) throws IOException {
        String safeLabel = label == null ? "" : label.replaceAll("[^A-Za-z0-9._-]", "");
        String name = child.id() + "-" + LocalDateTime.now().format(STAMP) + (safeLabel.isBlank() ? "" : "-" + safeLabel) + ".zip";
        Path destination = directory(child).resolve(name);
        Path temp = destination.resolveSibling(name + ".part");
        try (var out = Files.newOutputStream(temp)) {
            FileOps.zip(child.dir(), out);
        }
        Files.move(temp, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(destination.resolveSibling(name + ".json"),
                "{\"server\":\"" + child.id() + "\",\"type\":\"" + child.type() + "\",\"includeLogs\":" + includeLogs + "}",
                StandardCharsets.UTF_8);
        applyRetention(child, retention, destination);
        return new BackupInfo(name, Files.size(destination), Files.getLastModifiedTime(destination).toMillis());
    }

    public static List<BackupInfo> list(ChildConfig child) throws IOException {
        Path dir = directory(child);
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(path -> NAME.matcher(path.getFileName().toString()).matches())
                    .map(path -> {
                        try { return new BackupInfo(path.getFileName().toString(), Files.size(path), Files.getLastModifiedTime(path).toMillis()); }
                        catch (IOException e) { return new BackupInfo(path.getFileName().toString(), 0, 0); }
                    }).sorted(Comparator.comparingLong(BackupInfo::createdAt).reversed()).toList();
        }
    }

    public static Path resolve(ChildConfig child, String name) throws IOException {
        if (name == null || !NAME.matcher(name).matches()) throw new IOException("Invalid backup name");
        Path file = directory(child).resolve(name).normalize();
        if (!file.startsWith(directory(child)) || !Files.isRegularFile(file)) throw new IOException("Backup not found");
        return file;
    }

    public static void delete(ChildConfig child, String name) throws IOException {
        Path file = resolve(child, name);
        Files.deleteIfExists(file);
        Files.deleteIfExists(file.resolveSibling(name + ".json"));
    }

    public static void restore(ChildConfig child, String name) throws IOException {
        Path staging = child.dir().resolve(".restore-" + System.currentTimeMillis());
        try {
            FileOps.extract(resolve(child, name), staging);
            try (Stream<Path> entries = Files.list(staging)) {
                for (Path entry : entries.toList()) {
                    if (entry.getFileName().toString().equals("backups")) continue;
                    Path target = child.dir().resolve(entry.getFileName());
                    if (Files.isDirectory(entry)) FileOps.copyRecursive(entry, target);
                    else Files.copy(entry, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } finally {
            if (Files.exists(staging)) {
                try (Stream<Path> walk = Files.walk(staging)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) {} });
                }
            }
        }
    }

    private static void applyRetention(ChildConfig child, int retention, Path current) throws IOException {
        List<Path> archives;
        try (Stream<Path> entries = Files.list(directory(child))) {
            archives = entries.filter(path -> path.getFileName().toString().startsWith(child.id() + "-") && path.toString().endsWith(".zip"))
                    .sorted(Comparator.comparingLong((Path path) -> path.toFile().lastModified()).reversed()).toList();
        }
        int kept = 0;
        for (Path archive : archives) {
            if (archive.equals(current) || kept < Math.max(1, retention)) kept++;
            else Files.deleteIfExists(archive);
        }
    }
}
