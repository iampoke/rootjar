package rootjar.website;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/** A child process for static HTML/CSS/JS sites. No directory listing or server-side execution. */
public final class StaticSiteServer {
    private static final Map<String, String> MIME = Map.ofEntries(
        Map.entry("html", "text/html; charset=utf-8"), Map.entry("htm", "text/html; charset=utf-8"),
        Map.entry("css", "text/css; charset=utf-8"), Map.entry("js", "text/javascript; charset=utf-8"),
        Map.entry("mjs", "text/javascript; charset=utf-8"), Map.entry("json", "application/json; charset=utf-8"),
        Map.entry("txt", "text/plain; charset=utf-8"), Map.entry("svg", "image/svg+xml"),
        Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
        Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"), Map.entry("ico", "image/x-icon"),
        Map.entry("woff", "font/woff"), Map.entry("woff2", "font/woff2"), Map.entry("wasm", "application/wasm")
    );

    private StaticSiteServer() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: StaticSiteServer <bind> <port> <site-directory>");
        Path root = Path.of(args[2]).toRealPath();
        HttpServer server = HttpServer.create(new InetSocketAddress(args[0], Integer.parseInt(args[1])), 0);
        server.createContext("/", ex -> serve(ex, root));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("Static website listening on http://" + args[0] + ":" + args[1]);
    }

    private static void serve(HttpExchange ex, Path root) throws IOException {
        try (ex) {
            boolean head = ex.getRequestMethod().equals("HEAD");
            if (!head && !ex.getRequestMethod().equals("GET")) { reply(ex, 405); return; }
            String raw = ex.getRequestURI().getRawPath();
            String decoded;
            try { decoded = URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8); }
            catch (IllegalArgumentException e) { reply(ex, 400); return; }
            if (decoded.indexOf('\\') >= 0 || decoded.indexOf('\0') >= 0) { reply(ex, 403); return; }
            Path file = root;
            for (String part : decoded.split("/")) {
                if (part.isEmpty()) continue;
                if (part.equals(".") || part.equals("..") || part.startsWith(".")) { reply(ex, 403); return; }
                file = file.resolve(part);
                if (Files.isSymbolicLink(file)) { reply(ex, 403); return; }
            }
            if (!file.normalize().startsWith(root)) { reply(ex, 403); return; }
            if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) file = file.resolve("index.html");
            if (Files.isSymbolicLink(file)) { reply(ex, 403); return; }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(file)) { reply(ex, 404); return; }
            String name = file.getFileName().toString();
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
            ex.getResponseHeaders().set("Content-Type", MIME.getOrDefault(ext, "application/octet-stream"));
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            ex.sendResponseHeaders(200, head ? -1 : Files.size(file));
            if (!head) Files.copy(file, ex.getResponseBody());
        }
    }

    private static void reply(HttpExchange ex, int code) throws IOException {
        ex.sendResponseHeaders(code, -1);
    }
}
