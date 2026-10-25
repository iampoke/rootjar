package rootjar;

import rootjar.addons.Addons;
import rootjar.config.ChildConfig;
import rootjar.config.ConfigException;
import rootjar.config.ConfigLoader;
import rootjar.config.ConfigSaver;
import rootjar.config.ConfigValidator;
import rootjar.config.JavaRuntimes;
import rootjar.config.RootConfig;
import rootjar.config.PasswordHasher;
import rootjar.config.SoftwareRegistry;
import rootjar.config.StopStrategy;
import rootjar.console.LogRingBuffer;
import rootjar.console.StdinRouter;
import rootjar.console.StdinRouter.Kind;
import rootjar.supervisor.BackoffPolicy;
import rootjar.supervisor.ChildProcess;
import rootjar.supervisor.CommandLineBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Dependency-free unit checks (JUnit is not available offline). Exits 1 if anything fails. */
public final class ChecksMain {
    private static int passed, failed;

    private static final String SHA256_HEX =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String SHA1_HEX = "0123456789abcdef0123456789abcdef01234567";

    private static void check(boolean ok, String name) {
        if (ok) passed++;
        else { failed++; System.out.println("  CHECK FAILED: " + name); }
    }

    public static void main(String[] args) throws Exception {
        router();
        ringBuffer();
        backoff();
        passwords();
        commandLine();
        javaPathConfig();
        softwareRegistryParsers();
        versionOrdering();
        bungeeArtifactUrl();
        fillBuildTravertineFallback();
        compareVersionIdsEdges();
        javaRuntimeHelpers();
        commandLineJavaPath();
        configLoadsTemplateShape();
        validation();
        addons();
        System.out.println("unit checks: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void router() {
        StdinRouter r = new StdinRouter(Set.of("velocity", "nanolimbo"));
        check(r.parse("status").kind() == Kind.STATUS, "status");
        check(r.parse("HELP").kind() == Kind.HELP, "help case-insensitive");
        check(r.parse("stop").kind() == Kind.STOP_ALL, "bare stop");
        check(r.parse("  ").kind() == Kind.NONE, "blank");
        var c = r.parse("velocity.restart");
        check(c.kind() == Kind.LIFECYCLE && c.childId().equals("velocity") && c.text().equals("restart"), "id.restart");
        check(r.parse("nanolimbo.stop").kind() == Kind.LIFECYCLE, "id.stop");
        c = r.parse("velocity.mute");
        check(c.kind() == Kind.LIFECYCLE && c.text().equals("mute"), "id.mute");
        c = r.parse("velocity.unmute");
        check(c.kind() == Kind.LIFECYCLE && c.text().equals("unmute"), "id.unmute");
        c = r.parse("velocity plist");
        check(c.kind() == Kind.SEND && c.childId().equals("velocity") && c.text().equals("plist"), "id command");
        c = r.parse("nanolimbo say hello world\r");
        check(c.kind() == Kind.SEND && c.text().equals("say hello world"), "id command keeps args, drops \\r");
        c = r.parse("whitelist add Steve");
        check(c.kind() == Kind.FALLBACK && c.text().equals("whitelist add Steve"), "fallback");
        check(r.parse("ghost.stop").kind() == Kind.FALLBACK, "unknown id falls through");
        check(r.parse("stop now").kind() == Kind.FALLBACK, "'stop now' is not bare stop");
    }

    private static void ringBuffer() {
        LogRingBuffer b = new LogRingBuffer(3);
        check(b.lastSeq() == 0 && b.since(0).isEmpty(), "empty buffer");
        for (int i = 1; i <= 5; i++) b.append("line" + i);
        var all = b.since(0);
        check(all.size() == 3 && all.get(0).seq() == 3 && all.get(2).line().equals("line5"), "keeps newest 3");
        check(b.since(4).size() == 1 && b.since(4).get(0).line().equals("line5"), "since(4)");
        check(b.since(5).isEmpty(), "since(last) is empty");
        b.append("x".repeat(5000));
        check(b.since(5).get(0).line().length() < 2100, "long lines are truncated");
    }

    private static void backoff() {
        BackoffPolicy p = new BackoffPolicy(List.of(5, 10, 30));
        check(p.next() == 5 && p.next() == 10 && p.next() == 30 && p.next() == 30 && p.next() == 30, "escalates then caps");
        p.reset();
        check(p.next() == 5 && p.attempts() == 1, "reset");
    }

    private static void passwords() {
        String h = PasswordHasher.hash("correct horse".toCharArray());
        check(h.startsWith("pbkdf2$sha256$210000$"), "hash format");
        check(PasswordHasher.verify("correct horse".toCharArray(), h), "verify ok");
        check(!PasswordHasher.verify("wrong".toCharArray(), h), "verify wrong");
        check(!h.equals(PasswordHasher.hash("correct horse".toCharArray())), "salted: two hashes differ");
        check(PasswordHasher.isWellFormed(h) && !PasswordHasher.isWellFormed("placeholder"), "wellformed");
    }

    private static void commandLine() throws Exception {
        Path dir = Files.createTempDirectory("cl");
        RootConfig cfg = loadConfig(dir, "placeholder", 25568, "127.0.0.1", 25566, 25565, true);
        var cmd = CommandLineBuilder.build(cfg.children().get(0), "java");
        check(cmd.get(0).equals("java") && cmd.get(1).equals("-Xms256M") && cmd.get(2).equals("-Xmx256M"), "heap flags");
        check(cmd.indexOf("-XX:+UnlockExperimentalVMOptions") < cmd.indexOf("-XX:G1NewSizePercent=30"), "unlock before G1 flags");
        check(cmd.indexOf("-XX:+UseG1GC") > 0 && cmd.contains("-XX:MaxTenuringThreshold=1"), "gc flags");
        int n = cmd.size();
        check(cmd.get(n - 3).equals("-jar") && cmd.get(n - 2).equals("NanoLimbo.jar") && cmd.get(n - 1).equals("nogui"), "-jar jar nogui last");
        check(cfg.children().get(0).stopStrategy() == StopStrategy.BOTH, "strategy parsed");
    }

    // ---- Phase 3: per-server java path in config.yml -----------------------------------------

    private static void javaPathConfig() throws Exception {
        Path dir = Files.createTempDirectory("java-path");
        RootConfig base = loadConfig(dir, PasswordHasher.hash("password1".toCharArray()),
                25568, "127.0.0.1", 25566, 25565, true);

        // A normal POSIX path is written unquoted and round-trips.
        ChildConfig configured = base.children().get(0).withJavaPath("/opt/jdk-21/bin/java");
        Path saved = dir.resolve("saved.yml");
        ConfigSaver.save(saved, new RootConfig(base.server(), List.of(configured), base.baseDir()));
        String text = Files.readString(saved);
        check(text.contains("java: /opt/jdk-21/bin/java"), "java path saved unquoted");
        check(!text.contains("java: \"/opt/jdk-21/bin/java\""), "java path is not double-quoted");
        check(ConfigLoader.load(saved).children().get(0).javaPath().equals("/opt/jdk-21/bin/java"),
                "java path save/load round-trip");

        // A blank path must not emit a java key at all (older configs stay unchanged).
        ChildConfig blank = base.children().get(0).withJavaPath("  ");
        Path blankSaved = dir.resolve("blank.yml");
        ConfigSaver.save(blankSaved, new RootConfig(base.server(), List.of(blank), base.baseDir()));
        boolean hasJavaKey = Files.readAllLines(blankSaved).stream()
                .anyMatch(line -> line.trim().startsWith("java:"));
        check(!hasJavaKey, "blank java path omits the java key");
        check(ConfigLoader.load(blankSaved).children().get(0).javaPath().isEmpty(),
                "blank java path loads as empty");

        // Windows paths must survive byte-for-byte: the unquoted scalar keeps every backslash.
        String windows = "C:\\Program Files\\Eclipse Adoptium\\jdk-21\\bin\\java.exe";
        ChildConfig win = base.children().get(0).withJavaPath(windows);
        Path winSaved = dir.resolve("windows.yml");
        ConfigSaver.save(winSaved, new RootConfig(base.server(), List.of(win), base.baseDir()));
        check(Files.readString(winSaved).contains("java: " + windows),
                "windows java path saved unquoted");
        check(ConfigLoader.load(winSaved).children().get(0).javaPath().equals(windows),
                "windows java path survives save/load byte-for-byte");
    }

    // ---- Phase 3: SoftwareRegistry pure parsers against inline fixtures ----------------------

    private static void softwareRegistryParsers() throws Exception {
        // fill (Paper/Folia/Velocity/Waterfall/Travertine) versions, both shapes.
        String fillVersions = """
                {"versions":{"1.21.4":[232,231],"1.21.3":[88]}}
                """;
        List<String> fillIds = SoftwareRegistry.parseFillVersionIds(fillVersions);
        check(fillIds.size() == 2 && fillIds.get(0).equals("1.21.4"), "fill versions newest first");

        String fillVersionsList = """
                {"versions":[
                  {"version":{"id":"1.21.4"},"builds":[232,231]},
                  {"id":"1.20.4","builds":[496]}]}
                """;
        List<String> fillListIds = SoftwareRegistry.parseFillVersionIds(fillVersionsList);
        check(fillListIds.size() == 2 && fillListIds.contains("1.20.4")
                && fillListIds.get(0).equals("1.21.4"), "fill versions object array");

        String fillBuild = """
                {"id":232,"downloads":{"server:default":{
                  "name":"paper-1.21.4-232.jar",
                  "url":"https://fill-data.papermc.io/v2/objects/0000/paper.jar",
                  "checksums":{"sha256":"%s"},
                  "size":51960123}}}
                """.formatted(SHA256_HEX);
        SoftwareRegistry.FillBuild build = SoftwareRegistry.parseFillBuild(fillBuild);
        check(build.name().equals("paper-1.21.4-232.jar"), "fill build name");
        check(build.url().equals("https://fill-data.papermc.io/v2/objects/0000/paper.jar"),
                "fill build url");
        check(build.sha256().equals(SHA256_HEX), "fill build sha256");
        check(build.size() == 51960123L, "fill build size");

        // Purpur versions and latest build.
        String purpurVersions = """
                {"project":"purpur","versions":["1.21.3","1.21.4","1.20.4"]}
                """;
        List<String> purpurIds = SoftwareRegistry.parsePurpurVersions(purpurVersions);
        check(purpurIds.size() == 3 && purpurIds.get(0).equals("1.21.4"),
                "purpur versions newest first");

        String purpurBuild = """
                {"version":"1.21.4","builds":{"latest":"2107","all":["2106","2107"]}}
                """;
        check(SoftwareRegistry.parsePurpurBuild(purpurBuild).equals("2107"), "purpur latest build");

        // Mojang manifest, release filter, per-version URL and version JSON.
        String vanillaManifest = """
                {"latest":{"release":"1.21.4","snapshot":"26w03a"},
                 "versions":[
                   {"id":"1.21.4","type":"release",
                    "url":"https://piston-meta.mojang.com/v1/packages/aaaa/1.21.4.json"},
                   {"id":"26w03a","type":"snapshot",
                    "url":"https://piston-meta.mojang.com/v1/packages/bbbb/26w03a.json"},
                   {"id":"1.20.4","type":"release",
                    "url":"https://piston-meta.mojang.com/v1/packages/cccc/1.20.4.json"}]}
                """;
        check(SoftwareRegistry.parseVanillaManifest(vanillaManifest).equals("1.21.4"),
                "vanilla manifest latest release");
        check(SoftwareRegistry.parseVanillaReleases(vanillaManifest).equals(List.of("1.21.4", "1.20.4")),
                "vanilla manifest releases only, newest first");
        check(SoftwareRegistry.parseVanillaVersionUrl(vanillaManifest, "1.20.4")
                        .equals("https://piston-meta.mojang.com/v1/packages/cccc/1.20.4.json"),
                "vanilla per-version url");
        check(throwsIo(() -> SoftwareRegistry.parseVanillaVersionUrl(vanillaManifest, "1.19.4")),
                "vanilla unknown version throws");

        String vanillaVersion = """
                {"id":"1.21.4","type":"release","downloads":{"server":{
                  "sha1":"%s","size":54000000,
                  "url":"https://piston-data.mojang.com/v1/objects/dddd/server.jar"}}}
                """.formatted(SHA1_HEX);
        SoftwareRegistry.VanillaDownload vanilla =
                SoftwareRegistry.parseVanillaVersionJson(vanillaVersion);
        check(vanilla.version().equals("1.21.4"), "vanilla version json id");
        check(vanilla.url().equals("https://piston-data.mojang.com/v1/objects/dddd/server.jar"),
                "vanilla version json url");
        check(vanilla.sha1().equals(SHA1_HEX), "vanilla version json sha1");

        // Fabric installer (stable wins), loader and game versions.
        String fabricInstaller = """
                [{"version":"0.11.2","stable":false,
                  "url":"https://maven.fabricmc.net/net/fabricmc/fabric-installer/0.11.2/fabric-installer-0.11.2.jar"},
                 {"version":"1.0.1","stable":true,
                  "url":"https://maven.fabricmc.net/net/fabricmc/fabric-installer/1.0.1/fabric-installer-1.0.1.jar"}]
                """;
        SoftwareRegistry.InstallerFile fabric =
                SoftwareRegistry.parseFabricInstaller(fabricInstaller);
        check(fabric.version().equals("1.0.1")
                && fabric.url().endsWith("fabric-installer-1.0.1.jar"), "fabric installer stable entry");
        check(fabric.sha256().isEmpty(), "fabric installer has no inline checksum");

        String fabricLoader = """
                [{"loader":{"version":"0.16.9","stable":false}},
                 {"loader":{"version":"0.16.10","stable":true}}]
                """;
        check(SoftwareRegistry.parseFabricLoader(fabricLoader).equals("0.16.10"),
                "fabric stable loader");

        String fabricGame = """
                [{"version":"1.21.4","stable":true},
                 {"version":"26w03a","stable":false},
                 {"version":"1.21.3","stable":true}]
                """;
        check(SoftwareRegistry.parseFabricGameVersions(fabricGame)
                .equals(List.of("1.21.4", "1.21.3")), "fabric stable game versions");

        // Quilt installer (hashes.sha256 fallback) and game versions.
        String quiltInstaller = """
                [{"version":"0.9.2",
                  "url":"https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-installer/0.9.2/quilt-installer-0.9.2.jar",
                  "hashes":{"sha256":"%s"}}]
                """.formatted(SHA256_HEX);
        SoftwareRegistry.InstallerFile quilt =
                SoftwareRegistry.parseQuiltInstaller(quiltInstaller);
        check(quilt.version().equals("0.9.2") && quilt.url().endsWith("quilt-installer-0.9.2.jar"),
                "quilt installer entry");
        check(quilt.sha256().equals(SHA256_HEX), "quilt installer hashes.sha256");

        String quiltGame = """
                [{"version":"1.21.4","stable":true},{"version":"1.21.3","stable":true}]
                """;
        check(SoftwareRegistry.parseQuiltGameVersions(quiltGame)
                .equals(List.of("1.21.4", "1.21.3")), "quilt stable game versions");

        // Maven .sha256 sidecar.
        check(SoftwareRegistry.parseSha256Sidecar(
                        SHA256_HEX.toUpperCase() + "  quilt-installer-0.9.2.jar\n").equals(SHA256_HEX),
                "sha256 sidecar extracts and lowercases digest");
        check(SoftwareRegistry.parseSha256Sidecar("no digest here").isEmpty(),
                "sha256 sidecar without digest is empty");
        check(SoftwareRegistry.parseSha256Sidecar(null).isEmpty(), "sha256 sidecar null is empty");

        // BungeeCord Jenkins build number, with the buildNumber alias.
        check(SoftwareRegistry.parseBungeeBuild("{\"number\":2107,\"result\":\"SUCCESS\"}") == 2107,
                "bungee build number");
        check(SoftwareRegistry.parseBungeeBuild("{\"buildNumber\":\"2099\"}") == 2099,
                "bungee buildNumber alias");

        // NanoLimbo release: tag, jar asset preference, url and digest.
        String nanoRelease = """
                {"tag_name":"v1.9.4","name":"NanoLimbo 1.9.4","draft":false,
                 "assets":[
                   {"name":"sources.jar",
                    "browser_download_url":"https://github.com/Nan1t/NanoLimbo/releases/download/v1.9.4/sources.jar"},
                   {"name":"NanoLimbo.jar",
                    "browser_download_url":"https://github.com/Nan1t/NanoLimbo/releases/download/v1.9.4/NanoLimbo.jar",
                    "digest":"sha256:%s"}]}
                """.formatted(SHA256_HEX);
        SoftwareRegistry.NanoLimboRelease nano =
                SoftwareRegistry.parseNanoLimboRelease(nanoRelease);
        check(nano.tag().equals("v1.9.4"), "nanolimbo release tag");
        check(nano.name().equals("NanoLimbo 1.9.4"), "nanolimbo release name");
        check(nano.assetName().equals("NanoLimbo.jar"), "nanolimbo prefers NanoLimbo.jar asset");
        check(nano.url().endsWith("/v1.9.4/NanoLimbo.jar"), "nanolimbo asset url");
        check(nano.sha256().equals(SHA256_HEX), "nanolimbo digest sha256");

        String nanoReleases = """
                [{"tag_name":"v1.9.4","name":"NanoLimbo 1.9.4","draft":false},
                 {"tag_name":"v1.9.3","name":"","draft":true}]
                """;
        List<SoftwareRegistry.VersionOption> nanoOptions =
                SoftwareRegistry.parseNanoLimboReleases(nanoReleases);
        check(nanoOptions.size() == 1 && nanoOptions.get(0).id().equals("v1.9.4"),
                "nanolimbo releases skip drafts");
        check(nanoOptions.get(0).label().equals("NanoLimbo 1.9.4")
                && nanoOptions.get(0).subtitle().equals("v1.9.4"), "nanolimbo release label/subtitle");

        // Malformed and empty bodies must throw IOException (never a silent empty result).
        check(throwsIo(() -> SoftwareRegistry.parseFillVersionIds("")), "empty fill versions throws");
        check(throwsIo(() -> SoftwareRegistry.parseFillVersionIds("{}")), "fill versions without key throws");
        check(throwsIo(() -> SoftwareRegistry.parseFillBuild("")), "empty fill build throws");
        check(throwsIo(() -> SoftwareRegistry.parseFillBuild("{\"downloads\":{}}")),
                "fill build without server:default throws");
        check(throwsIo(() -> SoftwareRegistry.parsePurpurVersions("")), "empty purpur versions throws");
        check(throwsIo(() -> SoftwareRegistry.parsePurpurBuild("{\"builds\":{}}")),
                "purpur build without latest throws");
        check(throwsIo(() -> SoftwareRegistry.parseVanillaManifest("")), "empty vanilla manifest throws");
        check(throwsIo(() -> SoftwareRegistry.parseVanillaReleases("{\"versions\":[]}")),
                "vanilla manifest without releases throws");
        check(throwsIo(() -> SoftwareRegistry.parseVanillaVersionJson("")),
                "empty vanilla version json throws");
        check(throwsIo(() -> SoftwareRegistry.parseFabricInstaller("[]")),
                "empty fabric installer list throws");
        check(throwsIo(() -> SoftwareRegistry.parseFabricLoader("[]")),
                "empty fabric loader list throws");
        check(throwsIo(() -> SoftwareRegistry.parseFabricGameVersions("{}")),
                "fabric game versions non-array throws");
        check(throwsIo(() -> SoftwareRegistry.parseQuiltInstaller("[]")),
                "empty quilt installer list throws");
        check(throwsIo(() -> SoftwareRegistry.parseQuiltGameVersions("[]")),
                "empty quilt game versions throws");
        check(throwsIo(() -> SoftwareRegistry.parseNanoLimboRelease("")),
                "empty nanolimbo release throws");
        check(throwsIo(() -> SoftwareRegistry.parseNanoLimboReleases("[]")),
                "empty nanolimbo releases throws");
        check(throwsIo(() -> SoftwareRegistry.parseBungeeBuild("{}")),
                "bungee build without number throws");

        // Version token validation used by resolve() and the HTTP layer.
        for (String v : List.of("1.21.4", "1.21.4-232", "26.3", "0.16.10+1.21.4",
                "1" + "a".repeat(31))) {
            check(SoftwareRegistry.validVersion(v), "validVersion accepts " + v);
        }
        for (String v : List.of("", "../x", "a/b", "1" + "a".repeat(32), "-1.21.4", ".hidden")) {
            check(!SoftwareRegistry.validVersion(v), "validVersion rejects " + v);
        }
        check(!SoftwareRegistry.validVersion(null), "validVersion rejects null");
    }

    // ---- Phase 3: version ordering (build suffix is newer than a bare core) -------------------

    private static void versionOrdering() {
        List<String[]> newer = List.of(
                new String[]{"1.21.4-232", "1.21.4"},
                new String[]{"26.3", "26.3-rc-3"},
                new String[]{"1.21.10", "1.21.4"});
        List<String[]> older = List.of(
                new String[]{"1.21.4-231", "1.21.4-232"},
                new String[]{"26.3-rc-2", "26.3-rc-10"},
                new String[]{"1.21.11-pre5", "1.21.11"},
                new String[]{"3.4.0-SNAPSHOT-552", "3.4.0-SNAPSHOT-556"});

        for (String[] pair : newer) {
            check(SoftwareRegistry.compareVersionIds(pair[0], pair[1]) > 0,
                    pair[0] + " sorts after " + pair[1]);
        }
        for (String[] pair : older) {
            check(SoftwareRegistry.compareVersionIds(pair[0], pair[1]) < 0,
                    pair[0] + " sorts before " + pair[1]);
        }

        // Both sort directions must agree for every pair above.
        boolean symmetric = true;
        List<String[]> all = new ArrayList<>(newer);
        all.addAll(older);
        for (String[] pair : all) {
            int forward = SoftwareRegistry.compareVersionIds(pair[0], pair[1]);
            int backward = SoftwareRegistry.compareVersionIds(pair[1], pair[0]);
            if (Integer.signum(forward) != -Integer.signum(backward)) {
                symmetric = false;
                System.out.println("  not antisymmetric: " + pair[0] + " vs " + pair[1]
                        + " (" + forward + " / " + backward + ")");
            }
        }
        check(symmetric, "compareVersionIds is antisymmetric for the ordering fixtures");
    }

    // ---- Phase 3: BungeeCord Jenkins artifact URL --------------------------------------------

    private static void bungeeArtifactUrl() {
        check(SoftwareRegistry.bungeeArtifactUrl(2107).equals(
                        "https://ci.md-5.net/job/BungeeCord/2107/artifact/bootstrap/target/BungeeCord.jar"),
                "bungee artifact url keeps the /job/BungeeCord segment");
        // The implementation rejects non-positive builds with IllegalArgumentException.
        check(throwsIllegalArgument(() -> SoftwareRegistry.bungeeArtifactUrl(0)),
                "bungee build 0 throws IllegalArgumentException");
        check(throwsIllegalArgument(() -> SoftwareRegistry.bungeeArtifactUrl(-1)),
                "bungee negative build throws IllegalArgumentException");
    }

    // ---- Phase 3: fill build "application" fallback (Travertine) -----------------------------

    private static void fillBuildTravertineFallback() throws Exception {
        String applicationOnly = """
                {"id":569,"downloads":{"application":{
                  "name":"travertine-1.21.4-569.jar",
                  "url":"https://fill-data.papermc.io/v2/objects/aaaa/travertine.jar",
                  "checksums":{"sha256":"%s"},
                  "size":4356789}}}
                """.formatted(SHA256_HEX);
        SoftwareRegistry.FillBuild fallback = SoftwareRegistry.parseFillBuild(applicationOnly);
        check(fallback.name().equals("travertine-1.21.4-569.jar"),
                "fill application fallback name");
        check(fallback.url().equals("https://fill-data.papermc.io/v2/objects/aaaa/travertine.jar"),
                "fill application fallback url");
        check(fallback.sha256().equals(SHA256_HEX), "fill application fallback sha256");
        check(fallback.size() == 4356789L, "fill application fallback size");

        String bothDownloads = """
                {"id":232,"downloads":{
                  "server:default":{
                    "name":"paper-1.21.4-232.jar",
                    "url":"https://fill-data.papermc.io/v2/objects/bbbb/paper.jar",
                    "checksums":{"sha256":"%s"},
                    "size":51960123},
                  "application":{
                    "name":"travertine-1.21.4-569.jar",
                    "url":"https://fill-data.papermc.io/v2/objects/aaaa/travertine.jar",
                    "checksums":{"sha256":"%s"},
                    "size":4356789}}}
                """.formatted(SHA256_HEX, "f".repeat(64));
        SoftwareRegistry.FillBuild preferred = SoftwareRegistry.parseFillBuild(bothDownloads);
        check(preferred.name().equals("paper-1.21.4-232.jar")
                        && preferred.url().endsWith("/paper.jar"),
                "fill build prefers server:default over application");
        check(preferred.sha256().equals(SHA256_HEX) && preferred.size() == 51960123L,
                "fill build server:default keeps its own checksum and size");

        // No server:default and no usable application entry: still an IOException.
        check(throwsIo(() -> SoftwareRegistry.parseFillBuild("{\"downloads\":{}}")),
                "fill build without any usable download throws IOException");
        check(throwsIo(() -> SoftwareRegistry.parseFillBuild(
                        "{\"downloads\":{\"application\":{\"name\":\"no-url.jar\"}}}")),
                "fill application fallback without url throws IOException");
    }

    // ---- Phase 3: compareVersionIds edge cases ----------------------------------------------

    private static void compareVersionIdsEdges() {
        check(SoftwareRegistry.compareVersionIds("1.21.4", "1.21.4") == 0,
                "equal version ids compare 0");
        check(SoftwareRegistry.compareVersionIds(" 1.21.4 ", "1.21.4") == 0,
                "version ids are trimmed before comparing");
        check(SoftwareRegistry.compareVersionIds(null, null) == 0,
                "null/null compares 0 without throwing");
        check(SoftwareRegistry.compareVersionIds("", "") == 0,
                "blank/blank compares 0 without throwing");

        boolean deterministic = true;
        for (String weird : List.of("", "unknown", "-", "???", "../x", "1.21.4+build.1")) {
            int first = SoftwareRegistry.compareVersionIds(weird, "1.21.4");
            int second = SoftwareRegistry.compareVersionIds(weird, "1.21.4");
            int reverse = SoftwareRegistry.compareVersionIds("1.21.4", weird);
            if (first != second || Integer.signum(first) != -Integer.signum(reverse)) {
                deterministic = false;
                System.out.println("  non-deterministic compare for \"" + weird + "\"");
            }
        }
        check(deterministic, "unknown/blank version ids compare deterministically without throwing");
    }

    private static boolean throwsIllegalArgument(IoAction action) {
        try {
            action.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- Phase 3: JavaRuntimes pure helpers --------------------------------------------------

    private static void javaRuntimeHelpers() {
        JavaRuntimes.JavaVersion openjdk21 = JavaRuntimes.parseVersionOutput("""
                openjdk version "21.0.13" 2025-10-21 LTS
                OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS)
                OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing)
                """);
        check(openjdk21.major() == 21 && openjdk21.full().equals("21.0.13"),
                "parse openjdk 21.0.13+9-LTS");
        check(JavaRuntimes.parseVersionOutput("21.0.13+9-LTS").major() == 21,
                "parse bare 21.0.13+9-LTS");

        JavaRuntimes.JavaVersion legacy8 = JavaRuntimes.parseVersionOutput("""
                openjdk version "1.8.0_402"
                OpenJDK Runtime Environment (build 1.8.0_402-b06)
                """);
        check(legacy8.major() == 8 && legacy8.full().equals("1.8.0_402"), "parse legacy 1.8.0_402");

        JavaRuntimes.JavaVersion jdk25 = JavaRuntimes.parseVersionOutput(
                "openjdk version \"25.0.5\" 2025-09-16");
        check(jdk25.major() == 25 && jdk25.full().equals("25.0.5"), "parse 25.0.5");

        JavaRuntimes.JavaVersion garbage =
                JavaRuntimes.parseVersionOutput("not a java version at all");
        check(garbage.major() == 0 && garbage.full().isEmpty(), "parse garbage yields major 0");
        check(JavaRuntimes.parseVersionOutput(null).major() == 0, "parse null yields major 0");

        check(JavaRuntimes.recommendedMajor("1.16.5") == 8, "recommend java 8 for 1.16.5");
        check(JavaRuntimes.recommendedMajor("1.20.4") == 17, "recommend java 17 for 1.20.4");
        check(JavaRuntimes.recommendedMajor("1.20.5") == 21, "recommend java 21 for 1.20.5");
        check(JavaRuntimes.recommendedMajor("1.21.4") == 21, "recommend java 21 for 1.21.4");
        check(JavaRuntimes.recommendedMajor("26.3") == 25, "recommend java 25 for 26.3");
        check(JavaRuntimes.recommendedMajor("unknown") == 0, "recommend nothing for unknown version");
    }

    // ---- Phase 3: configured java binary reaches the command line ---------------------------

    private static void commandLineJavaPath() throws Exception {
        Path dir = Files.createTempDirectory("java-cl");
        RootConfig cfg = loadConfig(dir, PasswordHasher.hash("password1".toCharArray()),
                25568, "127.0.0.1", 25566, 25565, true);
        ChildConfig plain = cfg.children().get(0);
        String panelJava = panelJavaBinary();

        check(ChildProcess.javaFor(plain).equals(panelJava), "blank java path uses panel JVM");
        check(ChildProcess.javaFor(plain.withJavaPath("   ")).equals(panelJava),
                "whitespace java path uses panel JVM");
        check(ChildProcess.javaFor(null).equals(panelJava), "null config uses panel JVM");

        String custom = "/opt/jdk-21/bin/java";
        ChildConfig customCfg = plain.withJavaPath(custom);
        check(ChildProcess.javaFor(customCfg).equals(custom), "configured java path wins");

        List<String> cmd = CommandLineBuilder.build(customCfg, ChildProcess.javaFor(customCfg));
        check(cmd.get(0).equals(custom), "configured java path is argv[0]");
        check(cmd.get(cmd.size() - 3).equals("-jar") && cmd.get(cmd.size() - 1).equals("nogui"),
                "configured java path keeps -jar jar nogui tail");

        List<String> panelCmd = CommandLineBuilder.build(plain, ChildProcess.javaFor(plain));
        check(panelCmd.get(0).equals(panelJava), "panel JVM is argv[0] when java path is unset");
    }

    private static void configLoadsTemplateShape() throws Exception {
        Path dir = Files.createTempDirectory("cfg");
        RootConfig cfg = loadConfig(dir, PasswordHasher.hash("password1".toCharArray()), 25568, "127.0.0.1", 25566, 25565, true);
        check(cfg.server().port() == 25568 && cfg.server().trustedProxies().equals(List.of("*")), "server block");
        check(cfg.children().size() == 2 && cfg.children().get(1).id().equals("velocity"), "two children in order");
        var v = cfg.children().get(1);
        check(v.stopCommand().equals("shutdown") && v.stopTimeoutSeconds() == 30 && v.consoleEnabled(), "velocity fields");
        check(!cfg.children().get(0).consoleEnabled() && cfg.children().get(0).restartBackoffSeconds().equals(List.of(5, 10, 30, 60, 300)), "nanolimbo fields");
    }

    private static void validation() throws Exception {
        String good = PasswordHasher.hash("password1".toCharArray());
        check(problems(good, 25568, "127.0.0.1", 25566, 25565, true).isEmpty(), "valid config passes");
        check(contains(problems("placeholder", 25568, "127.0.0.1", 25566, 25565, true), "placeholder"), "rejects placeholder hash");
        check(contains(problems(good, 25565, "127.0.0.1", 25566, 25565, true), "collides with the web panel"), "rejects panel/child port clash");
        check(contains(problems(good, 25568, "0.0.0.0", 25566, 25565, true), "loopback"), "rejects non-loopback NanoLimbo");
        // The validator no longer requires child dirs/jars to exist: the web panel creates them at runtime.
        check(problems(good, 25568, "127.0.0.1", 25566, 25565, false).isEmpty(), "missing dirs are allowed");
        check(contains(problems(good, 25568, "127.0.0.1", 25565, 25565, true), "used by another child"), "rejects duplicate child ports");
        check(contains(problemsHeap("64M"), "too small"), "rejects heap below 256M with default flags");
    }

    // ---- helpers: write a temp config + child dirs, then load / validate it ----

    private static List<String> problems(String hash, int panel, String nanoBind, int nanoPort, int veloPort, boolean mkdirs)
            throws Exception {
        Path dir = Files.createTempDirectory("val");
        try {
            RootConfig cfg = loadConfig(dir, hash, panel, nanoBind, nanoPort, veloPort, mkdirs);
            ConfigValidator.validate(cfg);
            return List.of();
        } catch (ConfigException e) {
            return e.problems();
        }
    }

    private static List<String> problemsHeap(String heap) throws Exception {
        Path dir = Files.createTempDirectory("heap");
        RootConfig cfg = loadConfig(dir, PasswordHasher.hash("password1".toCharArray()), 25568, "127.0.0.1", 25566, 25565, true);
        Path f = dir.resolve("config.yml");
        Files.writeString(f, Files.readString(f).replace("heap: 256M", "heap: " + heap));
        try {
            ConfigValidator.validate(ConfigLoader.load(f));
            return List.of();
        } catch (ConfigException e) {
            return e.problems();
        }
    }

    private static void addons() throws Exception {
        check(!Addons.supportsMods("paper"), "paper does not load mods");
        check(Addons.supportsMods("fabric") && Addons.supportsMods("arclight"), "fabric/arclight load mods");
        check(Addons.supportsPlugins("velocity") && !Addons.supportsPlugins("fabric"), "proxy plugins");

        check(!Addons.loaders("paper", false).isEmpty() && Addons.loaders("paper", false).contains("spigot"), "paper includes spigot plugins");
        check(Addons.loaders("fabric", true).equals(List.of("fabric")), "fabric mod loader");
        check(Addons.loaders("quilt", true).contains("fabric"), "quilt accepts fabric mods");
        check(Addons.loaders("paper", true).isEmpty(), "paper has no mod loaders");
        check(Addons.validLoader("pa-per_1") && !Addons.validLoader("pa per"), "loader token validation");

        check("fabric-api-0.119.4+1.21.4.jar".equals(Addons.sanitizeJar("some/dir/fabric-api-0.119.4+1.21.4.jar")), "sanitize keeps + in name");
        check(Addons.sanitizeJar("notes.txt") == null, "sanitize rejects non-jar");
        check(Addons.sanitizeJar("x.jar/../../evil") == null, "sanitize rejects traversal");
        check(Addons.sanitizeJar(null) == null, "sanitize null");

        check(Addons.versionMatches(">=1.20.1", "1.21.4"), "range >= matches");
        check(!Addons.versionMatches("[1.20,1.21)", "1.21"), "range exclusive upper");
        check(Addons.versionMatches("[1.20,1.21)", "1.20.4"), "range inside");
        check(Addons.versionMatches("*", "1.7.10"), "range any");
        check(!Addons.versionMatches("1.20.1", "1.20"), "exact range mismatch");
        check(Addons.compareVersions("1.21.4", "1.21") > 0, "version compare");

        Path dir = Files.createTempDirectory("addons-check");
        Path fabric = jarWith(dir, "fabric.jar", "fabric.mod.json",
                "{\"id\":\"coolmod\",\"name\":\"Cool Mod\",\"depends\":{\"minecraft\":\">=1.21\"}}");
        Addons.JarMeta fabricMeta = Addons.metadata(fabric);
        check(fabricMeta != null && "fabric".equals(fabricMeta.format()), "fabric metadata format");
        check(fabricMeta != null && ">=1.21".equals(fabricMeta.mcRange()), "fabric metadata range");
        check(fabricMeta != null && "Cool Mod".equals(fabricMeta.name()), "fabric metadata name");

        Path forge = jarWith(dir, "forge.jar", "META-INF/mods.toml",
                "modLoader=\"javafml\"\n[[dependencies.example]]\n    modId=\"minecraft\"\n    versionRange=\"[1.20.1,1.21)\"\n");
        Addons.JarMeta forgeMeta = Addons.metadata(forge);
        check(forgeMeta != null && "[1.20.1,1.21)".equals(forgeMeta.mcRange()), "forge metadata range");

        Path plugin = jarWith(dir, "plugin.jar", "plugin.yml", "name: CoolPlugin\napi-version: 1.20\n");
        Addons.JarMeta pluginMeta = Addons.metadata(plugin);
        check(pluginMeta != null && ">=1.20".equals(pluginMeta.mcRange()), "bukkit api-version floor");

        String hash = Addons.sha512(fabric);
        check(hash.length() == 128 && hash.equals(Addons.sha512(fabric)), "sha512 stable and hex");
        check(Addons.metadata(dir.resolve("missing.jar")) == null, "metadata tolerates missing jar");
    }

    private static Path jarWith(Path dir, String name, String entryName, String content) throws Exception {
        Path file = dir.resolve(name);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(file))) {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return file;
    }

    private static boolean contains(List<String> problems, String text) {
        return problems.stream().anyMatch(p -> p.contains(text));
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws Exception;
    }

    /** True only when the action throws IOException; other exceptions count as a failed check. */
    private static boolean throwsIo(IoAction action) {
        try {
            action.run();
            return false;
        } catch (IOException e) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Mirrors ChildProcess.javaFor's panel default so the check is host-independent. */
    private static String panelJavaBinary() {
        Path p = Path.of(System.getProperty("java.home"), "bin", "java");
        return Files.isExecutable(p) ? p.toString() : "java";
    }

    private static RootConfig loadConfig(Path dir, String hash, int panel, String nanoBind, int nanoPort, int veloPort,
                                           boolean mkdirs) throws Exception {
        if (mkdirs) {
            for (String d : new String[]{"nanolimbo", "velocity"}) Files.createDirectories(dir.resolve(d));
            Files.writeString(dir.resolve("nanolimbo/NanoLimbo.jar"), "x");
            Files.writeString(dir.resolve("velocity/velocity.jar"), "x");
        }
        String yml = """
                server:
                  bind: 0.0.0.0
                  port: %d
                  password_hash: "%s"    # a comment
                  trusted_proxies: ["*"]
                children:
                  - id: nanolimbo
                    type: nanolimbo
                    dir: ./nanolimbo
                    jar: NanoLimbo.jar
                    heap: 256M
                    port: %d
                    bind: %s
                    stop_strategy: both
                    start_order: 1
                    stop_order: 2
                    restart_backoff_seconds: [5, 10, 30, 60, 300]
                    extra_flags: []
                    console_enabled: false
                  - id: velocity
                    type: velocity
                    dir: ./velocity
                    jar: velocity.jar
                    heap: 768M
                    port: %d
                    bind: 0.0.0.0
                    stop_strategy: stdin
                    stop_command: "shutdown"
                    start_order: 2
                    stop_order: 1
                    console_enabled: true
                """.formatted(panel, hash, nanoPort, nanoBind, veloPort);
        Path file = dir.resolve("config.yml");
        Files.writeString(file, yml);
        return ConfigLoader.load(file);
    }
}
