package rootjar;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import rootjar.addons.Addons;
import rootjar.config.ChildConfig;
import rootjar.config.ConfigEditor;
import rootjar.config.ConfigSaver;
import rootjar.config.ConfigValidator;
import rootjar.config.ConfigException;
import rootjar.config.JavaRuntimes;
import rootjar.config.RootConfig;
import rootjar.config.ServerConfig;
import rootjar.config.SoftwareRegistry;
import rootjar.config.StopStrategy;
import rootjar.backup.BackupManager;
import rootjar.console.ConsoleOutput;
import rootjar.files.FileOps;
import rootjar.notify.Notifier;
import rootjar.security.ActivityLog;
import rootjar.supervisor.ChildProcess;
import rootjar.tasks.TaskScheduler;
import rootjar.supervisor.ChildState;
import rootjar.supervisor.Supervisor;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class WebServer {
    private record Session(String token, String user, long createdAt, String csrf, String ip, String userAgent, long lastSeen) {}

    private final HttpServer server;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    // Login hardening state, all keyed by client IP.
    private final Map<String, Deque<Long>> loginAttempts = new ConcurrentHashMap<>();
    private final Map<String, Integer> loginFailures = new ConcurrentHashMap<>();
    private final Map<String, Long> loginLockedUntil = new ConcurrentHashMap<>();
    private final Object netStatsLock = new Object();

    // Updated whenever the config is edited through the panel so user/password changes apply without a restart.
    private volatile RootConfig config;
    private final Notifier notifier = new Notifier(() -> config.server());
    private final TaskScheduler tasks;
    private final java.util.Set<String> configRestartRequired = ConcurrentHashMap.newKeySet();
    private final Map<String, java.util.ArrayDeque<long[]>> metrics = new ConcurrentHashMap<>();
    private final Supervisor supervisor;
    private final Path configPath;

    /** Optional previous/next panel links shown as fixed buttons in the UI. */
    private record NodeButtons(String previousUrl, String nextUrl, boolean showPrevious, boolean showNext) {}
    private record PanelOptions(List<Integer> ports, long diskLimitBytes, long backupLimitBytes, boolean setupComplete) {}
    private volatile NodeButtons nodeButtons;
    private volatile PanelOptions panelOptions;
    private final Path nodeButtonsPath;
    
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
    private static final long UPDATE_CACHE_MS = 30 * 60_000;
    private record Cached(long at, String body) {}
    private final Map<String, Cached> httpCache = new ConcurrentHashMap<>();
    private final Map<String, Cached> hashCache = new ConcurrentHashMap<>();
    private final Map<String, Cached> checkCache = new ConcurrentHashMap<>();
    private final Map<String, String> fileHashCache = new ConcurrentHashMap<>();

    // ---- Phase 3: version changes, software catalog, per-server Java ----
    private final SoftwareRegistry registry;
    private final JavaRuntimes javaRuntimes;
    /** Servers with a version change in flight; a second request answers 409 instead of racing on files. */
    private final Set<String> versionLocks = ConcurrentHashMap.newKeySet();
    private final Map<String, Cached> updateCache = new ConcurrentHashMap<>();
    /** Server jars are ~50 MB; anything bigger is a bad redirect/error page, not a jar. */
    private static final long MAX_JAR_BYTES = 200L * 1024L * 1024L;
    private static final long MAX_INSTALLER_BYTES = 64L * 1024L * 1024L;
    /** Phase 3 request bodies are tiny JSON objects; refuse bigger bodies instead of buffering them. */
    private static final int MAX_SMALL_BODY = 8 * 1024;
    /** Never read a whole latest.log: it can grow to hundreds of megabytes. */
    private static final int LOG_HEAD_LINES = 200;
    private static final long LOG_HEAD_BYTES = 256L * 1024L;
    private static final long STOP_DEAD_WAIT_MS = 15_000;
    /** Server types that have no Minecraft version of their own (a proxy version is not an MC version). */
    private static final Set<String> PROXY_TYPES =
            Set.of("velocity", "waterfall", "bungeecord", "travertine", "nanolimbo");

    public WebServer(RootConfig config, Supervisor supervisor, Path configPath) throws IOException {
        this.config = config;
        this.tasks = new TaskScheduler(supervisor, config.baseDir().resolve("tasks.yml"), () -> config.server().backupRetention(), config.baseDir());
        this.supervisor = supervisor;
        this.configPath = configPath;
        this.nodeButtonsPath = config.baseDir().resolve("node-buttons.properties");
        this.nodeButtons = loadNodeButtons(this.nodeButtonsPath);
        this.panelOptions = loadPanelOptions(config.baseDir().resolve("panel-options.properties"));
        // httpClient is a field initializer, so both dependencies exist here.
        this.registry = new SoftwareRegistry(httpClient);
        this.javaRuntimes = new JavaRuntimes(config.baseDir(), httpClient);

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
        route("/api/server/edit", this::handleEditServer);
        route("/api/server/reorder", this::handleReorderServers);

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

        // Phase 3: Change Version + per-server Java
        route("/api/software", this::handleSoftwareCatalog);
        route("/api/version/info", this::handleVersionInfo);
        route("/api/version/available", this::handleVersionAvailable);
        route("/api/version/change", this::handleVersionChange);
        route("/api/update/check", this::handleUpdateCheck);
        route("/api/java/runtimes", this::handleJavaRuntimes);
        route("/api/java/download", this::handleJavaDownload);
        route("/api/java/", this::handleJavaDelete);

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
        route("/api/files/download/", this::handleFileDownload);
        route("/api/files/download-zip/", this::handleFileDownloadZip);
        route("/api/files/search/", this::handleFileSearch);
        route("/api/files/stat/", this::handleFileStat);
        route("/api/files/move/", this::handleFileMove);
        route("/api/files/copy/", this::handleFileCopy);
        route("/api/files/extract/", this::handleFileExtract);
        route("/api/files/trash/", this::handleFileTrash);
        route("/api/files/restore/", this::handleFileRestore);
        route("/api/files/empty-trash/", this::handleFileEmptyTrash);
        route("/api/files/import/", this::handleFileImport);
        route("/api/backups/list", this::handleBackupList);
        route("/api/backups/create", this::handleBackupCreate);
        route("/api/backups/download", this::handleBackupDownload);
        route("/api/backups/restore", this::handleBackupRestore);
        route("/api/backups/delete", this::handleBackupDelete);
        route("/api/backups/upload", this::handleBackupUpload);
        route("/api/settings", this::handleSettings);
        route("/api/tasks", this::handleTasks);
        route("/api/sessions", this::handleSessions);
        route("/api/activity", this::handleActivity);
        route("/api/players", this::handlePlayers);
        route("/api/metrics", this::handleMetrics);
        route("/api/files/grep", this::handleFileGrep);
        server.createContext("/status", this::handlePublicStatus);

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

    private boolean userAllows(HttpExchange ex, boolean panel) {
        if (isAdmin(ex)) return true;
        String user = getSessionUser(ex);
        return config.server().users().stream().filter(u -> u.username().equals(user)).findFirst()
                .map(u -> panel ? u.allowPanelIpView() : u.allowConsoleIpView()).orElse(false);
    }
    private boolean canViewPanelIps(HttpExchange ex) { return userAllows(ex, true); }
    private boolean canViewConsoleIps(HttpExchange ex) { return userAllows(ex, false); }
    private static final java.util.regex.Pattern IP_ADDRESS = java.util.regex.Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b|(?:[A-Fa-f0-9]{0,4}:){2,7}[A-Fa-f0-9]{0,4}");
    private String filterConsoleIps(HttpExchange ex, String line) { return canViewConsoleIps(ex) ? line : IP_ADDRESS.matcher(line).replaceAll("[hidden ip]"); }

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
        return config.server().users().stream().filter(u -> u.username().equals(user)).findFirst().map(u -> {
            boolean allowed = u.allowedServers().contains("*") || u.allowedServers().contains(serverId);
            if (!allowed) return null;
            String configured = u.serverRoles().get(serverId);
            if (configured == null && u.allowedServers().contains("*")) configured = u.serverRoles().get("*");
            return switch (configured == null ? "operator" : configured) {
                case "viewer" -> Role.VIEWER;
                case "owner" -> Role.OWNER;
                default -> Role.OPERATOR;
            };
        }).orElse(null);
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

    /**
     * Interim "owner" check for version changes and per-server Java: ADMIN or OPERATOR (any user the
     * server is assigned to). Structured body because every new Phase 3 endpoint reports errors this way.
     */
    private boolean requireOwner(HttpExchange ex, String serverId) throws IOException {
        // TODO(Phase 7: server_roles)
        Role role = roleFor(ex, serverId);
        if (role != Role.ADMIN && role != Role.OWNER) {
            sendError(ex, 403, "resolve", "No permission");
            return false;
        }
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
                .header("User-Agent", panelUserAgent())
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
                    .header("User-Agent", panelUserAgent())
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
                for (String line : readHeadLines(log, LOG_HEAD_LINES, LOG_HEAD_BYTES)) {
                    Matcher m = Pattern.compile("Starting minecraft server version\\s+(\\S+)").matcher(line);
                    if (m.find()) return m.group(1);
                    m = Pattern.compile("Loading Minecraft\\s+(\\S+)").matcher(line);
                    if (m.find()) return m.group(1);
                    m = Pattern.compile("\\(MC:\\s*([^)]+)\\)").matcher(line);
                    if (m.find()) return m.group(1).trim();
                }
            }
        } catch (Exception ignored) {}
        // A proxy version (4.3.0) is not a Minecraft version, so never fall back to the jar name for one.
        if (isProxyType(cfg.type())) return "";
        Matcher m = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)").matcher(cfg.jar() == null ? "" : cfg.jar());
        return m.find() ? m.group(1) : "";
    }

    private static final java.text.SimpleDateFormat STAMP = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss");
    private static synchronized String stamp() { return STAMP.format(new java.util.Date()); }

    private void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (!path.equals("/") && !path.equals("/index.html") && !path.startsWith("/server/")
                && !path.startsWith("/manage/") && !path.equals("/settings")) { send(ex, 404, "Not Found"); return; }

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
            sessions.put(token, new Session(token, user, now, UUID.randomUUID().toString(), clientIp(ex), ex.getRequestHeaders().getFirst("User-Agent"), now));
            ex.getResponseHeaders().add("Set-Cookie", "session=" + token + "; HttpOnly; SameSite=Strict; Path=/");
            audit(user, "login", "signed in from " + ip);
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
        String user = getSessionUser(ex);
        String token = sessionToken(ex);
        if (token != null) sessions.remove(token);
        ex.getResponseHeaders().add("Set-Cookie", "session=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0");
        if (user != null) audit(user, "logout", "signed out");
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

        int cpuMax = detectCpuMax();

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
            java.util.ArrayDeque<long[]> samples = metrics.computeIfAbsent(c.id(), ignored -> new java.util.ArrayDeque<>());
            synchronized (samples) {
                samples.addLast(new long[]{System.currentTimeMillis(), cpuRounded, ramMb, c.playerCount()});
                while (samples.size() > 900) samples.removeFirst();
            }
        }
        totalUsedCpu = Math.max(0, totalUsedCpu);

        StringBuilder sb = new StringBuilder();
        Session sess = sessionOf(ex);
        sb.append("{\"version\":\"").append(jsonEscape(Main.VERSION)).append("\",")
          .append("\"publicIp\":\"").append(jsonEscape(config.server().publicIp())).append("\",")
          .append("\"csrfToken\":\"").append(jsonEscape(sess == null ? "" : sess.csrf())).append("\",")
          .append("\"ramMax\":").append(maxRamMb)
          .append(",\"cpuMax\":").append(cpuMax)
          .append(",\"ramUsed\":").append(totalUsedRam)
          .append(",\"cpuUsed\":").append(totalUsedCpu)
          .append(",\"storageUsed\":").append(totalDisk)
          .append(",\"storageMax\":").append((long) config.server().diskMaxGb() << 30)
          .append(",\"netRx\":").append(rxSpeed).append(",\"netTx\":").append(txSpeed)
          .append(",\"isAdmin\":").append(admin)
          .append(",\"allowPanelIpView\":").append(canViewPanelIps(ex))
          .append(",\"allowConsoleIpView\":").append(canViewConsoleIps(ex))
          .append(",\"nodePreviousUrl\":\"").append(jsonEscape(nodeButtons.previousUrl())).append("\"")
          .append(",\"nodeNextUrl\":\"").append(jsonEscape(nodeButtons.nextUrl())).append("\"")
          .append(",\"nodeShowPrevious\":").append(nodeButtons.showPrevious())
          .append(",\"nodeShowNext\":").append(nodeButtons.showNext())
          .append(",\"availablePorts\":").append(availablePortsJson())
          .append(",\"availablePortsText\":\"").append(jsonEscape(portListText())).append("\"")
          .append(",\"diskLimitGb\":").append(panelOptions.diskLimitBytes() / 1073741824L)
          .append(",\"backupLimitGb\":").append(panelOptions.backupLimitBytes() / 1073741824L)
          .append(",\"diskLimit\":").append(panelOptions.diskLimitBytes())
          .append(",\"backupLimit\":").append(panelOptions.backupLimitBytes())
          .append(",\"setupComplete\":").append(panelOptions.setupComplete())
          .append(",\"servers\":[");
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
              .append(",\"restartRequired\":").append(configRestartRequired.contains(c.id()))
              .append(",\"cpuUsed\":").append(s.cpuPct()).append("}");
        }
        sb.append("]}");
        send(ex, 200, sb.toString());
    }

    private static String panelUserAgent() { return "childserversjar-panel/" + Main.VERSION; }

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
        audit(ex, "server.delete", id);
        saveConfig(new RootConfig(config.server(), newChildren, config.baseDir()));

        send(ex, 200, "Deleted");
    }

    // --- Multi-step creation flow handlers ---

    private void handleCreateTypes(HttpExchange ex) throws IOException {
        if (!isAdmin(ex)) { send(ex, 403, "Admin only"); return; }
        send(ex, 200, "[{\"id\":\"minecraft\",\"name\":\"Minecraft Server\",\"description\":\"Java Minecraft servers\"},"
                + "{\"id\":\"discord_bot\",\"name\":\"Discord Bot\",\"description\":\"Java-based Discord bots\"},"
                + "{\"id\":\"website\",\"name\":\"Website\",\"description\":\"Static HTML, CSS and JavaScript sites\"},"
                + "{\"id\":\"custom\",\"name\":\"Custom\",\"description\":\"Manually configured server\"}]");
    }

    private void handleCreateForks(HttpExchange ex) throws IOException {
        if (!isAdmin(ex)) { send(ex, 403, "Admin only"); return; }
        String type = extractQuery(ex.getRequestURI().getRawQuery(), "type");
        if (type == null || type.equals("minecraft")) {
            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (SoftwareRegistry.Software software : registry.catalog()) {
                if (!first) json.append(',');
                first = false;
                int ram = software.category() == SoftwareRegistry.Category.PROXY ? 512 : 1024;
                json.append("{\"id\":\"").append(jsonEscape(software.id()))
                    .append("\",\"name\":\"").append(jsonEscape(software.name()))
                    .append("\",\"ram_recommended\":").append(ram).append('}');
            }
            send(ex, 200, json.append(']').toString());
        } else if (type.equals("discord_bot")) {
            send(ex, 200, "[{\"id\":\"jda\",\"name\":\"JDA (Java Discord API)\",\"ram_recommended\":512},"
                + "{\"id\":\"discord4j\",\"name\":\"Discord4J\",\"ram_recommended\":512}]");
        } else if (type.equals("website")) {
            send(ex, 200, "[{\"id\":\"html\",\"name\":\"HTML starter\",\"ram_recommended\":128},"
                + "{\"id\":\"css\",\"name\":\"CSS starter\",\"ram_recommended\":128},"
                + "{\"id\":\"js\",\"name\":\"JavaScript starter\",\"ram_recommended\":128}]");
        } else {
            send(ex, 200, "[]");
        }
    }

    /** Uses the container CPU quota when present, so a restricted JVM cannot report only 200%. */
    private static int detectCpuMax() {
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        Double quotaCores = cgroupCpuCores();
        int cores = quotaCores == null ? processors : (int) Math.ceil(quotaCores);
        return Math.max(100, Math.max(processors, cores) * 100);
    }

    private static Double cgroupCpuCores() {
        try {
            String max = Files.readString(Paths.get("/sys/fs/cgroup/cpu.max")).trim();
            String[] parts = max.split("\\s+");
            if (parts.length == 2 && !parts[0].equals("max")) {
                double quota = Double.parseDouble(parts[0]);
                double period = Double.parseDouble(parts[1]);
                if (quota > 0 && period > 0) return quota / period;
            }
        } catch (Exception ignored) {}
        try {
            double quota = Double.parseDouble(Files.readString(Paths.get("/sys/fs/cgroup/cpu/cpu.cfs_quota_us")).trim());
            double period = Double.parseDouble(Files.readString(Paths.get("/sys/fs/cgroup/cpu/cpu.cfs_period_us")).trim());
            if (quota > 0 && period > 0) return quota / period;
        } catch (Exception ignored) {}
        return null;
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
                .header("User-Agent", panelUserAgent())
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
                // [{"version":"1.21.4","stable":true}, ...] - skip snapshots and pre-releases
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
                    .header("User-Agent", panelUserAgent())
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
                    .header("User-Agent", panelUserAgent())
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

        name = rootjar.config.ConfigValidator.normalizeId(name);
        if (!name.matches("^[a-zA-Z0-9_-]+$") || Set.of("status", "help", "stop", "list").contains(name.toLowerCase(java.util.Locale.ROOT))) {
            send(ex, 400, "Invalid name. Use letters, numbers, spaces, - or _."); return;
        }
        if (supervisor.child(name).isPresent() || Files.exists(config.baseDir().resolve(name))) {
            send(ex, 400, "A server or folder with this name already exists."); return;
        }
        boolean website = "website".equals(serverType);
        if (website && !Set.of("html", "css", "js").contains(fork)) { send(ex, 400, "Invalid website starter"); return; }
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
        if (ram < 128 || ram > 1048576) { send(ex, 400, "RAM must be between 128 and 1048576 MB"); return; }

        try {
            Path dir = config.baseDir().resolve(name);
            Files.createDirectories(dir);
            if (website) {
                Path site = dir.resolve("public");
                Files.createDirectories(site);
                String html = "<!doctype html>\n<html lang=\"en\">\n<head>\n  <meta charset=\"utf-8\">\n  <meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n  <title>My Website</title>\n  <link rel=\"stylesheet\" href=\"style.css\">\n</head>\n<body>\n  <h1>Welcome to your website!</h1>\n  <p>Edit these files in the panel's Files tab.</p>\n  <script src=\"script.js\" defer></script>\n</body>\n</html>\n";
                if (fork.equals("css")) html = html.replace("<h1>Welcome to your website!</h1>", "<h1>Style your website!</h1>");
                if (fork.equals("js")) html = html.replace("<h1>Welcome to your website!</h1>", "<h1 id=\"message\">Build something interactive!</h1>");
                Files.writeString(site.resolve("index.html"), html);
                Files.writeString(site.resolve("style.css"), "body { font-family: system-ui, sans-serif; max-width: 700px; margin: 80px auto; padding: 20px; background: #111827; color: #f9fafb; }\nh1 { color: #60a5fa; }\n");
                Files.writeString(site.resolve("script.js"), fork.equals("js") ? "document.querySelector('#message').addEventListener('click', () => alert('Hello from your website!'));\n" : "// Your website's JavaScript goes here.\n");
            } else {
                Files.writeString(dir.resolve("eula.txt"), "eula=true");
                generateServerProperties(dir.resolve("server.properties"), port);
            }

            int startOrder = supervisor.children().stream().mapToInt(c -> c.config().startOrder()).max().orElse(0) + 1;
            int stopOrder = supervisor.children().stream().mapToInt(c -> c.config().stopOrder()).max().orElse(0) + 1;
            String type = website ? "website" : (serverType != null && !serverType.isBlank() && !serverType.equals("minecraft") ? serverType : (fork != null ? fork : "paper"));

            ChildConfig newChild = new ChildConfig(name, type, dir, jar, ram + "M", true, port, "0.0.0.0",
                website ? StopStrategy.SIGTERM : StopStrategy.STDIN, "stop", 30, startOrder, stopOrder, true, List.of(5, 10, 30, 60, 300), List.of(), true, !website, false, "", true);
            supervisor.addChild(newChild);

            saveConfig(new RootConfig(config.server(),
                    supervisor.children().stream().map(ChildProcess::config).toList(), config.baseDir()));

            // The wizard preview resolves the exact Paper/Purpur build. Fetch it now so the server is ready
            // to start; a failed download must not undo/roll back the server entry.
            Path jarPath = dir.resolve(jar);
            boolean downloaded = false;
            String warning = null;
            if (!website && downloadUrl != null && !downloadUrl.isBlank() && !Files.exists(jarPath)) {
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
            audit(ex, "server.create", name + " (port " + port + ")");
            send(ex, 200, response.append("}").toString());
        } catch (IOException e) { send(ex, 500, e.getMessage()); }
    }

    /** Saves the dashboard drag order without changing start or stop behavior. */
    private void handleReorderServers(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { send(ex, 405, "Method not allowed"); return; }
        if (!isAdmin(ex)) { send(ex, 403, "Admin only"); return; }
        String body = new String(ex.getRequestBody().readNBytes(8193), StandardCharsets.UTF_8);
        if (body.length() > 8192) { send(ex, 413, "Request too large"); return; }
        List<String> ids = new ArrayList<>();
        int arrayStart = body.indexOf('['), arrayEnd = body.indexOf(']');
        String array = arrayStart >= 0 && arrayEnd > arrayStart ? body.substring(arrayStart, arrayEnd) : "";
        Matcher matcher = Pattern.compile("\"([A-Za-z0-9_-]+)\"").matcher(array);
        while (matcher.find()) ids.add(matcher.group(1));
        synchronized (supervisor) {
            Set<String> known = supervisor.children().stream().map(ChildProcess::id).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (!known.equals(new LinkedHashSet<>(ids))) { send(ex, 400, "Server list is out of date"); return; }
            supervisor.reorder(ids);
            List<ChildConfig> children = new ArrayList<>();
            int index = 1;
            for (ChildProcess child : supervisor.children()) {
                ChildConfig updated = child.config().withOrders(index, supervisor.children().size() - index + 1);
                child.setConfig(updated);
                children.add(updated);
                index++;
            }
            saveConfig(new RootConfig(config.server(), children, config.baseDir()));
            audit(ex, "server.reorder", String.join(", ", ids));
        }
        send(ex, 200, "{\"saved\":true}");
    }

    /** Change allocation or rename a stopped server without touching its world/site files. */
    private void handleEditServer(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { send(ex, 405, "Method not allowed"); return; }
        if (!isAdmin(ex)) { send(ex, 403, "Admin only"); return; }
        String body = new String(ex.getRequestBody().readNBytes(8193), StandardCharsets.UTF_8);
        if (body.length() > 8192) { send(ex, 413, "Request too large"); return; }
        String id = extractJson(body, "id"), name = rootjar.config.ConfigValidator.normalizeId(extractJson(body, "name")), jar = extractJson(body, "jar"), type = extractJson(body, "type");
        if (!id.matches("[A-Za-z0-9_-]+") || !name.matches("[A-Za-z0-9_-]+")
                || Set.of("status", "help", "stop", "list").contains(name.toLowerCase(java.util.Locale.ROOT))) {
            send(ex, 400, "Invalid server name"); return;
        }
        int port, ram;
        try { port = Integer.parseInt(extractJson(body, "port")); ram = Integer.parseInt(extractJson(body, "ram")); }
        catch (NumberFormatException e) { send(ex, 400, "Invalid port or RAM"); return; }
        if (port < 1 || port > 65535 || port == config.server().port() || ram < 128 || ram > 1048576) {
            send(ex, 400, "Port or RAM outside allowed range"); return;
        }
        if (!type.isBlank() && !type.matches("[a-z0-9_-]{1,32}")) { send(ex, 400, "Invalid server type"); return; }
        if (jar.isBlank()) jar = null;
        else {
            jar = Paths.get(jar.replace('\\', '/')).getFileName().toString();
            if (jar.equals(".") || jar.equals("..") || !jar.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
                send(ex, 400, "Jar file name must end with .jar"); return;
            }
        }
        synchronized (supervisor) {
            ChildProcess child = supervisor.child(id).orElse(null);
            if (child == null) { send(ex, 404, "Server not found"); return; }
            if (child.isAlive() || child.state() == ChildState.STARTING || child.state() == ChildState.STOPPING
                    || child.state() == ChildState.RESTARTING) {
                send(ex, 409, "Stop the server before editing its name, port or RAM"); return;
            }
            if (!name.equals(id) && supervisor.child(name).isPresent()) { send(ex, 409, "Name is already in use"); return; }
            for (ChildProcess other : supervisor.children()) {
                if (!other.id().equals(id) && other.config().port() == port) { send(ex, 409, "Port is already in use"); return; }
            }
            ChildConfig old = child.config();
            Path oldDir = old.dir();
            Path newDir = name.equals(id) ? oldDir : config.baseDir().resolve(name).normalize();
            if (!name.equals(id) && (Files.exists(newDir) || !oldDir.toAbsolutePath().normalize().getParent().equals(newDir.toAbsolutePath().normalize().getParent()))) {
                send(ex, 409, "Destination folder already exists or is outside the server directory"); return;
            }
            ChildConfig updated = new ChildConfig(name, type.isBlank() ? old.type() : type, newDir, jar == null ? old.jar() : jar, ram + "M", old.xmsEqualXmx(),
                port, old.bind(), old.stopStrategy(), old.stopCommand(), old.stopTimeoutSeconds(), old.startOrder(),
                old.stopOrder(), old.restartOnCrash(), old.restartBackoffSeconds(), old.extraFlags(),
                old.consoleEnabled(), old.useAikarFlags(), old.silent(), old.javaPath(), old.autoStart());
            List<ChildConfig> children = supervisor.children().stream()
                .map(c -> c.id().equals(id) ? updated : c.config()).toList();
            try { ConfigValidator.validate(new RootConfig(config.server(), children, config.baseDir())); }
            catch (ConfigException e) { send(ex, 400, String.join("; ", e.problems())); return; }
            if (!old.type().equals("website") && port != old.port() && Files.exists(oldDir.resolve("server.properties"))) {
                send(ex, 409, "Edit server.properties server-port first, then change the allocation"); return;
            }
            boolean moved = false;
            try {
                if (!name.equals(id)) { Files.move(oldDir, newDir); moved = true; }
                saveConfig(new RootConfig(config.server(), children, config.baseDir()));
            } catch (IOException e) {
                if (moved) try { Files.move(newDir, oldDir); } catch (IOException ignored) {}
                send(ex, 500, "Could not save server settings: " + e.getMessage()); return;
            }
            if (name.equals(id)) supervisor.updateChildConfig(id, updated);
            else { supervisor.removeChild(id); supervisor.addChild(updated); }
            diskUsage.remove(id);
            audit(ex, "server.edit", name + " (port " + port + ", " + ram + "M)" + (name.equals(id) ? "" : ", renamed from " + id));
            send(ex, 200, "{\"id\":\"" + jsonEscape(name) + "\",\"port\":" + port + ",\"ram\":" + ram + "}");
        }
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
                .header("User-Agent", panelUserAgent())
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
        if (!requireOperator(ex, id)) return;
        if (!rateAllows(ex, "power", 30)) { send(ex, 429, "Too many actions"); return; }
        String action = new String(ex.getRequestBody().readAllBytes());
        activity(id, ex, "server." + action, "");
        if (action.equals("kill")) supervisor.child(id).ifPresent(ChildProcess::kill);
        else supervisor.requestLifecycle(id, action);
        if (Set.of("start", "stop", "restart", "kill").contains(action)) {
            if (action.equals("start") || action.equals("restart")) configRestartRequired.remove(id);
            boolean shouldRun = action.equals("start") || action.equals("restart");
            supervisor.child(id).ifPresent(c -> supervisor.setChildConfig(id, c.config().withAutoStart(shouldRun)));
            persistConfig();
        }
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
        } else if (rest.equals("java")) {
            handleServerJava(ex, serverId);
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
            audit(ex, id + ".file.write", path);
            send(ex, 200, "Saved");
        } else if (method.equals("DELETE")) {
            Files.deleteIfExists(file);
            audit(ex, id + ".file.delete", path);
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
        audit(ex, child.id() + ".config.write", path);
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
                    os.write(("data: " + filterConsoleIps(ex, entry.line()).replace("\r", "").replace("\n", "") + "\n\n").getBytes(StandardCharsets.UTF_8));
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
        for (var entry : child.log().since(0)) sb.append(filterConsoleIps(ex, entry.line())).append("\r\n");
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
            // Re-read the live instance right before mutating: a concurrent version change may have replaced
            // this child (new process + new config) while the port checks ran, and must not be reverted.
            ChildProcess live = supervisor.child(id).orElse(null);
            if (live == null) { send(ex, 404, "Not found"); return; }
            if (live != child) { send(ex, 409, "The server was reconfigured; reload and try again"); return; }
            ChildConfig old = live.config();
            ChildConfig newCfg = new ChildConfig(old.id(), old.type(), old.dir(), jar, heap, old.xmsEqualXmx(),
                port, old.bind(), old.stopStrategy(), old.stopCommand(), old.stopTimeoutSeconds(),
                old.startOrder(), old.stopOrder(), old.restartOnCrash(), old.restartBackoffSeconds(),
                old.extraFlags(), old.consoleEnabled(), old.useAikarFlags(), old.silent(), old.javaPath(), old.autoStart());
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
            audit(ex, id + ".file.write", relPath);
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
            audit(ex, id + ".file.delete", relPath);
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
            audit(ex, id + ".file.mkdir", relPath);
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
            audit(ex, id + ".file.rename", oldP + " -> " + newP);
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
            audit(ex, id + ".file.zip", relPath);
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
            audit(ex, id + ".file.upload", path + "/" + filename);
            send(ex, 200, "Uploaded");
        } catch (Exception e) { send(ex, 500, e.getMessage()); }
    }

    private ChildProcess fileChild(HttpExchange ex, boolean mutate) throws IOException {
        String id = ex.getRequestURI().getPath().substring(ex.getRequestURI().getPath().lastIndexOf('/') + 1);
        if (!(mutate ? requireOperator(ex, id) : requireServer(ex, id))) return null;
        ChildProcess child = supervisor.child(id).orElse(null);
        if (child == null) send(ex, 404, "Not found");
        return child;
    }

    private void handleFileDownload(HttpExchange ex) throws IOException {
        ChildProcess child = fileChild(ex, false);
        if (child == null) return;
        try {
            Path file = FileOps.resolve(child.config().dir(), param(ex, "path", ""));
            if (!Files.isRegularFile(file)) { send(ex, 404, "Not found"); return; }
            String name = file.getFileName().toString();
            ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
            ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + name.replace("\"", "") + "\"");
            ex.sendResponseHeaders(200, Files.size(file));
            try (OutputStream out = ex.getResponseBody()) { Files.copy(file, out); }
        } catch (Exception e) { send(ex, 403, e.getMessage()); }
    }

    private void handleFileDownloadZip(HttpExchange ex) throws IOException {
        ChildProcess child = fileChild(ex, false);
        if (child == null) return;
        try {
            Path source = FileOps.resolve(child.config().dir(), param(ex, "path", "."));
            ex.getResponseHeaders().set("Content-Type", "application/zip");
            ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + source.getFileName() + ".zip\"");
            ex.sendResponseHeaders(200, 0);
            FileOps.zip(source, ex.getResponseBody());
            ex.close();
        } catch (Exception e) { send(ex, 403, e.getMessage()); }
    }

    private void handleFileSearch(HttpExchange ex) throws IOException {
        ChildProcess child = fileChild(ex, false);
        if (child == null) return;
        try {
            Path root = FileOps.resolve(child.config().dir(), param(ex, "path", "."));
            int limit = clampInt(param(ex, "limit", "200"), 200, 1, 200);
            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (Path match : FileOps.searchNames(root, param(ex, "q", ""), limit)) {
                if (!first) json.append(',');
                first = false;
                json.append("{\"path\":\"").append(jsonEscape(child.config().dir().relativize(match).toString().replace('\\', '/')))
                    .append("\",\"name\":\"").append(jsonEscape(match.getFileName().toString()))
                    .append("\",\"dir\":").append(Files.isDirectory(match)).append('}');
            }
            send(ex, 200, json.append(']').toString());
        } catch (Exception e) { send(ex, 403, e.getMessage()); }
    }

    private void handleFileStat(HttpExchange ex) throws IOException {
        ChildProcess child = fileChild(ex, false);
        if (child == null) return;
        try {
            Path file = FileOps.resolve(child.config().dir(), param(ex, "path", ""));
            send(ex, 200, "{\"name\":\"" + jsonEscape(file.getFileName().toString()) + "\",\"dir\":" + Files.isDirectory(file)
                    + ",\"size\":" + (Files.isDirectory(file) ? 0 : Files.size(file))
                    + ",\"modified\":" + Files.getLastModifiedTime(file).toMillis() + "}");
        } catch (Exception e) { send(ex, 403, e.getMessage()); }
    }

    private void handleFileMove(HttpExchange ex) throws IOException { transferFile(ex, true); }
    private void handleFileCopy(HttpExchange ex) throws IOException { transferFile(ex, false); }

    private void transferFile(HttpExchange ex, boolean move) throws IOException {
        ChildProcess child = fileChild(ex, true);
        if (child == null) return;
        try {
            String fromKey = move ? "old" : "from", toKey = move ? "new" : "to";
            Path source = FileOps.resolve(child.config().dir(), param(ex, fromKey, ""));
            Path target = FileOps.resolve(child.config().dir(), param(ex, toKey, ""));
            if (move) Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            else FileOps.copyRecursive(source, target);
            audit(ex, child.id() + (move ? ".file.move" : ".file.copy"), param(ex, fromKey, "") + " -> " + param(ex, toKey, ""));
            send(ex, 200, move ? "Moved" : "Copied");
        } catch (Exception e) { send(ex, 403, e.getMessage()); }
    }

    private void handleFileExtract(HttpExchange ex) throws IOException {
        ChildProcess child = fileChild(ex, true);
        if (child == null) return;
        try {
            Path zip = FileOps.resolve(child.config().dir(), param(ex, "path", ""));
            String dest = param(ex, "dest", "");
            if (dest.isBlank()) dest = param(ex, "path", "").replaceAll("(?i)\\.zip$", "");
            FileOps.extract(zip, FileOps.resolve(child.config().dir(), dest));
            audit(ex, child.id() + ".file.extract", param(ex, "path", "") + " -> " + dest);
            send(ex, 200, "Extracted");
        } catch (Exception e) { send(ex, 403, e.getMessage()); }
    }

    private void handleFileTrash(HttpExchange ex) throws IOException {
        if (ex.getRequestURI().getPath().endsWith("/list")) { handleTrashList(ex); return; }
        ChildProcess child = fileChild(ex, true);
        if (child == null) return;
        try {
            Path source = FileOps.resolve(child.config().dir(), param(ex, "path", ""));
            Path trash = child.config().dir().resolve(".trash");
            Files.createDirectories(trash);
            String stamped = System.currentTimeMillis() + "-" + source.getFileName();
            Files.move(source, trash.resolve(stamped));
            Files.writeString(trash.resolve(stamped + ".meta"), param(ex, "path", ""));
            audit(ex, child.id() + ".file.trash", param(ex, "path", ""));
            send(ex, 200, "{\"name\":\"" + jsonEscape(stamped) + "\"}");
        } catch (Exception e) { send(ex, 403, e.getMessage()); }
    }

    private void handleTrashList(HttpExchange ex) throws IOException {
        ChildProcess child = fileChild(ex, false);
        if (child == null) return;
        Path trash = child.config().dir().resolve(".trash");
        StringBuilder json = new StringBuilder("[");
        if (Files.isDirectory(trash)) {
            boolean first = true;
            try (Stream<Path> entries = Files.list(trash)) {
                for (Path entry : entries.filter(p -> !p.getFileName().toString().endsWith(".meta")).toList()) {
                    if (!first) json.append(',');
                    first = false;
                    Path meta = trash.resolve(entry.getFileName() + ".meta");
                    String original = Files.exists(meta) ? Files.readString(meta).trim() : "";
                    json.append("{\"name\":\"").append(jsonEscape(entry.getFileName().toString()))
                        .append("\",\"original\":\"").append(jsonEscape(original)).append("\"}");
                }
            }
        }
        send(ex, 200, json.append(']').toString());
    }

    private void handleFileRestore(HttpExchange ex) throws IOException {
        ChildProcess child = fileChild(ex, true);
        if (child == null) return;
        try {
            String name = FileOps.safeName(param(ex, "name", ""));
            Path trashed = child.config().dir().resolve(".trash").resolve(name);
            Path meta = trashed.resolveSibling(name + ".meta");
            String original = Files.exists(meta) ? Files.readString(meta).trim() : name.replaceFirst("^\\d+-", "");
            Path target = FileOps.resolve(child.config().dir(), original);
            if (Files.exists(target)) target = target.resolveSibling(target.getFileName() + ".restored");
            Files.createDirectories(target.getParent());
            Files.move(trashed, target);
            Files.deleteIfExists(meta);
            send(ex, 200, "Restored");
        } catch (Exception e) { send(ex, 403, e.getMessage()); }
    }

    private void handleFileEmptyTrash(HttpExchange ex) throws IOException {
        ChildProcess child = fileChild(ex, true);
        if (child == null) return;
        Path trash = child.config().dir().resolve(".trash");
        if (Files.isDirectory(trash)) {
            try (Stream<Path> walk = Files.walk(trash)) {
                walk.sorted(Comparator.reverseOrder()).filter(p -> !p.equals(trash)).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            }
        }
        send(ex, 200, "Trash emptied");
    }

    private void handleBackupList(HttpExchange ex) throws IOException {
        String server = param(ex, "server", "");
        if (!requireServer(ex, server)) return;
        ChildProcess child = supervisor.child(server).orElse(null);
        if (child == null) { send(ex, 404, "Not found"); return; }
        StringBuilder json = new StringBuilder("{\"retention\":").append(config.server().backupRetention()).append(",\"backupBytes\":");
        java.util.List<BackupManager.BackupInfo> backups = BackupManager.list(child.config());
        long backupBytes = backups.stream().mapToLong(BackupManager.BackupInfo::bytes).sum();
        json.append(backupBytes).append(",\"backupLimit\":").append(panelOptions.backupLimitBytes()).append(",\"backups\":[");
        boolean first = true;
        for (BackupManager.BackupInfo info : backups) {
            if (!first) json.append(',');
            first = false;
            json.append("{\"name\":\"").append(jsonEscape(info.name())).append("\",\"folder\":\"").append(jsonEscape(info.folder()))
                .append("\",\"size\":").append(info.bytes()).append(",\"created\":").append(info.createdAt()).append('}');
        }
        send(ex, 200, json.append("]}").toString());
    }

    private void handleBackupCreate(HttpExchange ex) throws IOException {
        String server = param(ex, "server", "");
        if (!requireOperator(ex, server)) return;
        ChildProcess child = supervisor.child(server).orElse(null);
        if (child == null) { send(ex, 404, "Not found"); return; }
        if (child.isAlive() && !param(ex, "force", "").equals("1")) { send(ex, 409, "Server is running"); return; }
        BackupManager.BackupInfo info = BackupManager.create(child.config(), param(ex, "label", ""), param(ex, "include_logs", "1").equals("1"), config.server().backupRetention());
        activity(server, ex, "backup.create", info.name());
        send(ex, 200, "{\"name\":\"" + jsonEscape(info.name()) + "\",\"size\":" + info.bytes() + "}");
    }

    private void handleBackupDownload(HttpExchange ex) throws IOException {
        String server = param(ex, "server", "");
        if (!requireServer(ex, server)) return;
        ChildProcess child = supervisor.child(server).orElse(null);
        if (child == null) { send(ex, 404, "Not found"); return; }
        Path file = BackupManager.resolve(child.config(), param(ex, "name", ""), param(ex, "folder", ""));
        ex.getResponseHeaders().set("Content-Type", "application/zip");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + file.getFileName() + "\"");
        ex.sendResponseHeaders(200, Files.size(file));
        try (OutputStream out = ex.getResponseBody()) { Files.copy(file, out); }
    }

    private void handleBackupRestore(HttpExchange ex) throws IOException {
        String server = param(ex, "server", "");
        if (!requireOwner(ex, server)) return;
        if (!param(ex, "confirm", "").equals(server)) { send(ex, 400, "Type the server name to confirm"); return; }
        ChildProcess child = supervisor.child(server).orElse(null);
        if (child == null) { send(ex, 404, "Not found"); return; }
        if (child.isAlive()) child.stop();
        if (child.isAlive()) { send(ex, 409, "Could not stop server"); return; }
        String name = param(ex, "name", "");
        String folder = param(ex, "folder", "");
        Path source = BackupManager.resolve(child.config(), name, folder);
        BackupManager.create(child.config(), "pre-restore", true, config.server().backupRetention(), source.getFileName().toString());
        BackupManager.restore(child.config(), name, folder);
        activity(server, ex, "backup.restore", name);
        send(ex, 200, "Restored");
    }

    private void handleBackupDelete(HttpExchange ex) throws IOException {
        String server = param(ex, "server", "");
        if (!requireOwner(ex, server)) return;
        ChildProcess child = supervisor.child(server).orElse(null);
        if (child == null) { send(ex, 404, "Not found"); return; }
        String name = param(ex, "name", "");
        String folder = param(ex, "folder", "");
        BackupManager.delete(child.config(), name, folder);
        activity(server, ex, "backup.delete", name);
        send(ex, 200, "Deleted");
    }

    private void handleSettings(HttpExchange ex) throws IOException {
        if (!isAdmin(ex)) { send(ex, 403, "Admin only"); return; }
        if (ex.getRequestMethod().equals("GET")) {
            ServerConfig server = config.server();
            send(ex, 200, "{\"webhookUrl\":\"" + jsonEscape(server.webhookUrl()) + "\",\"backupRetention\":" + server.backupRetention()
                    + ",\"publicStatus\":" + server.publicStatus() + ",\"sessionTtl\":" + server.sessionTtlSeconds()
                    + ",\"nodePreviousUrl\":\"" + jsonEscape(nodeButtons.previousUrl()) + "\""
                    + ",\"nodeNextUrl\":\"" + jsonEscape(nodeButtons.nextUrl()) + "\""
                    + ",\"nodeShowPrevious\":" + nodeButtons.showPrevious()
                    + ",\"nodeShowNext\":" + nodeButtons.showNext()
                    + ",\"ports\":\"" + jsonEscape(portListText()) + "\""
                    + ",\"diskLimitGb\":" + (panelOptions.diskLimitBytes() / 1073741824L)
                    + ",\"backupLimitGb\":" + (panelOptions.backupLimitBytes() / 1073741824L)
                    + ",\"setupComplete\":" + panelOptions.setupComplete() + "}");
            return;
        }
        String body = new String(ex.getRequestBody().readNBytes(8193), StandardCharsets.UTF_8);
        if (body.length() > 8192) { send(ex, 413, "Request too large"); return; }
        ServerConfig old = config.server();
        String webhook = jsonHas(body, "webhookUrl") ? extractJson(body, "webhookUrl") : old.webhookUrl();
        if (!webhook.isBlank() && !webhook.startsWith("https://")) { send(ex, 400, "Webhook must be an HTTPS URL"); return; }
        int retention = old.backupRetention();
        if (jsonHas(body, "backupRetention")) {
            try { retention = Math.max(1, Math.min(50, Integer.parseInt(extractJson(body, "backupRetention")))); }
            catch (NumberFormatException e) { send(ex, 400, "Backup retention must be a number"); return; }
        }
        boolean publicStatus = jsonHas(body, "publicStatus") ? "true".equals(extractJson(body, "publicStatus")) : old.publicStatus();
        NodeButtons updatedButtons = null;
        if (jsonHas(body, "nodePreviousUrl") || jsonHas(body, "nodeNextUrl")
                || jsonHas(body, "nodeShowPrevious") || jsonHas(body, "nodeShowNext")) {
            String previousUrl = jsonHas(body, "nodePreviousUrl") ? validNodeLink(extractJson(body, "nodePreviousUrl")) : nodeButtons.previousUrl();
            String nextUrl = jsonHas(body, "nodeNextUrl") ? validNodeLink(extractJson(body, "nodeNextUrl")) : nodeButtons.nextUrl();
            if (previousUrl == null || nextUrl == null) {
                send(ex, 400, "Node links must start with http:// or https://");
                return;
            }
            updatedButtons = new NodeButtons(previousUrl, nextUrl,
                    jsonHas(body, "nodeShowPrevious") ? "true".equals(extractJson(body, "nodeShowPrevious")) : nodeButtons.showPrevious(),
                    jsonHas(body, "nodeShowNext") ? "true".equals(extractJson(body, "nodeShowNext")) : nodeButtons.showNext());
        }
        saveConfig(new RootConfig(new ServerConfig(old.bind(), old.publicIp(), old.port(), old.sessionTtlSeconds(),
                old.loginRateLimitPerIpPerMinute(), old.loginLockoutAfterFailures(), old.loginLockoutSeconds(), old.passwordHash(),
                old.trustedProxies(), old.diskMaxGb(), retention, webhook, publicStatus, old.users()), config.children(), config.baseDir()));
        if (jsonHas(body, "ports") || jsonHas(body, "diskLimitGb") || jsonHas(body, "backupLimitGb") || jsonHas(body, "setupComplete")) {
            long diskBytes = jsonHas(body, "diskLimitGb") ? Math.max(0, parseLong(extractJson(body, "diskLimitGb"))) * 1073741824L : panelOptions.diskLimitBytes();
            long backupBytes = jsonHas(body, "backupLimitGb") ? Math.max(0, parseLong(extractJson(body, "backupLimitGb"))) * 1073741824L : panelOptions.backupLimitBytes();
            boolean setupComplete = jsonHas(body, "setupComplete") ? !"false".equals(extractJson(body, "setupComplete")) : panelOptions.setupComplete();
            PanelOptions updatedOptions = new PanelOptions(jsonHas(body, "ports") ? parsePorts(extractJson(body, "ports")) : panelOptions.ports(), diskBytes, backupBytes, setupComplete);
            savePanelOptions(updatedOptions);
            panelOptions = updatedOptions;
        }
        audit(ex, "settings.changed", "Panel settings updated");
        if (updatedButtons != null) {
            try {
                saveNodeButtons(updatedButtons);
            } catch (IOException e) {
                send(ex, 500, "Could not save node links");
                return;
            }
            nodeButtons = updatedButtons;
        }
        notifier.send("settings.changed", "Panel settings were updated");
        send(ex, 200, "{\"saved\":true}");
    }

    private static long parseLong(String value) { try { return Long.parseLong(value); } catch (NumberFormatException e) { return 0; } }
    private static List<Integer> parsePorts(String text) {
        List<Integer> ports = new ArrayList<>();
        for (String part : text.split("[,\\s]+")) {
            if (part.isBlank()) continue;
            try { int port = Integer.parseInt(part); if (port >= 1 && port <= 65535 && !ports.contains(port)) ports.add(port); }
            catch (NumberFormatException ignored) {}
        }
        return ports;
    }
    private String portListText() { return panelOptions.ports().stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse(""); }
    private String availablePortsJson() {
        Set<Integer> used = supervisor.children().stream().map(child -> child.config().port()).collect(java.util.stream.Collectors.toSet());
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (int port : panelOptions.ports()) {
            if (used.contains(port)) continue;
            if (!first) json.append(',');
            first = false;
            json.append(port);
        }
        return json.append(']').toString();
    }
    private static PanelOptions loadPanelOptions(Path file) {
        String ports = "";
        long disk = 0, backup = 0;
        boolean setup = false;
        if (Files.exists(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (!line.contains("=")) continue;
                    String key = line.substring(0, line.indexOf('=')).trim();
                    String value = line.substring(line.indexOf('=') + 1).trim();
                    switch (key) {
                        case "ports" -> ports = value;
                        case "diskLimit" -> disk = parseLong(value);
                        case "backupLimit" -> backup = parseLong(value);
                        case "setupComplete" -> setup = value.equals("true");
                    }
                }
            } catch (IOException ignored) {}
        }
        return new PanelOptions(parsePorts(ports), disk, backup, setup);
    }
    private void savePanelOptions(PanelOptions options) throws IOException {
        Path file = config.baseDir().resolve("panel-options.properties");
        Files.writeString(file, "ports=" + options.ports().stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("")
                + "\ndiskLimit=" + options.diskLimitBytes() + "\nbackupLimit=" + options.backupLimitBytes()
                + "\nsetupComplete=" + options.setupComplete() + "\n", StandardCharsets.UTF_8);
    }
    private static boolean jsonHas(String json, String key) {
        return json != null && json.contains("\"" + key + "\"");
    }

    /** Returns a trimmed link, an empty string, or null when the pasted value is not a safe HTTP(S) URL. */
    private static String validNodeLink(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "";
        if (value.length() > 2048 || value.chars().anyMatch(Character::isWhitespace)) return null;
        return value.matches("(?i)^https?://.+") ? value : null;
    }

    private static NodeButtons loadNodeButtons(Path file) {
        String previousUrl = "", nextUrl = "";
        boolean showPrevious = true, showNext = true;
        if (Files.notExists(file)) return new NodeButtons(previousUrl, nextUrl, showPrevious, showNext);
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#") || !line.contains("=")) continue;
                String key = line.substring(0, line.indexOf('=')).trim();
                String value = line.substring(line.indexOf('=') + 1).trim();
                switch (key) {
                    case "previousUrl" -> previousUrl = value;
                    case "nextUrl" -> nextUrl = value;
                    case "showPrevious" -> showPrevious = !value.equalsIgnoreCase("false");
                    case "showNext" -> showNext = !value.equalsIgnoreCase("false");
                }
            }
        } catch (IOException ignored) {}
        return new NodeButtons(previousUrl, nextUrl, showPrevious, showNext);
    }

    private void saveNodeButtons(NodeButtons buttons) throws IOException {
        Path parent = nodeButtonsPath.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(nodeButtonsPath,
                "# Rootjar panel previous/next node buttons\n"
                        + "previousUrl=" + buttons.previousUrl() + "\n"
                        + "nextUrl=" + buttons.nextUrl() + "\n"
                        + "showPrevious=" + buttons.showPrevious() + "\n"
                        + "showNext=" + buttons.showNext() + "\n",
                StandardCharsets.UTF_8);
    }

    private final Map<String, java.util.Deque<Long>> requestRates = new ConcurrentHashMap<>();
    private boolean rateAllows(HttpExchange ex, String action, int limit) {
        String key = getSessionUser(ex) + "|" + action;
        java.util.Deque<Long> times = requestRates.computeIfAbsent(key, ignored -> new java.util.ArrayDeque<>());
        long now = System.currentTimeMillis();
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > 60_000) times.removeFirst();
            if (times.size() >= limit) return false;
            times.addLast(now);
        }
        return true;
    }

    private void activity(String serverId, HttpExchange ex, String action, String details) {
        supervisor.child(serverId).ifPresent(child -> {
            try { ActivityLog.append(child.config().dir(), getSessionUser(ex), action, details); }
            catch (IOException ignored) {}
        });
        audit(ex, serverId + "." + action, details);
    }

    private void audit(HttpExchange ex, String action, String details) {
        try { ActivityLog.append(config.baseDir(), getSessionUser(ex), action, details); }
        catch (IOException ignored) {}
    }

    /** Audit overload for actions without a session yet (login) or with an explicit user. */
    private void audit(String user, String action, String details) {
        try { ActivityLog.append(config.baseDir(), user == null ? "" : user, action, details); }
        catch (IOException ignored) {}
    }

    private void handleSessions(HttpExchange ex) throws IOException {
        if (!isAuthed(ex)) { send(ex, 401, "Unauthorized"); return; }
        if (ex.getRequestMethod().equals("GET")) {
            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (Session session : sessions.values()) {
                if (!isAdmin(ex) && !session.user().equals(getSessionUser(ex))) continue;
                if (!first) json.append(',');
                first = false;
                json.append("{\"token\":\"").append(jsonEscape(session.token())).append("\",\"user\":\"").append(jsonEscape(session.user())).append("\",\"ip\":\"").append(jsonEscape(canViewPanelIps(ex) ? session.ip() : "Hidden")).append("\",\"lastSeen\":").append(session.lastSeen()).append('}');
            }
            send(ex, 200, json.append(']').toString());
            return;
        }
        String token = extractJson(new String(ex.getRequestBody().readNBytes(4096), StandardCharsets.UTF_8), "token");
        Session target = sessions.get(token);
        if (target != null && (isAdmin(ex) || target.user().equals(getSessionUser(ex)))) sessions.remove(token);
        send(ex, 200, "Revoked");
    }

    private void handlePlayers(HttpExchange ex) throws IOException {
        String server = param(ex, "server", "");
        if (ex.getRequestMethod().equals("GET")) {
            if (!requireServer(ex, server)) return;
            ChildProcess child = supervisor.child(server).orElse(null);
            if (child == null) { send(ex, 404, "Not found"); return; }
            if (child.isAlive()) child.sendLine("list");
            send(ex, 200, "{\"count\":" + child.playerCount() + ",\"players\":[]}");
            return;
        }
        if (!requireOperator(ex, server)) return;
        if (!rateAllows(ex, "players", 20)) { send(ex, 429, "Too many player actions"); return; }
        String body = new String(ex.getRequestBody().readNBytes(1024), StandardCharsets.UTF_8);
        String action = extractJson(body, "action"), player = extractJson(body, "player");
        if (!player.matches("[A-Za-z0-9_]{1,16}")) { send(ex, 400, "Invalid player name"); return; }
        String command = switch (action) {
            case "kick" -> "kick " + player;
            case "ban" -> "ban " + player;
            case "pardon" -> "pardon " + player;
            case "op" -> "op " + player;
            case "deop" -> "deop " + player;
            case "whitelist_add" -> "whitelist add " + player;
            case "whitelist_remove" -> "whitelist remove " + player;
            default -> "";
        };
        if (command.isBlank()) { send(ex, 400, "Invalid player action"); return; }
        ChildProcess child = supervisor.child(server).orElse(null);
        if (child == null || !child.sendLine(command)) { send(ex, 409, "Server is not running"); return; }
        activity(server, ex, "player." + action, player);
        send(ex, 200, "Sent");
    }

    private void handleMetrics(HttpExchange ex) throws IOException {
        String server = param(ex, "server", "");
        if (!requireServer(ex, server)) return;
        java.util.ArrayDeque<long[]> samples = metrics.getOrDefault(server, new java.util.ArrayDeque<>());
        StringBuilder json = new StringBuilder("[");
        synchronized (samples) {
            boolean first = true;
            for (long[] sample : samples) {
                if (!first) json.append(',');
                first = false;
                json.append("{\"time\":").append(sample[0]).append(",\"cpu\":").append(sample[1]).append(",\"ram\":").append(sample[2]).append(",\"players\":").append(sample[3]).append('}');
            }
        }
        send(ex, 200, json.append(']').toString());
    }

    private void handleFileGrep(HttpExchange ex) throws IOException {
        ChildProcess child = fileChild(ex, false);
        if (child == null) return;
        String query = param(ex, "q", "");
        if (query.length() < 2 || query.length() > 200) { send(ex, 400, "Query must be 2-200 characters"); return; }
        Path root = FileOps.resolve(child.config().dir(), param(ex, "path", "."));
        StringBuilder json = new StringBuilder("[");
        int matches = 0;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        try (java.util.stream.Stream<Path> files = Files.walk(root, FileOps.MAX_SEARCH_DEPTH)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (matches >= 100 || System.nanoTime() > deadline) break;
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.matches(".*\\.(ya?ml|json|properties|txt|log|toml|conf|cfg|md)$") || Files.size(file) > 2_097_152) continue;
                int lineNumber = 0;
                for (String line : Files.readAllLines(file)) {
                    lineNumber++;
                    if (!line.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) continue;
                    if (matches++ > 0) json.append(',');
                    json.append("{\"file\":\"").append(jsonEscape(child.config().dir().relativize(file).toString().replace('\\', '/')))
                        .append("\",\"line\":").append(lineNumber).append(",\"text\":\"").append(jsonEscape(line)).append("\"}");
                    if (matches >= 100) break;
                }
            }
        }
        send(ex, 200, json.append(']').toString());
    }

    private void handlePublicStatus(HttpExchange ex) throws IOException {
        if (!config.server().publicStatus()) { send(ex, 404, "Not found"); return; }
        StringBuilder rows = new StringBuilder();
        for (ChildProcess child : supervisor.children()) {
            rows.append("<tr><td>").append(jsonEscape(child.id())).append("</td><td>").append(jsonEscape(child.config().type()))
                .append("</td><td>").append(jsonEscape(child.state().name())).append("</td><td>").append(child.playerCount()).append("</td></tr>");
        }
        byte[] body = ("<!doctype html><meta http-equiv=\"refresh\" content=\"15\"><title>Server Status</title><table><tr><th>Server</th><th>Type</th><th>Status</th><th>Players</th></tr>" + rows + "</table>").getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "public, max-age=5");
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    private void handleActivity(HttpExchange ex) throws IOException {
        String server = param(ex, "server", "");
        if (server.isBlank()) {
            if (!isAdmin(ex)) { send(ex, 403, "Admin only"); return; }
            send(ex, 200, ActivityLog.recent(config.baseDir(), 100));
            return;
        }
        if (!requireServer(ex, server)) return;
        ChildProcess child = supervisor.child(server).orElse(null);
        if (child == null) { send(ex, 404, "Not found"); return; }
        send(ex, 200, ActivityLog.recent(child.config().dir(), 100));
    }

    private void handleTasks(HttpExchange ex) throws IOException {
        if (!isAdmin(ex)) { send(ex, 403, "Admin only"); return; }
        if (ex.getRequestMethod().equals("GET")) { send(ex, 200, tasks.read()); return; }
        String body = new String(ex.getRequestBody().readNBytes(65537), StandardCharsets.UTF_8);
        if (body.length() > 65536) { send(ex, 413, "tasks.yml is too large"); return; }
        try { tasks.save(body); audit(ex, "tasks.changed", "Schedules updated"); send(ex, 200, "Saved"); }
        catch (ConfigException | IOException e) { send(ex, 400, e.getMessage()); }
    }

    private void handleBackupUpload(HttpExchange ex) throws IOException {
        String server = param(ex, "server", "");
        if (!requireOperator(ex, server)) return;
        ChildProcess child = supervisor.child(server).orElse(null);
        if (child == null) { send(ex, 404, "Not found"); return; }
        String name = "import-" + System.currentTimeMillis() + ".zip";
        Path target = BackupManager.directory(child.config()).resolve(name);
        Path temp = target.resolveSibling(name + ".part");
        try {
            try (InputStream in = ex.getRequestBody()) { Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING); }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        activity(server, ex, "backup.upload", name);
        send(ex, 200, "{\"name\":\"" + name + "\"}");
    }

    private void handleFileImport(HttpExchange ex) throws IOException {
        ChildProcess child = fileChild(ex, true);
        if (child == null) return;
        Path temp = Files.createTempFile(child.config().dir(), ".import-", ".zip");
        try {
            try (InputStream in = ex.getRequestBody()) { Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING); }
            String dest = param(ex, "path", "public");
            FileOps.extract(temp, FileOps.resolve(child.config().dir(), dest));
            audit(ex, child.id() + ".file.import", dest);
            send(ex, 200, "Imported");
        } catch (Exception e) {
            send(ex, 403, e.getMessage());
        } finally { Files.deleteIfExists(temp); }
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
            audit(ex, id + ".version.install", "paper " + version);
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
                        .header("User-Agent", panelUserAgent())
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
            audit(ex, serverId + (mods ? ".mod.install" : ".plugin.install"), fileName + (replaced ? " (updated)" : ""));
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
            audit(ex, serverId + (mods ? ".mod.toggle" : ".plugin.toggle"), renamed.getFileName().toString()
                    + (disabled ? " (enabled)" : " (disabled)"));
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
            audit(ex, serverId + (mods ? ".mod.delete" : ".plugin.delete"), target.getFileName().toString());
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
        try {
            Path backups = BackupManager.addonDirectory(child.config());
            String name = "addons-" + Addons.folder(mods) + "-" + stamp() + ".zip";
            Path out = backups.resolve(name);
            Path temp = out.resolveSibling(name + ".part");
            int count = 0;
            try {
                try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(temp))) {
                    for (Path file : listJars(folder)) {
                        zos.putNextEntry(new ZipEntry(file.getFileName().toString()));
                        Files.copy(file, zos);
                        zos.closeEntry();
                        count++;
                    }
                }
                Files.move(temp, out, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                Files.deleteIfExists(temp);
                throw e;
            }
            audit(ex, serverId + (mods ? ".mod.backup" : ".plugin.backup"), name);
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

    // ---- Phase 3: software catalog, version info/change, update badge, Java runtimes ----

    /** JSON error body used by every Phase 3 endpoint: {"ok":false,"stage":...,"error":...}. */
    private void sendError(HttpExchange ex, int code, String stage, String message) throws IOException {
        send(ex, code, "{\"ok\":false,\"stage\":\"" + jsonEscape(stage == null ? "" : stage)
                + "\",\"error\":\"" + jsonEscape(message == null ? "" : message) + "\"}");
    }

    /** GET-only guard for the read-only Phase 3 endpoints: anything else answers the structured 405 body. */
    private boolean requireGet(HttpExchange ex, String stage) throws IOException {
        if ("GET".equalsIgnoreCase(ex.getRequestMethod())) return true;
        sendError(ex, 405, stage, "GET only");
        return false;
    }

    /** Reads a request body up to {@code maxBytes}; null means the body was larger and nothing was consumed. */
    private static byte[] readBodyCapped(HttpExchange ex, int maxBytes) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] data = in.readNBytes(maxBytes + 1);
            return data.length > maxBytes ? null : data;
        }
    }

    /** True when the flat JSON body contains {@code "key":} (an empty string value still counts). */
    private static boolean hasJsonKey(String json, String key) {
        if (json == null) return false;
        int k = json.indexOf("\"" + key + "\"");
        if (k < 0) return false;
        return json.indexOf(':', k + key.length() + 2) >= 0;
    }

    /** Signals a conflict that must not be reported as a generic 500 (jar replaced under a live process). */
    private static final class ChangeConflictException extends IOException {
        ChangeConflictException(String message) { super(message); }
    }

    private static String roleName(Role role) {
        return role == null ? "" : role.name().toLowerCase(Locale.ROOT);
    }

    private static boolean canChangeVersion(Role role) {
        return role == Role.ADMIN || role == Role.OPERATOR;
    }

    /** MC version for the UI: detectMcVersion() returns "" when it cannot tell, the API says "unknown". */
    private static String mcVersionOf(ChildConfig cfg) {
        String v = detectMcVersion(cfg);
        return v == null || v.isBlank() ? "unknown" : v;
    }

    private static boolean knownVersion(String v) {
        return v != null && !v.isBlank() && !"unknown".equalsIgnoreCase(v.trim());
    }

    private static boolean isProxyType(String type) {
        return type != null && PROXY_TYPES.contains(type.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Reads at most {@code maxLines} lines / {@code maxBytes} from the head of a file. Server logs reach
     * hundreds of megabytes, so {@code Files.readAllLines()} must never be used for them.
     */
    private static List<String> readHeadLines(Path file, int maxLines, long maxBytes) {
        if (file == null || !Files.isRegularFile(file)) return List.of();
        int limit = Math.max(1, Math.min(maxLines, LOG_HEAD_LINES));
        List<String> lines = new ArrayList<>(limit);
        try (Reader reader = new BufferedReader(new InputStreamReader(
                Files.newInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder line = new StringBuilder(256);
            long consumed = 0;
            boolean eof = false;
            char[] buffer = new char[8192];
            while (lines.size() < limit && consumed < maxBytes) {
                int n = reader.read(buffer);
                if (n < 0) { eof = true; break; }
                for (int i = 0; i < n; i++) {
                    if (consumed >= maxBytes || lines.size() >= limit) break;
                    char c = buffer[i];
                    consumed++;
                    if (c == '\n') {
                        lines.add(line.toString());
                        line.setLength(0);
                    } else if (c != '\r') {
                        line.append(c);
                    }
                }
            }
            // Flush a pending final line only at real EOF. When the byte cap stopped us mid-line the
            // remainder is truncated, so reporting it as a line would be misleading.
            if (eof && lines.size() < limit && line.length() > 0) lines.add(line.toString());
        } catch (Exception ignored) {
            // Unreadable / not UTF-8: return what was read so far.
        }
        return lines;
    }

    private static List<String> logHead(ChildConfig cfg, int maxLines) {
        try {
            return readHeadLines(cfg.dir().resolve("logs").resolve("latest.log"), maxLines, LOG_HEAD_BYTES);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String versionHistory(ChildConfig cfg) {
        try {
            Path history = cfg.dir().resolve("version_history.json");
            return Files.isRegularFile(history) ? Files.readString(history) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String tidyVersion(String raw) {
        if (raw == null) return "";
        String v = raw.trim();
        while (!v.isEmpty() && ",;:()[]\"'".indexOf(v.charAt(v.length() - 1)) >= 0) {
            v = v.substring(0, v.length() - 1).trim();
        }
        return v;
    }

    /**
     * Version component of a server jar name: paper-1.21.4-232.jar -> 1.21.4-232,
     * BungeeCord-2107.jar -> 2107, velocity-3.4.0-SNAPSHOT-556.jar -> 3.4.0-SNAPSHOT-556.
     */
    private static String softwareVersionFromJar(String jarName) {
        if (jarName == null) return "";
        String base = jarName.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        base = base.replaceFirst("(?i)\\.jar(?:\\.disabled)?$", "").trim();
        if (base.isEmpty()) return "";
        Matcher branded = Pattern.compile("(?i)(?:^|[-_.])(paper|purpur|folia|velocity|waterfall|travertine|vanilla|fabric|quilt|spigot|bungeecord|nanolimbo)[-_.]?([0-9][A-Za-z0-9._+-]*)").matcher(base);
        if (branded.find()) {
            String v = tidyVersion(branded.group(2));
            if (!v.isEmpty()) return v;
        }
        Matcher mcBuild = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)(?:[-_](\\d+))?").matcher(base);
        if (mcBuild.find()) return mcBuild.group(2) == null ? mcBuild.group(1) : mcBuild.group(1) + "-" + mcBuild.group(2);
        Matcher build = Pattern.compile("(?:^|[^0-9])(\\d{1,7})$").matcher(base);
        return build.find() ? build.group(1) : "";
    }

    /** Raw current software version (1.21.4-232, 2107, v1.9.3); "unknown" when nothing is parseable. */
    private static String detectSoftwareVersion(ChildConfig cfg) {
        // The jar name is the most stable source (Paper/Purpur/BungeeCord ship the build in it).
        String fromJar = softwareVersionFromJar(cfg.jar());
        if (!fromJar.isEmpty()) return fromJar;
        Pattern brand = Pattern.compile("(?i)\\b(paper|purpur|folia|velocity|waterfall|travertine|spigot)\\b[^\\n]*?\\bversion\\s+([0-9][^\\s()]*)");
        // One bounded read of the log head, reused for both patterns (this runs on a polled endpoint).
        List<String> head = logHead(cfg, 120);
        for (String line : head) {
            Matcher m = brand.matcher(line);
            if (m.find()) {
                String v = tidyVersion(m.group(2));
                if (!v.isEmpty()) return v;
            }
        }
        for (String line : head) {
            Matcher m = Pattern.compile("(?i)(?:Starting minecraft server version|Loading Minecraft)\\s+(\\S+)").matcher(line);
            if (m.find()) {
                String v = tidyVersion(m.group(1));
                if (!v.isEmpty()) return v;
            }
        }
        Matcher history = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(versionHistory(cfg));
        if (history.find()) {
            String v = tidyVersion(history.group(1));
            if (!v.isEmpty()) return v;
        }
        String mc = detectMcVersion(cfg);
        return mc == null || mc.isBlank() ? "unknown" : mc;
    }

    // ---- software catalog --------------------------------------------------------------------

    private void handleSoftwareCatalog(HttpExchange ex) throws IOException {
        if (!requireGet(ex, "resolve")) return;
        if (!isAuthed(ex)) { sendError(ex, 401, "resolve", "Unauthorized"); return; }
        List<SoftwareRegistry.Software> catalog = registry.catalog();
        StringBuilder sb = new StringBuilder("{\"software\":[");
        for (int i = 0; i < catalog.size(); i++) {
            SoftwareRegistry.Software s = catalog.get(i);
            boolean supported = s.strategy() != SoftwareRegistry.Strategy.MANUAL;
            if (i > 0) sb.append(',');
            sb.append("{\"id\":\"").append(jsonEscape(s.id()))
              .append("\",\"name\":\"").append(jsonEscape(s.name()))
              .append("\",\"description\":\"").append(jsonEscape(s.description()))
              .append("\",\"category\":\"").append(s.category().name())
              .append("\",\"strategy\":\"").append(s.strategy().name())
              .append("\",\"supported\":").append(supported)
              .append(",\"reason\":\"").append(jsonEscape(supported ? "" : s.reason())).append("\"}");
        }
        send(ex, 200, sb.append("]}").toString());
    }

    // ---- version info / available ------------------------------------------------------------

    private void handleVersionInfo(HttpExchange ex) throws IOException {
        if (!requireGet(ex, "resolve")) return;
        String serverId = param(ex, "server", "");
        ChildProcess child = supervisor.child(serverId).orElse(null);
        if (child == null) { sendError(ex, 404, "resolve", "Server not found"); return; }
        Role role = roleFor(ex, serverId);
        if (role == null) { sendError(ex, 403, "resolve", "No permission"); return; }
        ChildConfig cfg = child.config();
        String mcVersion = mcVersionOf(cfg);
        String softwareVersion = detectSoftwareVersion(cfg);
        SoftwareRegistry.Software software = registry.find(cfg.type()).orElse(null);
        boolean supported = software != null && software.strategy() != SoftwareRegistry.Strategy.MANUAL;
        String reason = supported ? "" : (software == null
                ? "This server type is not managed by the version catalog. Install it manually."
                : (software.reason() == null ? "" : software.reason()));
        String javaPath = cfg.javaPath() == null ? "" : cfg.javaPath();
        String javaVersion = "";
        try { javaVersion = javaRuntimes.versionAt(ChildProcess.javaFor(cfg)); } catch (RuntimeException ignored) {}
        boolean restartRequired = false;
        try { restartRequired = child.javaRestartRequired(); } catch (RuntimeException ignored) {}
        String json = "{\"server\":\"" + jsonEscape(cfg.id())
                + "\",\"type\":\"" + jsonEscape(cfg.type())
                + "\",\"jar\":\"" + jsonEscape(cfg.jar())
                + "\",\"mcVersion\":\"" + jsonEscape(mcVersion)
                + "\",\"softwareVersion\":\"" + jsonEscape(softwareVersion)
                + "\",\"running\":" + child.isAlive()
                + ",\"players\":" + child.playerCount()
                + ",\"supported\":" + supported
                + ",\"reason\":\"" + jsonEscape(reason)
                + "\",\"modsSupported\":" + Addons.supportsMods(cfg.type())
                + ",\"hasModsFolder\":" + Files.isDirectory(cfg.dir().resolve("mods"))
                + ",\"javaPath\":\"" + jsonEscape(javaPath)
                + "\",\"javaVersion\":\"" + jsonEscape(javaVersion)
                + "\",\"role\":\"" + roleName(role)
                + "\",\"canChange\":" + canChangeVersion(role)
                + ",\"javaRestartRequired\":" + restartRequired + "}";
        send(ex, 200, json);
    }

    private void handleVersionAvailable(HttpExchange ex) throws IOException {
        if (!requireGet(ex, "resolve")) return;
        String serverId = param(ex, "server", "");
        ChildProcess child = supervisor.child(serverId).orElse(null);
        if (child == null) { sendError(ex, 404, "resolve", "Server not found"); return; }
        if (roleFor(ex, serverId) == null) { sendError(ex, 403, "resolve", "No permission"); return; }
        String softwareId = param(ex, "software", "").trim().toLowerCase(Locale.ROOT);
        if (softwareId.isEmpty()) { sendError(ex, 400, "resolve", "software is required"); return; }
        SoftwareRegistry.Software software = registry.find(softwareId).orElse(null);
        if (software == null) { sendError(ex, 400, "resolve", "Unknown software"); return; }

        ChildConfig cfg = child.config();
        String current = detectSoftwareVersion(cfg);
        String currentMc = mcVersionOf(cfg);
        boolean supported = software.strategy() != SoftwareRegistry.Strategy.MANUAL;
        String reason = supported ? "" : (software.reason() == null ? "" : software.reason());
        List<SoftwareRegistry.VersionOption> versions;
        if (!supported) {
            versions = List.of();
        } else {
            try {
                versions = registry.versions(software.id());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sendError(ex, 502, "resolve", "Version lookup interrupted");
                return;
            } catch (IOException e) {
                sendError(ex, 502, "resolve", "Could not load versions: " + e.getMessage());
                return;
            }
        }
        StringBuilder sb = new StringBuilder("{\"software\":\"").append(jsonEscape(software.id()))
                .append("\",\"versions\":[");
        for (int i = 0; i < versions.size(); i++) {
            SoftwareRegistry.VersionOption option = versions.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"id\":\"").append(jsonEscape(option.id()))
              .append("\",\"label\":\"").append(jsonEscape(option.label()))
              .append("\",\"subtitle\":\"").append(jsonEscape(option.subtitle())).append("\"}");
        }
        sb.append("],\"current\":\"").append(jsonEscape(current))
          .append("\",\"currentMc\":\"").append(jsonEscape(currentMc))
          .append("\",\"supported\":").append(supported)
          .append(",\"reason\":\"").append(jsonEscape(reason)).append("\"}");
        send(ex, 200, sb.toString());
    }

    // ---- version change ----------------------------------------------------------------------

    /** Bare file name under dir; null when the name is unsafe or escapes the server folder. */
    private static Path jarPathIn(Path dir, String name) {
        if (name == null) return null;
        String clean = name.replace('\\', '/');
        int slash = clean.lastIndexOf('/');
        if (slash >= 0) clean = clean.substring(slash + 1);
        clean = clean.trim();
        if (clean.isEmpty() || clean.equals(".") || clean.equals("..") || clean.indexOf('\u0000') >= 0) return null;
        Path base = dir.toAbsolutePath().normalize();
        Path target = base.resolve(clean).normalize();
        return target.getParent() != null && target.getParent().equals(base) ? target : null;
    }

    /** Copies a jar into {@code <dir>/backups/jars/<name>.<stamp>.bak}; null when it does not exist. */
    private static Path backupJar(Path dir, String jarName) throws IOException {
        Path source = jarPathIn(dir, jarName);
        if (source == null || !Files.isRegularFile(source)) return null;
        // Never follow a symlinked jar: the backup would silently capture a file outside the server folder.
        if (Files.isSymbolicLink(source)) {
            throw new IOException("Refusing to back up a symlinked jar: " + source.getFileName());
        }
        Path backups = dir.resolve("backups").resolve("jars");
        Files.createDirectories(backups);
        Path target = backups.resolve(source.getFileName().toString() + "." + stamp() + ".bak");
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
        return target;
    }

    private void handleVersionChange(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { sendError(ex, 405, "resolve", "POST only"); return; }
        // Auth first, then the body cap: an oversized body is a 413 even when its "server" key is unreadable.
        if (!isAuthed(ex)) { sendError(ex, 401, "resolve", "Unauthorized"); return; }
        byte[] rawBody = readBodyCapped(ex, MAX_SMALL_BODY);
        if (rawBody == null) { sendError(ex, 413, "resolve", "Request body is too large"); return; }
        String body = new String(rawBody, StandardCharsets.UTF_8);
        String rawServerId = extractJson(body, "server");
        if (rawServerId.isEmpty()) rawServerId = param(ex, "server", "");
        final String serverId = rawServerId;
        ChildProcess child = supervisor.child(serverId).orElse(null);
        if (child == null) { sendError(ex, 404, "resolve", "Server not found"); return; }
        if (!requireOwner(ex, serverId)) return;

        String softwareId = extractJson(body, "software").trim().toLowerCase(Locale.ROOT);
        String version = extractJson(body, "version").trim();
        if (softwareId.isEmpty()) { sendError(ex, 400, "resolve", "software is required"); return; }
        if (!SoftwareRegistry.validVersion(version)) { sendError(ex, 400, "resolve", "Invalid version"); return; }
        SoftwareRegistry.Software software = registry.find(softwareId).orElse(null);
        if (software == null) { sendError(ex, 400, "resolve", "Unknown software"); return; }
        if (software.strategy() == SoftwareRegistry.Strategy.MANUAL) {
            sendError(ex, 400, "resolve", software.reason() == null || software.reason().isBlank()
                    ? "This software must be installed manually." : software.reason());
            return;
        }
        // "backup" is accepted but Phase 3 always backs up the jar(s); only a full world backup is out of scope.
        if (!versionLocks.add(serverId)) {
            sendError(ex, 409, "stop", "A version change is already in progress for this server");
            return;
        }
        // Whether the server must be running when the request finishes (running now, or a crash restart pending).
        final boolean[] restart = { false };
        try {
            // Resolve while the server is still running, so bad input / offline upstream never stops it.
            SoftwareRegistry.InstallPlan plan;
            try {
                plan = registry.resolve(software.id(), version);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sendError(ex, 502, "resolve", "Version lookup interrupted");
                return;
            } catch (IOException e) {
                sendError(ex, 502, "resolve", "Could not resolve this version: " + e.getMessage());
                return;
            }
            if (plan.strategy() == SoftwareRegistry.Strategy.MANUAL) {
                sendError(ex, 400, "resolve", plan.reason() == null || plan.reason().isBlank()
                        ? "This software must be installed manually." : plan.reason());
                return;
            }

            // resolve() can take a while: sample the live child NOW, not before it, and cancel a queued crash
            // restart before touching any file so it cannot resurrect the old jar halfway through the change.
            ChildProcess active = supervisor.child(serverId).orElse(null);
            if (active == null || active != child) {
                sendError(ex, 409, "save", "The server was reconfigured during the version change; nothing was changed");
                return;
            }
            ChildConfig oldCfg = active.config();
            // A graceful stop is already in flight (the user just pressed Stop): do not restart behind their
            // back. Nothing has been touched yet, so just release the lock in the finally block.
            ChildState before = active.state();
            if (before == ChildState.STOPPING) {
                sendError(ex, 409, "stop", "The server is stopping; wait for it to finish");
                return;
            }
            restart[0] = active.isAlive() || before == ChildState.RESTARTING;
            supervisor.cancelPendingRestart(serverId);
            // Holder so the file-replacing lambda can re-check the instance that is current at that moment.
            final ChildProcess[] owner = { active };

            String plannedJar = plan.strategy() == SoftwareRegistry.Strategy.INSTALLER
                    ? (plan.outputJar() == null || plan.outputJar().isBlank() ? plan.jarName() : plan.outputJar())
                    : plan.jarName();
            String newJar = Addons.sanitizeJar(plannedJar);
            Path dir = oldCfg.dir();
            Path targetPath = newJar == null ? null : jarPathIn(dir, newJar);
            if (newJar == null || targetPath == null) {
                restoreAfterAbort(serverId, restart[0]);
                sendError(ex, 400, "resolve", "The upstream service did not provide a safe jar name");
                return;
            }
            if (Files.isDirectory(targetPath)) {
                restoreAfterAbort(serverId, restart[0]);
                sendError(ex, 400, "resolve", "The target jar path is a directory");
                return;
            }

            String oldJar = oldCfg.jar() == null ? "" : oldCfg.jar();
            Path oldJarPath = jarPathIn(dir, oldJar);
            boolean oldExisted = oldJarPath != null && Files.isRegularFile(oldJarPath);
            boolean targetExisted = Files.isRegularFile(targetPath);
            boolean sameJar = oldJarPath != null && oldJarPath.equals(targetPath);
            Path oldBackup;
            Path targetBackup;
            try {
                oldBackup = oldExisted ? backupJar(dir, oldJar) : null;
                targetBackup = (targetExisted && !sameJar) ? backupJar(dir, newJar) : null;
            } catch (IOException e) {
                restoreAfterAbort(serverId, restart[0]);
                sendError(ex, 500, "save", "Could not back up the existing jar: " + e.getMessage());
                return;
            }

            // Stop only when a process is actually alive. A process ChildProcess had to SIGKILL is a successful
            // stop once it is dead; abort only when it is *still* alive after a bounded wait.
            if (active.isAlive()) {
                active.stop();
                if (!waitUntilDead(active, STOP_DEAD_WAIT_MS)) {
                    restoreAfterAbort(serverId, restart[0]);
                    sendError(ex, 409, "stop",
                            "Could not stop the server (still alive after SIGKILL); the version was not changed");
                    return;
                }
                restart[0] = true;
            }

            String stage = "download";
            try {
                if (plan.strategy() == SoftwareRegistry.Strategy.DIRECT) {
                    downloadVerifiedJar(plan.url(), targetPath, plan.sha256(), plan.sha1(),
                            1024L * 1024L, MAX_JAR_BYTES, () -> installGuard(serverId, owner[0], restart));
                } else {
                    if (!allowedDownloadUrl(plan.installerUrl())) {
                        throw new IOException("The installer URL is not on the download allowlist");
                    }
                    Path installer = dir.resolve(".installer-" + System.currentTimeMillis() + ".jar");
                    try {
                        downloadVerifiedJar(plan.installerUrl(), installer, plan.installerSha256(), "",
                                1024L, MAX_INSTALLER_BYTES, null);
                        stage = "install";
                        if (!installGuard(serverId, active, restart)) {
                            throw new ChangeConflictException(
                                    "The server was started or reconfigured during the version change");
                        }
                        runInstaller(installerJava(oldCfg), installer, plan.installerArgs(), dir);
                        stage = "verify";
                        if (!Files.isRegularFile(targetPath) || Files.size(targetPath) <= 1024 || !hasZipMagic(targetPath)) {
                            throw new IOException("The installer did not create a usable " + newJar);
                        }
                    } finally {
                        try { Files.deleteIfExists(installer); } catch (IOException ignored) {}
                    }
                }
                stage = "save";
                // Build the new config from the *live* config (only type/jar change) so concurrent javaPath,
                // heap, port or silent edits made while we downloaded are not reverted.
                ChildProcess current = supervisor.child(serverId).orElse(null);
                if (current == null || current != active) {
                    throw new ChangeConflictException("The server was reconfigured during the version change");
                }
                ChildConfig liveCfg = current.config();
                if (!liveCfg.dir().equals(oldCfg.dir())) {
                    throw new ChangeConflictException("The server folder changed during the version change");
                }
                if (current.isAlive()) {
                    current.stop();
                    if (!waitUntilDead(current, STOP_DEAD_WAIT_MS)) {
                        throw new ChangeConflictException(
                                "The server restarted during the version change and could not be stopped");
                    }
                    restart[0] = true;
                }
                ChildConfig newCfg = new ChildConfig(liveCfg.id(), software.id(), liveCfg.dir(), newJar,
                        liveCfg.heap(), liveCfg.xmsEqualXmx(), liveCfg.port(), liveCfg.bind(),
                        liveCfg.stopStrategy(), liveCfg.stopCommand(), liveCfg.stopTimeoutSeconds(),
                        liveCfg.startOrder(), liveCfg.stopOrder(), liveCfg.restartOnCrash(),
                        liveCfg.restartBackoffSeconds(), liveCfg.extraFlags(), liveCfg.consoleEnabled(),
                        liveCfg.useAikarFlags(), liveCfg.silent(), liveCfg.javaPath(), liveCfg.autoStart());
                supervisor.updateChildConfig(serverId, newCfg);
                owner[0] = supervisor.child(serverId).orElse(null); // updateChildConfig installs a new instance
                persistConfig();
                stage = "start";
                if (restart[0]) startIfRegistered(serverId);
                boolean backedUp = oldBackup != null || targetBackup != null;
                audit(ex, serverId + ".version.change", software.id() + " " + version);
                String response = "{\"ok\":true,\"jar\":\"" + jsonEscape(newJar)
                        + "\",\"type\":\"" + jsonEscape(software.id())
                        + "\",\"restarted\":" + restart[0]
                        + ",\"backup\":" + backedUp + ",\"stage\":\"done\"}";
                // The change already happened; a broken client connection must not trigger a rollback.
                try { send(ex, 200, response); } catch (IOException ignored) {}
                return;
            } catch (ChangeConflictException e) {
                rollbackVersionChange(serverId, owner[0], oldCfg, oldJarPath, oldBackup, targetPath, targetBackup,
                        targetExisted, sameJar, restart[0]);
                sendError(ex, 409, stage, e.getMessage());
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                rollbackVersionChange(serverId, owner[0], oldCfg, oldJarPath, oldBackup, targetPath, targetBackup,
                        targetExisted, sameJar, restart[0]);
                sendError(ex, 500, stage, "The version change was interrupted");
                return;
            } catch (Exception e) {
                rollbackVersionChange(serverId, owner[0], oldCfg, oldJarPath, oldBackup, targetPath, targetBackup,
                        targetExisted, sameJar, restart[0]);
                sendError(ex, 500, stage, e.getMessage() == null ? "The version change failed" : e.getMessage());
                return;
            }
        } finally {
            versionLocks.remove(serverId);
        }
    }

    /** True once the child has no live process; polls so a SIGKILLed child that takes a moment to die counts. */
    private static boolean waitUntilDead(ChildProcess child, long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        while (child.isAlive() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return !child.isAlive();
            }
        }
        return !child.isAlive();
    }

    /**
     * True when jar files may be replaced: the same child is still registered and not running. A child that
     * came alive unexpectedly (manual start, missed crash restart) is stopped again and remembered for the
     * final restart; false means "abort without touching the jar".
     */
    private boolean installGuard(String serverId, ChildProcess expected, boolean[] restart) {
        ChildProcess live = supervisor.child(serverId).orElse(null);
        if (live == null || live != expected) return false;
        if (!live.isAlive()) {
            // An armed crash restart could fire during/after the swap; cancel it and remember that the
            // server is expected to be running again once the change completes.
            if (live.state() == ChildState.RESTARTING) {
                supervisor.cancelPendingRestart(serverId);
                restart[0] = true;
            }
            return true;
        }
        supervisor.cancelPendingRestart(serverId);
        live.stop();
        if (!waitUntilDead(live, STOP_DEAD_WAIT_MS)) return false;
        restart[0] = true;
        return true;
    }

    /** Restarts a child only while it is still registered; a child deleted mid-change must not throw. */
    private void startIfRegistered(String serverId) {
        try {
            if (supervisor.child(serverId).isPresent()) supervisor.requestLifecycle(serverId, "start");
        } catch (RuntimeException ignored) {}
    }

    /** On an early abort, undo what we already did: restart a child whose queued crash restart we cancelled. */
    private void restoreAfterAbort(String serverId, boolean shouldRun) {
        if (!shouldRun) return;
        try {
            ChildProcess live = supervisor.child(serverId).orElse(null);
            if (live != null && !live.isAlive()) startIfRegistered(serverId);
        } catch (RuntimeException ignored) {}
    }

    /** Panel default JVM for installers (the child's own Java may be an old Java 8 that cannot run them). */
    private String installerJava(ChildConfig cfg) {
        try {
            JavaRuntimes.JavaInfo def = javaRuntimes.defaultJava();
            if (def != null && def.path() != null && !def.path().isBlank()) return def.path();
        } catch (RuntimeException ignored) {}
        return ChildProcess.javaFor(cfg);
    }

    /** Copies a backup back into place, skipping the copy when the file is already byte-identical. */
    private static void restoreFromBackup(Path backup, Path target) throws IOException {
        if (backup == null || target == null) return;
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && Files.mismatch(backup, target) == -1) return;
        Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Restores the previous jars/config after a failure that happened once the server had been stopped. */
    private void rollbackVersionChange(String serverId, ChildProcess active, ChildConfig oldCfg, Path oldJarPath,
                                       Path oldBackup, Path targetPath, Path targetBackup, boolean targetExisted,
                                       boolean sameJar, boolean restart) {
        try {
            if (sameJar) {
                if (oldBackup != null) restoreFromBackup(oldBackup, targetPath);
                else if (!targetExisted) Files.deleteIfExists(targetPath);
            } else {
                if (oldBackup != null && oldJarPath != null) restoreFromBackup(oldBackup, oldJarPath);
                if (targetBackup != null) restoreFromBackup(targetBackup, targetPath);
                else if (!targetExisted) Files.deleteIfExists(targetPath);
            }
        } catch (Exception e) {
            ConsoleOutput.root("version rollback failed: could not restore the jar files: " + e.getMessage());
        }
        try {
            ChildProcess live = supervisor.child(serverId).orElse(null);
            // Only touch a config we are still responsible for; another actor may have replaced the child.
            if (live != null && live == active) {
                supervisor.updateChildConfig(serverId, oldCfg);
                persistConfig();
            }
        } catch (Exception e) {
            ConsoleOutput.root("version rollback failed: could not restore the previous config: " + e.getMessage());
        }
        if (restart) startIfRegistered(serverId);
    }

    /** Exact-scheme https + SoftwareRegistry host allowlist; no user-supplied URL ever reaches the client. */
    private static boolean allowedDownloadUrl(String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            URI uri = URI.create(raw);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            if (uri.getUserInfo() != null) return false;
            String host = uri.getHost();
            if (host == null) return false;
            if (!SoftwareRegistry.downloadHosts().contains(host.toLowerCase(Locale.ROOT))) return false;
            int port = uri.getPort();
            return port == -1 || port == 443;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Downloads a jar to {@code target + ".download"}, verifies the host (also after redirects), size,
     * ZIP magic and the optional SHA-256/SHA-1, then atomically moves it into place. The streamed size is
     * capped at {@code maxBytes}; {@code installGuard} runs immediately before the move so a jar is never
     * replaced under a process that came back alive.
     */
    private void downloadVerifiedJar(String url, Path target, String sha256, String sha1, long minBytes,
                                     long maxBytes, BooleanSupplier installGuard)
            throws IOException, InterruptedException {
        if (!allowedDownloadUrl(url)) throw new IOException("The download URL is not on the allowlist");
        Path tmp = target.resolveSibling(target.getFileName() + ".download");
        try {
            Files.deleteIfExists(tmp);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", panelUserAgent())
                    .timeout(Duration.ofMinutes(10))
                    .build();
            HttpResponse<InputStream> res = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = res.body()) {
                if (res.statusCode() != 200) throw new IOException("Download returned HTTP " + res.statusCode());
                if (!allowedDownloadUrl(res.uri().toString())) {
                    throw new IOException("Download was redirected to a disallowed host");
                }
                String limitError = "Download is larger than the " + (maxBytes / (1024L * 1024L)) + " MB limit";
                Optional<String> declared = res.headers().firstValue("Content-Length");
                if (declared.isPresent()) {
                    try {
                        if (Long.parseLong(declared.get().trim()) > maxBytes) throw new IOException(limitError);
                    } catch (NumberFormatException ignored) {
                        // A malformed Content-Length is caught by the streamed cap below.
                    }
                }
                long total = 0;
                byte[] buffer = new byte[64 * 1024];
                try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        total += n;
                        if (total > maxBytes) throw new IOException(limitError);
                        out.write(buffer, 0, n);
                    }
                }
            }
            long size = Files.size(tmp);
            if (size <= minBytes) throw new IOException("Downloaded file is too small (" + size + " bytes)");
            if (!hasZipMagic(tmp)) throw new IOException("Downloaded file is not a jar (ZIP magic missing)");
            if (sha256 != null && !sha256.isBlank()) {
                if (!digestHex(tmp, "SHA-256").equalsIgnoreCase(sha256.trim())) {
                    throw new IOException("SHA-256 checksum mismatch");
                }
            } else if (sha1 != null && !sha1.isBlank()) {
                if (!digestHex(tmp, "SHA-1").equalsIgnoreCase(sha1.trim())) {
                    throw new IOException("SHA-1 checksum mismatch");
                }
            }
            if (installGuard != null && !installGuard.getAsBoolean()) {
                throw new ChangeConflictException("The server was started or reconfigured during the version change");
            }
            moveAtomically(tmp, target);
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }

    private static void moveAtomically(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean hasZipMagic(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return in.read() == 'P' && in.read() == 'K';
        } catch (IOException e) {
            return false;
        }
    }

    private static String digestHex(Path file, String algorithm) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(algorithm + " is not available", e);
        }
    }

    /**
     * Runs an installer jar in the server folder: 10-minute timeout, bounded output, forced kill on
     * timeout, non-zero exit is an error. The caller deletes the installer in a finally block.
     */
    private String runInstaller(String java, Path installer, List<String> args, Path workingDir)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(java);
        command.add("-jar");
        command.add(installer.toAbsolutePath().toString());
        if (args != null) command.addAll(args);
        final Process process;
        try {
            process = new ProcessBuilder(command)
                    .directory(workingDir.toFile())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            throw new IOException("Could not start the installer: " + e.getMessage(), e);
        }
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    synchronized (output) {
                        int room = 32_768 - output.length();
                        if (room > 0) output.append(new String(buffer, 0, Math.min(room, n), StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException ignored) {}
        }, "version-installer");
        reader.setDaemon(true);
        reader.start();
        boolean finished;
        try {
            finished = process.waitFor(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw e;
        }
        if (!finished) {
            process.destroyForcibly();
            try { process.waitFor(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            throw new IOException("The installer timed out after 10 minutes");
        }
        try { reader.join(3_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (process.exitValue() != 0) {
            String tail;
            synchronized (output) {
                String text = output.toString().trim();
                tail = text.length() > 800 ? text.substring(text.length() - 800) : text;
            }
            throw new IOException("The installer failed (exit " + process.exitValue() + ")"
                    + (tail.isEmpty() ? "" : ": " + tail));
        }
        return output.toString();
    }

    // ---- update badge ------------------------------------------------------------------------

    private record UpdateResult(String latest, boolean available, boolean checked, String error) {}

    private void handleUpdateCheck(HttpExchange ex) throws IOException {
        if (!requireGet(ex, "resolve")) return;
        String serverId = param(ex, "server", "");
        ChildProcess child = supervisor.child(serverId).orElse(null);
        if (child == null) { sendError(ex, 404, "resolve", "Server not found"); return; }
        if (roleFor(ex, serverId) == null) { sendError(ex, 403, "resolve", "No permission"); return; }
        ChildConfig cfg = child.config();
        String softwareId = cfg.type() == null ? "" : cfg.type().trim().toLowerCase(Locale.ROOT);
        // Key on the jar (cheap) so a cache hit never has to read version_history.json / latest.log again.
        String cacheKey = serverId + "|" + softwareId + "|" + (cfg.jar() == null ? "" : cfg.jar());
        long now = System.currentTimeMillis();
        Cached cached = updateCache.get(cacheKey);
        if (cached != null && now - cached.at() < UPDATE_CACHE_MS) {
            send(ex, 200, cached.body());
            return;
        }
        String current = detectSoftwareVersion(cfg);
        UpdateResult result;
        try {
            result = computeUpdate(registry.find(softwareId).orElse(null), current, cfg);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            send(ex, 200, updateJson(softwareId, current, "", false, false, "Update check interrupted"));
            return;
        } catch (Exception e) {
            // Never let the badge endpoint 5xx; it is polled by the UI.
            send(ex, 200, updateJson(softwareId, current, "", false, false,
                    "Could not check for updates: " + e.getMessage()));
            return;
        }
        String json = updateJson(softwareId, current, result.latest(), result.available(),
                result.checked(), result.error());
        if (result.checked()) {
            if (updateCache.size() > 200) updateCache.clear();
            updateCache.put(cacheKey, new Cached(now, json));
        }
        send(ex, 200, json);
    }

    private UpdateResult computeUpdate(SoftwareRegistry.Software software, String current, ChildConfig cfg)
            throws IOException, InterruptedException {
        if (software == null) {
            return new UpdateResult("", false, false, "This server type is not managed by the version catalog.");
        }
        if (software.strategy() == SoftwareRegistry.Strategy.MANUAL) {
            return new UpdateResult("", false, false, software.reason() == null || software.reason().isBlank()
                    ? "This software must be installed manually." : software.reason());
        }
        List<SoftwareRegistry.VersionOption> versions = registry.versions(software.id());
        if (versions.isEmpty()) return new UpdateResult("", false, false, "No versions are available for this software.");
        String id = software.id();
        String latestMc = versions.get(0).id();

        if ("fabric".equals(id) || "quilt".equals(id)) {
            // Loader versions follow the latest MC version they support.
            String currentMc = mcVersionOf(cfg);
            boolean update = knownVersion(currentMc) && Addons.compareVersions(latestMc, currentMc) > 0;
            return new UpdateResult(latestMc, update, true, "");
        }
        if ("nanolimbo".equals(id)) {
            boolean update = knownVersion(current) && Addons.compareVersions(latestMc, current) > 0;
            return new UpdateResult(latestMc, update, true, "");
        }
        if ("bungeecord".equals(id)) {
            SoftwareRegistry.InstallPlan plan = registry.resolve(id, "latest");
            String latest = softwareVersionFromJar(plan.jarName());
            boolean update = knownVersion(current) && !latest.isEmpty()
                    && Addons.compareVersions(latest, current) > 0;
            return new UpdateResult(latest.isEmpty() ? "latest" : latest, update, true, "");
        }

        String currentMc = mcVersionOf(cfg);
        if (knownVersion(currentMc)) {
            int cmp = Addons.compareVersions(latestMc, currentMc);
            if (cmp > 0) return new UpdateResult(latestMc, true, true, "");
            if (cmp < 0) return new UpdateResult(latestMc, false, true, "");
        }
        // Same MC version (or a proxy with no MC version): compare builds through the resolved jar name.
        SoftwareRegistry.InstallPlan plan = registry.resolve(id, latestMc);
        String latest = softwareVersionFromJar(plan.jarName());
        if (latest.isEmpty()) latest = latestMc;
        if (!knownVersion(current)) {
            return new UpdateResult(latest, false, false, "Could not detect the current version.");
        }
        boolean update = Addons.compareVersions(latest, current) > 0;
        return new UpdateResult(latest, update, true, "");
    }

    private static String updateJson(String software, String current, String latest,
                                     boolean available, boolean checked, String error) {
        return "{\"software\":\"" + jsonEscape(software)
                + "\",\"current\":\"" + jsonEscape(current)
                + "\",\"latest\":\"" + jsonEscape(latest)
                + "\",\"updateAvailable\":" + available
                + ",\"checked\":" + checked
                + ",\"error\":\"" + jsonEscape(error == null ? "" : error) + "\"}";
    }

    // ---- Java runtimes -----------------------------------------------------------------------

    private static void appendJavaInfos(StringBuilder sb, List<JavaRuntimes.JavaInfo> infos) {
        for (int i = 0; i < infos.size(); i++) {
            JavaRuntimes.JavaInfo info = infos.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"id\":\"").append(jsonEscape(info.id()))
              .append("\",\"path\":\"").append(jsonEscape(info.path()))
              .append("\",\"version\":\"").append(jsonEscape(info.version()))
              .append("\",\"major\":").append(info.major())
              .append(",\"source\":\"").append(jsonEscape(info.source())).append("\"}");
        }
    }

    private void handleJavaRuntimes(HttpExchange ex) throws IOException {
        if (!requireGet(ex, "resolve")) return;
        if (!isAuthed(ex)) { sendError(ex, 401, "resolve", "Unauthorized"); return; }
        String serverId = param(ex, "server", "");
        ChildProcess child = null;
        if (!serverId.isEmpty()) {
            child = supervisor.child(serverId).orElse(null);
            if (child == null) { sendError(ex, 404, "resolve", "Server not found"); return; }
            if (!requireOwner(ex, serverId)) return;
        } else if (!isAdmin(ex)) {
            sendError(ex, 403, "resolve", "Admin only");
            return;
        }
        int recommended = child == null ? 0 : JavaRuntimes.recommendedMajor(detectMcVersion(child.config()));
        JavaRuntimes.JavaInfo def;
        List<JavaRuntimes.JavaInfo> detected;
        List<JavaRuntimes.JavaInfo> managed;
        try {
            def = javaRuntimes.defaultJava();
            detected = javaRuntimes.detect();
            managed = javaRuntimes.managed();
        } catch (RuntimeException e) {
            sendError(ex, 500, "resolve", "Could not inspect Java runtimes: " + e.getMessage());
            return;
        }
        StringBuilder sb = new StringBuilder("{\"default\":{\"path\":\"").append(jsonEscape(def.path()))
                .append("\",\"version\":\"").append(jsonEscape(def.version()))
                .append("\"},\"detected\":[");
        appendJavaInfos(sb, detected);
        sb.append("],\"managed\":[");
        appendJavaInfos(sb, managed);
        sb.append("],\"recommendedMajor\":").append(recommended)
          .append(",\"server\":\"").append(jsonEscape(serverId)).append("\"}");
        send(ex, 200, sb.toString());
    }

    private void handleJavaDownload(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { sendError(ex, 405, "download", "POST only"); return; }
        if (!isAdmin(ex)) { sendError(ex, 403, "download", "Admin only"); return; }
        byte[] rawBody = readBodyCapped(ex, MAX_SMALL_BODY);
        if (rawBody == null) { sendError(ex, 413, "download", "Request body is too large"); return; }
        String body = new String(rawBody, StandardCharsets.UTF_8);
        String raw = extractJson(body, "major");
        if (raw.isEmpty()) raw = param(ex, "major", "");
        int major;
        try {
            major = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            sendError(ex, 400, "download", "major must be a number");
            return;
        }
        if (major < 8 || major > 30) { sendError(ex, 400, "download", "Unsupported Java major"); return; }
        try {
            JavaRuntimes.JavaInfo info = javaRuntimes.download(major);
            send(ex, 200, "{\"ok\":true,\"id\":\"" + jsonEscape(info.id())
                    + "\",\"path\":\"" + jsonEscape(info.path())
                    + "\",\"version\":\"" + jsonEscape(info.version()) + "\"}");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendError(ex, 500, "download", "Java download interrupted");
        } catch (IOException e) {
            sendError(ex, 502, "download", "Java download failed: " + e.getMessage());
        } catch (RuntimeException e) {
            sendError(ex, 500, "download", "Java download failed: " + e.getMessage());
        }
    }

    private void handleJavaDelete(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("DELETE")) { sendError(ex, 405, "save", "DELETE only"); return; }
        if (!isAdmin(ex)) { sendError(ex, 403, "save", "Admin only"); return; }
        String path = ex.getRequestURI().getPath();
        String id = path.substring(path.lastIndexOf('/') + 1);
        if (!id.isEmpty() && id.equals("download")) { sendError(ex, 405, "save", "DELETE only"); return; }
        if (id.isEmpty()) { sendError(ex, 400, "save", "Java runtime id is required"); return; }
        try {
            boolean deleted = javaRuntimes.delete(id,
                    supervisor.children().stream().map(ChildProcess::config).toList());
            if (!deleted) { sendError(ex, 404, "save", "Java runtime not found"); return; }
            send(ex, 200, "{\"ok\":true,\"id\":\"" + jsonEscape(id) + "\"}");
        } catch (IOException e) {
            String message = e.getMessage() == null ? "Could not delete the Java runtime" : e.getMessage();
            int code = message.toLowerCase(Locale.ROOT).contains("in use") ? 409 : 400;
            sendError(ex, code, "save", message);
        }
    }

    /** POST /api/servers/{id}/java: persists the path without touching a running process. */
    private void handleServerJava(HttpExchange ex, String serverId) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { sendError(ex, 405, "save", "POST only"); return; }
        ChildProcess child = supervisor.child(serverId).orElse(null);
        if (child == null) { sendError(ex, 404, "resolve", "Server not found"); return; }
        if (!requireOwner(ex, serverId)) return;
        byte[] rawBody = readBodyCapped(ex, MAX_SMALL_BODY);
        if (rawBody == null) { sendError(ex, 413, "save", "Request body is too large"); return; }
        String body = new String(rawBody, StandardCharsets.UTF_8);
        // An unparseable body must not be treated as "clear javaPath": the path key has to be present.
        if (!hasJsonKey(body, "path")) { sendError(ex, 400, "save", "Missing path"); return; }
        String raw = extractJson(body, "path");
        String clean;
        try {
            clean = javaRuntimes.validate(raw);
        } catch (IOException e) {
            sendError(ex, 400, "verify", e.getMessage() == null ? "Invalid Java path" : e.getMessage());
            return;
        }
        // validate() can spend seconds probing the runtime; apply the path to the LIVE config atomically so a
        // concurrent version change (which replaces the child) is not reverted by our stale snapshot.
        try {
            supervisor.setJavaPath(serverId, clean);
        } catch (RuntimeException e) {
            sendError(ex, 500, "save", "Could not apply the Java path: " + e.getMessage());
            return;
        }
        ChildProcess live = supervisor.child(serverId).orElse(null);
        if (live == null) {
            sendError(ex, 409, "save", "The server was removed while the Java path was being saved");
            return;
        }
        try {
            persistConfig();
        } catch (IOException e) {
            sendError(ex, 500, "save", "Could not save the Java path: " + e.getMessage());
            return;
        }
        String version = "";
        try {
            version = clean.isEmpty() ? javaRuntimes.defaultJava().version() : javaRuntimes.versionAt(clean);
        } catch (RuntimeException ignored) {}
        boolean restartRequired = live.javaRestartRequired();
        send(ex, 200, "{\"ok\":true,\"path\":\"" + jsonEscape(clean)
                + "\",\"version\":\"" + jsonEscape(version)
                + "\",\"restartRequired\":" + restartRequired + "}");
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
            audit(ex, id + ".config.write", fileName);
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
                configRestartRequired.add(serverId);
                audit(ex, serverId + ".config.save", fileName);
                send(ex, 200, "{\"saved\":true,\"mode\":\"raw\",\"restartRequired\":true}");
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
            audit(ex, serverId + ".config.save", fileName);
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
            sb.append("{\"username\":\"").append(jsonEscape(u.username())).append("\",\"role\":\"").append(jsonEscape(u.role())).append("\",\"servers\":\"").append(jsonEscape(String.join(",", u.allowedServers()))).append("\",\"allowPanelIpView\":").append(u.allowPanelIpView()).append(",\"allowConsoleIpView\":").append(u.allowConsoleIpView()).append("}");
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
        boolean allowPanel = !"false".equals(extractJson(body, "allowPanelIpView"));
        boolean allowConsole = !"false".equals(extractJson(body, "allowConsoleIpView"));
        newUsers.add(new ServerConfig.UserConfig(user, hash, "user", allowed, java.util.Map.of(), allowPanel, allowConsole));

        ServerConfig old = config.server();
        ServerConfig updated = new ServerConfig(old.bind(), old.publicIp(), old.port(), old.sessionTtlSeconds(), old.loginRateLimitPerIpPerMinute(), old.loginLockoutAfterFailures(), old.loginLockoutSeconds(), old.passwordHash(), old.trustedProxies(), old.diskMaxGb(), old.backupRetention(), old.webhookUrl(), old.publicStatus(), newUsers);
        saveConfig(new RootConfig(updated, config.children(), config.baseDir()));
        audit(ex, "users.create", user);
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
        String role = extractJson(body, "role");
        if (!Set.of("viewer", "operator", "owner").contains(role)) role = "operator";
        Map<String, String> roles = new java.util.LinkedHashMap<>();
        if (!targetServer.isBlank()) roles.put(targetServer, role);
        newUsers.add(new ServerConfig.UserConfig(u.username(), u.passwordHash(), u.role(), newAllowed, roles, u.allowPanelIpView(), u.allowConsoleIpView()));
            } else {
                newUsers.add(u);
            }
        }

        ServerConfig old = config.server();
        ServerConfig updated = new ServerConfig(old.bind(), old.publicIp(), old.port(), old.sessionTtlSeconds(), old.loginRateLimitPerIpPerMinute(), old.loginLockoutAfterFailures(), old.loginLockoutSeconds(), old.passwordHash(), old.trustedProxies(), old.diskMaxGb(), old.backupRetention(), old.webhookUrl(), old.publicStatus(), newUsers);
        saveConfig(new RootConfig(updated, config.children(), config.baseDir()));
        audit(ex, "users.assign", targetUser + ": " + action + (targetServer.isBlank() ? "" : " " + targetServer));
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
