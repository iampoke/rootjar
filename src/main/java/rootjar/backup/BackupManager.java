package rootjar.backup;

import rootjar.config.ChildConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import rootjar.files.FileOps;

/** Creates, lists, restores, and retains full server backup archives. */
public final class BackupManager {
    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]+\\.zip");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private BackupManager() {}

    public record BackupInfo(String name, long bytes, long createdAt) {}

    public static Path directory(ChildConfig child) throws IOException {
        Path dir = child.dir().resolve("backup-live");
        Files.createDirectories(dir);
        return dir;
    }

    private static boolean isBackupStorage(String name) {
        return name.equals("backup-live") || name.equals("backup-old") || name.equals("backups");
    }

    public static BackupInfo create(ChildConfig child, String label, boolean includeLogs, int retention) throws IOException {
        String safeLabel = label == null ? "" : label.replaceAll("[^A-Za-z0-9._-]", "");
        if (safeLabel.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) safeLabel = safeLabel.substring(0, safeLabel.length() - 4);
        String prefix = safeLabel.isBlank() ? child.id() + "-" + LocalDateTime.now().format(STAMP) : safeLabel;
        String name = prefix + ".zip";
        for (int suffix = 2; Files.exists(directory(child).resolve(name)); suffix++) name = prefix + "-" + suffix + ".zip";
        Path destination = directory(child).resolve(name);
        Path temp = destination.resolveSibling(name + ".part");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temp))) {
            out.putNextEntry(new ZipEntry("backup.json"));
            out.write(("{\"server\":\"" + child.id() + "\",\"type\":\"" + child.type()
                    + "\",\"includeLogs\":" + includeLogs + "}").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            Path root = child.dir();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(root)) return FileVisitResult.CONTINUE;
                    String folder = dir.getFileName().toString();
                    if (Files.isSymbolicLink(dir) || isBackupStorage(folder) || folder.equals(".trash")
                            || folder.startsWith(".restore-") || folder.startsWith(".import-")
                            || (!includeLogs && folder.equals("logs"))) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (Files.isSymbolicLink(file) || file.getFileName().toString().equals("backup.json")) return FileVisitResult.CONTINUE;
                    out.putNextEntry(new ZipEntry(root.relativize(file).toString().replace('\\', '/')));
                    Files.copy(file, out);
                    out.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        Files.move(temp, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        applyRetention(child, retention, destination);
        return new BackupInfo(name, Files.size(destination), Files.getLastModifiedTime(destination).toMillis());
    }

    public static List<BackupInfo> list(ChildConfig child) throws IOException {
        List<BackupInfo> all = new java.util.ArrayList<>();
        for (String folder : List.of("backup-live", "backups")) all.addAll(listFolder(child.dir().resolve(folder)));
        all.sort(Comparator.comparingLong(BackupInfo::createdAt).reversed());
        return all;
    }

    private static List<BackupInfo> listFolder(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
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
        for (String folder : List.of("backup-live", "backups")) {
            Path file = child.dir().resolve(folder).resolve(name).normalize();
            if (file.startsWith(child.dir().resolve(folder)) && Files.isRegularFile(file)) return file;
        }
        throw new IOException("Backup not found");
    }

    public static void delete(ChildConfig child, String name) throws IOException {
        Path file = resolve(child, name);
        Files.deleteIfExists(file);
    }

    public static void restore(ChildConfig child, String name) throws IOException {
        Path source = resolve(child, name);
        Path staging = child.dir().resolve(".restore-" + System.currentTimeMillis());
        Path old = child.dir().resolve("backup-old");
        Path previousOld = child.dir().resolve(".backup-old-previous");
        boolean movedOld = false;
        try {
            FileOps.extract(source, staging);
            if (Files.exists(old)) Files.move(old, previousOld);
            Files.createDirectories(old);
            movedOld = true;
            try (Stream<Path> current = Files.list(child.dir())) {
                for (Path entry : current.toList()) {
                    String entryName = entry.getFileName().toString();
                    if (isBackupStorage(entryName) || entry.equals(staging)) continue;
                    Files.move(entry, old.resolve(entryName));
                }
            }
            try (Stream<Path> restored = Files.list(staging)) {
                for (Path entry : restored.toList()) {
                    if (isBackupStorage(entry.getFileName().toString())) continue;
                    Files.move(entry, child.dir().resolve(entry.getFileName()));
                }
            }
            deleteTree(previousOld);
        } catch (IOException e) {
            if (movedOld && Files.isDirectory(old)) {
                try (Stream<Path> saved = Files.list(old)) {
                    for (Path entry : saved.toList()) Files.move(entry, child.dir().resolve(entry.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (Files.exists(previousOld)) Files.move(previousOld, old, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            throw e;
        } finally {
            deleteTree(staging);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
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
