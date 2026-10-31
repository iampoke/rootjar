package rootjar.config;

import rootjar.console.ConsoleOutput;
import rootjar.supervisor.ChildProcess;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Discovery, download and deletion of Java runtimes used by children.
 *
 * <p>Detected runtimes are never modified. Managed runtimes live under {@code <baseDir>/java/<id>} and are
 * described in {@code <baseDir>/java/runtimes.json}; that file is only a cache - the directory scan is the
 * source of truth, so a missing or corrupt file never breaks boot. All paths handed to a child must be an
 * absolute path to a {@code java}/{@code java.exe} executable (or the literal {@code "java"} for PATH).</p>
 *
 * <p>Downloads come from Adoptium's v3 API over HTTPS only; the archive is SHA-256 checked before it is
 * extracted and the extraction refuses absolute/.. archive entries. A managed runtime that the panel (or a
 * child) is using is never replaced or deleted. Every filesystem failure here is reported as an
 * {@link IOException} so the HTTP layer can turn it into an error body.</p>
 */
public final class JavaRuntimes {

    public record JavaVersion(int major, int minor, String full) {}

    public record JavaInfo(String id, String path, String version, int major, String source) {}

    /** Hosts a Temurin package link may point at (GitHub is the redirect source Adoptium uses). */
    private static final Set<String> DOWNLOAD_HOSTS = Set.of(
            "api.adoptium.net", "github.com", "objects.githubusercontent.com",
            "release-assets.githubusercontent.com");
    private static final long PROBE_TIMEOUT_MS = 5_000;
    private static final int MAX_PROBE_BYTES = 32 * 1024;
    private static final int VERSION_CACHE_LIMIT = 256;
    private static final Pattern VERSION_QUOTED = Pattern.compile("version\\s+\"([^\"]+)\"");
    private static final Pattern VERSION_PLAIN = Pattern.compile("version\\s+(\\d+(?:\\.\\d+)*)");
    private static final Pattern BARE_VERSION = Pattern.compile("(\\d+(?:\\.\\d+)+)");
    private static final Pattern NUMBER_PAIR = Pattern.compile("(\\d+)(?:\\.(\\d+))?");
    private static final Pattern MC_VERSION = Pattern.compile("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private final Path baseDir;
    private final Path javaDir;
    private final Path runtimesFile;
    private final HttpClient client;

    /** Full version strings by path; only successful probes are cached. */
    private final Map<String, String> versionCache = new ConcurrentHashMap<>();
    /** Serializes downloads so two admin requests cannot write the same staging folder. */
    private final Object downloadLock = new Object();
    /** Guards runtimes.json only; never held while probing a JVM or downloading. */
    private final Object fileLock = new Object();

    public JavaRuntimes(Path baseDir, HttpClient client) {
        this.baseDir = (baseDir == null ? Paths.get("").toAbsolutePath() : baseDir).toAbsolutePath().normalize();
        this.javaDir = this.baseDir.resolve("java");
        this.runtimesFile = this.javaDir.resolve("runtimes.json");
        this.client = client != null ? client : HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ------------------------------------------------------------------ discovery

    /** The JVM running the panel. Never returns null; version/major are 0/"" when the probe fails. */
    public JavaInfo defaultJava() {
        Path exe = panelJavaPath();
        if (exe == null || !Files.isRegularFile(exe)) {
            String version = versionAt("java");
            return new JavaInfo("panel", "java", version, parseVersionOutput(version).major(), "panel");
        }
        String path = exe.toString();
        String version = versionAt(path);
        return new JavaInfo("panel", path, version, parseVersionOutput(version).major(), "panel");
    }

    /** JVMs installed on the host (panel JVM, JAVA_HOME, distro/OS folders, PATH). Broken entries are omitted. */
    public List<JavaInfo> detect() {
        Map<String, Candidate> found = new LinkedHashMap<>();
        addCandidate(found, panelJavaPath(), "panel", "panel");
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            addCandidate(found, binJava(javaHome), "java-home", "JAVA_HOME");
        }

        if (isWindows()) {
            String programFiles = System.getenv("ProgramFiles");
            if (programFiles == null || programFiles.isBlank()) programFiles = "C:\\Program Files";
            try {
                for (String vendor : List.of("Java", "Eclipse Adoptium", "Amazon Corretto", "Zulu")) {
                    scanWindowsVendor(found, Paths.get(programFiles, vendor), "win");
                }
                String userProfile = System.getenv("USERPROFILE");
                if (userProfile != null && !userProfile.isBlank()) {
                    scanWindowsVendor(found, Paths.get(userProfile, ".jdks"), "jdks");
                }
            } catch (RuntimeException ignored) {
                // Invalid environment path: keep whatever was found so far.
            }
            scanPathCommand(found, "where");
        } else {
            scanJvmDir(found, Paths.get("/usr/lib/jvm"), "jvm");
            scanMacDir(found, Paths.get("/Library/Java/JavaVirtualMachines"), "mac");
            scanPathCommand(found, "which");
        }

        List<JavaInfo> out = new ArrayList<>();
        Set<String> usedIds = new HashSet<>();
        for (Candidate candidate : found.values()) {
            String version = versionAt(candidate.path());
            if (version.isEmpty()) continue; // not a working JVM for this OS/arch
            int major = parseVersionOutput(version).major();
            out.add(new JavaInfo(uniqueId(candidate.id(), usedIds), candidate.path(), version, major, candidate.source()));
        }
        out.sort(Comparator.comparingInt(JavaInfo::major).thenComparing(JavaInfo::path));
        return out;
    }

    /** Runtimes installed by {@link #download(int)} under {@code <baseDir>/java}. */
    public List<JavaInfo> managed() {
        Map<String, JavaInfo> byId = new LinkedHashMap<>();
        Set<String> seenPaths = new HashSet<>();
        for (Map<String, Object> record : readRuntimes()) {
            String id = text(record.get("id"));
            String rawPath = text(record.get("path"));
            if (!safeId(id)) continue;
            Path exe = safeManagedPath(rawPath);
            if (exe == null || !Files.isRegularFile(exe)) continue;
            if (!seenPaths.add(exe.toString())) continue;
            String recorded = text(record.get("version"));
            String probed = versionAt(exe.toString());
            String full = probed.isEmpty() ? recorded : probed;
            int major = parseVersionOutput(full).major();
            if (major == 0) {
                Object value = record.get("major");
                if (value instanceof Number n) major = n.intValue();
            }
            byId.putIfAbsent(id, new JavaInfo(id, exe.toString(), full, major, "managed"));
        }
        if (Files.isDirectory(javaDir)) {
            try (Stream<Path> stream = Files.list(javaDir)) {
                for (Path dir : stream.sorted().toList()) {
                    if (!Files.isDirectory(dir)) continue;
                    String id = dir.getFileName().toString();
                    if (!safeId(id)) continue;
                    Path exe = dir.resolve("bin").resolve(javaExeName());
                    if (!Files.isRegularFile(exe) || byId.containsKey(id)) continue;
                    if (!seenPaths.add(exe.toString())) continue;
                    String version = versionAt(exe.toString());
                    byId.put(id, new JavaInfo(id, exe.toString(), version,
                            parseVersionOutput(version).major(), "managed"));
                }
            } catch (IOException ignored) {
                // An unreadable java dir just means "no managed runtimes".
            }
        }
        List<JavaInfo> out = new ArrayList<>(byId.values());
        out.sort(Comparator.comparingInt(JavaInfo::major).thenComparing(JavaInfo::id));
        return out;
    }

    // ------------------------------------------------------------------ versions

    /** Cached {@code <path> -version} full version string; "" when the path is not a usable JVM. */
    public String versionAt(String path) {
        if (path == null || path.isBlank()) return "";
        String key = path.trim();
        String cached = versionCache.get(key);
        if (cached != null) return cached;
        String output = runCapture(List.of(key, "-version"), PROBE_TIMEOUT_MS);
        JavaVersion parsed = parseVersionOutput(output);
        // A binary that merely prints `version "anything"` is not a JVM: never cache a major-0 probe.
        if (parsed.major() <= 0 || parsed.full().isEmpty()) return "";
        if (versionCache.size() >= VERSION_CACHE_LIMIT) versionCache.clear();
        versionCache.put(key, parsed.full());
        return parsed.full();
    }

    /** Drops cached probe results for a path (or for everything below it, for a runtime directory). */
    private void invalidateVersionCache(Path path) {
        if (path == null) return;
        String prefix = path.toAbsolutePath().normalize().toString();
        versionCache.keySet().removeIf(key -> key.equals(prefix)
                || key.startsWith(prefix + "/") || key.startsWith(prefix + "\\"));
    }

    /**
     * Parses {@code java -version} output. Handles legacy {@code 1.8.0_402} (major 8), and modern
     * {@code 21.0.13} / {@code 25.0.5}. Returns major/minor 0 with an empty {@code full} when there is no
     * version string at all, so callers never have to null-check.
     */
    public static JavaVersion parseVersionOutput(String versionOutput) {
        if (versionOutput == null || versionOutput.isBlank()) return new JavaVersion(0, 0, "");
        String full;
        Matcher quoted = VERSION_QUOTED.matcher(versionOutput);
        if (quoted.find()) {
            full = quoted.group(1).trim();
        } else {
            Matcher plain = VERSION_PLAIN.matcher(versionOutput);
            if (plain.find()) {
                full = plain.group(1).trim();
            } else {
                // Also accept a bare version string ("21.0.13", "21.0.5+11.0.LTS") so callers can re-parse
                // the value they got from versionAt().
                Matcher bare = BARE_VERSION.matcher(versionOutput);
                if (!bare.find()) return new JavaVersion(0, 0, "");
                full = bare.group(1).trim();
            }
        }
        String numeric = full.startsWith("1.") ? full.substring(2) : full;
        Matcher numbers = NUMBER_PAIR.matcher(numeric);
        if (!numbers.find()) return new JavaVersion(0, 0, full);
        int major;
        try {
            major = Integer.parseInt(numbers.group(1));
        } catch (NumberFormatException e) {
            return new JavaVersion(0, 0, full);
        }
        int minor = 0;
        if (numbers.group(2) != null) {
            try {
                minor = Integer.parseInt(numbers.group(2));
            } catch (NumberFormatException ignored) {
                minor = 0;
            }
        }
        return new JavaVersion(major, minor, full);
    }

    /** Java major a Minecraft version needs. Falls back to 21 for future 1.x and 0 for unparseable input. */
    public static int recommendedMajor(String mcVersion) {
        if (mcVersion == null) return 0;
        Matcher m = MC_VERSION.matcher(mcVersion.trim());
        if (!m.find()) return 0;
        int a;
        try {
            a = Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
        int b = m.group(2) == null ? 0 : parseIntOrZero(m.group(2));
        int c = m.group(3) == null ? 0 : parseIntOrZero(m.group(3));
        if (a >= 26) return 25;       // new snapshot scheme (26.x)
        if (a != 1) return 0;
        if (b <= 16) return 8;        // <= 1.16
        if (b <= 19) return 17;       // 1.17 - 1.19
        if (b == 20) return c >= 5 ? 21 : 17; // 1.20.0-4 -> 17, 1.20.5+ -> 21
        return 21;                    // 1.21.x and later 1.x
    }

    // ------------------------------------------------------------------ validation

    /**
     * Validates a user-supplied java path. Returns "" for blank (panel default), the literal {@code "java"}
     * for PATH, or the normalised absolute executable path. Throws IOException when the path is unusable.
     */
    public String validate(String raw) throws IOException {
        if (raw == null || raw.isBlank()) return "";
        String value = raw.trim();
        if (value.equalsIgnoreCase("java")) return "java";
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw new IOException("java path must not contain control characters");
            }
        }
        // ConfigSaver writes this value unquoted (quoting would break Windows backslashes), and the YAML
        // reader strips everything from " #" on as a comment. Reject it here so the path cannot silently
        // come back truncated after a restart.
        if (value.startsWith("#")) {
            throw new IOException("path starts with '#' which YAML treats as a comment: " + value);
        }
        if (value.contains(" #")) {
            throw new IOException("path contains ' #' which YAML cannot round-trip: " + value);
        }
        if (value.contains("..")) throw new IOException("java path must not contain '..'");
        Path path;
        try {
            path = Paths.get(value);
        } catch (InvalidPathException e) {
            throw new IOException("invalid java path: " + value);
        }
        if (!path.isAbsolute()) throw new IOException("java path must be absolute: " + value);
        Path fileName = path.getFileName();
        String name = fileName == null ? "" : fileName.toString();
        if (!name.equalsIgnoreCase("java") && !name.equalsIgnoreCase("java.exe")) {
            throw new IOException("java path must point at java or java.exe: " + value);
        }
        Path absolute = path.normalize();
        if (!Files.isRegularFile(absolute)) throw new IOException("java executable not found: " + absolute);
        if (!Files.isExecutable(absolute)) throw new IOException("java is not executable: " + absolute);
        String version = versionAt(absolute.toString());
        if (version.isEmpty()) throw new IOException("not a working java executable: " + absolute);
        if (parseVersionOutput(version).major() <= 0) {
            throw new IOException("not a working java executable (no JVM version): " + absolute);
        }
        return absolute.toString();
    }

