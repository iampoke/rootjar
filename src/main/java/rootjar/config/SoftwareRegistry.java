package rootjar.config;

import rootjar.Main;
import rootjar.addons.Addons;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Catalog and download resolver for the Change Version tab.
 *
 * <p>Only the official upstream APIs are used: fill.papermc.io, api.purpurmc.org, Mojang piston,
 * Fabric/Quilt meta, GitHub releases and the BungeeCord Jenkins. Every request is HTTPS and checked
 * against {@link #downloadHosts()}; no caller-supplied URL is ever fetched. Responses are cached for
 * ten minutes and a stale cached body is used when an upstream request fails.</p>
 *
 * <p>The {@code parse*} helpers are pure and network-free so they can be exercised by ChecksMain
 * against saved fixtures.</p>
 */
public final class SoftwareRegistry {

    public enum Category { JAVA, PROXY }

    public enum Strategy { DIRECT, INSTALLER, MANUAL }

    public record Software(String id, String name, String description,
                           Category category, Strategy strategy, String reason) {}

    public record VersionOption(String id, String label, String subtitle) {}

    public record InstallPlan(String jarName, String url, String sha256, String sha1,
                              String installerUrl, String installerSha256,
                              List<String> installerArgs, String outputJar,
                              Strategy strategy, String reason) {}

    /** One fill v3 build download, returned by {@link #parseFillBuild(String)}. */
    public record FillBuild(String name, String url, String sha256, long size) {}

    /** Mojang version-JSON server download, returned by {@link #parseVanillaVersionJson(String)}. */
    public record VanillaDownload(String version, String url, String sha1) {}

    /** A Fabric or Quilt installer entry from their meta APIs. */
    public record InstallerFile(String version, String url, String sha256) {}

    /** One NanoLimbo GitHub release, returned by {@link #parseNanoLimboRelease(String)}. */
    public record NanoLimboRelease(String tag, String name, String assetName,
                                   String url, String sha256) {}

    /** Internal shape of a fill v3 {@code /versions} entry. */
    private record FillVersion(String id, long latestBuild) {}

    private static final String USER_AGENT = "childserversjar-panel/" + Main.VERSION;
    private static final long CACHE_TTL_MS = 10L * 60L * 1000L;
    private static final int CACHE_MAX = 128;
    private static final int MAX_JSON_DEPTH = 100;

    private static final String FILL = "https://fill.papermc.io";
    private static final String PURPUR = "https://api.purpurmc.org/v2/purpur";
    private static final String MOJANG_MANIFEST =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String FABRIC = "https://meta.fabricmc.net";
    private static final String QUILT = "https://meta.quiltmc.org";
    private static final String BUNGEE_HOST = "https://ci.md-5.net";
    private static final String BUNGEE_JOB = "/job/BungeeCord";
    /** Jenkins job base. The {@code /job/BungeeCord} segment is mandatory: omitting it is a 404. */
    private static final String BUNGEE = BUNGEE_HOST + BUNGEE_JOB;
    private static final String NANOLIMBO = "https://api.github.com/repos/Nan1t/NanoLimbo/releases";

    private static final Pattern VERSION_ID =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._+-]{0,31}$");
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern SHA1_HEX = Pattern.compile("[0-9a-fA-F]{40}");

    /**
     * Hosts this class may contact, including metadata and download endpoints. WebServer may reuse
     * this for its own server-jar download whitelist.
     */
    private static final List<String> HOSTS = List.of(
            "fill.papermc.io",
            "fill-data.papermc.io",
            "api.purpurmc.org",
            "piston-meta.mojang.com",
            "piston-data.mojang.com",
            "meta.fabricmc.net",
            "meta.quiltmc.org",
            "maven.fabricmc.net",
            "maven.quiltmc.org",
            "api.github.com",
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "ci.md-5.net",
            "hub.spigotmc.org",
            "api.adoptium.net");

    private static final String FORGE_REASON =
            "Automatic switching is not supported yet: modern Forge launches from a generated "
            + "arguments file, not a single downloadable jar. Install Forge manually.";
    private static final String NEOFORGE_REASON =
            "Automatic switching is not supported yet: NeoForge launches from a generated "
            + "arguments file, not a single downloadable jar. Install NeoForge manually.";
    private static final String SPIGOT_REASON =
            "Spigot has no official prebuilt jar; it must be compiled with BuildTools. "
            + "Install Spigot manually.";
    private static final String ARCLIGHT_REASON =
            "Arclight publishes separate Forge, NeoForge and Fabric builds. Choose and install "
            + "the matching jar manually.";

    private static final List<Software> CATALOG = List.of(
            new Software("vanilla", "Vanilla",
                    "Mojang's official Minecraft server, releases only.",
                    Category.JAVA, Strategy.DIRECT, ""),
            new Software("paper", "Paper",
                    "High-performance Spigot fork with a large plugin ecosystem.",
                    Category.JAVA, Strategy.DIRECT, ""),
            new Software("purpur", "Purpur",
                    "Paper fork with extra gameplay and configuration options.",
                    Category.JAVA, Strategy.DIRECT, ""),
            new Software("fabric", "Fabric",
                    "Lightweight, fast-updating mod loader.",
                    Category.JAVA, Strategy.INSTALLER, ""),
            new Software("quilt", "Quilt",
                    "Fabric-compatible mod loader with its own toolchain.",
                    Category.JAVA, Strategy.INSTALLER, ""),
            new Software("folia", "Folia",
                    "Paper's multithreaded server for very large worlds.",
                    Category.JAVA, Strategy.DIRECT, ""),
            new Software("velocity", "Velocity",
                    "Modern, high-performance Minecraft proxy.",
                    Category.PROXY, Strategy.DIRECT, ""),
            new Software("waterfall", "Waterfall",
                    "BungeeCord-compatible proxy, discontinued but still published.",
                    Category.PROXY, Strategy.DIRECT, ""),
            new Software("travertine", "Travertine",
                    "Velocity-based proxy for older Minecraft clients.",
                    Category.PROXY, Strategy.DIRECT, ""),
            new Software("bungeecord", "BungeeCord",
                    "Classic Minecraft proxy, installed from the latest Jenkins build.",
                    Category.PROXY, Strategy.DIRECT, ""),
            new Software("nanolimbo", "NanoLimbo",
                    "Very small Limbo server for proxy hubs.",
                    Category.PROXY, Strategy.DIRECT, ""),
            new Software("forge", "Forge", "Minecraft Forge mod loader.",
                    Category.JAVA, Strategy.MANUAL, FORGE_REASON),
            new Software("neoforge", "NeoForge", "NeoForge mod loader.",
                    Category.JAVA, Strategy.MANUAL, NEOFORGE_REASON),
            new Software("spigot", "Spigot", "Spigot server, built from source with BuildTools.",
                    Category.JAVA, Strategy.MANUAL, SPIGOT_REASON),
            new Software("arclight", "Arclight", "Hybrid Forge/Fabric server implementation.",
                    Category.JAVA, Strategy.MANUAL, ARCLIGHT_REASON)
    );

    private final HttpClient client;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(long at, String body) {}

    /**
     * Creates a registry. When {@code client} is null a JDK client with normal redirects and a 15 s
     * connect timeout is built; the supplied WebServer client is used as-is otherwise. Requests
     * always carry a 60 s per-request timeout.
     */
    public SoftwareRegistry(HttpClient client) {
        this.client = client != null ? client : HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Immutable software catalog, in UI display order. */
    public List<Software> catalog() {
        return CATALOG;
    }

    /** Finds a catalog entry by id, case-insensitively. */
    public Optional<Software> find(String id) {
        if (id == null) return Optional.empty();
        String key = id.trim().toLowerCase(Locale.ROOT);
        for (Software software : CATALOG) {
            if (software.id().equals(key)) return Optional.of(software);
        }
        return Optional.empty();
    }

    /** Version choices for one catalog id, newest first. Manual software returns an empty list. */
    public List<VersionOption> versions(String id) throws IOException, InterruptedException {
        Software software = require(id);
        if (software.strategy() == Strategy.MANUAL) return List.of();
        return switch (software.id()) {
            case "paper", "folia", "velocity", "waterfall", "travertine" ->
                    fillVersions(fillProject(software.id()));
            case "purpur" -> purpurVersions();
            case "vanilla" -> vanillaVersions();
            case "fabric" -> gameVersions(fetch(FABRIC + "/v2/versions/game"),
                    "Fabric game versions");
            case "quilt" -> gameVersions(fetch(QUILT + "/v3/versions/game"),
                    "Quilt game versions");
            case "bungeecord" -> bungeeVersions();
            case "nanolimbo" -> nanoLimboVersions();
            default -> throw new IOException("No version source for software " + software.id());
        };
    }

    /**
     * Resolves an install plan for a catalog id and a version id returned by {@link #versions(String)}.
     * Manual entries return {@link Strategy#MANUAL} with a user-facing reason and no URLs.
     */
    public InstallPlan resolve(String id, String version) throws IOException, InterruptedException {
        Software software = require(id);
        if (software.strategy() == Strategy.MANUAL) {
            String reason = software.reason() == null || software.reason().isBlank()
                    ? "This software must be installed manually." : software.reason();
            return new InstallPlan("", "", "", "", "", "", List.of(), "",
                    Strategy.MANUAL, reason);
        }
        if (version == null || !validVersion(version)) {
            throw new IOException("Invalid version for " + software.id() + ": "
                    + (version == null ? "null" : "\"" + version + "\""));
        }
        InstallPlan plan = switch (software.id()) {
            case "paper", "folia", "velocity", "waterfall", "travertine" ->
                    resolveFill(software.id(), version);
            case "purpur" -> resolvePurpur(version);
            case "vanilla" -> resolveVanilla(version);
            case "bungeecord" -> resolveBungee(version);
            case "nanolimbo" -> resolveNanoLimbo(version);
            case "fabric" -> resolveFabric(version);
            case "quilt" -> resolveQuilt(version);
            default -> throw new IOException("No resolver for software " + software.id());
        };
        // Last line of defence: every plan URL must be HTTPS on downloadHosts(), digests valid,
        // and jar names plain bare file names (no separators or traversal).
        return validatePlan(plan);
    }

    /** Version ids accepted by the resolver; also rejects path traversal. */
    public static boolean validVersion(String v) {
        return v != null && VERSION_ID.matcher(v).matches() && !v.contains("..");
    }

    /** Hosts used by this registry, suitable for a WebServer download allowlist. */
    public static List<String> downloadHosts() {
        return HOSTS;
    }

    /**
     * Jenkins artifact URL for a BungeeCord build. The {@code /job/BungeeCord} segment is required
     * (verified live: {@code /job/BungeeCord/2107/artifact/...} is 200, {@code /2107/artifact/...}
     * is 404). Pure and network-free so it can be asserted by fixture checks.
     */
    public static String bungeeArtifactUrl(int build) {
        if (build <= 0) {
            throw new IllegalArgumentException("Invalid BungeeCord build: " + build);
        }
        return BUNGEE + "/" + build + "/artifact/bootstrap/target/BungeeCord.jar";
    }

    // ---- version ordering --------------------------------------------------------------------

    private static final Pattern PRE_KIND = Pattern.compile("^([a-z]+)");

    private static final List<String> DIGEST_PREFIXES = List.of(
            "sha256:", "sha256=", "sha-256:", "sha-256=",
            "sha1:", "sha1=", "sha-1:", "sha-1=");

    /**
     * Compares two version ids, oldest first: numeric core first ({@code 1.21.10 > 1.21.4}).
     * When the cores are equal a final release outranks its pre-releases, and pre-releases rank by
     * kind (snapshot &lt; alpha &lt; beta &lt; pre &lt; rc) then by their own number, so
     * {@code 26.3 > 26.3-rc-3 > 26.3-rc-2}. Stable server-ordered lists can still be re-sorted
     * with this comparator without losing their normal numeric order.
     */
    public static int compareVersionIds(String a, String b) {
        String left = a == null ? "" : a.trim();
        String right = b == null ? "" : b.trim();
        int byCore = Addons.compareVersions(versionCore(left), versionCore(right));
        if (byCore != 0) return byCore;

        String preLeft = versionPrerelease(left);
        String preRight = versionPrerelease(right);
        if (preLeft.isEmpty() != preRight.isEmpty()) {
            // The final release is newer than any pre-release of the same numeric core.
            return preLeft.isEmpty() ? 1 : -1;
        }
        if (!preLeft.isEmpty()) {
            int byKind = Integer.compare(prereleaseRank(preLeft), prereleaseRank(preRight));
            if (byKind != 0) return byKind;
            int byNumber = Addons.compareVersions(preLeft, preRight);
            if (byNumber != 0) return byNumber;
            int byText = preLeft.compareTo(preRight);
            if (byText != 0) return byText;
        }
        int byFull = Addons.compareVersions(left, right);
        if (byFull != 0) return byFull;
        return left.compareTo(right);
    }

    /** Numeric part of a version id: everything before the first {@code -} or {@code +}. */
    private static String versionCore(String version) {
        int cut = version.length();
        int dash = version.indexOf('-');
        int plus = version.indexOf('+');
        if (dash >= 0) cut = Math.min(cut, dash);
        if (plus >= 0) cut = Math.min(cut, plus);
        String core = version.substring(0, cut).trim();
        return core.isEmpty() ? version : core;
    }

    /**
     * Pre-release label after the first {@code -}; empty for a final release. A purely numeric suffix is
     * a build number, not a pre-release: Paper publishes {@code 1.21.4-232}, which is newer than
     * {@code 1.21.4} (and build 231 is older than build 232).
     */
    private static String versionPrerelease(String version) {
        int dash = version.indexOf('-');
        if (dash < 0) return "";
        String prerelease = version.substring(dash + 1).trim().toLowerCase(Locale.ROOT);
        if (prerelease.matches("\\d+")) return "";
        return prerelease;
    }

    private static int prereleaseRank(String prerelease) {
        Matcher matcher = PRE_KIND.matcher(prerelease);
        String kind = matcher.find() ? matcher.group(1) : prerelease;
        return switch (kind) {
            case "snapshot", "snap" -> 0;
            case "alpha", "a" -> 1;
            case "beta", "b" -> 2;
            case "pre", "preview", "m", "milestone" -> 3;
            case "rc" -> 4;
            default -> 3;
        };
    }

    // ---- version lists -----------------------------------------------------------------------

    private List<VersionOption> fillVersions(String project) throws IOException, InterruptedException {
        String url = FILL + "/v3/projects/" + path(project) + "/versions";
        List<FillVersion> entries = parseFillVersionEntries(fetch(url));
        List<VersionOption> out = new ArrayList<>(entries.size());
        for (FillVersion entry : entries) {
            String subtitle = entry.latestBuild() > 0
                    ? "Build " + entry.latestBuild() : "Latest build";
            out.add(new VersionOption(entry.id(), entry.id(), subtitle));
        }
        return out;
    }

    private List<VersionOption> purpurVersions() throws IOException, InterruptedException {
        List<String> ids = parsePurpurVersions(fetch(PURPUR));
        List<VersionOption> out = new ArrayList<>(ids.size());
        for (String id : ids) out.add(new VersionOption(id, id, "Latest build"));
        return out;
    }

    private List<VersionOption> vanillaVersions() throws IOException, InterruptedException {
        List<String> ids = parseVanillaReleases(fetch(MOJANG_MANIFEST));
        List<VersionOption> out = new ArrayList<>(ids.size());
        for (String id : ids) out.add(new VersionOption(id, id, "Release"));
        return out;
    }

    private List<VersionOption> gameVersions(String json, String context) throws IOException {
        List<String> ids = parseGameVersions(json, context);
        List<VersionOption> out = new ArrayList<>(ids.size());
        for (String id : ids) out.add(new VersionOption(id, id, "Stable"));
        return out;
    }

    private List<VersionOption> bungeeVersions() throws IOException, InterruptedException {
        int build = parseBungeeBuild(fetch(BUNGEE + "/lastSuccessfulBuild/api/json"));
        return List.of(new VersionOption("latest", "Latest build", "Build " + build));
    }

    private List<VersionOption> nanoLimboVersions() throws IOException, InterruptedException {
        return parseNanoLimboReleases(fetch(NANOLIMBO));
    }

    // ---- resolvers ---------------------------------------------------------------------------

    private InstallPlan resolveFill(String softwareId, String version)
            throws IOException, InterruptedException {
        String project = fillProject(softwareId);
        String url = FILL + "/v3/projects/" + path(project) + "/versions/" + path(version)
                + "/builds/latest";
        FillBuild build = parseFillBuild(fetch(url));
        String jar = safeJar(build.name(), "fill build for " + softwareId + " " + version);
        String download = requireDownloadUrl(build.url(), "fill download for " + softwareId);
        String sha256 = normaliseSha256(build.sha256(), "fill build " + build.name());
        return new InstallPlan(jar, download, sha256, "", "", "", List.of(), "",
                Strategy.DIRECT, "");
    }

    private InstallPlan resolvePurpur(String version) throws IOException, InterruptedException {
        String json = fetch(PURPUR + "/" + path(version));
        String build = parsePurpurBuild(json);
        String jar = safeJar("purpur-" + version + "-" + build + ".jar",
                "Purpur jar name");
        String url = requireDownloadUrl(PURPUR + "/" + path(version) + "/" + path(build)
                + "/download", "Purpur download for " + version);
        // Purpur publishes only an MD5 digest for builds; the installer verifies size and ZIP magic.
        return new InstallPlan(jar, url, "", "", "", "", List.of(), "",
                Strategy.DIRECT, "");
    }

    private InstallPlan resolveVanilla(String version) throws IOException, InterruptedException {
        String manifest = fetch(MOJANG_MANIFEST);
        String versionUrl = parseVanillaVersionUrl(manifest, version);
        VanillaDownload download = parseVanillaVersionJson(fetch(versionUrl));
        String resolvedVersion = download.version().isBlank() ? version : download.version();
        String jar = safeJar("vanilla-" + resolvedVersion + ".jar", "Vanilla jar name");
        String url = requireDownloadUrl(download.url(), "Vanilla download for " + version);
        String sha1 = normaliseSha1(download.sha1(), "Vanilla version " + version);
        return new InstallPlan(jar, url, "", sha1, "", "", List.of(), "",
                Strategy.DIRECT, "");
    }

    private InstallPlan resolveBungee(String version) throws IOException, InterruptedException {
        int build;
        if ("latest".equals(version)) {
            build = parseBungeeBuild(fetch(BUNGEE + "/lastSuccessfulBuild/api/json"));
        } else if (version.matches("[0-9]{1,9}")) {
            try {
                build = Integer.parseInt(version);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid BungeeCord build number: " + version, e);
            }
        } else {
            throw new IOException("BungeeCord supports only \"latest\" or a numeric build, got \""
                    + version + "\"");
        }
        String jar = safeJar("BungeeCord-" + build + ".jar", "BungeeCord jar name");
        String url = requireDownloadUrl(bungeeArtifactUrl(build),
                "BungeeCord artifact for build " + build);
        return new InstallPlan(jar, url, "", "", "", "", List.of(), "",
                Strategy.DIRECT, "");
    }

    private InstallPlan resolveNanoLimbo(String version) throws IOException, InterruptedException {
        String url = "latest".equals(version)
                ? NANOLIMBO + "/latest" : NANOLIMBO + "/tags/" + path(version);
        NanoLimboRelease release = parseNanoLimboRelease(fetch(url));
        // The asset is always NanoLimbo.jar upstream; still validate the upstream name before
        // deriving the versioned local name.
        safeJar(release.assetName(), "NanoLimbo release asset");
        String tag = safeTag(release.tag());
        String jar = safeJar("NanoLimbo-" + tag + ".jar", "NanoLimbo jar name");
        String download = requireDownloadUrl(release.url(),
                "NanoLimbo release " + release.tag());
        String sha256 = normaliseSha256(release.sha256(),
                "NanoLimbo release " + release.tag());
        return new InstallPlan(jar, download, sha256, "", "", "", List.of(), "",
                Strategy.DIRECT, "");
    }

    private InstallPlan resolveFabric(String version) throws IOException, InterruptedException {
        InstallerFile installer = parseFabricInstaller(
                fetch(FABRIC + "/v2/versions/installer"));
        String loader = parseFabricLoader(
                fetch(FABRIC + "/v2/versions/loader/" + path(version)));
        String installerUrl = requireDownloadUrl(installer.url(),
                "Fabric installer " + installer.version());
        String installerSha = installer.sha256();
        if (installerSha.isBlank()) {
            installerSha = fetchSha256Sidecar(installerUrl);
        }
        String output = "fabric-server-launch.jar";
        List<String> args = List.of("server", "-mcversion", version,
                "-loader", loader, "-downloadMinecraft");
        return new InstallPlan(output, "", "", "", installerUrl, installerSha,
                args, output, Strategy.INSTALLER, "");
    }

    private InstallPlan resolveQuilt(String version) throws IOException, InterruptedException {
        InstallerFile installer = parseQuiltInstaller(
                fetch(QUILT + "/v3/versions/installer"));
        String installerUrl = requireDownloadUrl(installer.url(),
                "Quilt installer " + installer.version());
        String output = "quilt-server-launch.jar";
        List<String> args = List.of("install", "server", version, "--download-server");
        return new InstallPlan(output, "", "", "", installerUrl, installer.sha256(),
                args, output, Strategy.INSTALLER, "");
    }

    private static String fillProject(String softwareId) throws IOException {
        return switch (softwareId) {
            case "paper" -> "paper";
            case "folia" -> "folia";
            case "velocity" -> "velocity";
            case "waterfall" -> "waterfall";
            case "travertine" -> "travertine";
            default -> throw new IOException("fill does not publish " + softwareId);
        };
    }

    // ---- pure parsers (network-free; used by ChecksMain fixtures) ----------------------------

    /**
     * Parses fill v3 {@code /projects/{id}/versions}; returns version ids (for example
     * {@code 1.21.8}) newest first.
     */
    public static List<String> parseFillVersionIds(String json) throws IOException {
        List<String> ids = new ArrayList<>();
        for (FillVersion entry : parseFillVersionEntries(json)) ids.add(entry.id());
        return ids;
    }

    /**
     * Parses fill v3 {@code /projects/{id}/versions}. Each entry has a version id and its highest
     * build number (0 when the response did not include builds).
     */
    private static List<FillVersion> parseFillVersionEntries(String json) throws IOException {
        Object root = JsonParser.parse(json);
        Object raw;
        if (root instanceof List) {
            raw = root;
        } else if (root instanceof Map) {
            raw = asMap(root, "fill versions response").get("versions");
        } else {
            throw new IOException("Malformed fill versions JSON: expected an object or array");
        }
        if (raw == null) {
            throw new IOException("Malformed fill versions JSON: missing \"versions\"");
        }

        Map<String, FillVersion> found = new LinkedHashMap<>();
        if (raw instanceof List) {
            for (Object item : asList(raw, "fill versions.versions")) {
                FillVersion entry = fillVersionEntry(item);
                if (entry != null) mergeFillVersion(found, entry);
            }
        } else if (raw instanceof Map) {
            Map<String, Object> versions = asMap(raw, "fill versions.versions");
            for (Map.Entry<String, Object> item : versions.entrySet()) {
                String key = item.getKey() == null ? "" : item.getKey().trim();
                Object value = item.getValue();
                if (value instanceof List) {
                    long maxBuild = 0;
                    boolean addedString = false;
                    for (Object element : asList(value, "fill versions.versions." + key)) {
                        Long number = asLong(element);
                        if (number != null) {
                            maxBuild = Math.max(maxBuild, number);
                            continue;
                        }
                        String id = asStringValue(element);
                        if (id != null && !id.isBlank()) {
                            mergeFillVersion(found, new FillVersion(id.trim(), 0));
                            addedString = true;
                        }
                    }
                    if (!addedString && !key.isEmpty()) {
                        mergeFillVersion(found, new FillVersion(key, maxBuild));
                    }
                } else if (value instanceof Map) {
                    FillVersion entry = fillVersionEntry(value);
                    if (entry != null) mergeFillVersion(found, entry);
                } else {
                    String id = asStringValue(value);
                    if (id == null || id.isBlank()) id = key;
                    if (!id.isBlank()) mergeFillVersion(found, new FillVersion(id.trim(), 0));
                }
            }
        } else {
            throw new IOException("Malformed fill versions JSON: \"versions\" must be a list or object");
        }

        List<FillVersion> out = new ArrayList<>(found.values());
        if (out.isEmpty()) {
            throw new IOException("Malformed fill versions JSON: no versions found");
        }
        out.sort((a, b) -> compareVersionIds(b.id(), a.id()));
        return out;
    }

    private static FillVersion fillVersionEntry(Object item) throws IOException {
        if (item instanceof String) {
            String id = ((String) item).trim();
            return id.isEmpty() ? null : new FillVersion(id, 0);
        }
        if (!(item instanceof Map)) return null;
        Map<String, Object> map = asMap(item, "fill version entry");
        String id = null;
        Object version = map.get("version");
        if (version instanceof Map) {
            id = optionalString(asMap(version, "fill version entry.version"), "id");
        } else {
            id = asStringValue(version);
        }
        if (id == null || id.isBlank()) id = optionalString(map, "id");
        if (id == null || id.isBlank()) {
            Object rawVersion = map.get("version");
            id = rawVersion instanceof String ? (String) rawVersion : null;
        }
        if (id == null || id.isBlank()) return null;

        long maxBuild = 0;
        Object builds = map.get("builds");
        if (builds instanceof List) {
            for (Object build : asList(builds, "fill version entry.builds")) {
                Long number = asLong(build);
                if (number != null) maxBuild = Math.max(maxBuild, number);
            }
        }
        return new FillVersion(id.trim(), maxBuild);
    }

    private static void mergeFillVersion(Map<String, FillVersion> found, FillVersion entry) {
        FillVersion previous = found.get(entry.id());
        if (previous == null || entry.latestBuild() > previous.latestBuild()) {
            found.put(entry.id(), entry);
        }
    }

    /**
     * Parses fill v3 {@code /versions/{version}/builds/latest}. The primary download key is
     * {@code server:default}; Travertine publishes only {@code application}, so that entry (with
     * its own name, URL and checksums) is used as a fallback when {@code server:default} is absent.
     *
     * @throws IOException when the response has neither download entry
     */
    public static FillBuild parseFillBuild(String json) throws IOException {
        Map<String, Object> root = asMap(JsonParser.parse(json), "fill build response");
        Object downloadsRaw = root.get("downloads");
        if (!(downloadsRaw instanceof Map)) {
            throw new IOException("Malformed fill build JSON: missing \"downloads\" object");
        }
        Map<String, Object> downloads = asMap(downloadsRaw, "fill build.downloads");
        Map<String, Object> server = fillDownload(downloads, "server:default");
        if (server == null) server = fillDownload(downloads, "application");
        if (server == null) {
            throw new IOException("Malformed fill build JSON: missing downloads[\"server:default\"] "
                    + "(no usable \"application\" fallback either)");
        }
        String name = requiredString(server, "name", "fill build download");
        String url = requiredString(server, "url", "fill build download");
        String sha256 = "";
        Object checksumsRaw = server.get("checksums");
        if (checksumsRaw instanceof Map) {
            Map<String, Object> checksums = asMap(checksumsRaw, "fill build download.checksums");
            sha256 = optionalString(checksums, "sha256");
            if (sha256 == null) sha256 = "";
            sha256 = normaliseSha256(sha256, "fill build " + name);
        }
        long size = 0;
        Object sizeRaw = server.get("size");
        Long parsedSize = asLong(sizeRaw);
        if (parsedSize != null) size = parsedSize;
        return new FillBuild(name, url, sha256, size);
    }

    /** One fill download entry, or null when the key is absent or has no usable URL. */
    private static Map<String, Object> fillDownload(Map<String, Object> downloads, String key)
            throws IOException {
        Object raw = downloads.get(key);
        if (!(raw instanceof Map)) return null;
        Map<String, Object> download = asMap(raw, "fill build.downloads[\"" + key + "\"]");
        String url = asStringValue(download.get("url"));
        return url == null || url.isBlank() ? null : download;
    }

    /** Parses the Purpur v2 project response and returns versions newest first. */
    public static List<String> parsePurpurVersions(String json) throws IOException {
        Object root = JsonParser.parse(json);
        Object raw;
        if (root instanceof List) {
            raw = root;
        } else if (root instanceof Map) {
            raw = asMap(root, "Purpur versions response").get("versions");
        } else {
            throw new IOException("Malformed Purpur versions JSON: expected an object or array");
        }
        if (!(raw instanceof List)) {
            throw new IOException("Malformed Purpur versions JSON: missing \"versions\" list");
        }
        List<String> ids = new ArrayList<>();
        for (Object item : asList(raw, "Purpur versions")) {
            String id = asStringValue(item);
            if (id != null && !id.isBlank() && !ids.contains(id.trim())) ids.add(id.trim());
        }
        if (ids.isEmpty()) {
            throw new IOException("Malformed Purpur versions JSON: no versions found");
        }
        ids.sort((a, b) -> compareVersionIds(b, a));
        return ids;
    }

    /** Parses the Purpur v2 version response and returns {@code builds.latest}. */
    public static String parsePurpurBuild(String json) throws IOException {
        Map<String, Object> root = asMap(JsonParser.parse(json), "Purpur build response");
        Object buildsRaw = root.get("builds");
        if (!(buildsRaw instanceof Map)) {
            throw new IOException("Malformed Purpur build JSON: missing \"builds\" object");
        }
        Map<String, Object> builds = asMap(buildsRaw, "Purpur build.builds");
        String latest = asStringValue(builds.get("latest"));
        if (latest == null || latest.isBlank()) {
            throw new IOException("Malformed Purpur build JSON: missing builds.latest");
        }
        return latest.trim();
    }

    /** Parses the Mojang version manifest and returns {@code latest.release}. */
    public static String parseVanillaManifest(String json) throws IOException {
        Map<String, Object> root = asMap(JsonParser.parse(json), "Mojang manifest");
        Object latestRaw = root.get("latest");
        if (latestRaw instanceof Map) {
            String release = optionalString(asMap(latestRaw, "Mojang manifest.latest"), "release");
            if (release != null && !release.isBlank()) return release.trim();
        }
        List<String> releases = parseVanillaReleases(json);
        if (releases.isEmpty()) {
            throw new IOException("Malformed Mojang manifest: no release version found");
        }
        return releases.get(0);
    }

    /** Parses the Mojang manifest and returns all release ids, newest first. */
    public static List<String> parseVanillaReleases(String json) throws IOException {
        Map<String, Object> root = asMap(JsonParser.parse(json), "Mojang manifest");
        Object versionsRaw = root.get("versions");
        if (!(versionsRaw instanceof List)) {
            throw new IOException("Malformed Mojang manifest: missing \"versions\" list");
        }
        List<String> ids = new ArrayList<>();
        for (Object item : asList(versionsRaw, "Mojang manifest.versions")) {
            Map<String, Object> version = asMap(item, "Mojang manifest version");
            String type = optionalString(version, "type");
            String id = optionalString(version, "id");
            if (id == null || id.isBlank()) continue;
            if (!"release".equals(type)) continue;
            if (!ids.contains(id.trim())) ids.add(id.trim());
        }
        if (ids.isEmpty()) {
            throw new IOException("Malformed Mojang manifest: no release versions found");
        }
        ids.sort((a, b) -> compareVersionIds(b, a));
        return ids;
    }

    /**
     * Finds the version JSON URL for {@code version} in a Mojang manifest. Public so fixtures can
     * check the manifest walk without network access.
     */
    public static String parseVanillaVersionUrl(String json, String version) throws IOException {
        Map<String, Object> root = asMap(JsonParser.parse(json), "Mojang manifest");
        Object versionsRaw = root.get("versions");
        if (!(versionsRaw instanceof List)) {
            throw new IOException("Malformed Mojang manifest: missing \"versions\" list");
        }
        for (Object item : asList(versionsRaw, "Mojang manifest.versions")) {
            Map<String, Object> entry = asMap(item, "Mojang manifest version");
            String id = optionalString(entry, "id");
            if (id == null || !id.trim().equals(version)) continue;
            String url = optionalString(entry, "url");
            if (url == null || url.isBlank()) {
                throw new IOException("Mojang manifest version " + version + " has no URL");
            }
            return url.trim();
        }
        throw new IOException("Mojang manifest has no version \"" + version + "\"");
    }

    /** Parses a Mojang version JSON and returns its server download. */
    public static VanillaDownload parseVanillaVersionJson(String json) throws IOException {
        Map<String, Object> root = asMap(JsonParser.parse(json), "Mojang version JSON");
        Object downloadsRaw = root.get("downloads");
        if (!(downloadsRaw instanceof Map)) {
            throw new IOException("Malformed Mojang version JSON: missing \"downloads\" object");
        }
        Object serverRaw = asMap(downloadsRaw, "Mojang version.downloads").get("server");
        if (!(serverRaw instanceof Map)) {
            throw new IOException("Malformed Mojang version JSON: missing downloads.server");
        }
        Map<String, Object> server = asMap(serverRaw, "Mojang version.downloads.server");
        String url = requiredString(server, "url", "Mojang server download");
        String sha1 = optionalString(server, "sha1");
        if (sha1 == null) sha1 = "";
        sha1 = normaliseSha1(sha1, "Mojang server download");
        String version = optionalString(root, "id");
        if (version == null) version = "";
        return new VanillaDownload(version.trim(), url, sha1);
    }

    /** Parses Fabric/Quilt {@code versions/game} and returns stable versions newest first. */
    private static List<String> parseGameVersions(String json, String context) throws IOException {
        Object root = JsonParser.parse(json);
        if (!(root instanceof List)) {
            throw new IOException("Malformed " + context + " JSON: expected an array");
        }
        List<String> ids = new ArrayList<>();
        for (Object item : asList(root, context)) {
            Map<String, Object> entry = asMap(item, context + " entry");
            String id = optionalString(entry, "version");
            Object stable = entry.get("stable");
            if (id == null || id.isBlank()) continue;
            if (!Boolean.TRUE.equals(stable)) continue;
            if (!ids.contains(id.trim())) ids.add(id.trim());
        }
        if (ids.isEmpty()) {
            throw new IOException("Malformed " + context + " JSON: no stable versions found");
        }
        ids.sort((a, b) -> compareVersionIds(b, a));
        return ids;
    }

    /** Public Fabric game-version parser used by fixture checks. */
    public static List<String> parseFabricGameVersions(String json) throws IOException {
        return parseGameVersions(json, "Fabric game versions");
    }

    /** Public Quilt game-version parser used by fixture checks. */
    public static List<String> parseQuiltGameVersions(String json) throws IOException {
        return parseGameVersions(json, "Quilt game versions");
    }

    /**
     * Parses {@code meta.fabricmc.net/v2/versions/installer}; prefers the newest entry marked
     * stable. Fabric meta does not include a checksum, so {@code sha256} is empty here.
     */
    public static InstallerFile parseFabricInstaller(String json) throws IOException {
        List<Object> entries = installerEntries(json, "Fabric installer");
        InstallerFile fallback = null;
        for (Object item : entries) {
            Map<String, Object> entry = asMap(item, "Fabric installer entry");
            String url = optionalString(entry, "url");
            if (url == null || url.isBlank()) continue;
            String version = optionalString(entry, "version");
            if (version == null) version = "";
            String sha256 = optionalSha256(entry, "Fabric installer " + version);
            InstallerFile file = new InstallerFile(version.trim(), url.trim(), sha256);
            if (fallback == null) fallback = file;
            if (Boolean.TRUE.equals(entry.get("stable"))) return file;
        }
        if (fallback == null) {
            throw new IOException("Malformed Fabric installer JSON: no installer URL");
        }
        return fallback;
    }

    /** Parses {@code meta.quiltmc.org/v3/versions/installer}; uses the first entry. */
    public static InstallerFile parseQuiltInstaller(String json) throws IOException {
        List<Object> entries = installerEntries(json, "Quilt installer");
        for (Object item : entries) {
            Map<String, Object> entry = asMap(item, "Quilt installer entry");
            String url = optionalString(entry, "url");
            if (url == null || url.isBlank()) continue;
            String version = optionalString(entry, "version");
            if (version == null) version = "";
            String sha256 = optionalSha256(entry, "Quilt installer " + version);
            Object hashesRaw = entry.get("hashes");
            if (sha256.isBlank() && hashesRaw instanceof Map) {
                sha256 = optionalSha256(asMap(hashesRaw, "Quilt installer.hashes"),
                        "Quilt installer " + version);
            }
            return new InstallerFile(version.trim(), url.trim(), sha256);
        }
        throw new IOException("Malformed Quilt installer JSON: no installer URL");
    }

    private static List<Object> installerEntries(String json, String context) throws IOException {
        Object root = JsonParser.parse(json);
        if (root instanceof List) return asList(root, context);
        if (root instanceof Map) {
            Object nested = asMap(root, context + " response").get("installer");
            if (nested instanceof List) return asList(nested, context);
        }
        throw new IOException("Malformed " + context + " JSON: expected an array");
    }

    /** Parses {@code meta.fabricmc.net/v2/versions/loader/{mc}} and returns a stable loader. */
    public static String parseFabricLoader(String json) throws IOException {
        Object root = JsonParser.parse(json);
        if (!(root instanceof List)) {
            throw new IOException("Malformed Fabric loader JSON: expected an array");
        }
        String fallback = null;
        for (Object item : asList(root, "Fabric loader")) {
            Map<String, Object> entry = asMap(item, "Fabric loader entry");
            Object loaderRaw = entry.get("loader");
            if (!(loaderRaw instanceof Map)) continue;
            Map<String, Object> loader = asMap(loaderRaw, "Fabric loader entry.loader");
            String version = optionalString(loader, "version");
            if (version == null || version.isBlank()) continue;
            if (fallback == null) fallback = version.trim();
            if (Boolean.TRUE.equals(loader.get("stable"))) return version.trim();
        }
        if (fallback == null) {
            throw new IOException("Malformed Fabric loader JSON: no loader version");
        }
        return fallback;
    }

    /** Parses a single NanoLimbo GitHub release object. */
    public static NanoLimboRelease parseNanoLimboRelease(String json) throws IOException {
        Map<String, Object> release = asMap(JsonParser.parse(json), "NanoLimbo release");
        String tag = optionalString(release, "tag_name");
        if (tag == null || tag.isBlank()) {
            throw new IOException("Malformed NanoLimbo release JSON: missing \"tag_name\"");
        }
        String name = optionalString(release, "name");
        if (name == null) name = tag;
        Object assetsRaw = release.get("assets");
        if (!(assetsRaw instanceof List)) {
            throw new IOException("Malformed NanoLimbo release JSON: missing \"assets\" list");
        }
        Map<String, Object> chosen = null;
        for (Object item : asList(assetsRaw, "NanoLimbo release.assets")) {
            Map<String, Object> asset = asMap(item, "NanoLimbo release asset");
            String assetName = optionalString(asset, "name");
            if (assetName == null) continue;
            if (assetName.equalsIgnoreCase("NanoLimbo.jar")) {
                chosen = asset;
                break;
            }
            if (chosen == null && assetName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                chosen = asset;
            }
        }
        if (chosen == null) {
            throw new IOException("NanoLimbo release " + tag + " has no jar asset");
        }
        String assetName = requiredString(chosen, "name", "NanoLimbo asset");
        String url = requiredString(chosen, "browser_download_url", "NanoLimbo asset");
        String sha256 = optionalString(chosen, "digest");
        if (sha256 == null) sha256 = "";
        sha256 = normaliseSha256(sha256, "NanoLimbo asset " + assetName);
        return new NanoLimboRelease(tag.trim(), name.trim(), assetName.trim(), url.trim(), sha256);
    }

    /** Parses the GitHub releases array into version options (tag id, release name label). */
    public static List<VersionOption> parseNanoLimboReleases(String json) throws IOException {
        Object root = JsonParser.parse(json);
        if (!(root instanceof List)) {
            throw new IOException("Malformed NanoLimbo releases JSON: expected an array");
        }
        List<VersionOption> out = new ArrayList<>();
        for (Object item : asList(root, "NanoLimbo releases")) {
            Map<String, Object> release = asMap(item, "NanoLimbo release entry");
            if (Boolean.TRUE.equals(release.get("draft"))) continue;
            String tag = optionalString(release, "tag_name");
            if (tag == null || tag.isBlank()) continue;
            String name = optionalString(release, "name");
            String label = name == null || name.isBlank() ? tag.trim() : name.trim();
            out.add(new VersionOption(tag.trim(), label, tag.trim()));
        }
        if (out.isEmpty()) {
            throw new IOException("Malformed NanoLimbo releases JSON: no published releases found");
        }
        return out;
    }

    /** Parses a Jenkins build JSON and returns {@code number}. */
    public static int parseBungeeBuild(String json) throws IOException {
        Map<String, Object> root = asMap(JsonParser.parse(json), "BungeeCord Jenkins build");
        Object numberRaw = root.get("number");
        if (numberRaw == null) numberRaw = root.get("buildNumber");
        Long number = asLong(numberRaw);
        if (number == null || number <= 0 || number > Integer.MAX_VALUE) {
            throw new IOException("Malformed BungeeCord build JSON: missing valid \"number\"");
        }
        return number.intValue();
    }

    /**
     * Extracts the first SHA-256 hex digest from a Maven/Fabric {@code .sha256} sidecar or a
     * {@code sha256sum} file. Handles {@code <hex>  <file>}, {@code <file>  <hex>} and
     * {@code sha256:<hex>} layouts; returns an empty string when no digest is present.
     */
    public static String parseSha256Sidecar(String body) {
        if (body == null) return "";
        for (String line : body.split("\\R")) {
            String digest = extractDigest(line, SHA256_HEX);
            if (!digest.isEmpty()) return digest;
        }
        return extractDigest(body, SHA256_HEX);
    }

    // ---- HTTP --------------------------------------------------------------------------------

    /** Cached GET; falls back to a stale body when the upstream request fails. */
    private String fetch(String url) throws IOException, InterruptedException {
        requireAllowedUrl(url, "Metadata URL");
        long now = System.currentTimeMillis();
        Cached cached = cache.get(url);
        if (cached != null && now - cached.at() < CACHE_TTL_MS) return cached.body();

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json, text/plain, */*")
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            requireAllowedUrl(response.uri().toString(), "Redirected URL");
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " from " + url);
            }
            String body = response.body();
            if (body == null || body.isBlank()) {
                throw new IOException("Empty response body from " + url);
            }
            store(url, now, body);
            return body;
        } catch (IOException e) {
            if (cached != null && cached.body() != null && !cached.body().isBlank()) {
                return cached.body();
            }
            throw e;
        }
    }

    private void store(String url, long now, String body) {
        if (cache.size() >= CACHE_MAX) {
            long cutoff = now - CACHE_TTL_MS;
            cache.entrySet().removeIf(entry -> entry.getValue().at() < cutoff);
        }
        if (cache.size() >= CACHE_MAX) cache.clear();
        cache.put(url, new Cached(now, body));
    }

    private String fetchSha256Sidecar(String installerUrl) throws IOException, InterruptedException {
        try {
            String body = fetch(installerUrl + ".sha256");
            return parseSha256Sidecar(body);
        } catch (IOException e) {
            // Some old installers have no sidecar; the caller can still verify size/ZIP after download.
            return "";
        }
    }

    private static String requireDownloadUrl(String url, String context) throws IOException {
        requireAllowedUrl(url, context);
        return url;
    }

    private static void requireAllowedUrl(String raw, String context) throws IOException {
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException e) {
            throw new IOException(context + ": invalid URL \"" + raw + "\"", e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !"https".equalsIgnoreCase(scheme)) {
            throw new IOException(context + ": only HTTPS URLs are allowed (" + raw + ")");
        }
        if (uri.getUserInfo() != null) {
            throw new IOException(context + ": URLs with credentials are not allowed");
        }
        String host = uri.getHost();
        if (host == null) throw new IOException(context + ": URL has no host (" + raw + ")");
        host = host.toLowerCase(Locale.ROOT);
        if (!HOSTS.contains(host)) {
            throw new IOException(context + ": host \"" + host + "\" is not allowed (" + raw + ")");
        }
        int port = uri.getPort();
        if (port != -1 && port != 443) {
            throw new IOException(context + ": only the default HTTPS port is allowed (" + raw + ")");
        }
    }

    /**
     * Re-checks a resolver result before it leaves the registry: all URLs must be HTTPS on an
     * allowlisted host, digests must be clean hex, and jar names must be bare file names.
     */
    private static InstallPlan validatePlan(InstallPlan plan) throws IOException {
        if (plan.strategy() == Strategy.MANUAL) return plan;
        String jar = safeJar(plan.jarName(), "install plan jar");
        String output = plan.outputJar() == null || plan.outputJar().isBlank()
                ? "" : safeJar(plan.outputJar(), "install plan output jar");
        String url = plan.url() == null ? "" : plan.url().trim();
        if (!url.isEmpty()) {
            url = requireDownloadUrl(url, "install plan download");
        } else if (plan.strategy() == Strategy.DIRECT) {
            throw new IOException("Install plan for " + jar + " has no download URL");
        }
        String installerUrl = plan.installerUrl() == null ? "" : plan.installerUrl().trim();
        if (!installerUrl.isEmpty()) {
            installerUrl = requireDownloadUrl(installerUrl, "install plan installer download");
        } else if (plan.strategy() == Strategy.INSTALLER) {
            throw new IOException("Install plan for " + jar + " has no installer URL");
        }
        List<String> args = plan.installerArgs() == null
                ? List.of() : List.copyOf(plan.installerArgs());
        for (String arg : args) {
            if (arg == null || arg.isBlank()) {
                throw new IOException("Install plan for " + jar + " has a blank installer argument");
            }
        }
        return new InstallPlan(jar, url,
                normaliseSha256(plan.sha256(), "install plan SHA-256"),
                normaliseSha1(plan.sha1(), "install plan SHA-1"),
                installerUrl,
                normaliseSha256(plan.installerSha256(), "install plan installer SHA-256"),
                args, output, plan.strategy(), plan.reason() == null ? "" : plan.reason());
    }

    // ---- validation helpers ------------------------------------------------------------------

    private Software require(String id) throws IOException {
        return find(id).orElseThrow(() -> new IOException(
                "Unknown software: " + (id == null ? "null" : "\"" + id + "\"")));
    }

    private static String path(String value) throws IOException {
        if (!validVersion(value)) throw new IOException("Invalid version id: \"" + value + "\"");
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String safeJar(String name, String context) throws IOException {
        if (name == null || name.isBlank()) {
            throw new IOException(context + ": upstream did not provide a jar name");
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.indexOf(':') >= 0 || name.contains("..")) {
            throw new IOException(context + ": unsafe jar name \"" + name + "\"");
        }
        String safe = Addons.sanitizeJar(name);
        if (safe == null) {
            throw new IOException(context + ": unsafe jar name \"" + name + "\"");
        }
        return safe;
    }

    private static String safeTag(String tag) throws IOException {
        if (tag == null || tag.isBlank()) {
            throw new IOException("NanoLimbo release has no tag");
        }
        if (tag.indexOf('/') >= 0 || tag.indexOf('\\') >= 0 || tag.contains("..")) {
            throw new IOException("NanoLimbo release tag is unsafe: \"" + tag + "\"");
        }
        return tag.replaceAll("[^A-Za-z0-9._+-]", "_");
    }

    private static String normaliseSha256(String raw, String context) throws IOException {
        if (raw == null || raw.isBlank()) return "";
        String value = extractDigest(raw, SHA256_HEX);
        if (value.isEmpty()) {
            throw new IOException(context + ": invalid SHA-256 \"" + raw + "\"");
        }
        return value;
    }

    private static String normaliseSha1(String raw, String context) throws IOException {
        if (raw == null || raw.isBlank()) return "";
        String value = extractDigest(raw, SHA1_HEX);
        if (value.isEmpty()) {
            throw new IOException(context + ": invalid SHA-1 \"" + raw + "\"");
        }
        return value;
    }

    /**
     * Finds the first whitespace/comma separated token in {@code raw} that is exactly a digest of
     * the given shape. Accepts the common {@code sha256:<hex>}, {@code SHA256 (<file>) = <hex>},
     * {@code <hex>  <file>} and {@code <file>  <hex>} spellings.
     */
    private static String extractDigest(String raw, Pattern hex) {
        if (raw == null || raw.isBlank()) return "";
        for (String token : raw.trim().split("[\\s,;]+")) {
            String candidate = trimDigestPunctuation(token);
            String lower = candidate.toLowerCase(Locale.ROOT);
            for (String prefix : DIGEST_PREFIXES) {
                if (lower.startsWith(prefix)) {
                    candidate = candidate.substring(prefix.length());
                    break;
                }
            }
            candidate = trimDigestPunctuation(candidate);
            if (hex.matcher(candidate).matches()) return candidate.toLowerCase(Locale.ROOT);
        }
        return "";
    }

    private static String trimDigestPunctuation(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && "()[]{}<>=:\"',*".indexOf(value.charAt(start)) >= 0) start++;
        while (end > start && "()[]{}<>=:\"',*".indexOf(value.charAt(end - 1)) >= 0) end--;
        return value.substring(start, end);
    }

    private static String optionalSha256(Map<String, Object> map, String context)
            throws IOException {
        String raw = optionalString(map, "sha256");
        if (raw == null) return "";
        return normaliseSha256(raw, context);
    }

    // ---- small JSON accessors ----------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value, String context) throws IOException {
        if (value instanceof Map) return (Map<String, Object>) value;
        throw new IOException("Malformed JSON (" + context + "): expected an object");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value, String context) throws IOException {
        if (value instanceof List) return (List<Object>) value;
        throw new IOException("Malformed JSON (" + context + "): expected an array");
    }

    private static String requiredString(Map<String, Object> map, String key, String context)
            throws IOException {
        String value = asStringValue(map.get(key));
        if (value == null || value.isBlank()) {
            throw new IOException("Malformed JSON (" + context + "): missing \"" + key + "\"");
        }
        return value;
    }

    private static String optionalString(Map<String, Object> map, String key) {
        return asStringValue(map.get(key));
    }

    private static String asStringValue(Object value) {
        if (value instanceof String) return (String) value;
        if (value instanceof Long || value instanceof Double || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return null;
    }

    private static Long asLong(Object value) {
        if (value instanceof Long) return (Long) value;
        if (value instanceof Double) {
            double d = (Double) value;
            if (Double.isFinite(d) && d == Math.rint(d) && d >= Long.MIN_VALUE
                    && d <= Long.MAX_VALUE) {
                return (long) d;
            }
            return null;
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            if (text.matches("-?[0-9]{1,18}")) {
                try {
                    return Long.parseLong(text);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    // ---- minimal JSON parser (JDK only; no third-party dependency) ----------------------------

    /**
     * Recursive-descent parser for the JSON subset used by the upstream APIs. Objects become
     * {@code LinkedHashMap}, arrays {@code ArrayList}, numbers {@code Long} or {@code Double}.
     */
    private static final class JsonParser {

        private final String text;
        private int index;
        private int depth;

        private JsonParser(String text) {
            this.text = text;
        }

        static Object parse(String json) throws IOException {
            if (json == null) throw new IOException("Malformed JSON: empty response");
            JsonParser parser = new JsonParser(json);
            Object value = parser.value();
            parser.skipWhitespace();
            if (parser.index < parser.text.length()) {
                throw parser.error("unexpected trailing data");
            }
            return value;
        }

        private Object value() throws IOException {
            skipWhitespace();
            if (index >= text.length()) throw error("unexpected end of input");
            char c = text.charAt(index);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) yield number();
                    throw error("unexpected character '" + c + "'");
                }
            };
        }

        private Map<String, Object> object() throws IOException {
            enter();
            Map<String, Object> map = new LinkedHashMap<>();
            index++; // {
            skipWhitespace();
            if (peek('}')) {
                index++;
                depth--;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (index >= text.length() || text.charAt(index) != '"') {
                    throw error("expected a string key");
                }
                String key = string();
                skipWhitespace();
                expect(':');
                map.put(key, value());
                skipWhitespace();
                if (peek('}')) {
                    index++;
                    depth--;
                    return map;
                }
                expect(',');
            }
        }

        private List<Object> array() throws IOException {
            enter();
            List<Object> list = new ArrayList<>();
            index++; // [
            skipWhitespace();
            if (peek(']')) {
                index++;
                depth--;
                return list;
            }
            while (true) {
                list.add(value());
                skipWhitespace();
                if (peek(']')) {
                    index++;
                    depth--;
                    return list;
                }
                expect(',');
            }
        }

        private String string() throws IOException {
            index++; // opening quote
            StringBuilder out = new StringBuilder();
            while (index < text.length()) {
                char c = text.charAt(index++);
                if (c == '"') return out.toString();
                if (c == '\\') {
                    if (index >= text.length()) throw error("unterminated escape");
                    char escape = text.charAt(index++);
                    switch (escape) {
                        case '"' -> out.append('"');
                        case '\\' -> out.append('\\');
                        case '/' -> out.append('/');
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> {
                            if (index + 4 > text.length()) throw error("truncated unicode escape");
                            int code = 0;
                            for (int i = 0; i < 4; i++) {
                                int digit = Character.digit(text.charAt(index + i), 16);
                                if (digit < 0) throw error("invalid unicode escape");
                                code = code * 16 + digit;
                            }
                            index += 4;
                            out.append((char) code);
                        }
                        default -> throw error("invalid escape '\\" + escape + "'");
                    }
                } else {
                    if (c < 0x20) throw error("control character in string");
                    out.append(c);
                }
            }
            throw error("unterminated string");
        }

        private Object number() throws IOException {
            int start = index;
            if (peek('-')) index++;
            if (index >= text.length()) throw error("truncated number");
            char c = text.charAt(index);
            if (c == '0') {
                index++;
            } else if (c >= '1' && c <= '9') {
                while (index < text.length() && isDigit(text.charAt(index))) index++;
            } else {
                throw error("invalid number");
            }
            boolean floating = false;
            if (index < text.length() && text.charAt(index) == '.') {
                floating = true;
                index++;
                if (index >= text.length() || !isDigit(text.charAt(index))) {
                    throw error("invalid fraction");
                }
                while (index < text.length() && isDigit(text.charAt(index))) index++;
            }
            if (index < text.length() && (text.charAt(index) == 'e'
                    || text.charAt(index) == 'E')) {
                floating = true;
                index++;
                if (index < text.length() && (text.charAt(index) == '+'
                        || text.charAt(index) == '-')) index++;
                if (index >= text.length() || !isDigit(text.charAt(index))) {
                    throw error("invalid exponent");
                }
                while (index < text.length() && isDigit(text.charAt(index))) index++;
            }
            String raw = text.substring(start, index);
            try {
                if (floating) return Double.valueOf(raw);
                return Long.valueOf(raw);
            } catch (NumberFormatException e) {
                throw error("invalid number \"" + raw + "\"");
            }
        }

        private Object literal(String expected, Object value) throws IOException {
            if (!text.startsWith(expected, index)) throw error("invalid literal");
            index += expected.length();
            return value;
        }

        private void enter() throws IOException {
            depth++;
            if (depth > MAX_JSON_DEPTH) throw error("JSON is nested too deeply");
        }

        private void expect(char expected) throws IOException {
            skipWhitespace();
            if (index >= text.length() || text.charAt(index) != expected) {
                throw error("expected '" + expected + "'");
            }
            index++;
        }

        private boolean peek(char expected) {
            return index < text.length() && text.charAt(index) == expected;
        }

        private void skipWhitespace() {
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') index++;
                else break;
            }
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private IOException error(String message) {
            return new IOException("Malformed JSON at offset " + index + ": " + message);
        }
    }
}
