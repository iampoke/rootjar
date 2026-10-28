package rootjar.backup;

import rootjar.config.ChildConfig;
import rootjar.supervisor.PermissionRepair;
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

    public record BackupInfo(String name, String folder, long bytes, long createdAt) {}

    public static Path directory(ChildConfig child) throws IOException {
        Path dir = child.dir().resolve("backup-live");
        Files.createDirectories(dir);
        return dir;
    }

    private static String safeFolder(String folder) throws IOException {
        if ("backup-live".equals(folder) || "backups".equals(folder)) return folder;
        throw new IOException("Invalid backup folder");
    }

    private static boolean isBackupStorage(String name) {
        return name.equals("backup-live") || name.equals("backup-old") || name.equals("backups");
    }

    public static BackupInfo create(ChildConfig child, String label, boolean includeLogs, int retention) throws IOException {
        return create(child, label, includeLogs, retention, "");
    }

    public static BackupInfo create(ChildConfig child, String label, boolean includeLogs, int retention, String keepName) throws IOException {
        String safeLabel = label == null ? "" : label.replaceAll("[^A-Za-z0-9._-]", "");
        if (safeLabel.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) safeLabel = safeLabel.substring(0, safeLabel.length() - 4);
        boolean automatic = safeLabel.isBlank() || safeLabel.equals("scheduled") || safeLabel.equals("pre-restore");
        String prefix = automatic ? child.id() + "-" + (safeLabel.isBlank() ? "" : safeLabel + "-") + LocalDateTime.now().format(STAMP) : safeLabel;
        String name = prefix + ".zip";
        for (int suffix = 2; nameExists(child, name); suffix++) name = prefix + "-" + suffix + ".zip";
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
                    String fileName = file.getFileName().toString();
                    if (Files.isSymbolicLink(file) || fileName.equals("backup.json")
                            || fileName.equals(PermissionRepair.SUCCESS_MARKER) || fileName.equals(PermissionRepair.FAILURE_MARKER)) return FileVisitResult.CONTINUE;
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
        applyRetention(child, retention, destination, keepName);
        return new BackupInfo(name, "backup-live", Files.size(destination), Files.getLastModifiedTime(destination).toMillis());
    }

    public static List<BackupInfo> list(ChildConfig child) throws IOException {
        List<BackupInfo> all = new java.util.ArrayList<>();
        for (String folder : List.of("backup-live", "backups")) all.addAll(listFolder(child.dir().resolve(folder), folder));
        all.sort(Comparator.comparingLong(BackupInfo::createdAt).reversed());
        return all;
    }

    private static List<BackupInfo> listFolder(Path dir, String folder) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(path -> NAME.matcher(path.getFileName().toString()).matches() && !isAddonArchive(path.getFileName().toString()))
                    .map(path -> {
                        try { return new BackupInfo(path.getFileName().toString(), folder, Files.size(path), Files.getLastModifiedTime(path).toMillis()); }
                        catch (IOException e) { return new BackupInfo(path.getFileName().toString(), folder, 0, 0); }
                    }).sorted(Comparator.comparingLong(BackupInfo::createdAt).reversed()).toList();
        }
    }

    /** True for a plugin or mod archive, which must never be listed or restored as a full server backup. */
    public static boolean isAddonArchive(String name) {
        return name != null && (name.startsWith("addons-plugins-") || name.startsWith("addons-mods-"));
    }

    public static Path addonDirectory(ChildConfig child) throws IOException {
        Path dir = child.dir().resolve("backups").resolve("addons");
        Files.createDirectories(dir);
        return dir;
    }

    private static boolean nameExists(ChildConfig child, String name) {
        for (String folder : List.of("backup-live", "backups")) {
            if (Files.exists(child.dir().resolve(folder).resolve(name))) return true;
        }
        return false;
    }

    public static Path resolve(ChildConfig child, String name) throws IOException {
        return resolve(child, name, "");
    }

    public static Path resolve(ChildConfig child, String name, String folder) throws IOException {
        if (name == null || !NAME.matcher(name).matches() || isAddonArchive(name)) throw new IOException("Invalid backup name");
        java.util.List<String> folders = folder == null || folder.isBlank() ? List.of("backup-live", "backups") : List.of(safeFolder(folder));
        for (String candidate : folders) {
            Path file = child.dir().resolve(candidate).resolve(name).normalize();
            if (file.startsWith(child.dir().resolve(candidate)) && Files.isRegularFile(file)) return file;
        }
        throw new IOException("Backup not found");
    }

    public static void delete(ChildConfig child, String name, String folder) throws IOException {
        Path file = resolve(child, name, folder);
        Files.deleteIfExists(file);
    }

    public static void restore(ChildConfig child, String name, String folder) throws IOException {
        Path source = resolve(child, name, folder);
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
            Files.deleteIfExists(child.dir().resolve(PermissionRepair.SUCCESS_MARKER));
            Files.deleteIfExists(child.dir().resolve(PermissionRepair.FAILURE_MARKER));
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

    private static void applyRetention(ChildConfig child, int retention, Path current, String keepName) throws IOException {
        List<Path> archives = new java.util.ArrayList<>();
        for (String folder : List.of("backup-live", "backups")) {
            Path dir = child.dir().resolve(folder);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> entries = Files.list(dir)) {
                entries.filter(path -> NAME.matcher(path.getFileName().toString()).matches() && !isAddonArchive(path.getFileName().toString())).forEach(archives::add);
            }
        }
        archives.sort(Comparator.comparingLong((Path path) -> path.toFile().lastModified()).reversed());
        int kept = 0;
        int limit = Math.max(1, retention);
        for (Path archive : archives) {
            boolean selected = !archive.equals(current) && !keepName.isBlank() && archive.getFileName().toString().equals(keepName);
            if (archive.equals(current) || kept < limit) kept++;
            else if (!selected) Files.deleteIfExists(archive);
        }
    }
}