    // ------------------------------------------------------------------ managed runtimes

    /**
     * Synchronous Adoptium download + install into {@code <baseDir>/java/temurin-<major>}. The archive is
     * SHA-256 verified and extracted into a fresh staging folder first, so a failed download never leaves a
     * partial runtime behind; the staging JDK is moved into place only once it is complete. Replacing a
     * runtime that is currently in use (the panel's own JVM) is refused before any network work is done.
     * Serialized on one lock; may take minutes.
     */
    public JavaInfo download(int major) throws IOException, InterruptedException {
        if (major < 8 || major > 30) throw new IOException("unsupported Java major: " + major);
        String os = osName();
        String arch = archName();
        String id = "temurin-" + major;
        Path destination = javaDir.resolve(id);
        String apiUrl = "https://api.adoptium.net/v3/assets/latest/" + major + "/hotspot?os=" + os
                + "&architecture=" + arch + "&image_type=jdk";

        synchronized (downloadLock) {
            // Abort before downloading: install() would move this folder away and delete it.
            String inUse = panelRuntimeUse(destination);
            if (inUse != null) {
                throw new IOException("Java runtime " + id + " is in use by " + inUse
                        + "; choose another Java major or stop the panel before replacing it");
            }
            ConsoleOutput.root("Java download: requesting Temurin " + major + " (" + os + "/" + arch + ")");
            HttpResponse<String> meta = send(HttpRequest.newBuilder(URI.create(apiUrl))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (meta.statusCode() != 200) {
                throw new IOException("Adoptium API returned HTTP " + meta.statusCode());
            }
            Object parsed;
            try {
                parsed = Json.parse(meta.body());
            } catch (IOException e) {
                throw new IOException("cannot parse Adoptium response: " + e.getMessage());
            }
            Object first = parsed instanceof List<?> list && !list.isEmpty() ? list.get(0) : null;
            Object pkg = get(get(first, "binary"), "package");
            String link = text(get(pkg, "link"));
            String checksum = text(get(pkg, "checksum"));
            String packageName = text(get(pkg, "name"));
            String semver = text(get(get(first, "version"), "semver"));
            if (link.isEmpty()) throw new IOException("Adoptium response has no package link");
            if (!checksum.matches("(?i)[0-9a-f]{64}")) {
                throw new IOException("Adoptium response has no usable SHA-256 checksum");
            }
            URI downloadUri;
            try {
                downloadUri = URI.create(link);
            } catch (IllegalArgumentException e) {
                throw new IOException("invalid download URL from Adoptium");
            }
            validateDownloadUri(downloadUri);

            Files.createDirectories(javaDir);
            Path archive = Files.createTempFile(javaDir, "adoptium-" + major + "-", ".tmp");
            Path staging = javaDir.resolve(".extract-" + UUID.randomUUID());
            try {
                String actual = downloadArchive(downloadUri, archive);
                if (!actual.equalsIgnoreCase(checksum)) {
                    throw new IOException("SHA-256 mismatch for Java " + major + " (expected "
                            + checksum + ", got " + actual + ")");
                }
                if (os.equals("windows") || packageName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    unzip(archive, staging);
                } else {
                    untar(archive, staging);
                }
                Path home = extractedHome(staging);
                if (home == null) throw new IOException("downloaded archive did not contain a JDK");
                makeExecutable(home);

                // Re-check just before the swap: the panel JVM must never be moved out from under itself.
                inUse = panelRuntimeUse(destination);
                if (inUse != null) {
                    throw new IOException("Java runtime " + id + " is in use by " + inUse
                            + "; refusing to replace it");
                }
                install(home, destination);
                String exe = destination.resolve("bin").resolve(javaExeName()).toString();
                invalidateVersionCache(destination);
                String version = versionAt(exe);
                int effectiveMajor = major;
                if (version.isEmpty()) {
                    version = semver;
                    int parsedMajor = parseVersionOutput(version).major();
                    if (parsedMajor > 0) effectiveMajor = parsedMajor;
                } else {
                    int parsedMajor = parseVersionOutput(version).major();
                    if (parsedMajor > 0) effectiveMajor = parsedMajor;
                }
                JavaInfo info = new JavaInfo(id, exe, version, effectiveMajor, "managed");
                writeManagedRuntimes();
                ConsoleOutput.root("Java download: installed Temurin " + effectiveMajor + " at " + exe);
                return info;
            } finally {
                deleteQuietly(archive);
                deleteRecursivelyQuietly(staging);
            }
        }
    }

    /**
     * Deletes a managed runtime. Refuses (IOException) when the panel or any child would lose the java it
     * actually resolves to - both the configured path and {@link ChildProcess#javaFor(ChildConfig)} are
     * checked, so a server on the panel default blocks deletion of the panel's JVM. Returns false for an
     * unknown id.
     */
    public boolean delete(String id, List<ChildConfig> children) throws IOException {
        if (!safeId(id)) return false;
        List<ChildConfig> configs = children == null ? List.of() : children;
        synchronized (downloadLock) {
            JavaInfo target = null;
            for (JavaInfo info : managed()) {
                if (info.id().equals(id)) {
                    target = info;
                    break;
                }
            }
            if (target == null) return false;
            Path binDir = Paths.get(target.path()).toAbsolutePath().normalize().getParent();
            Path dir = binDir == null ? null : binDir.getParent();
            if (dir == null || !dir.startsWith(javaDir) || dir.equals(javaDir)) {
                throw new IOException("refusing to delete outside the managed java folder");
            }
            for (ChildConfig cfg : configs) {
                String who = "server '" + cfg.id() + "'";
                requireNotInUse(dir, cfg.javaPath(), id, who);                 // explicitly configured path
                requireNotInUse(dir, ChildProcess.javaFor(cfg), id, who);      // executable it resolves to
            }
            String panelUse = panelRuntimeUse(dir);
            if (panelUse != null) {
                throw new IOException("Java runtime " + id + " is in use by " + panelUse);
            }
            deleteRecursively(dir);
            invalidateVersionCache(dir);
            writeManagedRuntimes();
            return true;
        }
    }

    /** Throws when {@code javaValue} resolves to an executable inside {@code dir}. */
    private static void requireNotInUse(Path dir, String javaValue, String id, String who) throws IOException {
        if (javaValue == null || javaValue.isBlank()) return;
        String value = javaValue.trim();
        if (value.equalsIgnoreCase("java")) return; // PATH lookup, cannot be a managed folder
        Path used;
        try {
            used = Paths.get(value).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return;
        }
        if (inside(dir, used)) {
            throw new IOException("Java runtime " + id + " is in use by " + who);
        }
    }

    /** Describes the panel's JVM when it lives inside {@code dir}, else null. */
    private String panelRuntimeUse(Path dir) {
        if (dir == null) return null;
        Path own = panelJavaPath();
        if (inside(dir, own)) return "the panel's own JVM";
        String defaultPath = defaultJava().path();
        if (defaultPath != null && !defaultPath.isBlank() && !defaultPath.equalsIgnoreCase("java")) {
            try {
                if (inside(dir, Paths.get(defaultPath))) return "the panel default Java";
            } catch (InvalidPathException ignored) {
                // A non-path default cannot live in a managed folder.
            }
        }
        return null;
    }

    private static boolean inside(Path dir, Path candidate) {
        if (candidate == null) return false;
        try {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (normalized.startsWith(dir)) return true;
            // Catch aliases (e.g. /usr/bin/java -> managed/bin/java) when both paths exist.
            if (!Files.exists(normalized) || !Files.exists(dir)) return false;
            return normalized.toRealPath().startsWith(dir.toRealPath());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ managed metadata

    private void writeManagedRuntimes() {
        StringBuilder sb = new StringBuilder("[\n");
        List<JavaInfo> infos = managed();
        for (int i = 0; i < infos.size(); i++) {
            JavaInfo info = infos.get(i);
            sb.append("  {\"id\":\"").append(jsonEscape(info.id()))
                    .append("\",\"path\":\"").append(jsonEscape(info.path()))
                    .append("\",\"version\":\"").append(jsonEscape(info.version()))
                    .append("\",\"major\":").append(info.major()).append("}");
            if (i < infos.size() - 1) sb.append(',');
            sb.append('\n');
        }
        sb.append("]\n");
        synchronized (fileLock) {
            try {
                Files.createDirectories(javaDir);
                Path tmp = javaDir.resolve("runtimes.json.tmp-" + System.nanoTime());
                Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, runtimesFile, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, runtimesFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                // The directory scan is authoritative, so a metadata write failure must not fail an install.
                ConsoleOutput.root("could not write " + runtimesFile + ": " + e.getMessage());
            }
        }
    }

    private List<Map<String, Object>> readRuntimes() {
        synchronized (fileLock) {
            if (!Files.isRegularFile(runtimesFile)) return List.of();
            try {
                Object root = Json.parse(Files.readString(runtimesFile, StandardCharsets.UTF_8));
                if (!(root instanceof List<?> list)) return List.of();
                List<Map<String, Object>> out = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map) out.add(asObject(item));
                }
                return out;
            } catch (IOException | RuntimeException e) {
                ConsoleOutput.root("ignoring unreadable " + runtimesFile + ": " + e.getMessage());
                return List.of();
            }
        }
    }

    private Path safeManagedPath(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            Path path = Paths.get(raw);
            if (!path.isAbsolute()) return null;
            Path absolute = path.normalize();
            if (!absolute.startsWith(javaDir)) return null;
            Path fileName = absolute.getFileName();
            if (fileName == null) return null;
            String name = fileName.toString().toLowerCase(Locale.ROOT);
            if (!name.equals("java") && !name.equals("java.exe")) return null;
            return absolute;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ download / extract

    private String downloadArchive(URI uri, Path archive) throws IOException, InterruptedException {
        validateDownloadUri(uri);
        HttpResponse<InputStream> response = send(HttpRequest.newBuilder(uri)
                .header("Accept", "application/octet-stream")
                .timeout(Duration.ofMinutes(10))
                .GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        // HttpClient follows redirects; the final URI is what actually served the bytes, so vet it again
        // before trusting (or reading) the body.
        try {
            validateDownloadUri(response.uri());
        } catch (IOException e) {
            try {
                response.body().close();
            } catch (IOException ignored) {}
            throw e;
        }
        if (response.statusCode() != 200) {
            try {
                response.body().close(); // release the connection
            } catch (IOException ignored) {}
            throw new IOException("Java download returned HTTP " + response.statusCode());
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
        try (InputStream in = response.body();
             OutputStream out = Files.newOutputStream(archive, StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                digest.update(buffer, 0, n);
            }
        }
        return hex(digest.digest());
    }

    /** One validator for the initial and the post-redirect download URI: https, no userinfo, allowlist, 443. */
    private static void validateDownloadUri(URI uri) throws IOException {
        if (uri == null) throw new IOException("download URL is missing");
        String shown = uri.toString();
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("download URL is not https: " + shown);
        }
        if (uri.getUserInfo() != null) {
            throw new IOException("download URL must not contain credentials: " + shown);
        }
        int port = uri.getPort();
        if (port != -1 && port != 443) {
            throw new IOException("download URL uses a non-HTTPS port: " + shown);
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!DOWNLOAD_HOSTS.contains(host)) throw new IOException("download host not allowed: " + host);
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        return client.send(request, handler);
    }

    /** Extracts a zip, stripping the single top-level folder, refusing absolute and .. entries. */
    private static void unzip(Path archive, Path destination) throws IOException {
        Files.createDirectories(destination);
        Path root = destination.toAbsolutePath().normalize();
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory()) continue;
                if (unsafeEntry(name)) throw new IOException("unsafe zip entry: " + entry.getName());
                int slash = name.indexOf('/');
                String relative = slash > 0 ? name.substring(slash + 1) : name;
                if (relative.isEmpty()) continue;
                Path target = root.resolve(relative).normalize();
                if (!target.startsWith(root)) throw new IOException("zip entry outside destination: " + entry.getName());
                Path parent = target.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.copy(zin, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Extracts a tar.gz using the system tar; JDK-only code has no bundled tar/gzip reader. The extracted
     * tree is walked afterwards (symlinks are rejected and every real path must stay inside the staging
     * folder) because tar happily writes symlinks that could point outside it.
     */
    private static void untar(Path archive, Path destination) throws IOException {
        Files.createDirectories(destination);
        ProcessBuilder builder = new ProcessBuilder("tar", "-xzf", archive.toAbsolutePath().toString(),
                "-C", destination.toAbsolutePath().toString(), "--strip-components=1");
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IOException("system tar is required to extract Java archives: " + e.getMessage(), e);
        }
        try {
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                deleteRecursivelyQuietly(destination);
                throw new IOException("tar timed out while extracting Java");
            }
            if (process.exitValue() != 0) {
                deleteRecursivelyQuietly(destination);
                throw new IOException("tar failed while extracting Java (exit " + process.exitValue() + ")");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            deleteRecursivelyQuietly(destination);
            throw new IOException("interrupted while extracting Java", e);
        }
        verifyExtractedTree(destination);
    }

    /**
     * Rejects anything whose real path escapes the staging root, and replaces safe symlinks with copies of
     * their target so the installed runtime contains no links (Adoptium puts relative links in legal/).
     * On failure the staging folder is removed so no half-verified JDK can be installed.
     */
    private static void verifyExtractedTree(Path destination) throws IOException {
        Path root;
        try {
            root = destination.toRealPath();
        } catch (IOException e) {
            deleteRecursivelyQuietly(destination);
            throw e;
        }
        try {
            materializeSymlinks(destination, root);
            try (Stream<Path> stream = Files.walk(destination)) {
                for (Path path : stream.toList()) {
                    if (Files.isSymbolicLink(path)) {
                        throw new IOException("refusing symlink in Java archive: " + path);
                    }
                    Path real = path.toRealPath();
                    if (!real.startsWith(root)) {
                        throw new IOException("archive entry outside destination: " + path);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            deleteRecursivelyQuietly(destination);
            if (e instanceof IOException io) throw io;
            throw new IOException("could not verify the extracted Java archive: " + e.getMessage(), e);
        }
    }

    /** Replaces each in-tree symlink with a copy of the file it points to. */
    private static void materializeSymlinks(Path destination, Path root) throws IOException {
        java.util.List<Path> links;
        try (Stream<Path> stream = Files.walk(destination)) {
            links = stream.filter(Files::isSymbolicLink).toList();
        }
        for (Path link : links) {
            Path target = link.getParent().resolve(Files.readSymbolicLink(link)).normalize();
            if (!target.startsWith(root) || !Files.exists(target)) {
                throw new IOException("archive symlink points outside destination: " + link);
            }
            Path real = target.toRealPath();
            if (!real.startsWith(root)) throw new IOException("archive symlink points outside destination: " + link);
            if (!Files.isRegularFile(real)) throw new IOException("archive symlink does not point to a file: " + link);
            byte[] data = Files.readAllBytes(real);
            Files.delete(link);
            Files.write(link, data);
        }
    }

    private static boolean unsafeEntry(String name) {
        if (name.isEmpty() || name.startsWith("/")) return true;
        String[] parts = name.split("/");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.equals("..")) return true;
            if (i == 0 && part.length() == 2 && part.charAt(1) == ':'
                    && Character.isLetter(part.charAt(0))) return true; // C:/...
        }
        return false;
    }

    /** The folder holding bin/java after extraction; usually the staging folder itself. */
    private static Path extractedHome(Path staging) throws IOException {
        if (Files.isRegularFile(staging.resolve("bin").resolve(javaExeName()))) return staging;
        try (Stream<Path> stream = Files.list(staging)) {
            for (Path dir : stream.toList()) {
                if (Files.isDirectory(dir) && Files.isRegularFile(dir.resolve("bin").resolve(javaExeName()))) {
                    return dir;
                }
            }
        }
        return null;
    }

    private static void makeExecutable(Path home) {
        if (isWindows()) return;
        for (String relative : List.of("bin/java", "lib/jspawnhelper", "lib/jexec")) {
            Path file = home.resolve(relative);
            if (!Files.isRegularFile(file)) continue;
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
            } catch (UnsupportedOperationException | IOException e) {
                file.toFile().setExecutable(true, false);
            }
        }
    }

    /** Moves the extracted JDK into its final place; an existing runtime is replaced only after success. */
    private static void install(Path home, Path destination) throws IOException {
        if (!Files.exists(destination)) {
            move(home, destination);
            return;
        }
        Path backup = destination.resolveSibling("." + destination.getFileName() + ".old-" + System.nanoTime());
        move(destination, backup);
        try {
            move(home, destination);
        } catch (IOException e) {
            try {
                move(backup, destination);
            } catch (IOException ignored) {
                // Leave the backup for the operator rather than deleting the only copy.
            }
            throw e;
        }
        deleteRecursivelyQuietly(backup);
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(root)) {
            Files.deleteIfExists(root);
            return;
        }
        if (Files.isDirectory(root)) {
            try (Stream<Path> stream = Files.list(root)) {
                for (Path child : stream.toList()) deleteRecursively(child);
            }
        }
        Files.deleteIfExists(root);
    }

    private static void deleteRecursivelyQuietly(Path root) {
        try {
            deleteRecursively(root);
        } catch (IOException ignored) {}
    }

    private static void deleteQuietly(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {}
    }

    // ------------------------------------------------------------------ helpers

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }

    private static int parseIntOrZero(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String osName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "mac";
        return "linux";
    }

    private static String archName() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return arch.contains("aarch64") || arch.contains("arm64") ? "aarch64" : "x64";
    }

