package rootjar;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The panel UI. Served from index.html next to the jar; this class only supplies the first-run copy,
 * bundled as the jar resource /index.html (the root index.html copied in at build time). Keeping the
 * HTML out of a Java string constant avoids the 65,535-byte UTF-8 limit on a single constant, which
 * the embedded copy outgrew.
 */
public final class Frontend {
    private Frontend() {}

    /**
     * Keeps the on-disk index.html in step with the copy bundled in this jar. The panel serves the disk
     * copy, so replacing server.jar alone used to leave the old UI running. Returns "created" on first
     * run, "refreshed" when the disk copy was stale (the previous one is kept as index.html.old), or
     * "unchanged".
     */
    public static String syncToDisk(java.nio.file.Path indexFile) throws IOException {
        String bundled = html();
        if (!java.nio.file.Files.exists(indexFile)) {
            java.nio.file.Files.writeString(indexFile, bundled, StandardCharsets.UTF_8);
            return "created";
        }
        String onDisk = stripBom(java.nio.file.Files.readString(indexFile, StandardCharsets.UTF_8));
        if (onDisk.equals(bundled)) return "unchanged";
        java.nio.file.Files.writeString(indexFile.resolveSibling(indexFile.getFileName() + ".old"), onDisk, StandardCharsets.UTF_8);
        java.nio.file.Files.writeString(indexFile, bundled, StandardCharsets.UTF_8);
        return "refreshed";
    }

    /**
     * A byte-order mark is invisible in an editor but makes two identical panels compare unequal, which
     * would rewrite index.html and its .old backup on every start. It also means some tool saved the file
     * outside UTF-8, which is how the panel's arrows ended up rendering as "â†“".
     */
    private static String stripBom(String s) { return s.startsWith("\uFEFF") ? s.substring(1) : s; }

    public static String html() {
        try (InputStream in = Frontend.class.getResourceAsStream("/index.html")) {
            if (in != null) return stripBom(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException ignored) {}
        return "<!DOCTYPE html><html><head><title>Panel</title></head><body style=\"background:#111;color:#eee;font-family:sans-serif\">"
                + "<h1>Panel UI missing</h1><p>This jar was built without the bundled index.html. "
                + "Place an index.html next to server.jar and reload.</p></body></html>";
    }
}
