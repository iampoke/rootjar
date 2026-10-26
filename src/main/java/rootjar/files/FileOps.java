package rootjar.files;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Path-safe file operations for one child server directory. No HTTP or third-party dependencies. */
public final class FileOps {
    public static final int MAX_SEARCH_DEPTH = 8;
    public static final int MAX_ZIP_ENTRIES = 10_000;
    public static final long MAX_EXTRACTED_BYTES = 4L * 1024 * 1024 * 1024;

    private FileOps() {}

    public static Path resolve(Path base, String relative) throws IOException {
        if (relative == null || relative.isBlank() || relative.indexOf('\0') >= 0 || relative.indexOf('\\') >= 0) {
            throw new IOException("Invalid path");
        }
        Path root = base.toAbsolutePath().normalize();
        Path target = root;
        for (String raw : relative.replace('\\', '/').split("/")) {
            if (raw.isEmpty() || raw.equals(".")) continue;
            if (raw.equals("..") || raw.startsWith("..")) throw new IOException("Directory traversal detected");
            target = target.resolve(raw).normalize();
        }
        if (!target.startsWith(root)) throw new IOException("Directory traversal detected");
        if (Files.exists(target)) {
            Path realParent = target.getParent() == null ? root : target.getParent().toRealPath();
            Path real = realParent.resolve(target.getFileName()).normalize();
            if (Files.isSymbolicLink(target)) real = target.toRealPath().normalize();
            if (!real.startsWith(root)) throw new IOException("Directory traversal detected");
        }
        return target;
    }

    public static String safeName(String name) throws IOException {
        if (name == null || name.isBlank() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.indexOf('\0') >= 0 || name.equals(".") || name.equals("..") || name.startsWith(".")) {
            throw new IOException("Invalid file name");
        }
        return Path.of(name).getFileName().toString();
    }

    public static void copyRecursive(Path source, Path destination) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(destination.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(file)) return FileVisitResult.CONTINUE;
                Path target = destination.resolve(source.relativize(file));
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static void zip(Path source, OutputStream out) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (Files.isSymbolicLink(file)) return FileVisitResult.CONTINUE;
                    String name = source.relativize(file).toString().replace('\\', '/');
                    zip.putNextEntry(new ZipEntry(name));
                    Files.copy(file, zip);
                    zip.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    public static void extract(Path zipFile, Path destination) throws IOException {
        Files.createDirectories(destination);
        Path root = destination.toAbsolutePath().normalize();
        int entries = 0;
        long bytes = 0;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) throw new IOException("Archive has too many entries");
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains(":") || name.contains("\0")) throw new IOException("Unsafe archive entry");
                Path target = root;
                for (String part : name.split("/")) {
                    if (part.isEmpty() || part.equals(".")) continue;
                    if (part.equals("..") || part.startsWith(".")) throw new IOException("Unsafe archive entry");
                    target = target.resolve(part).normalize();
                }
                if (!target.startsWith(root)) throw new IOException("Unsafe archive entry");
                if (entry.isDirectory()) { Files.createDirectories(target); continue; }
                Files.createDirectories(target.getParent());
                bytes += copyBounded(in, target, MAX_EXTRACTED_BYTES - bytes);
                if (bytes > MAX_EXTRACTED_BYTES) throw new IOException("Archive expands beyond the size limit");
            }
        }
    }

    public static List<Path> searchNames(Path root, String query, int limit) throws IOException {
        String needle = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT);
        List<Path> matches = new ArrayList<>();
        Files.walkFileTree(root, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), MAX_SEARCH_DEPTH,
                new SimpleFileVisitor<>() {
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (name.equals(".trash") || name.equals("backups") || name.startsWith(".restore-") || name.startsWith(".import-")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (matches.size() >= limit) return FileVisitResult.TERMINATE;
                if (file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).contains(needle)) matches.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        matches.sort(Comparator.comparing(Path::toString));
        return matches;
    }

    private static long copyBounded(InputStream in, Path target, long remaining) throws IOException {
        long total = 0;
        try (OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > remaining) throw new IOException("Archive expands beyond the size limit");
                out.write(buffer, 0, read);
            }
        }
        return total;
    }
}
