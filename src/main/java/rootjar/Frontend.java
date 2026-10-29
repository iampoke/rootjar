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

    public static String html() {
        try (InputStream in = Frontend.class.getResourceAsStream("/index.html")) {
            if (in != null) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
        return "<!DOCTYPE html><html><head><title>Panel</title></head><body style=\"background:#111;color:#eee;font-family:sans-serif\">"
                + "<h1>Panel UI missing</h1><p>This jar was built without the bundled index.html. "
                + "Place an index.html next to server.jar and reload.</p></body></html>";
    }
}
