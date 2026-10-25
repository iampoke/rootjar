package rootjar;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import rootjar.addons.Addons;
import rootjar.config.ChildConfig;
import rootjar.config.ConfigEditor;
import rootjar.config.ConfigSaver;
import rootjar.config.RootConfig;
import rootjar.config.ServerConfig;
import rootjar.config.StopStrategy;
import rootjar.supervisor.ChildProcess;
import rootjar.supervisor.Supervisor;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class WebServer {
    private record Session(String user, long createdAt, String csrf) {}

    private final HttpServer server;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    // Login hardening state, all keyed by client IP.
    private final Map<String, Deque<Long>> loginAttempts = new ConcurrentHashMap<>();
    private final Map<String, Integer> loginFailures = new ConcurrentHashMap<>();
    private final Map<String, Long> loginLockedUntil = new ConcurrentHashMap<>();
    private final Object netStatsLock = new Object();

    // Updated whenever the config is edited through the panel so user/password changes apply without a restart.
    private volatile RootConfig config;
    private final Supervisor supervisor;
    private final Path configPath;
    
    private final Map<Long, Long> lastCpuTime = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastWallTime = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    // Per-child disk usage, recomputed at most every DISK_TTL_MS: walking a large server folder on every
    // 2-second status poll would hammer the disk, so the scan runs on a background thread and the poll
    // serves the last known value.
    private record DiskUsage(long bytes, long computedAt) {}
    private static final long DISK_TTL_MS = 60_000;
    private final Map<String, DiskUsage> diskUsage = new ConcurrentHashMap<>();
    private final ExecutorService diskScanner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "disk-scanner"); t.setDaemon(true); return t;
    });

    private long lastNetTime = 0;
    private long lastRxBytes = 0;
    private long lastTxBytes = 0;

    // ---- addon/Modrinth caches (Phase 2) ----
    private static final long HTTP_CACHE_MS = 60_000;
    private static final long HASH_CACHE_MS = 10 * 60_000;
    private static final long CHECK_CACHE_MS = 10 * 60_000;
    private record Cached(long at, String body) {}
    private final Map<String, Cached> httpCache = new ConcurrentHashMap<>();
    private final Map<String, Cached> hashCache = new ConcurrentHashMap<>();
    private final Map<String, Cached> checkCache = new ConcurrentHashMap<>();
    private final Map<String, String> fileHashCache = new ConcurrentHashMap<>();

    public WebServer(RootConfig config, Supervisor supervisor, Path configPath) throws IOException {
        this.config = config;
        this.supervisor = supervisor;
        this.configPath = configPath;

        this.server = HttpServer.create(new InetSocketAddress(config.server().bind(), config.server().port()), 0);
        this.server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/", this::handleStatic);
        route("/api/login", this::handleLogin);
        route("/api/logout", this::handleLogout);
        route("/api/status", this::handleStatus);

        // Multi-step creation flow endpoints
        route("/api/create/types", this::handleCreateTypes);
        route("/api/create/forks", this::handleCreateForks);
        route("/api/create/versions", this::handleCreateVersions);
        route("/api/create/preview", this::handleCreatePreviewJar);
        route("/api/create/server", this::handleCreateServer);

        // Server management endpoints
        route("/api/servers/", this::handleServerRoute);

        // Enhanced config editor API
        route("/api/config/files", this::handleConfigFilesList);
        route("/api/config/read", this::handleConfigRead);
        route("/api/config/write", this::handleConfigWrite);
        route("/api/config/schema", this::handleConfigSchema);
        route("/api/config/editor/files", this::handleConfigEditorFiles);
        route("/api/config/editor/read", this::handleConfigEditorRead);
        route("/api/config/editor/save", this::handleConfigEditorSave);

        // Plugin management with version picker
        route("/api/modrinth/search", this::handleModrinthSearch);
        route("/api/modrinth/versions", this::handleModrinthVersions);
        route("/api/modrinth/install", this::handleModrinthInstall);
        route("/api/modrinth/updates", this::handleModrinthUpdates);

        // Installed addons (Mods/Plugins tab)
        route("/api/addons/list", this::handleAddonsList);
        route("/api/addons/toggle", this::handleAddonsToggle);
        route("/api/addons/delete", this::handleAddonsDelete);
        route("/api/addons/backup", this::handleAddonsBackup);
        route("/api/version/check", this::handleVersionCheck);

        route("/api/action/", this::handleAction);
        route("/api/silent/", this::handleSilent);
        route("/api/delete/", this::handleDelete);
        route("/api/console/", this::handleSSE);
        route("/api/command/", this::handleCommand);
        route("/api/startup/update/", this::handleStartupUpdate);

        route("/api/files/list/", this::handleFileList);
        route("/api/files/read/", this::handleFileRead);
        route("/api/files/write/", this::handleFileWrite);
        route("/api/files/delete/", this::handleFileDelete);
        route("/api/files/mkdir/", this::handleFileMkdir);
        route("/api/files/zip/", this::handleFileZip);
        route("/api/files/upload/", this::handleFileUpload);
        route("/api/files/rename/", this::handleFileRename);

        route("/api/logs/download", this::handleLogsDownload);

        route("/api/install/paper/", this::handleInstallPaper);

        route("/api/users/list", this::handleUsersList);
        route("/api/users/create", this::handleUsersCreate);
        route("/api/users/assign", this::handleUsersAssign);
    }

    public void start() { server.start(); }

    private String sessionToken(HttpExchange ex) {
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) return null;
        for (String c : cookie.split(";")) {
            if (c.trim().startsWith("session=")) return c.trim().substring(8);
        }
        return null;
    }

    private Session sessionOf(HttpExchange ex) {
        String token = sessionToken(ex);
        if (token == null) return null;
        Session s = sessions.get(token);
        if (s == null) return null;
        if (System.currentTimeMillis() - s.createdAt() > config.server().sessionTtlSeconds() * 1000L) {
            sessions.remove(token);
            return null;
        }
        return s;
    }

    private String getSessionUser(HttpExchange ex) {
        Session s = sessionOf(ex);
        return s == null ? null : s.user();
    }

    private boolean isAuthed(HttpExchange ex) { return getSessionUser(ex) != null; }

    /**
     * Registers a context behind the CSRF guard. GET/HEAD are untouched; every state-changing /api/* call
     * must echo the per-session token in X-CSRF-Token (login/logout are exempt because no session exists yet).
     */
    private void route(String path, HttpHandler handler) {
        server.createContext(path, ex -> {
            if (!csrfOk(ex)) { send(ex, 403, "CSRF token missing"); return; }
            handler.handle(ex);
        });
    }

    private boolean csrfOk(HttpExchange ex) {
        String method = ex.getRequestMethod();
        if (method.equalsIgnoreCase("GET") || method.equalsIgnoreCase("HEAD") || method.equalsIgnoreCase("OPTIONS")) return true;
        String path = ex.getRequestURI().getPath();
        if (!path.startsWith("/api/")) return true;
        if (path.equals("/api/login") || path.equals("/api/logout")) return true;
        String token = ex.getRequestHeaders().getFirst("X-CSRF-Token");
        if (token == null || token.isBlank()) return false;
        Session s = sessionOf(ex);
        return s != null && token.equals(s.csrf());
    }

    private boolean isAdmin(HttpExchange ex) {
        String user = getSessionUser(ex);
        if (user == null) return false;
        if (user.equals("admin")) return true;
        return config.server().users().stream().anyMatch(u -> u.username().equals(user) && u.role().equals("admin"));
    }

    private boolean canAccessServer(HttpExchange ex, String serverId) {
        String user = getSessionUser(ex);
        if (user == null) return false;
        if (user.equals("admin") || isAdmin(ex)) return true;
        return config.server().users().stream()
            .filter(u -> u.username().equals(user))
            .anyMatch(u -> u.allowedServers().contains("*") || u.allowedServers().contains(serverId));
    }

    // ---- per-server roles (Phase 7 adds server_roles; until then allowed users are operators) ----

    private enum Role { VIEWER, OPERATOR, OWNER, ADMIN }

    private Role roleFor(HttpExchange ex, String serverId) {
        String user = getSessionUser(ex);
        if (user == null) return null;
        if (user.equals("admin") || isAdmin(ex)) return Role.ADMIN;
        boolean allowed = config.server().users().stream()
                .filter(u -> u.username().equals(user))
                .anyMatch(u -> u.allowedServers().contains("*") || u.allowedServers().contains(serverId));
        return allowed ? Role.OPERATOR : null;
    }

    private boolean requireServer(HttpExchange ex, String serverId) throws IOException {
        if (roleFor(ex, serverId) == null) { send(ex, 403, "No permission"); return false; }
        return true;
    }

    private boolean requireOperator(HttpExchange ex, String serverId) throws IOException {
        Role role = roleFor(ex, serverId);
        if (role == null || role == Role.VIEWER) { send(ex, 403, "No permission"); return false; }
        return true;
    }

    /** Query param with a default; blank counts as absent. */
    private String param(HttpExchange ex, String key, String def) {
        String v = extractQuery(ex.getRequestURI().getRawQuery(), key);
        return v == null || v.isBlank() ? def : v;
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static int clampInt(String raw, int def, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(raw))); }
        catch (Exception e) { return def; }
    }

    /** Cached JSON GET against Modrinth; throws on non-200 so callers can answer 502. */
    private String modrinthGet(String url, long ttlMs) throws IOException, InterruptedException {
        long now = System.currentTimeMillis();
        Cached hit = httpCache.get(url);
        if (hit != null && now - hit.at() < ttlMs) return hit.body();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "childserversjar-panel/0.2.0")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IOException("Modrinth returned HTTP " + res.statusCode());
        if (httpCache.size() > 400) httpCache.clear();
        httpCache.put(url, new Cached(now, res.body()));
        return res.body();
    }

    /** best-effort JSON POST helper; returns null on any failure (offline, rate limit, bad body). */
    private String modrinthPost(String url, String body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "childserversjar-panel/0.2.0")
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return res.statusCode() == 200 ? res.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Minecraft version of a child, from version_history.json, latest.log, then the jar name. */
    private static String detectMcVersion(ChildConfig cfg) {
        Path dir = cfg.dir();
        try {
            Path history = dir.resolve("version_history.json");
            if (Files.isRegularFile(history)) {
                Matcher m = Pattern.compile("MC:\\s*([^)\\s]+)").matcher(Files.readString(history));
                if (m.find()) return m.group(1).replace("\"", "").trim();
            }
        } catch (Exception ignored) {}
        try {
            Path log = dir.resolve("logs").resolve("latest.log");
            if (Files.isRegularFile(log)) {
                List<String> lines = Files.readAllLines(log);
                for (int i = 0; i < Math.min(60, lines.size()); i++) {
                    Matcher m = Pattern.compile("Starting minecraft server version\\s+(\\S+)").matcher(lines.get(i));
                    if (m.find()) return m.group(1);
                    m = Pattern.compile("\\(MC:\\s*([^)]+)\\)").matcher(lines.get(i));
                    if (m.find()) return m.group(1).trim();
                }
            }
        } catch (Exception ignored) {}
        Matcher m = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)").matcher(cfg.jar() == null ? "" : cfg.jar());
        return m.find() ? m.group(1) : "";
    }

    private static final java.text.SimpleDateFormat STAMP = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss");
    private static synchronized String stamp() { return STAMP.format(new java.util.Date()); }

    private void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (!path.equals("/") && !path.equals("/index.html")) { send(ex, 404, "Not Found"); return; }

        byte[] bytes;
        Path file = Paths.get("index.html");
        if (Files.exists(file)) {
            bytes = Files.readAllBytes(file);
        } else {
            // Disk copy missing (deleted while running): fall back to the copy bundled in the jar.
            bytes = Frontend.html().getBytes(StandardCharsets.UTF_8);
        }
        // The UI ships inside the jar and is regenerated on disk; never let the browser pin an old copy,
        // or the panel keeps running stale JavaScript after an upgrade.
        ex.getResponseHeaders().set("Content-Type", "text/html");
        ex.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
        ex.getResponseHeaders().set("Pragma", "no-cache");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    /** Best-effort client IP: first X-Forwarded-For hop (the panel is meant to sit behind a proxy), else the socket. */
    private String clientIp(HttpExchange ex) {
        String xff = ex.getRequestHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String first = xff.split(",")[0].trim();
            if (!first.isEmpty()) return first;
        }
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }

    private void handleLogin(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { send(ex, 405, "POST only"); return; }
        ServerConfig srv = config.server();
        String ip = clientIp(ex);
        long now = System.currentTimeMillis();

        Long lockedUntil = loginLockedUntil.get(ip);
        if (lockedUntil != null) {
            if (lockedUntil > now) { send(ex, 429, "Too many failed attempts; try again later"); return; }
            loginLockedUntil.remove(ip);
        }
        Deque<Long> attempts = loginAttempts.computeIfAbsent(ip, k -> new ArrayDeque<>());
        while (!attempts.isEmpty() && attempts.peekFirst() < now - 60_000) attempts.pollFirst();
        if (attempts.size() >= srv.loginRateLimitPerIpPerMinute()) {
            send(ex, 429, "Rate limit exceeded; wait a minute");
            return;
        }
        attempts.addLast(now);

        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String user = extractJson(body, "u");
        String pass = extractJson(body, "p");

        boolean valid = false;
        if ("admin".equals(user) && !srv.passwordHash().equals("placeholder")
                && rootjar.config.PasswordHasher.verify(pass.toCharArray(), srv.passwordHash())) {
            valid = true;
        } else {
            for (ServerConfig.UserConfig u : srv.users()) {
                if (u.username().equals(user) && rootjar.config.PasswordHasher.verify(pass.toCharArray(), u.passwordHash())) {
                    valid = true; break;
                }
            }
        }

        if (valid) {
            loginFailures.remove(ip);
            String token = UUID.randomUUID().toString();
            sessions.put(token, new Session(user, now, UUID.randomUUID().toString()));
            ex.getResponseHeaders().add("Set-Cookie", "session=" + token + "; HttpOnly; SameSite=Strict; Path=/");
            send(ex, 200, "OK");
        } else {
            int failures = loginFailures.merge(ip, 1, Integer::sum);
            if (failures >= srv.loginLockoutAfterFailures()) {
                loginLockedUntil.put(ip, now + srv.loginLockoutSeconds() * 1000L);
                loginFailures.remove(ip);
            }
            send(ex, 401, "Bad Login");
        }
    }

    private void handleLogout(HttpExchange ex) throws IOException {
        String token = sessionToken(ex);
        if (token != null) sessions.remove(token);
        ex.getResponseHeaders().add("Set-Cookie", "session=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0");
        send(ex, 200, "OK");
    }

    private long[] getNetworkStats() {
        long rx = 0, tx = 0;
        try {
            Path netPath = Paths.get("/sys/class/net");
            if (Files.exists(netPath)) {
                try (Stream<Path> stream = Files.list(netPath)) {
                    for (Path iface : stream.toList()) {
                        String name = iface.getFileName().toString();
                        if (name.equals("lo")) continue;
                        Path rxPath = iface.resolve("statistics/rx_bytes");
                        Path txPath = iface.resolve("statistics/tx_bytes");
                        if (Files.exists(rxPath)) rx += Long.parseLong(Files.readString(rxPath).trim());
                        if (Files.exists(txPath)) tx += Long.parseLong(Files.readString(txPath).trim());
                    }
                }
            }
        } catch (Exception ignored) {}
        return new long[]{rx, tx};
    }

    private String formatDuration(long millis) {
        long s = millis / 1000;
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m " + (s % 60) + "s";
        long h = m / 60;
        if (h < 24) return h + "h " + (m % 60) + "m";
        return (h / 24) + "d " + (h % 24) + "h";
    }

    /** Cached disk usage of a child's folder; first call computes inline, later refreshes run in the background. */
    private long diskUsageOf(ChildProcess c) {
        String id = c.id();
        DiskUsage e = diskUsage.get(id);
        long now = System.currentTimeMillis();
        if (e == null) {
            long bytes = dirSize(c.config().dir());
            diskUsage.put(id, new DiskUsage(bytes, now));
            return bytes;
        }
        if (now - e.computedAt() > DISK_TTL_MS) {
            try {
                diskScanner.execute(() -> diskUsage.put(id,
                        new DiskUsage(dirSize(c.config().dir()), System.currentTimeMillis())));
            } catch (RejectedExecutionException ignored) {}
        }
        return e.bytes();
    }

    /** Total size of all regular files under dir; 0 if missing/unreadable. */
    private static long dirSize(Path dir) {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { return 0; } })
                    .sum();
        } catch (Exception e) {
            return 0;
        }
    }

    private void handleStatus(HttpExchange ex) throws IOException {
        if (!isAuthed(ex)) { send(ex, 401, "Unauthorized"); return; }
        boolean admin = isAdmin(ex);

        long maxRamMb = 3072;
        try {
            String maxStr = Files.readString(Paths.get("/sys/fs/cgroup/memory.max")).trim();
            if (!maxStr.equals("max")) maxRamMb = Long.parseLong(maxStr) / (1024 * 1024);
        } catch (Exception ignored) {}

        int cpuMax = Math.max(100, Runtime.getRuntime().availableProcessors() * 100);

        long rxSpeed = 0, txSpeed = 0;
        synchronized (netStatsLock) {
            try {
                long currentTime = System.currentTimeMillis();
                long[] netStats = getNetworkStats();
                if (lastNetTime > 0) {
                    long timeDiff = currentTime - lastNetTime;
                    if (timeDiff > 0) {
                        rxSpeed = ((netStats[0] - lastRxBytes) * 1000 / timeDiff) / 1024;
                        txSpeed = ((netStats[1] - lastTxBytes) * 1000 / timeDiff) / 1024;
                    }
                }
                lastNetTime = currentTime;
                lastRxBytes = netStats[0];
                lastTxBytes = netStats[1];
            } catch (Exception ignored) {}
        }

        record ChildStats(ChildProcess child, long ramMb, long cpuPct, long diskBytes) {}
        List<ChildStats> stats = new ArrayList<>();
        long totalUsedRam = 0, totalUsedCpu = 0, totalDisk = 0;
        for (ChildProcess c : supervisor.children()) {
            if (!admin && !canAccessServer(ex, c.id())) continue;

            long ramMb = 0; double cpuPct = 0;
            long pid = c.pid();
            if (pid > 0 && c.isAlive()) {
                try {
                    for (String line : Files.readAllLines(Paths.get("/proc/" + pid + "/status"))) {
                        if (line.startsWith("VmRSS:")) { ramMb = Long.parseLong(line.replaceAll("[^0-9]", "")) / 1024; break; }
                    }
                } catch (Exception ignored) {}
                try {
                    Optional<Duration> totalCpuOpt = ProcessHandle.of(pid).flatMap(ph -> ph.info().totalCpuDuration());
                    if (totalCpuOpt.isPresent()) {
                        long nowCpu = totalCpuOpt.get().toMillis(); long nowWall = System.currentTimeMillis();
                        if (lastCpuTime.containsKey(pid) && lastWallTime.containsKey(pid)) {
                            long cpuDelta = nowCpu - lastCpuTime.get(pid); long wallDelta = nowWall - lastWallTime.get(pid);
                            if (wallDelta > 0) cpuPct = ((double) cpuDelta / wallDelta) * 100.0;
                        }
                        lastCpuTime.put(pid, nowCpu); lastWallTime.put(pid, nowWall);
                    }
                } catch (Exception ignored) {}
            }
            long cpuRounded = Math.round(cpuPct);
            long diskBytes = diskUsageOf(c);
            totalUsedRam += ramMb;
            totalUsedCpu += cpuRounded;
            totalDisk += diskBytes;
            stats.add(new ChildStats(c, ramMb, cpuRounded, diskBytes));
        }
        totalUsedCpu = Math.min(totalUsedCpu, cpuMax);

        StringBuilder sb = new StringBuilder();
        Session sess = sessionOf(ex);
        sb.append("{\"publicIp\":\"").append(jsonEscape(config.server().publicIp())).append("\",")
          .append("\"csrfToken\":\"").append(jsonEscape(sess == null ? "" : sess.csrf())).append("\",")
          .append("\"ramMax\":").append(maxRamMb)
          .append(",\"cpuMax\":").append(cpuMax)
          .append(",\"ramUsed\":").append(totalUsedRam)
          .append(",\"cpuUsed\":").append(totalUsedCpu)
          .append(",\"storageUsed\":").append(totalDisk)
          .append(",\"storageMax\":").append((long) config.server().diskMaxGb() << 30)
          .append(",\"netRx\":").append(rxSpeed).append(",\"netTx\":").append(txSpeed)
          .append(",\"isAdmin\":").append(admin).append(",\"servers\":[");
        boolean first = true;
        for (ChildStats s : stats) {
            ChildProcess c = s.child();
            ChildConfig k = c.config();
            if (!first) sb.append(","); first = false;
            sb.append("{\"id\":\"").append(jsonEscape(k.id())).append("\",\"port\":").append(k.port())
              .append(",\"type\":\"").append(jsonEscape(k.type()))
              .append("\",\"jar\":\"").append(jsonEscape(k.jar())).append("\",\"heap\":\"").append(jsonEscape(k.heap()))
              .append("\",\"useAikarFlags\":").append(k.useAikarFlags()).append(",\"extraFlags\":\"")
              .append(jsonEscape(String.join(" ", k.extraFlags()))).append("\",\"status\":\"").append(c.state().name())
              .append("\",\"silent\":").append(k.silent()).append(",")
              .append("\"uptime\":\"").append(formatDuration(c.uptimeMillis())).append("\",")
              .append("\"players\":").append(c.playerCount()).append(",")
              .append("\"ramUsed\":").append(s.ramMb()).append(",\"ramMax\":").append(parseRam(k.heap()))
              .append(",\"diskUsed\":").append(s.diskBytes())
              .append(",\"cpuUsed\":").append(s.cpuPct()).append("}");
        }
        sb.append("]}");
        send(ex, 200, sb.toString());
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
                }
            }
        }
        return sb.toString();
    }

    private void handleSilent(HttpExchange ex) throws IOException {
        if (!isAdmin(ex)) { send(ex, 403, "Admin only"); return; }
        String id = ex.getRequestURI().getPath().substring(ex.getRequestURI().getPath().lastIndexOf('/') + 1);
        boolean silent = Boolean.parseBoolean(new String(ex.getRequestBody().readAllBytes()));
        
        supervisor.setSilent(id, silent);
        persistConfig();

        send(ex, 200, "OK");
    }

    private void handleDelete(HttpExchange ex) throws IOException {
        if (!isAdmin(ex)) { send(ex, 403, "Admin only"); return; }
        String id = ex.getRequestURI().getPath().substring(ex.getRequestURI().getPath().lastIndexOf('/') + 1);

        ChildProcess child = supervisor.child(id).orElse(null);
        if (child == null) { send(ex, 404, "Not found"); return; }

        long pid = child.pid();
        // Files on disk are intentionally kept: this only removes the server from the panel/config.
        supervisor.removeChild(id);
        lastCpuTime.remove(pid);
        lastWallTime.remove(pid);
        diskUsage.remove(id);

        List<ChildConfig> newChildren = new ArrayList<>();
        for (ChildProcess c : supervisor.children()) {
            newChildren.add(c.config());
        }
        saveConfig(new RootConfig(config.server(), newChildren, config.baseDir()));

        send(ex, 200, "Deleted");
    }

    // --- Multi-step creation flow handlers ---

    private void handleCreateTypes(HttpExchange ex) throws IOException {
        if (!isAdmin(ex)) { send(ex, 403, "Admin only"); return; }
        send(ex, 200, "[{\"id\":\"minecraft\",\"name\":\"Minecraft Server\",\"description\":\"Java Minecraft servers\"},"
                + "{\"id\":\"discord_bot\",\"name\":\"Discord Bot\",\"description\":\"Java-based Discord bots\"},"
                + "{\"id\":\"custom\",\"name\":\"Custom\",\"description\":\"Manually configured server\"}]");
    }

    private void handleCreateForks(HttpExchange ex) throws IOException {
        if (!isAdmin(ex)) { send(ex, 403, "Admin only"); return; }
        String type = extractQuery(ex.getRequestURI().getRawQuery(), "type");
        if (type == null || type.equals("minecraft")) {
            send(ex, 200, "[{\"id\":\"paper\",\"name\":\"Paper\",\"ram_recommended\":1024},"
                + "{\"id\":\"purpur\",\"name\":\"Purpur\",\"ram_recommended\":1024},"
                + "{\"id\":\"fabric\",\"name\":\"Fabric\",\"ram_recommended\":1024},"
                + "{\"id\":\"quilt\",\"name\":\"Quilt\",\"ram_recommended\":1024},"
                + "{\"id\":\"spigot\",\"name\":\"Spigot\",\"ram_recommended\":1024}]");
        } else if (type.equals("discord_bot")) {
            send(ex, 200, "[{\"id\":\"jda\",\"name\":\"JDA (Java Discord API)\",\"ram_recommended\":512},"
                + "{\"id\":\"discord4j\",\"name\":\"Discord4J\",\"ram_recommended\":512}]");
        } else {
            send(ex, 200, "[]");
        }
    }

    private void handleCreateVersions(HttpExchange ex) throws IOException {
        if (!isAdmin(ex)) { send(ex, 403, "Admin only"); return; }
        String fork = extractQuery(ex.getRequestURI().getRawQuery(), "fork");
        if (fork == null || fork.isEmpty()) { send(ex, 400, "fork parameter required"); return; }

        // PaperMC's v2 API was sunset; paper lives on fill v3, purpur on its own api.purpurmc.org.
        String url;
        switch (fork) {
            case "paper" -> url = "https://fill.papermc.io/v3/projects/paper/versions";
            case "purpur" -> url = "https://api.purpurmc.org/v2/purpur";
            case "fabric" -> url = "https://meta.fabricmc.net/v2/versions/game";
            case "quilt" -> url = "https://meta.quiltmc.org/v3/versions/game";
            default -> { send(ex, 400, "Unknown fork"); return; }
        }

        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "childserversjar-panel")
                .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) { send(ex, 500, "Upstream API error " + res.statusCode()); return; }

            // Each upstream shapes its list differently; normalize to a flat JSON array of version strings.
            List<String> versions = new ArrayList<>();
            String body = res.body();
            if (fork.equals("paper")) {
                Matcher m = Pattern.compile("\"version\":\\{\"id\":\"([^\"]+)\"").matcher(body);
                while (m.find()) versions.add(m.group(1));
            } else if (fork.equals("purpur")) {
                Matcher m = Pattern.compile("\"versions\":\\[(.*?)]").matcher(body);
                if (m.find()) {
                    for (String v : m.group(1).split(",")) versions.add(v.trim().replace("\"", ""));
                }
                Collections.reverse(versions); // upstream is oldest-first; newest-first like the others
            } else {
                // [{"version":"1.21.4","stable":true}, ...] — skip snapshots and pre-releases
                Matcher m = Pattern.compile("\"version\":\\s*\"([^\"]+)\",\\s*\"stable\":\\s*(true|false)").matcher(body);
                while (m.find()) { if (m.group(2).equals("true")) versions.add(m.group(1)); }
            }
            versions.removeIf(String::isEmpty);

            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < versions.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(jsonEscape(versions.get(i))).append("\"");
            }
            send(ex, 200, sb.append("]").toString());
        } catch (Exception e) { send(ex, 500, e.getMessage()); }
    }

    private void handleCreatePreviewJar(HttpExchange ex) throws IOException {
        if (!isAdmin(ex)) { send(ex, 403, "Admin only"); return; }
        String fork = extractQuery(ex.getRequestURI().getRawQuery(), "fork");
        String version = extractQuery(ex.getRequestURI().getRawQuery(), "version");

        if (fork == null || version == null) {
            send(ex, 400, "fork and version required");
            return;
        }

        String jarName;
        String downloadUrl;
        try {
            if (fork.equals("paper")) {
                // fill v3: the latest build carries the server jar name and download URL.
                HttpRequest req = HttpRequest.newBuilder(URI.create("https://fill.papermc.io/v3/projects/paper/versions/" + version + "/builds/latest"))
                    .header("User-Agent", "childserversjar-panel")
                    .build();
                HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() != 200) { send(ex, 500, "Upstream API error " + res.statusCode()); return; }
                Matcher name = Pattern.compile("\"server:default\":\\{\"name\":\"([^\"]+)\"").matcher(res.body());
                Matcher url = Pattern.compile("\"url\":\"([^\"]+\\.jar)\"").matcher(res.body());
                if (!name.find() || !url.find()) { send(ex, 500, "Could not parse build info"); return; }
                jarName = name.group(1);
                downloadUrl = url.group(1).replace("\\/", "/");
            } else if (fork.equals("purpur")) {
                HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.purpurmc.org/v2/purpur/" + version))
                    .header("User-Agent", "childserversjar-panel")
                    .build();
                HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() != 200) { send(ex, 500, "Upstream API error " + res.statusCode()); return; }
                Matcher build = Pattern.compile("\"latest\":\"([0-9]+)\"").matcher(res.body());
                if (!build.find()) { send(ex, 500, "Could not parse build info"); return; }
                String b = build.group(1);
                jarName = "purpur-" + version + "-" + b + ".jar";
                downloadUrl = "https://api.purpurmc.org/v2/purpur/" + version + "/" + b + "/download";
            } else {
                send(ex, 400, "Preview not available for this fork");
                return;
            }
        } catch (Exception e) {
            send(ex, 500, e.getMessage());
            return;
        }

        send(ex, 200, "{\"jar_name\":\"" + jsonEscape(jarName) + "\",\"download_url\":\"" + jsonEscape(downloadUrl) + "\"}");
    }

    private void handleCreateServer(HttpExchange ex) throws IOException {
        if (!isAdmin(ex)) { send(ex, 403, "Admin only"); return; }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String name = extractJson(body, "name");
        String portStr = extractJson(body, "port");
        String ramStr = extractJson(body, "ram");
        String jar = extractJson(body, "jar");
        String fork = extractJson(body, "fork");
        String serverType = extractJson(body, "server_type");
        String downloadUrl = extractJson(body, "download_url");

        if (!name.matches("^[a-zA-Z0-9_-]+$") || Set.of("status", "help", "stop").contains(name.toLowerCase(java.util.Locale.ROOT))) {
            send(ex, 400, "Invalid name."); return;
        }
        if (supervisor.child(name).isPresent()) { send(ex, 400, "A server with this name already exists."); return; }
        if (jar == null || jar.isEmpty()) { jar = "server.jar"; }
        jar = Paths.get(jar.replace('\\', '/')).getFileName().toString();
        if (jar.equals(".") || jar.equals("..") || !jar.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
            send(ex, 400, "Jar file name must end with .jar"); return;
        }
        if (ramStr == null || ramStr.isEmpty()) { ramStr = "1024"; }

        int port, ram;
        try {
            port = Integer.parseInt(portStr);
            ram = Integer.parseInt(ramStr);
        } catch (NumberFormatException e) { send(ex, 400, "Bad numbers"); return; }
        if (port < 1 || port > 65535) { send(ex, 400, "Port must be 1-65535"); return; }
        if (port == config.server().port()) { send(ex, 400, "Port is used by the web panel"); return; }
        for (ChildProcess c : supervisor.children()) {
            if (c.config().port() == port) { send(ex, 400, "Port in use"); return; }
        }
        if (ram < 128) { send(ex, 400, "RAM must be at least 128 MB"); return; }

        try {
            Path dir = config.baseDir().resolve(name);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("eula.txt"), "eula=true");
            generateServerProperties(dir.resolve("server.properties"), port);

            int startOrder = supervisor.children().stream().mapToInt(c -> c.config().startOrder()).max().orElse(0) + 1;
            int stopOrder = supervisor.children().stream().mapToInt(c -> c.config().stopOrder()).max().orElse(0) + 1;
            String type = serverType != null ? serverType : (fork != null ? fork : "paper");

            ChildConfig newChild = new ChildConfig(name, type, dir, jar, ram + "M", true, port, "0.0.0.0",
                StopStrategy.STDIN, "stop", 30, startOrder, stopOrder, true, List.of(5, 10, 30, 60, 300), List.of(), true, true, false);
            supervisor.addChild(newChild);

            saveConfig(new RootConfig(config.server(),
                    supervisor.children().stream().map(ChildProcess::config).toList(), config.baseDir()));

            // The wizard preview resolves the exact Paper/Purpur build. Fetch it now so the server is ready
            // to start; a failed download must not undo/roll back the server entry.
            Path jarPath = dir.resolve(jar);
            boolean downloaded = false;
            String warning = null;
            if (downloadUrl != null && !downloadUrl.isBlank() && !Files.exists(jarPath)) {
                try {
                    downloadServerJar(jarPath, downloadUrl);
                    downloaded = true;
                } catch (Exception e) {
                    warning = "Server created, but the server jar download failed: " + e.getMessage();
                }
            } else if ((fork != null && (fork.equals("paper") || fork.equals("purpur")))
                    && !Files.exists(jarPath)) {
                warning = "Server created without a jar. Upload " + jar + " in Files before starting it.";
            }

            StringBuilder response = new StringBuilder("{\"id\":\"").append(jsonEscape(name))
                .append("\",\"message\":\"Created\",\"jar_downloaded\":").append(downloaded);
            if (warning != null) response.append(",\"warning\":\"").append(jsonEscape(warning)).append("\"");
            send(ex, 200, response.append("}").toString());
        } catch (IOException e) { send(ex, 500, e.getMessage()); }
    }

    /** Downloads a server jar from the official Paper/Purpur/Fabric/Quilt endpoints (host whitelist prevents SSRF). */
    private void downloadServerJar(Path target, String url) throws IOException {
        URI uri;
        try { uri = URI.create(url); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid download URL"); }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !Set.of("fill.papermc.io", "fill-data.papermc.io", "api.purpurmc.org",
                           "meta.fabricmc.net", "meta.quiltmc.org").contains(host)) {
            throw new IOException("Unsupported download host");
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".download");
        try {
            HttpRequest req = HttpRequest.newBuilder(uri)
                .header("User-Agent", "childserversjar-panel")
                .timeout(Duration.ofMinutes(5))
                .build();
            HttpResponse<Path> res = httpClient.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
            if (res.statusCode() != 200) throw new IOException("Download returned HTTP " + res.statusCode());
            if (!Files.isRegularFile(tmp) || Files.size(tmp) < 1024) throw new IOException("Downloaded jar is empty");
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }

    private void generateServerProperties(Path propsFile, int port) throws IOException {
        String props = "# Server properties\n"
                + "server-port=" + port + "\n"
                + "level-name=world\n"
                + "gamemode=survival\n"
                + "difficulty=normal\n"
                + "online-mode=true\n"
                + "max-players=20\n"
                + "pvp=true\n"
                + "generate-structures=true\n"
                + "spawn-protection=16\n"
                + "view-distance=10\n"
                + "allow-nether=true\n"
                + "enable-command-block=false\n";
        Files.writeString(propsFile, props);
    }

    private void handleAction(HttpExchange ex) throws IOException {
        if (!isAuthed(ex)) { send(ex, 401, "Unauthorized"); return; }
        String id = ex.getRequestURI().getPath().substring(ex.getRequestURI().getPath().lastIndexOf('/') + 1);
        if (!canAccessServer(ex, id)) { send(ex, 403, "No permission"); return; }
        String action = new String(ex.getRequestBody().readAllBytes());
        if (action.equals("kill")) supervisor.child(id).ifPresent(ChildProcess::kill);
        else supervisor.requestLifecycle(id, action);
        send(ex, 200, "OK");
    }

    // --- Server routing handler ---

    private void handleServerRoute(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String prefix = "/api/servers/";
        String subPath = path.length() > prefix.length() ? path.substring(prefix.length()) : "";

        if (subPath.isEmpty()) {
            send(ex, 200, handleServerList());
            return;
        }

        String[] parts = subPath.split("/");
        String serverId = parts[0];
        String rest = parts.length > 1 ? String.join("/", Arrays.copyOfRange(parts, 1, parts.length)) : "";

        if (rest.isEmpty() || rest.equals("info")) {
            handleServerInfo(ex, serverId);
        } else if (rest.equals("files")) {
            handleServerFilesList(ex, serverId);
        } else if (rest.startsWith("files/")) {
            handleServerFiles(ex, serverId, rest);
        } else if (rest.startsWith("config")) {
            handleServerConfigRoutes(ex, serverId, rest);
        } else {
            send(ex, 404, "Not found");
        }
    }

    private String handleServerList() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (ChildProcess c : supervisor.children()) {
            if (!first) sb.append(",");
            first = false;
            ChildConfig cfg = c.config();
            sb.append("{\"id\":\"").append(jsonEscape(cfg.id()))
                .append("\",\"type\":\"").append(cfg.type())
                .append("\",\"port\":").append(cfg.port())
                .append(",\"heap\":\"").append(jsonEscape(cfg.heap()))
                .append("\",\"jar\":\"").append(jsonEscape(cfg.jar()))
                .append("\",\"status\":\"").append(c.state().name()).append("\"}");
        }
        sb.append("]");
        return sb.toString();
    }

    private void handleServerInfo(HttpExchange ex, String id) throws IOException {
        if (!canAccessServer(ex, id)) { send(ex, 403, "No permission"); return; }
        ChildProcess child = supervisor.child(id).orElse(null);
        if (child == null) { send(ex, 404, "Not found"); return; }
        ChildConfig cfg = child.config();
        send(ex, 200, "{\"id\":\"" + id + "\",\"type\":\"" + cfg.type() + "\",\"port\":" + cfg.port()
            + ",\"heap\":\"" + cfg.heap() + "\",\"jar\":\"" + cfg.jar() + "\",\"status\":\"" + child.state().name()
            + "\",\"players\":" + child.playerCount()
            + ",\"uptime\":\"" + Supervisor.formatDuration(child.uptimeMillis()) + "\"}");
    }

    
    private void handleServerFilesList(HttpExchange ex, String id) throws IOException {
        if (!canAccessServer(ex, id)) { send(ex, 403, "No permission"); return; }
        ChildProcess child = supervisor.child(id).orElse(null);
        if (child == null) { send(ex, 404, "Not found"); return; }
        String path = extractQuery(ex.getRequestURI().getRawQuery(), "path");
        Path dir = path != null && !path.isEmpty() ? safeResolve(child, path) : child.config().dir();

        try (Stream<Path> entries = Files.list(dir)) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Path entry : entries.toList()) {
                if (!first) sb.append(",");
                first = false;
                String name = entry.getFileName().toString();
                boolean isDir = Files.isDirectory(entry);
                String type = isDir ? "dir" : (name.endsWith(".yml") || name.endsWith(".yaml") ? "yaml"
                    : name.endsWith(".json") ? "json"
                    : name.endsWith(".properties") ? "properties"
                    : "file");
                sb.append("{\"name\":\"").append(jsonEscape(name))
                    .append("\",\"type\":\"").append(type).append("\"")
                    .append(",\"dir\":").append(isDir)
                    .append(",\"size\":").append(isDir ? 0 : entry.toFile().length()).append("}");
            }
            sb.append("]");
            send(ex, 200, sb.toString());
        } catch (IOException e) { send(ex, 500, e.getMessage()); }
    }

    private void handleServerFiles(HttpExchange ex, String id, String rest) throws IOException {
        if (!canAccessServer(ex, id)) { send(ex, 403, "No permission"); return; }
        ChildProcess child = supervisor.child(id).orElse(null);
        if (child == null) { send(ex, 404, "Not found"); return; }
        String path = rest.substring(rest.indexOf('/', 1) + 1);
        Path file = safeResolve(child, path);

        String method = ex.getRequestMethod();
        if (method.equals("GET")) {
            if (Files.exists(file) && !Files.isDirectory(file)) {
                send(ex, 200, Files.readString(file));
            } else { send(ex, 404, "Not found"); }
        } else if (method.equals("POST")) {
            String content = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Files.writeString(file, content);
            send(ex, 200, "Saved");
        } else if (method.equals("DELETE")) {
            Files.deleteIfExists(file);
            send(ex, 200, "Deleted");
        } else { send(ex, 405, "Method not allowed"); }
    }

    private void handleServerConfigRoutes(HttpExchange ex, String id, String rest) throws IOException {
        if (!canAccessServer(ex, id)) { send(ex, 403, "No permission"); return; }
        ChildProcess child = supervisor.child(id).orElse(null);
        if (child == null) { send(ex, 404, "Not found"); return; }
        String path = extractQuery(ex.getRequestURI().getRawQuery(), "path");
        if (path == null || path.isEmpty()) { send(ex, 400, "path parameter required"); return; }

        String method = ex.getRequestMethod();
        if (method.equals("GET")) {
            handleServerConfigRead(ex, child, path);
        } else if (method.equals("POST")) {
            String content = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            handleServerConfigWrite(ex, child, path, content);
        } else { send(ex, 405, "Method not allowed"); }
    }

    private void handleServerConfigRead(HttpExchange ex, ChildProcess child, String path) throws IOException {
        Path file = safeResolve(child, path);
        if (!Files.exists(file)) { send(ex, 200, "{\"exists\":false}"); return; }

        String content = Files.readString(file);
        String format = "raw";
        if (path.endsWith(".yml") || path.endsWith(".yaml")) { format = "yaml"; }
        else if (path.endsWith(".json")) { format = "json"; }
        else if (path.endsWith(".properties")) { format = "properties"; }

        send(ex, 200, "{\"content\":\"" + jsonEscape(content) + "\",\"format\":\"" + format + "\",\"keys\":[]}");
    }

    private void handleServerConfigWrite(HttpExchange ex, ChildProcess child, String path, String content) throws IOException {
        Path file = safeResolve(child, path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        send(ex, 200, "{\"saved\":true}");
    }

    private void handleSSE(HttpExchange ex) throws IOException {
        if (!isAuthed(ex)) { send(ex, 401, "Unauthorized"); return; }
        String id = ex.getRequestURI().getPath().substring(ex.getRequestURI().getPath().lastIndexOf('/') + 1);
        if (!canAccessServer(ex, id)) { send(ex, 403, "No permission"); return; }
        ChildProcess child = supervisor.child(id).orElse(null);
        if (child == null) { send(ex, 404, "Not found"); return; }

        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        long lastSeq = child.log().lastSeq() > 100 ? child.log().lastSeq() - 50 : 0;
        
        try (OutputStream os = ex.getResponseBody()) {
            long lastWrite = System.currentTimeMillis();
            while (true) {
                var logs = child.log().since(lastSeq);
                for (var entry : logs) {
                    os.write(("data: " + entry.line().replace("\r", "").replace("\n", "") + "\n\n").getBytes(StandardCharsets.UTF_8));
                    os.flush(); lastSeq = entry.seq();
                }
                if (!logs.isEmpty()) {
                    lastWrite = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - lastWrite >= 10_000) {
                    os.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8)); // stops proxies dropping idle streams
                    os.flush();
                    lastWrite = System.currentTimeMillis();
                }
                Thread.sleep(500);
            }
        } catch (Exception e) {}
    }

    /** Full in-memory console log as a text download; the UI filter does not narrow it. */
    private void handleLogsDownload(HttpExchange ex) throws IOException {
        if (!isAuthed(ex)) { send(ex, 401, "Unauthorized"); return; }
        String id = extractQuery(ex.getRequestURI().getRawQuery(), "server");
        ChildProcess child = id == null ? null : supervisor.child(id).orElse(null);
        if (child == null || !canAccessServer(ex, id)) { send(ex, 404, "Not found"); return; }
        StringBuilder sb = new StringBuilder();
        for (var entry : child.log().since(0)) sb.append(entry.line()).append("\r\n");
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + id.replaceAll("[^A-Za-z0-9._-]", "_") + "-console.log\"");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private void handleCommand(HttpExchange ex) throws IOException {
        if (!isAuthed(ex)) { send(ex, 401, "Unauthorized"); return; }
        String id = ex.getRequestURI().getPath().substring(ex.getRequestURI().getPath().lastIndexOf('/') + 1);
        if (!canAccessServer(ex, id)) { send(ex, 403, "No permission"); return; }
        String cmd = new String(ex.getRequestBody().readAllBytes());
        supervisor.child(id).ifPresent(c -> c.sendLine(cmd));
        send(ex, 200, "OK");
    }

    private void handleStartupUpdate(HttpExchange ex) throws IOException {
        if (!isAdmin(ex)) { send(ex, 403, "Admin only"); return; }
        String id = ex.getRequestURI().getPath().substring(ex.getRequestURI().getPath().lastIndexOf('/') + 1);
        String body = new String(ex.getRequestBody().readAllBytes());
        String heap = extractJson(body, "heap"); String jar = extractJson(body, "jar"); String portStr = extractJson(body, "port");
        ChildProcess child = supervisor.child(id).orElse(null);
        if (child == null) { send(ex, 404, "Not found"); return; }
        try {
            int port = Integer.parseInt(portStr);
            if (port < 1 || port > 65535) { send(ex, 400, "Port must be 1-65535"); return; }
            if (port == config.server().port()) { send(ex, 400, "Port is used by the web panel"); return; }
            for (ChildProcess c : supervisor.children()) {
                if (!c.id().equals(id) && c.config().port() == port) { send(ex, 400, "Port in use"); return; }
            }
            ChildConfig old = child.config();
            ChildConfig newCfg = new ChildConfig(old.id(), old.type(), old.dir(), jar, heap, old.xmsEqualXmx(),
                port, old.bind(), old.stopStrategy(), old.stopCommand(), old.stopTimeoutSeconds(),
                old.startOrder(), old.stopOrder(), old.restartOnCrash(), old.restartBackoffSeconds(),
                old.extraFlags(), old.consoleEnabled(), old.useAikarFlags(), old.silent());
            supervisor.updateChildConfig(id, newCfg);
            saveConfig(new RootConfig(config.server(),
                    supervisor.children().stream().map(ChildProcess::config).toList(), config.baseDir()));
            send(ex, 200, "Updated");
        } catch (Exception e) { send(ex, 400, "Error"); }
    }

    private Path safeResolve(ChildProcess child, String relPath) throws IOException {
        Path base = child.config().dir().toRealPath();
        Path target = base.resolve(relPath).normalize();
        if (!target.startsWith(base)) throw new IOException("Directory traversal detected");
        return target;
    }

    private void handleFileList(HttpExchange ex) throws IOException {
        if (!isAuthed(ex)) { send(ex, 401, "Unauthorized"); return; }
        String id = ex.getRequestURI().getPath().substring(ex.getRequestURI().getPath().lastIndexOf('/') + 1);
        String relPath = extractQuery(ex.getRequestURI().getRawQuery(), "path");
        if (relPath == null || relPath.isEmpty()) relPath = ".";
        ChildProcess child = supervisor.child(id).orElse(null);
        if (child == null || !canAccessServer(ex, id)) { send(ex, 404, "Not found"); return; }
        try {
            Path dir = safeResolve(child, relPath);
            if (!Files.isDirectory(dir)) { send(ex, 400, "Not a directory"); return; }
            List<String> files = new ArrayList<>();
            try (Stream<Path> stream = Files.list(dir)) {
                stream.sorted(Comparator.comparing(Path::getFileName)).forEach(p -> {
                    try {
                        String name = p.getFileName().toString(); boolean isDir = Files.isDirectory(p);
                        long size = isDir ? 0 : Files.size(p); long modified = Files.getLastModifiedTime(p).toMillis();
                        files.add(String.format("{\"name\":\"%s\",\"dir\":%b,\"size\":%d,\"modified\":%d}", name.replace("\"", "\\\""), isDir, size, modified));
                    } catch (Exception ignored) {}
                });
            }
            send(ex, 200, "[" + String.join(",", files) + "]");
        } catch (Exception e) { send(ex, 403, e.getMessage()); }
    }

    private void handleFileRead(HttpExchange ex) throws IOException {
        if (!isAuthed(ex)) { send(ex, 401, "Unauthorized"); return; }
        String id = ex.getRequestURI().getPath().substring(ex.getRequestURI().getPath().lastIndexOf('/') + 1);
        String relPath = extractQuery(ex.getRequestURI().getRawQuery(), "path");
        ChildProcess child = supervisor.child(id).orElse(null);
        if (child == null || relPath == null || !canAccessServer(ex, id)) { send(ex, 400, "Bad Request"); return; }
        try {
            Path file = safeResolve(child, relPath);
            if (!Files.isRegularFile(file) || Files.size(file) > 5 * 1024 * 1024) { send(ex, 400, "File too large"); return; }
            send(ex, 200, Files.readString(file));
        } catch (Exception e) { send(ex, 403, e.getMessage()); }
    }

    private void handleFileWrite(HttpExchange ex) throws IOException {
        if (!isAuthed(ex)) { send(ex, 401, "Unauthorized"); return; }
        String id = ex.getRequestURI().getPath().substring(ex.getRequestURI().getPath().lastIndexOf('/') + 1);
        String relPath = extractQuery(ex.getRequestURI().getRawQuery(), "path");
        String content = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        ChildProcess child = supervisor.child(id).orElse(null);
        if (child == null || relPath == null || !canAccessServer(ex, id)) { send(ex, 400, "Bad Request"); return; }
        try {
            Path file = safeResolve(child, relPath);
            Files.writeString(file, content);
            send(ex, 200, "Saved");
        } catch (Exception e) { send(ex, 403, e.getMessage()); }
    }

    private void handleFileDelete(HttpExchange ex) throws IOException {
        if (!isAuthed(ex)) { send(ex, 401, "Unauthorized"); return; }
        String id = ex.getRequestURI().getPath().substring(ex.getRequestURI().getPath().lastIndexOf('/') + 1);
        String relPath = extractQuery(ex.getRequestURI().getRawQuery(), "path");
        ChildProcess child = supervisor.child(id).orElse(null);
        if (child == null || relPath == null || !canAccessServer(ex, id)) { send(ex, 400, "Bad Request"); return; }
        try {
            Path file = safeResolve(child, relPath);
            Files.delete(file);
            send(ex, 200, "Deleted");
        } catch (Exception e) { send(ex, 403, e.getMessage()); }
    }

    private void handleFileMkdir(HttpExchange ex) throws IOException {
        if (!isAuthed(ex)) { send(ex, 401, "Unauthorized"); return; }
        String id = ex.getRequestURI().getPath().substring(ex.getRequestURI().getPath().lastIndexOf('/') + 1);
        String relPath = extractQuery(ex.getRequestURI().getRawQuery(), "path");
        ChildProcess child = supervisor.child(id).orElse(null);
        if (child == null || relPath == null || !canAccessServer(ex, id)) { send(ex, 400, "Bad Request"); return; }
        try {
            Path dir = safeResolve(child, relPath);
            Files.createDirectories(dir);
            send(ex, 200, "Created");
        } catch (Exception e) { send(ex, 403, e.getMessage()); }
    }

    private void handleFileRename(HttpExchange ex) throws IOException {
        if (!isAuthed(ex)) { send(ex, 401, "Unauthorized"); return; }
        String id = ex.getRequestURI().getPath().substring(ex.getRequestURI().getPath().lastIndexOf('/') + 1);
        String oldP = extractQuery(ex.getRequestURI().getRawQuery(), "old");
        String newP = extractQuery(ex.getRequestURI().getRawQuery(), "new");
        ChildProcess child = supervisor.child(id).orElse(null);
        if (child == null || oldP == null || newP == null || !canAccessServer(ex, id)) { send(ex, 400, "Bad Request"); return; }
        try {
            Path source = safeResolve(child, oldP);
            Path target = safeResolve(child, newP);
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            send(ex, 200, "Renamed");
        } catch (Exception e) { send(ex, 403, e.getMessage()); }
    }

    private void handleFileZip(HttpExchange ex) throws IOException {
        if (!isAuthed(ex)) { send(ex, 401, "Unauthorized"); return; }
        String id = ex.getRequestURI().getPath().substring(ex.getRequestURI().getPath().lastIndexOf('/') + 1);
        String relPath = extractQuery(ex.getRequestURI().getRawQuery(), "path");
        ChildProcess child = supervisor.child(id).orElse(null);
        if (child == null || relPath == null || !canAccessServer(ex, id)) { send(ex, 400, "Bad Request"); return; }
        try {
            Path dir = safeResolve(child, relPath);
            Path zipFile = dir.resolveSibling(dir.getFileName() + ".zip");
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile)); Stream<Path> walk = Files.walk(dir)) {
                walk.filter(p -> !Files.isDirectory(p)).forEach(p -> {
                    try {
                        zos.putNextEntry(new ZipEntry(dir.relativize(p).toString().replace("\\", "/")));
                        Files.copy(p, zos); zos.closeEntry();
                    } catch (Exception ignored) {}
                });
            }
            send(ex, 200, "Zipped");
        } catch (Exception e) { send(ex, 403, e.getMessage()); }
    }

    private void handleFileUpload(HttpExchange ex) throws IOException {
        if (!isAuthed(ex)) { send(ex, 401, "Unauthorized"); return; }
        String id = ex.getRequestURI().getPath().substring(ex.getRequestURI().getPath().lastIndexOf('/') + 1);
        String path = extractQuery(ex.getRequestURI().getRawQuery(), "path");
        String filename = extractQuery(ex.getRequestURI().getRawQuery(), "name");
        ChildProcess child = supervisor.child(id).orElse(null);
        if (child == null || path == null || filename == null || !canAccessServer(ex, id)) { send(ex, 400, "Bad Request"); return; }
        try {
            Path dir = safeResolve(child, path);
            Path target = dir.resolve(filename).normalize();
            if (!target.startsWith(dir)) { send(ex, 403, "Invalid filename"); return; }
            long limit = 200L * 1024 * 1024;
            try (InputStream is = ex.getRequestBody(); OutputStream os = Files.newOutputStream(target)) {
                byte[] buffer = new byte[8192]; int read; long total = 0;
                while ((read = is.read(buffer)) != -1) {
                    total += read; if (total > limit) { Files.deleteIfExists(target); send(ex, 413, "Too large"); return; }
                    os.write(buffer, 0, read);
                }
            }
            send(ex, 200, "Uploaded");
        } catch (Exception e) { send(ex, 500, e.getMessage()); }
    }

    private void handleInstallPaper(HttpExchange ex) throws IOException {
        if (!isAdmin(ex)) { send(ex, 403, "Admin only"); return; }
        String id = ex.getRequestURI().getPath().substring(ex.getRequestURI().getPath().lastIndexOf('/') + 1);
        String version = extractQuery(ex.getRequestURI().getRawQuery(), "version");
        ChildProcess child = supervisor.child(id).orElse(null);
        if (child == null || version == null || !version.matches("[0-9]+(\\.[0-9]+)*")) { send(ex, 404, "Not found"); return; }
        try {
            // fill v3 (the old api.papermc.io/v2 was sunset); latest build's downloads["server:default"].url
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                    "https://fill.papermc.io/v3/projects/paper/versions/" + version + "/builds/latest")).build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) { send(ex, 500, "Paper API failed"); return; }
            int downloadsAt = res.body().indexOf("\"server:default\"");
            if (downloadsAt < 0) { send(ex, 500, "No server download in Paper response"); return; }
            Matcher m = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"").matcher(res.body());
            if (!m.find(downloadsAt)) { send(ex, 500, "No download URL found"); return; }
            String url = m.group(1).replace("\\/", "/");

            // Save as the child's configured jar name so the next start actually uses it.
            Files.createDirectories(child.config().dir());
            Path jarPath = child.config().dir().resolve(child.config().jar());
            HttpRequest dlReq = HttpRequest.newBuilder(URI.create(url)).build();
            HttpResponse<Path> dlRes = httpClient.send(dlReq, HttpResponse.BodyHandlers.ofFile(jarPath));
            if (dlRes.statusCode() != 200 || Files.size(jarPath) < 1024 * 1024) { Files.deleteIfExists(jarPath); send(ex, 500, "Download failed"); return; }
            send(ex, 200, "Installed Paper " + version);
        } catch (Exception e) { send(ex, 500, e.getMessage()); }
    }

    private void handleModrinthSearch(HttpExchange ex) throws IOException {
        if (!isAuthed(ex)) { send(ex, 401, "Unauthorized"); return; }
        String kind = param(ex, "kind", "plugin").toLowerCase(Locale.ROOT);
        if (!kind.equals("plugin") && !kind.equals("mod")) { send(ex, 400, "kind must be plugin or mod"); return; }
        String query = param(ex, "query", "");
        String version = param(ex, "version", "");
        List<String> loaders = new ArrayList<>();
        String loaderParam = param(ex, "loader", "");
        if (!loaderParam.isEmpty()) {
            for (String raw : loaderParam.split(",")) {
                String loader = raw.trim().toLowerCase(Locale.ROOT);
                if (loader.isEmpty()) continue;
                if (!Addons.validLoader(loader)) { send(ex, 400, "Bad loader"); return; }
                if (!loaders.contains(loader) && loaders.size() < 8) loaders.add(loader);
            }
        }
        // With ?server= the loader set and MC version come from that server, so the UI can just pass the id.
        ChildProcess searchChild = supervisor.child(param(ex, "server", "")).orElse(null);
        if (searchChild != null) {
            if (version.isEmpty()) version = detectMcVersion(searchChild.config());
            if (loaders.isEmpty()) loaders.addAll(Addons.loaders(searchChild.config().type(), kind.equals("mod")));
        }
        if (!version.isEmpty() && !version.matches("[A-Za-z0-9._+-]{1,32}")) { send(ex, 400, "Bad version"); return; }
        // Modrinth caps limit at 100; the panel only needs a page of cards at a time.
        int limit = clampInt(param(ex, "limit", "10"), 10, 1, 30);
        int offset = clampInt(param(ex, "offset", "0"), 0, 0, 10_000);

        StringBuilder facets = new StringBuilder("[[\"project_type:").append(kind).append("\"]");
        if (!loaders.isEmpty()) {
            // OR group: a Paper server should still see Spigot/Bukkit plugins.
            facets.append(",[");
            for (int i = 0; i < loaders.size(); i++) {
                if (i > 0) facets.append(',');
                facets.append("\"categories:").append(loaders.get(i)).append('\"');
            }
            facets.append(']');
        }
        if (!version.isEmpty()) facets.append(",[\"versions:").append(version).append("\"]");
        facets.append(']');

        String url = "https://api.modrinth.com/v2/search?query=" + urlEncode(query)
                + "&facets=" + urlEncode(facets.toString())
                + "&limit=" + limit + "&offset=" + offset;
        try {
            send(ex, 200, modrinthGet(url, HTTP_CACHE_MS));
        } catch (Exception e) {
            send(ex, 502, "{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}");
        }
    }

    private void handleModrinthVersions(HttpExchange ex) throws IOException {
        if (!isAuthed(ex)) { send(ex, 401, "Unauthorized"); return; }
        String projectId = param(ex, "project", "");
        if (!projectId.matches("[A-Za-z0-9_-]{3,40}")) { send(ex, 400, "Bad project"); return; }
        String kind = param(ex, "kind", "plugin").toLowerCase(Locale.ROOT);
        if (!kind.equals("plugin") && !kind.equals("mod")) { send(ex, 400, "kind must be plugin or mod"); return; }
        boolean mods = kind.equals("mod");
        String version = param(ex, "version", "");
        ChildProcess child = supervisor.child(param(ex, "server", "")).orElse(null);
        if (version.isEmpty() && child != null) version = detectMcVersion(child.config());
        if (!version.isEmpty() && !version.matches("[A-Za-z0-9._+-]{1,32}")) { send(ex, 400, "Bad version"); return; }

        List<String> loaders = new ArrayList<>();
        String loaderParam = param(ex, "loader", "");
        if (!loaderParam.isEmpty()) {
            for (String raw : loaderParam.split(",")) {
                String loader = raw.trim().toLowerCase(Locale.ROOT);
                if (!loader.isEmpty() && Addons.validLoader(loader) && !loaders.contains(loader)) loaders.add(loader);
            }
        } else if (child != null) {
            loaders.addAll(Addons.loaders(child.config().type(), mods));
        }

        StringBuilder url = new StringBuilder("https://api.modrinth.com/v2/project/")
                .append(projectId).append("/version");
        List<String> params = new ArrayList<>();
        if (!loaders.isEmpty()) {
            StringBuilder arr = new StringBuilder("[");
            for (int i = 0; i < loaders.size(); i++) {
                if (i > 0) arr.append(',');
                arr.append('"').append(loaders.get(i)).append('"');
            }
            arr.append(']');
            params.add("loaders=" + urlEncode(arr.toString()));
        }
        if (!version.isEmpty()) params.add("game_versions=" + urlEncode("[\"" + version + "\"]"));
        if (!params.isEmpty()) url.append('?').append(String.join("&", params));
        try {
            // A project can have 1000+ versions; the picker never needs more than the newest page.
            send(ex, 200, trimArray(modrinthGet(url.toString(), HTTP_CACHE_MS), 60));
        } catch (Exception e) {
            send(ex, 502, "{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}");
        }
    }

    /** Keeps at most {@code max} objects of a JSON array, leaving non-arrays untouched. */
    private static String trimArray(String json, int max) {
        List<String> objects = objectsInArray(json, null);
        if (objects.isEmpty()) return json;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < Math.min(max, objects.size()); i++) {
            if (i > 0) sb.append(',');
            sb.append(objects.get(i));
        }
        return sb.append(']').toString();
    }

    private void handleModrinthInstall(HttpExchange ex) throws IOException {
        String serverId = param(ex, "server", "");
        String versionId = param(ex, "version_id", "");
        String kind = param(ex, "kind", "plugin").toLowerCase(Locale.ROOT);
        String forceRaw = param(ex, "force", "");
        boolean force = forceRaw.equals("1") || forceRaw.equalsIgnoreCase("true");
        boolean mods = kind.equals("mod");
        if (!mods && !kind.equals("plugin")) { send(ex, 400, "kind must be plugin or mod"); return; }
        ChildProcess child = supervisor.child(serverId).orElse(null);
        if (child == null) { send(ex, 404, "Not found"); return; }
        if (!requireOperator(ex, serverId)) return;
        if (!versionId.matches("[A-Za-z0-9_-]{1,40}")) { send(ex, 400, "Bad version"); return; }
        // Plugin-only servers can still be forced (Arclight-style hybrids, user knows best).
        if (mods && !Addons.supportsMods(child.config().type()) && !force) {
            send(ex, 409, "{\"code\":\"mods_unsupported\",\"type\":\"" + jsonEscape(child.config().type())
                    + "\",\"message\":\"" + jsonEscape(child.config().type() + " does not load mods") + "\"}");
            return;
        }
        Path dir = child.config().dir();
        Path folder = dir.resolve(Addons.folder(mods));
        try {
            String json = modrinthGet("https://api.modrinth.com/v2/version/" + versionId, HTTP_CACHE_MS);
            String[] file = primaryFile(json);
            if (file == null) { send(ex, 404, "That version has no jar file"); return; }
            String downloadUrl = file[0];
            String fileName = Addons.sanitizeJar(file[1]);
            if (fileName == null) { send(ex, 400, "That version's file is not a jar"); return; }
            long expected = 0;
            try { expected = Long.parseLong(file[2]); } catch (Exception ignored) {}

            boolean createdFolder = Files.notExists(folder);
            Files.createDirectories(folder);
            Path target = folder.resolve(fileName);
            boolean replaced = Files.exists(target);
            if (replaced) backupAddon(dir, folder, fileName);

            Path part = folder.resolve(fileName + ".part");
            try {
                HttpRequest dl = HttpRequest.newBuilder(URI.create(downloadUrl))
                        .header("User-Agent", "childserversjar-panel/0.2.0")
                        .timeout(Duration.ofMinutes(5))
                        .build();
                HttpResponse<Path> res = httpClient.send(dl, HttpResponse.BodyHandlers.ofFile(part));
                if (res.statusCode() != 200) { Files.deleteIfExists(part); send(ex, 502, "Download failed (HTTP " + res.statusCode() + ")"); return; }
                long size = Files.size(part);
                if (size <= 0 || (expected > 0 && size != expected)) {
                    Files.deleteIfExists(part);
                    send(ex, 502, "Download incomplete (" + size + " of " + expected + " bytes)");
                    return;
                }
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                Files.deleteIfExists(part);
                throw e;
            }
            send(ex, 200, "{\"file\":\"" + jsonEscape(fileName) + "\",\"kind\":\"" + (mods ? "mod" : "plugin")
                    + "\",\"size\":" + Files.size(target) + ",\"url\":\"" + jsonEscape(downloadUrl)
                    + "\",\"replaced\":" + replaced + ",\"created_folder\":" + createdFolder + "}");
        } catch (Exception e) {
            send(ex, 502, "{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}");
        }
    }

    /** First primary file of a Modrinth version: {url, filename, size}. */
    private static String[] primaryFile(String json) {
        List<String> files = objectsInArray(json, "files");
        String[] fallback = null;
        for (String object : files) {
            String url = extractJson(object, "url");
            String name = extractJson(object, "filename");
            if (url.isEmpty() || name.isEmpty()) continue;
            String[] candidate = {url.replace("\\/", "/"), name, extractJson(object, "size")};
            if ("true".equals(extractJson(object, "primary"))) return candidate;
            if (fallback == null) fallback = candidate;
        }
        return fallback;
    }

    /** Objects of a JSON array, with string/escape aware brace matching. key=null starts at the first '['. */
    private static List<String> objectsInArray(String json, String key) {
        int start;
        if (key == null) {
            start = json.indexOf('[');
        } else {
            int at = json.indexOf("\"" + key + "\"");
            if (at < 0) return List.of();
            start = json.indexOf('[', at);
        }
        if (start < 0) return List.of();
        List<String> objects = new ArrayList<>();
        int arrayDepth = 0, objDepth = 0, objStart = -1;
        boolean inString = false, escape = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == '[') arrayDepth++;
            else if (c == ']') { arrayDepth--; if (arrayDepth == 0) break; }
            else if (c == '{') { if (objDepth == 0) objStart = i; objDepth++; }
            else if (c == '}') {
                objDepth--;
                if (objDepth == 0 && objStart >= 0) { objects.add(json.substring(objStart, i + 1)); objStart = -1; }
            }
        }
        return objects;
    }

    /** Value object for a hash key in a Modrinth hash map, or null. */
    private static String objectForHash(String json, String hash) {
        int at = json.indexOf("\"" + hash + "\"");
        if (at < 0) return null;
        int colon = json.indexOf(':', at + hash.length() + 2);
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != '{') return null;
        int depth = 0;
        boolean inString = false, escape = false;
        for (int j = i; j < json.length(); j++) {
            char c = json.charAt(j);
            if (inString) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return json.substring(i, j + 1); }
        }
        return null;
    }

    private static List<Path> listJars(Path folder) {
        if (!Files.isDirectory(folder)) return List.of();
        try (var stream = Files.list(folder)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".jar") || n.endsWith(".jar.disabled");
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static long sizeOf(Path file) {
        try { return Files.size(file); } catch (Exception e) { return 0; }
    }

    private String hashFor(Path file) throws IOException {
        String key = file.toAbsolutePath() + "|" + sizeOf(file) + "|" + Files.getLastModifiedTime(file).toMillis();
        String cached = fileHashCache.get(key);
        if (cached != null) return cached;
        String hash = Addons.sha512(file);
        if (fileHashCache.size() > 2000) fileHashCache.clear();
        fileHashCache.put(key, hash);
        return hash;
    }

    /** Batch hash -> {"projectId",...} fragment lookup, cached 10 min per hash. */
    private Map<String, String> lookupHashes(List<String> hashes) {
        Map<String, String> out = new HashMap<>();
        List<String> missing = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (String hash : hashes) {
            if (hash == null || hash.isEmpty()) continue;
            Cached cached = hashCache.get(hash);
            if (cached != null && now - cached.at() < HASH_CACHE_MS) out.put(hash, cached.body());
            else if (!missing.contains(hash)) missing.add(hash);
        }
        if (missing.isEmpty()) return out;
        StringBuilder body = new StringBuilder("{\"hashes\":[");
        for (int i = 0; i < missing.size(); i++) { if (i > 0) body.append(','); body.append('"').append(missing.get(i)).append('"'); }
        body.append("],\"algorithm\":\"sha512\"}");
        String response = modrinthPost("https://api.modrinth.com/v2/version_files", body.toString());
        if (response == null) return out;
        Map<String, String> titles = projectTitles(response);
        for (String hash : missing) {
            String object = objectForHash(response, hash);
            String fragment = "";
            if (object != null) {
                String projectId = extractJson(object, "project_id");
                String versionId = extractJson(object, "id");
                String versionNumber = extractJson(object, "version_number");
                String title = titles.getOrDefault(projectId, projectId);
                fragment = "\"projectId\":\"" + jsonEscape(projectId)
                        + "\",\"projectTitle\":\"" + jsonEscape(title)
                        + "\",\"versionId\":\"" + jsonEscape(versionId)
                        + "\",\"versionNumber\":\"" + jsonEscape(versionNumber) + "\"";
            }
            if (hashCache.size() > 2000) hashCache.clear();
            hashCache.put(hash, new Cached(now, fragment));
            out.put(hash, fragment);
        }
        return out;
    }

    private Map<String, String> projectTitles(String response) {
        List<String> ids = new ArrayList<>();
        Matcher m = Pattern.compile("\"project_id\"\\s*:\\s*\"([A-Za-z0-9]+)\"").matcher(response);
        while (m.find() && ids.size() < 100) if (!ids.contains(m.group(1))) ids.add(m.group(1));
        Map<String, String> titles = new HashMap<>();
        if (ids.isEmpty()) return titles;
        try {
            String url = "https://api.modrinth.com/v2/projects?ids=" + urlEncode("[\"" + String.join("\",\"", ids) + "\"]");
            for (String project : objectsInArray(modrinthGet(url, HASH_CACHE_MS), null)) {
                String id = extractJson(project, "id");
                String title = extractJson(project, "title");
                if (!id.isEmpty() && !title.isEmpty()) titles.put(id, title);
            }
        } catch (Exception ignored) {}
        return titles;
    }

    private Map<String, String> lookupUpdates(List<String> hashes, String type, boolean mods, String mcVersion) {
        Map<String, String> out = new HashMap<>();
        List<String> usable = new ArrayList<>();
        for (String hash : hashes) if (hash != null && !hash.isEmpty() && !usable.contains(hash)) usable.add(hash);
        if (usable.isEmpty()) return out;
        List<String> loaders = Addons.loaders(type, mods);
        StringBuilder body = new StringBuilder("{\"hashes\":[");
        for (int i = 0; i < usable.size(); i++) { if (i > 0) body.append(','); body.append('"').append(usable.get(i)).append('"'); }
        body.append("],\"algorithm\":\"sha512\"");
        if (!loaders.isEmpty()) {
            body.append(",\"loaders\":[");
            for (int i = 0; i < loaders.size(); i++) { if (i > 0) body.append(','); body.append('"').append(loaders.get(i)).append('"'); }
            body.append(']');
        }
        if (mcVersion != null && !mcVersion.isEmpty()) body.append(",\"game_versions\":[\"").append(mcVersion).append("\"]");
        body.append('}');
        String response = modrinthPost("https://api.modrinth.com/v2/version_files/updates", body.toString());
        if (response == null) return out;
        for (String hash : usable) {
            String object = objectForHash(response, hash);
            if (object == null) continue;
            String versionId = extractJson(object, "id");
            if (versionId.isEmpty()) continue;
            out.put(hash, "\"updateVersionId\":\"" + jsonEscape(versionId)
                    + "\",\"updateVersionNumber\":\"" + jsonEscape(extractJson(object, "version_number")) + "\"");
        }
        return out;
    }

    private void handleModrinthUpdates(HttpExchange ex) throws IOException {
        String serverId = param(ex, "server", "");
        ChildProcess child = supervisor.child(serverId).orElse(null);
        if (child == null) { send(ex, 404, "Not found"); return; }
        if (!requireOperator(ex, serverId)) return;
        String mcVersion = detectMcVersion(child.config());
        StringBuilder out = new StringBuilder("{\"updates\":[");
        boolean first = true;
        for (boolean mods : new boolean[]{false, true}) {
            if (mods && !Addons.supportsMods(child.config().type())) continue;
            Path folder = child.config().dir().resolve(Addons.folder(mods));
            List<Path> jars = listJars(folder);
            if (jars.isEmpty()) continue;
            List<String> hashes = new ArrayList<>();
            for (Path jar : jars) { try { hashes.add(hashFor(jar)); } catch (Exception e) { hashes.add(""); } }
            Map<String, String> updates = lookupUpdates(hashes, child.config().type(), mods, mcVersion);
            for (int i = 0; i < jars.size(); i++) {
                String update = updates.get(hashes.get(i));
                if (update == null) continue;
                if (!first) out.append(',');
                first = false;
                out.append("{\"file\":\"").append(jsonEscape(jars.get(i).getFileName().toString()))
                   .append("\",\"kind\":\"").append(mods ? "mod" : "plugin").append("\",").append(update).append('}');
            }
        }
        out.append("]}");
        send(ex, 200, out.toString());
    }

    private void handleAddonsList(HttpExchange ex) throws IOException {
        String serverId = param(ex, "server", "");
        ChildProcess child = supervisor.child(serverId).orElse(null);
        if (child == null) { send(ex, 404, "Not found"); return; }
        if (!requireServer(ex, serverId)) return;
        String kind = param(ex, "kind", "plugin").toLowerCase(Locale.ROOT);
        if (!kind.equals("plugin") && !kind.equals("mod")) { send(ex, 400, "kind must be plugin or mod"); return; }
        boolean mods = kind.equals("mod");
        boolean wantUpdates = param(ex, "updates", "").equals("1");
        Path dir = child.config().dir();
        Path folder = dir.resolve(Addons.folder(mods));
        List<Path> jars = listJars(folder);
        List<String> hashes = new ArrayList<>();
        for (Path jar : jars) { try { hashes.add(hashFor(jar)); } catch (Exception e) { hashes.add(""); } }
        Map<String, String> known = lookupHashes(hashes);
        Map<String, String> newer = wantUpdates
                ? lookupUpdates(hashes, child.config().type(), mods, detectMcVersion(child.config()))
                : Map.of();
        boolean supported = mods ? Addons.supportsMods(child.config().type()) : Addons.supportsPlugins(child.config().type());

        List<String> loaders = Addons.loaders(child.config().type(), mods);
        StringBuilder sb = new StringBuilder("{\"kind\":\"").append(kind)
                .append("\",\"folder\":\"").append(Addons.folder(mods))
                .append("\",\"supported\":").append(supported)
                .append(",\"hasFolder\":").append(Files.isDirectory(folder))
                .append(",\"mcVersion\":\"").append(jsonEscape(detectMcVersion(child.config())))
                .append("\",\"loaders\":[");
        for (int i = 0; i < loaders.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(jsonEscape(loaders.get(i))).append('"');
        }
        sb.append("],\"items\":[");
        for (int i = 0; i < jars.size(); i++) {
            Path jar = jars.get(i);
            String name = jar.getFileName().toString();
            String hash = hashes.get(i);
            String meta = known.getOrDefault(hash, "");
            if (i > 0) sb.append(',');
            sb.append("{\"file\":\"").append(jsonEscape(name))
              .append("\",\"size\":").append(sizeOf(jar))
              .append(",\"enabled\":").append(!name.toLowerCase(Locale.ROOT).endsWith(".jar.disabled"))
              .append(",\"hash\":\"").append(hash).append('"');
            if (!meta.isEmpty()) sb.append(',').append(meta);
            String update = newer.get(hash);
            if (update == null) sb.append(",\"updateAvailable\":false");
            else sb.append(',').append(update).append(",\"updateAvailable\":true");
            sb.append('}');
        }
        sb.append("]}");
        send(ex, 200, sb.toString());
    }

    private void handleAddonsToggle(HttpExchange ex) throws IOException {
        String serverId = param(ex, "server", "");
        ChildProcess child = supervisor.child(serverId).orElse(null);
        if (child == null) { send(ex, 404, "Not found"); return; }
        if (!requireOperator(ex, serverId)) return;
        String kind = param(ex, "kind", "plugin").toLowerCase(Locale.ROOT);
        boolean mods = kind.equals("mod");
        if (!mods && !kind.equals("plugin")) { send(ex, 400, "Bad kind"); return; }
        Path folder = child.config().dir().resolve(Addons.folder(mods));
        Path target = jarIn(folder, param(ex, "file", ""));
        if (target == null || !Files.isRegularFile(target)) { send(ex, 404, "Not found"); return; }
        String name = target.getFileName().toString();
        boolean disabled = name.toLowerCase(Locale.ROOT).endsWith(".jar.disabled");
        Path renamed = target.resolveSibling(disabled
                ? name.substring(0, name.length() - ".disabled".length())
                : name + ".disabled");
        try {
            Files.move(target, renamed, StandardCopyOption.REPLACE_EXISTING);
            send(ex, 200, "{\"file\":\"" + jsonEscape(renamed.getFileName().toString())
                    + "\",\"enabled\":" + disabled + "}");
        } catch (Exception e) {
            send(ex, 500, "{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}");
        }
    }

    private void handleAddonsDelete(HttpExchange ex) throws IOException {
        String serverId = param(ex, "server", "");
        ChildProcess child = supervisor.child(serverId).orElse(null);
        if (child == null) { send(ex, 404, "Not found"); return; }
        if (!requireOperator(ex, serverId)) return;
        String kind = param(ex, "kind", "plugin").toLowerCase(Locale.ROOT);
        boolean mods = kind.equals("mod");
        if (!mods && !kind.equals("plugin")) { send(ex, 400, "Bad kind"); return; }
        Path folder = child.config().dir().resolve(Addons.folder(mods));
        Path target = jarIn(folder, param(ex, "file", ""));
        if (target == null || !Files.isRegularFile(target)) { send(ex, 404, "Not found"); return; }
        try {
            Files.delete(target);
            send(ex, 200, "{\"deleted\":\"" + jsonEscape(target.getFileName().toString()) + "\"}");
        } catch (Exception e) {
            send(ex, 500, "{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}");
        }
    }

    private void handleAddonsBackup(HttpExchange ex) throws IOException {
        String serverId = param(ex, "server", "");
        ChildProcess child = supervisor.child(serverId).orElse(null);
        if (child == null) { send(ex, 404, "Not found"); return; }
        if (!requireOperator(ex, serverId)) return;
        String kind = param(ex, "kind", "plugin").toLowerCase(Locale.ROOT);
        boolean mods = kind.equals("mod");
        if (!mods && !kind.equals("plugin")) { send(ex, 400, "Bad kind"); return; }
        Path folder = child.config().dir().resolve(Addons.folder(mods));
        Path backups = child.config().dir().resolve("backups");
        try {
            Files.createDirectories(backups);
            String name = "addons-" + Addons.folder(mods) + "-" + stamp() + ".zip";
            Path out = backups.resolve(name);
            int count = 0;
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(out))) {
                for (Path file : listJars(folder)) {
                    zos.putNextEntry(new ZipEntry(file.getFileName().toString()));
                    Files.copy(file, zos);
                    zos.closeEntry();
                    count++;
                }
            }
            send(ex, 200, "{\"name\":\"" + jsonEscape(name) + "\",\"size\":" + Files.size(out) + ",\"files\":" + count + "}");
        } catch (Exception e) {
            send(ex, 500, "{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}");
        }
    }

    /** Validates a bare addon file name inside its folder; null when the name is unsafe or not a jar. */
    private static Path jarIn(Path folder, String file) {
        if (file == null || file.isBlank() || file.contains("/") || file.contains("\\") || file.contains("..")) return null;
        String lower = file.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".jar") && !lower.endsWith(".jar.disabled")) return null;
        Path target = folder.resolve(file).normalize();
        return target.getParent() != null && target.getParent().equals(folder.normalize()) ? target : null;
    }

    private void backupAddon(Path serverDir, Path folder, String fileName) throws IOException {
        Path backups = serverDir.resolve("backups").resolve("addons");
        Files.createDirectories(backups);
        Files.copy(folder.resolve(fileName), backups.resolve(stamp() + "-" + fileName), StandardCopyOption.REPLACE_EXISTING);
        try (var stream = Files.list(backups)) {
            List<Path> old = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith("-" + fileName))
                    .sorted(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .toList();
            for (int i = 0; i < old.size() - 3; i++) Files.deleteIfExists(old.get(i));
        }
    }

    private void handleVersionCheck(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { send(ex, 405, "POST only"); return; }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String serverId = extractJson(body, "server");
        if (serverId.isEmpty()) serverId = param(ex, "server", "");
        ChildProcess child = supervisor.child(serverId).orElse(null);
        if (child == null) { send(ex, 404, "Not found"); return; }
        if (!requireOperator(ex, serverId)) return;
        String software = extractJson(body, "software");
        if (software.isEmpty()) software = child.config().type();
        String target = extractJson(body, "version");
        if (target.isEmpty()) target = detectMcVersion(child.config());
        if (!target.matches("[A-Za-z0-9._+-]{1,32}")) { send(ex, 400, "Bad version"); return; }
        boolean mods = Addons.supportsMods(software);
        String cacheKey = serverId + "|" + software + "|" + target + "|" + mods;
        long now = System.currentTimeMillis();
        Cached cached = checkCache.get(cacheKey);
        if (cached != null && now - cached.at() < CHECK_CACHE_MS) { send(ex, 200, cached.body()); return; }

        List<Path> jars = listJars(child.config().dir().resolve(Addons.folder(mods)));
        List<String> hashes = new ArrayList<>();
        for (Path jar : jars) { try { hashes.add(hashFor(jar)); } catch (Exception e) { hashes.add(""); } }
        Map<String, String> known = lookupHashes(hashes);
        List<String> loaders = Addons.loaders(software, mods);
        List<String> compatible = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        List<String> incompatible = new ArrayList<>();
        long deadline = now + 20_000;
        int inspected = 0;

        for (int i = 0; i < jars.size() && inspected < 50; i++) {
            inspected++;
            Path jar = jars.get(i);
            String file = jar.getFileName().toString();
            Addons.JarMeta meta = Addons.metadata(jar);
            if (meta != null && meta.mcRange() != null && !meta.mcRange().isBlank()) {
                if (Addons.versionMatches(meta.mcRange(), target)) {
                    compatible.add(file);
                } else {
                    String title = meta.name() == null || meta.name().isBlank() ? file : meta.name();
                    incompatible.add("{\"file\":\"" + jsonEscape(file) + "\",\"title\":\"" + jsonEscape(title)
                            + "\",\"reason\":\"requires " + jsonEscape(meta.mcRange()) + "\"}");
                }
                continue;
            }
            String info = known.getOrDefault(hashes.get(i), "");
            String projectId = extractJson(info, "projectId");
            if (projectId.isEmpty() || System.currentTimeMillis() > deadline) { unknown.add(file); continue; }
            Boolean supported = projectSupports(projectId, target, loaders);
            String title = extractJson(info, "projectTitle");
            if (supported == null) { unknown.add(file); continue; }
            if (supported) { compatible.add(file); continue; }
            incompatible.add("{\"file\":\"" + jsonEscape(file) + "\",\"title\":\"" + jsonEscape(title.isEmpty() ? file : title)
                    + "\",\"reason\":\"no build for " + jsonEscape(target) + "\"}");
        }

        StringBuilder sb = new StringBuilder("{\"software\":\"").append(jsonEscape(software))
                .append("\",\"version\":\"").append(jsonEscape(target))
                .append("\",\"checked\":").append(inspected).append(",\"compatible\":[");
        for (int i = 0; i < compatible.size(); i++) { if (i > 0) sb.append(','); sb.append('"').append(jsonEscape(compatible.get(i))).append('"'); }
        sb.append("],\"incompatible\":[").append(String.join(",", incompatible)).append("],\"unknown\":[");
        for (int i = 0; i < unknown.size(); i++) { if (i > 0) sb.append(','); sb.append('"').append(jsonEscape(unknown.get(i))).append('"'); }
        sb.append("]}");
        String result = sb.toString();
        if (checkCache.size() > 200) checkCache.clear();
        checkCache.put(cacheKey, new Cached(now, result));
        send(ex, 200, result);
    }

    /** null = could not tell (offline / API error), so the caller reports "unknown" rather than "broken". */
    private Boolean projectSupports(String projectId, String target, List<String> loaders) {
        StringBuilder url = new StringBuilder("https://api.modrinth.com/v2/project/").append(projectId)
                .append("/version?game_versions=").append(urlEncode("[\"" + target + "\"]"));
        if (!loaders.isEmpty()) {
            StringBuilder arr = new StringBuilder("[");
            for (int i = 0; i < loaders.size(); i++) { if (i > 0) arr.append(','); arr.append('"').append(loaders.get(i)).append('"'); }
            arr.append(']');
            url.append("&loaders=").append(urlEncode(arr.toString()));
        }
        try {
            return !objectsInArray(modrinthGet(url.toString(), HTTP_CACHE_MS), null).isEmpty();
        } catch (Exception e) {
            return null;
        }
    }

    private void handleConfigRead(HttpExchange ex) throws IOException {
        if (!isAuthed(ex)) { send(ex, 401, "Unauthorized"); return; }
        String id = extractQuery(ex.getRequestURI().getRawQuery(), "server");
        String fileName = extractQuery(ex.getRequestURI().getRawQuery(), "file");
        ChildProcess child = supervisor.child(id).orElse(null);
        if (child == null || fileName == null || !canAccessServer(ex, id)) { send(ex, 400, "Bad Request"); return; }
        try {
            Path file = safeResolve(child, fileName);
            if (!Files.exists(file)) { send(ex, 200, "{}"); return; }
            Properties props = new Properties();
            try (InputStream is = Files.newInputStream(file)) { props.load(is); }
            StringBuilder json = new StringBuilder("{");
            boolean first = true;
            for (String key : props.stringPropertyNames()) {
                if (!first) json.append(","); first = false;
                json.append("\"").append(key).append("\":\"").append(props.getProperty(key).replace("\"", "\\\"")).append("\"");
            }
            json.append("}");
            send(ex, 200, json.toString());
        } catch (Exception e) { send(ex, 500, e.getMessage()); }
    }

    private void handleConfigWrite(HttpExchange ex) throws IOException {
        if (!isAuthed(ex)) { send(ex, 401, "Unauthorized"); return; }
        String id = extractQuery(ex.getRequestURI().getRawQuery(), "server");
        String fileName = extractQuery(ex.getRequestURI().getRawQuery(), "file");
        String body = new String(ex.getRequestBody().readAllBytes());
        ChildProcess child = supervisor.child(id).orElse(null);
        if (child == null || fileName == null || !canAccessServer(ex, id)) { send(ex, 400, "Bad Request"); return; }
        try {
            Path file = safeResolve(child, fileName);
            Properties props = new Properties();
            Matcher m = Pattern.compile("\"([^\"]+)\":\"([^\"]*)\"").matcher(body);
            while (m.find()) props.setProperty(m.group(1), m.group(2));
            try (OutputStream os = Files.newOutputStream(file)) { props.store(os, "Edited by Web Panel"); }
            send(ex, 200, "Saved");
        } catch (Exception e) { send(ex, 500, e.getMessage()); }
    }

    // --- Enhanced config editor handlers ---

    private void handleConfigFilesList(HttpExchange ex) throws IOException {
        if (!isAdmin(ex)) { send(ex, 403, "Admin only"); return; }
        String serverId = extractQuery(ex.getRequestURI().getRawQuery(), "server");
        String path = extractQuery(ex.getRequestURI().getRawQuery(), "path");

        ChildProcess child = supervisor.child(serverId).orElse(null);
        if (child == null) { send(ex, 404, "Not found"); return; }

        Path dir = path != null && !path.isEmpty() ? safeResolve(child, path) : child.config().dir();

        try (Stream<Path> entries = Files.list(dir)) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Path entry : entries.toList()) {
                if (!first) sb.append(",");
                first = false;
                String name = entry.getFileName().toString();
                boolean isDir = Files.isDirectory(entry);
                String type = isDir ? "dir" : (name.endsWith(".yml") || name.endsWith(".yaml") ? "yaml"
                    : name.endsWith(".json") ? "json"
                    : name.endsWith(".properties") ? "properties"
                    : "file");
                sb.append("{\"name\":\"").append(jsonEscape(name))
                    .append("\",\"type\":\"").append(type).append("\"")
                    .append(",\"dir\":").append(isDir)
                    .append(",\"size\":").append(isDir ? 0 : entry.toFile().length()).append("}");
            }
            sb.append("]");
            send(ex, 200, sb.toString());
        } catch (IOException e) { send(ex, 500, e.getMessage()); }
    }

    private void handleConfigSchema(HttpExchange ex) throws IOException {
        if (!isAdmin(ex)) { send(ex, 403, "Admin only"); return; }
        String serverId = extractQuery(ex.getRequestURI().getRawQuery(), "server");
        String file = extractQuery(ex.getRequestURI().getRawQuery(), "file");

        if (serverId == null || file == null) { send(ex, 400, "server and file parameters required"); return; }

        if (file.equals("server.properties")) {
            send(ex, 200, "{\"fields\":[{\"key\":\"server-port\",\"type\":\"number\",\"description\":\"The port of the server\"},"
                + "{\"key\":\"gamemode\",\"type\":\"string\",\"description\":\"Default game mode\"},"
                + "{\"key\":\"difficulty\",\"type\":\"string\",\"description\":\"Default difficulty\"}]}");
        } else {
            send(ex, 200, "{\"fields\":[],\"format\":\"generic\"}");
        }
    }

    /** Config files that make sense for this server's software (Paper -> paper/bukkit/spigot, Purpur -> +purpur.yml, ...). */
    private void handleConfigEditorFiles(HttpExchange ex) throws IOException {
        if (!isAuthed(ex)) { send(ex, 401, "Unauthorized"); return; }
        String serverId = extractQuery(ex.getRequestURI().getRawQuery(), "server");
        ChildProcess child = supervisor.child(serverId).orElse(null);
        if (child == null) { send(ex, 404, "Not found"); return; }
        if (!canAccessServer(ex, serverId)) { send(ex, 403, "No permission"); return; }

        List<ConfigEditor.FileInfo> files = ConfigEditor.listFiles(child.config());
        StringBuilder sb = new StringBuilder("{\"server\":\"").append(jsonEscape(serverId))
            .append("\",\"type\":\"").append(jsonEscape(child.config().type()))
            .append("\",\"files\":[");
        for (int i = 0; i < files.size(); i++) {
            ConfigEditor.FileInfo f = files.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"path\":\"").append(jsonEscape(f.path()))
                .append("\",\"scope\":\"").append(jsonEscape(f.scope()))
                .append("\",\"format\":\"").append(f.format())
                .append("\",\"exists\":").append(f.exists()).append("}");
        }
        send(ex, 200, sb.append("]}").toString());
    }

    /** Parsed view of one config file: scalar entries with dot paths plus the raw text for complex files. */
    private void handleConfigEditorRead(HttpExchange ex) throws IOException {
        if (!isAuthed(ex)) { send(ex, 401, "Unauthorized"); return; }
        String serverId = extractQuery(ex.getRequestURI().getRawQuery(), "server");
        String fileName = extractQuery(ex.getRequestURI().getRawQuery(), "file");
        ChildProcess child = supervisor.child(serverId).orElse(null);
        if (child == null) { send(ex, 404, "Not found"); return; }
        if (!canAccessServer(ex, serverId)) { send(ex, 403, "No permission"); return; }
        if (fileName == null || fileName.isEmpty() || !ConfigEditor.isEditable(fileName)) {
            send(ex, 400, "Not an editable config file"); return;
        }
        try {
            safeResolve(child, fileName); // traversal check
            ConfigEditor.ReadResult r = ConfigEditor.read(child.config().dir(), fileName);
            StringBuilder sb = new StringBuilder("{\"file\":\"").append(jsonEscape(fileName))
                .append("\",\"format\":\"").append(r.format())
                .append("\",\"exists\":").append(r.exists())
                .append(",\"entries\":[");
            for (int i = 0; i < r.entries().size(); i++) {
                ConfigEditor.Entry e = r.entries().get(i);
                if (i > 0) sb.append(",");
                sb.append("{\"path\":\"").append(jsonEscape(e.path()))
                    .append("\",\"section\":\"").append(jsonEscape(e.section()))
                    .append("\",\"key\":\"").append(jsonEscape(e.key()))
                    .append("\",\"type\":\"").append(jsonEscape(e.type()))
                    .append("\",\"value\":\"").append(jsonEscape(e.value())).append("\"}");
            }
            sb.append("],\"raw\":\"").append(jsonEscape(r.raw())).append("\"}");
            send(ex, 200, sb.toString());
        } catch (IllegalArgumentException e) {
            send(ex, 400, e.getMessage());
        } catch (Exception e) {
            send(ex, 500, e.getMessage());
        }
    }

    /**
     * Saves visual-editor changes. Body is newline-separated URL-encoded {@code path=value} pairs, or the raw
     * file text when {@code raw=1} is set. Values are patched in place so YAML comments/formatting survive.
     */
    private void handleConfigEditorSave(HttpExchange ex) throws IOException {
        if (!isAuthed(ex)) { send(ex, 401, "Unauthorized"); return; }
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { send(ex, 405, "POST only"); return; }
        String serverId = extractQuery(ex.getRequestURI().getRawQuery(), "server");
        String fileName = extractQuery(ex.getRequestURI().getRawQuery(), "file");
        String raw = extractQuery(ex.getRequestURI().getRawQuery(), "raw");
        ChildProcess child = supervisor.child(serverId).orElse(null);
        if (child == null) { send(ex, 404, "Not found"); return; }
        if (!canAccessServer(ex, serverId)) { send(ex, 403, "No permission"); return; }
        if (fileName == null || fileName.isEmpty() || !ConfigEditor.isEditable(fileName)) {
            send(ex, 400, "Not an editable config file"); return;
        }

        Path target;
        try { target = safeResolve(child, fileName); }
        catch (IOException e) { send(ex, 400, "Invalid path"); return; }
        if (!Files.isRegularFile(target)) { send(ex, 400, "Config file does not exist yet"); return; }

        byte[] bytes = ex.getRequestBody().readAllBytes();
        if (bytes.length > 1_048_576) { send(ex, 413, "Config file is too large to edit"); return; }
        String body = new String(bytes, StandardCharsets.UTF_8);

        try {
            if (raw != null) {
                Files.writeString(target, body, StandardCharsets.UTF_8);
                send(ex, 200, "{\"saved\":true,\"mode\":\"raw\"}");
                return;
            }
            Map<String, String> updates = new LinkedHashMap<>();
            for (String line : body.split("\n")) {
                if (line.isEmpty()) continue;
                int eq = line.indexOf('=');
                if (eq < 0) { send(ex, 400, "Malformed update"); return; }
                String path = java.net.URLDecoder.decode(line.substring(0, eq), StandardCharsets.UTF_8);
                String value = java.net.URLDecoder.decode(line.substring(eq + 1), StandardCharsets.UTF_8);
                updates.put(path, value);
            }
            if (updates.isEmpty()) { send(ex, 200, "{\"saved\":true,\"updated\":0}"); return; }
            int saved = ConfigEditor.patch(target, ConfigEditor.formatOf(fileName), updates);
            send(ex, 200, "{\"saved\":true,\"updated\":" + saved + "}");
        } catch (IllegalArgumentException e) {
            send(ex, 400, e.getMessage());
        } catch (Exception e) {
            send(ex, 500, e.getMessage());
        }
    }

    private void handleUsersList(HttpExchange ex) throws IOException {
        if (!isAdmin(ex)) { send(ex, 403, "Admin only"); return; }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (ServerConfig.UserConfig u : config.server().users()) {
            if (!first) sb.append(","); first = false;
            sb.append("{\"username\":\"").append(u.username()).append("\",\"role\":\"").append(u.role()).append("\",\"servers\":\"").append(String.join(",", u.allowedServers())).append("\"}");
        }
        sb.append("]");
        send(ex, 200, sb.toString());
    }

    private void handleUsersCreate(HttpExchange ex) throws IOException {
        if (!isAdmin(ex)) { send(ex, 403, "Admin only"); return; }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String user = extractJson(body, "user"); String pass = extractJson(body, "pass");
        if (!user.matches("[A-Za-z0-9_-]{3,32}")) { send(ex, 400, "Username must be 3-32 letters/digits/-/_"); return; }
        if (user.equals("admin") || config.server().users().stream().anyMatch(u -> u.username().equals(user))) {
            send(ex, 400, "User already exists"); return;
        }
        if (pass.length() < 8) { send(ex, 400, "Password must be at least 8 characters"); return; }
        String hash = rootjar.config.PasswordHasher.hash(pass.toCharArray());

        List<String> allowed = new ArrayList<>();
        List<ServerConfig.UserConfig> newUsers = new ArrayList<>(config.server().users());
        newUsers.add(new ServerConfig.UserConfig(user, hash, "user", allowed));

        ServerConfig old = config.server();
        ServerConfig updated = new ServerConfig(old.bind(), old.publicIp(), old.port(), old.sessionTtlSeconds(), old.loginRateLimitPerIpPerMinute(), old.loginLockoutAfterFailures(), old.loginLockoutSeconds(), old.passwordHash(), old.trustedProxies(), old.diskMaxGb(), newUsers);
        saveConfig(new RootConfig(updated, config.children(), config.baseDir()));
        send(ex, 200, "Created");
    }

    private void handleUsersAssign(HttpExchange ex) throws IOException {
        if (!isAdmin(ex)) { send(ex, 403, "Admin only"); return; }
        String body = new String(ex.getRequestBody().readAllBytes());
        String targetUser = extractJson(body, "user");
        String targetServer = extractJson(body, "server");
        String action = extractJson(body, "action");

        List<ServerConfig.UserConfig> currentUsers = config.server().users();
        List<ServerConfig.UserConfig> newUsers = new ArrayList<>();

        for (ServerConfig.UserConfig u : currentUsers) {
            if (u.username().equals(targetUser)) {
                List<String> newAllowed = new ArrayList<>(u.allowedServers());
                if (action.equals("add") && !newAllowed.contains(targetServer)) {
                    newAllowed.add(targetServer);
                } else if (action.equals("remove")) {
                    newAllowed.remove(targetServer);
                }
                newUsers.add(new ServerConfig.UserConfig(u.username(), u.passwordHash(), u.role(), newAllowed));
            } else {
                newUsers.add(u);
            }
        }

        ServerConfig old = config.server();
        ServerConfig updated = new ServerConfig(old.bind(), old.publicIp(), old.port(), old.sessionTtlSeconds(), old.loginRateLimitPerIpPerMinute(), old.loginLockoutAfterFailures(), old.loginLockoutSeconds(), old.passwordHash(), old.trustedProxies(), old.diskMaxGb(), newUsers);
        saveConfig(new RootConfig(updated, config.children(), config.baseDir()));
        send(ex, 200, "Assigned");
    }

    /** Rewrites config.yml from the live supervisor state (used by panel changes and console mute/unmute). */
    public void persistConfig() throws IOException {
        saveConfig(new RootConfig(config.server(), supervisor.children().stream().map(ChildProcess::config).toList(), config.baseDir()));
    }

    /** Persists the config and swaps the in-memory copy so panel edits (users, children) apply without a restart. */
    private void saveConfig(RootConfig newConfig) throws IOException {
        ConfigSaver.save(configPath, newConfig);
        this.config = newConfig;
    }

    private void send(HttpExchange ex, int code, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
    
    /** Minimal JSON value reader for the panel's flat {"k":"v"} bodies; quoted values may contain commas and escaped quotes. */
    static String extractJson(String json, String key) {
        if (json == null) return "";
        int k = json.indexOf("\"" + key + "\"");
        if (k < 0) return "";
        int colon = json.indexOf(':', k + key.length() + 2);
        if (colon < 0) return "";
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length()) return "";
        if (json.charAt(i) == '"') {
            StringBuilder sb = new StringBuilder();
            i++;
            while (i < json.length()) {
                char c = json.charAt(i++);
                if (c == '\\' && i < json.length()) {
                    char n = json.charAt(i++);
                    sb.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
        int end = i;
        while (end < json.length() && ",}] \n\r\t".indexOf(json.charAt(end)) < 0) end++;
        return json.substring(i, end).trim();
    }
    private String extractQuery(String query, String key) {
        if (query == null) return null;
        for (String param : query.split("&")) {
            String[] pair = param.split("=");
            if (pair.length > 1 && pair[0].equals(key)) {
                try { return java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8); } catch (Exception e) { return pair[1]; }
            }
        }
        return null;
    }
    private long parseRam(String heap) {
        heap = heap.toUpperCase(); long v = Long.parseLong(heap.replaceAll("[^0-9]", ""));
        return heap.endsWith("G") ? v * 1024 : v;
    }
}