    private static boolean isWindows() { return osName().equals("windows"); }

    private static String javaExeName() { return isWindows() ? "java.exe" : "java"; }

    private static Path panelJavaPath() {
        try {
            return Paths.get(System.getProperty("java.home", "")).resolve("bin").resolve(javaExeName());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Path binJava(String home) {
        if (home == null || home.isBlank()) return null;
        try {
            return Paths.get(home).resolve("bin").resolve(javaExeName());
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static boolean safeId(String id) {
        return id != null && SAFE_ID.matcher(id).matches() && !id.equals(".") && !id.equals("..");
    }

    private static String uniqueId(String base, Set<String> used) {
        String id = base;
        int n = 2;
        while (!used.add(id)) id = base + "-" + n++;
        return id;
    }

    private static String safeName(Path dir) {
        Path fileName = dir.getFileName();
        String name = fileName == null ? "dir" : fileName.toString().toLowerCase(Locale.ROOT);
        String cleaned = name.replaceAll("[^a-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        return cleaned.isEmpty() ? "dir" : cleaned;
    }

    private record Candidate(String id, String path, String source) {}

    private static void addCandidate(Map<String, Candidate> out, Path path, String id, String source) {
        if (path == null) return;
        Path absolute;
        try {
            absolute = path.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return;
        }
        if (!Files.isRegularFile(absolute)) return;
        String key;
        try {
            key = absolute.toRealPath().toString();
        } catch (IOException e) {
            key = absolute.toString();
        }
        out.putIfAbsent(key, new Candidate(id, absolute.toString(), source));
    }

    private static void scanJvmDir(Map<String, Candidate> out, Path root, String prefix) {
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> stream = Files.list(root)) {
            for (Path dir : stream.sorted().toList()) {
                if (!Files.isDirectory(dir)) continue;
                addCandidate(out, dir.resolve("bin").resolve(javaExeName()), prefix + "-" + safeName(dir), "jvm");
            }
        } catch (IOException ignored) {}
    }

    private static void scanMacDir(Map<String, Candidate> out, Path root, String prefix) {
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> stream = Files.list(root)) {
            for (Path dir : stream.sorted().toList()) {
                if (!Files.isDirectory(dir)) continue;
                addCandidate(out, dir.resolve("Contents").resolve("Home").resolve("bin").resolve(javaExeName()),
                        prefix + "-" + safeName(dir), "macos");
            }
        } catch (IOException ignored) {}
    }

    private static void scanWindowsVendor(Map<String, Candidate> out, Path root, String prefix) {
        if (root == null || !Files.isDirectory(root)) return;
        addCandidate(out, root.resolve("bin").resolve("java.exe"), prefix + "-root", "windows");
        try (Stream<Path> stream = Files.list(root)) {
            for (Path dir : stream.sorted().toList()) {
                if (!Files.isDirectory(dir)) continue;
                addCandidate(out, dir.resolve("bin").resolve("java.exe"), prefix + "-" + safeName(dir), "windows");
            }
        } catch (IOException ignored) {}
    }

    private static void scanPathCommand(Map<String, Candidate> out, String command) {
        String output = runCapture(List.of(command, "java"), PROBE_TIMEOUT_MS);
        if (output.isEmpty()) return;
        for (String line : output.split("\\R")) {
            String candidate = line.trim();
            if (candidate.isEmpty()) continue;
            try {
                addCandidate(out, Paths.get(candidate), "path", "path");
            } catch (RuntimeException ignored) {
                // Not a usable path (e.g. a shell alias); skip it.
            }
        }
    }

    /** Runs a command, capturing at most {@link #MAX_PROBE_BYTES}; "" on failure or timeout. Never throws. */
    private static String runCapture(List<String> command, long timeoutMs) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            Process running = process;
            AtomicReference<String> captured = new AtomicReference<>("");
            Thread reader = new Thread(() -> {
                try (InputStream in = running.getInputStream()) {
                    captured.set(new String(in.readNBytes(MAX_PROBE_BYTES), StandardCharsets.UTF_8));
                } catch (IOException ignored) {}
            }, "java-probe");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return "";
            }
            reader.join(2_000);
            return captured.get();
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            return "";
        }
    }

