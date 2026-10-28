package rootjar.supervisor;

import rootjar.console.ConsoleOutput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicBoolean;

/** One-shot repair of transferred server folders, tracked with marker files so healthy folders are not re-scanned. */
public final class PermissionRepair {
    public static final String SUCCESS_MARKER = ".rootjar-permissions-repaired";
    public static final String FAILURE_MARKER = ".rootjar-permissions-repair-failed";
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_AFTER_MS = 24L * 60 * 60 * 1000;

    private PermissionRepair() {}

    /** Repairs a transferred folder once, then records that the repair was attempted. */
    public static boolean repairOnce(Path root, String id) {
        if (root == null || !Files.isDirectory(root)) return false;
        Path marker = root.resolve(SUCCESS_MARKER);
        if (Files.exists(marker)) return false;
        if (!root.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            // Nothing to repair on this filesystem (e.g. Windows); record it so the folder is not walked every start.
            writeMarker(marker, "no-op");
            return false;
        }
        if (!shouldAttempt(readMarker(root.resolve(FAILURE_MARKER)), System.currentTimeMillis())) return false;
        AtomicBoolean failed = new AtomicBoolean();
        boolean repaired = repairCopiedFiles(root, failed);
        if (failed.get()) {
            recordFailure(root, id);
            return false;
        }
        try { Files.deleteIfExists(root.resolve(FAILURE_MARKER)); } catch (IOException ignored) {}
        return writeMarker(marker, "repaired") ? repaired : false;
    }

    /** True when another repair attempt is allowed: fewer than {@value #MAX_ATTEMPTS} failures, or the last one is older than a day. */
    public static boolean shouldAttempt(String failureMarkerText, long nowMillis) {
        if (failureMarkerText == null || failureMarkerText.isBlank()) return true;
        String[] parts = failureMarkerText.trim().split("\\s+");
        int attempts;
        long lastAttempt;
        try {
            attempts = Integer.parseInt(parts[0]);
            lastAttempt = parts.length > 1 ? Long.parseLong(parts[1]) : 0L;
        } catch (NumberFormatException e) { return true; }
        return attempts < MAX_ATTEMPTS || nowMillis - lastAttempt >= RETRY_AFTER_MS;
    }

    /** Makes a transferred server folder writable by the user running Rootjar. */
    private static boolean repairCopiedFiles(Path root, AtomicBoolean failed) {
        if (!Files.isDirectory(root)) return false;
        AtomicBoolean repaired = new AtomicBoolean();
        try (var paths = Files.walk(root)) {
            paths.forEach(path -> {
                try {
                    var permissions = EnumSet.noneOf(PosixFilePermission.class);
                    permissions.addAll(Files.getPosixFilePermissions(path));
                    permissions.add(PosixFilePermission.OWNER_READ);
                    permissions.add(PosixFilePermission.OWNER_WRITE);
                    if (Files.isDirectory(path)) permissions.add(PosixFilePermission.OWNER_EXECUTE);
                    Files.setPosixFilePermissions(path, permissions);
                    repaired.set(true);
                } catch (IOException e) { failed.set(true); }
            });
        } catch (IOException e) {
            failed.set(true);
            ConsoleOutput.root("could not repair permissions in " + root.getFileName() + ": " + e.getMessage());
        }
        return repaired.get();
    }

    private static void recordFailure(Path root, String id) {
        int attempts = 1;
        String[] parts = readMarker(root.resolve(FAILURE_MARKER)).trim().split("\\s+");
        try { attempts = Math.min(MAX_ATTEMPTS, Integer.parseInt(parts[0]) + 1); } catch (NumberFormatException ignored) {}
        ConsoleOutput.root("WARNING: could not repair file permissions in " + id + " (attempt " + attempts
                + " of " + MAX_ATTEMPTS + (attempts >= MAX_ATTEMPTS ? "; next retry in 24h" : "") + ")");
        writeMarker(root.resolve(FAILURE_MARKER), attempts + " " + System.currentTimeMillis());
    }

    private static String readMarker(Path marker) {
        try { return Files.readString(marker); } catch (IOException e) { return ""; }
    }

    private static boolean writeMarker(Path marker, String text) {
        try { Files.writeString(marker, text); return true; } catch (IOException e) { return false; }
    }
}