    private static String jsonEscape(String value) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ tiny JSON reader

    private static Object get(Object node, String key) {
        return node instanceof Map<?, ?> map ? map.get(key) : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object node) {
        return node instanceof Map ? (Map<String, Object>) node : Map.of();
    }

    private static String text(Object node) {
        if (node instanceof String s) return s;
        if (node instanceof Number n) return String.valueOf(n);
        return "";
    }

    /** Recursive-descent JSON reader (objects, arrays, strings, numbers, booleans, null). */
    private static final class Json {
        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String text) throws IOException {
            Json parser = new Json(text == null ? "" : text);
            parser.ws();
            Object value = parser.value();
            parser.ws();
            if (parser.i < parser.s.length()) throw parser.error("trailing data");
            return value;
        }

        private Object value() throws IOException {
            if (i >= s.length()) throw error("unexpected end of input");
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (c == 't') return literal("true", Boolean.TRUE);
            if (c == 'f') return literal("false", Boolean.FALSE);
            if (c == 'n') return literal("null", null);
            return number();
        }

        private Map<String, Object> object() throws IOException {
            Map<String, Object> map = new LinkedHashMap<>();
            i++;
            ws();
            if (peek('}')) {
                i++;
                return map;
            }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') throw error("expected object key");
                String key = string();
                ws();
                expect(':');
                ws();
                map.put(key, value());
                ws();
                if (peek('}')) {
                    i++;
                    return map;
                }
                expect(',');
            }
        }

        private List<Object> array() throws IOException {
            List<Object> list = new ArrayList<>();
            i++;
            ws();
            if (peek(']')) {
                i++;
                return list;
            }
            while (true) {
                ws();
                list.add(value());
                ws();
                if (peek(']')) {
                    i++;
                    return list;
                }
                expect(',');
            }
        }

        private String string() throws IOException {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (i >= s.length()) throw error("bad escape");
                char e = s.charAt(i++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw error("bad unicode escape");
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw error("bad unicode escape");
                        }
                        i += 4;
                    }
                    default -> throw error("bad escape \\" + e);
                }
            }
            throw error("unterminated string");
        }

        private Object number() throws IOException {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String raw = s.substring(start, i);
            if (raw.isEmpty()) throw error("unexpected character");
            try {
                if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) {
                    return Long.parseLong(raw);
                }
                return Double.parseDouble(raw);
            } catch (NumberFormatException e) {
                throw error("bad number: " + raw);
            }
        }

        private Object literal(String word, Object value) throws IOException {
            if (!s.startsWith(word, i)) throw error("expected " + word);
            i += word.length();
            return value;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private boolean peek(char c) {
            return i < s.length() && s.charAt(i) == c;
        }

        private void expect(char c) throws IOException {
            if (!peek(c)) throw error("expected '" + c + "'");
            i++;
        }

        private IOException error(String message) {
            return new IOException("invalid JSON at " + i + ": " + message);
        }
    }
}
