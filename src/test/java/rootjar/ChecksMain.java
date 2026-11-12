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
import rootjar.config.ServerConfig;
import rootjar.config.StopStrategy;
import rootjar.console.LogRingBuffer;
import rootjar.console.StdinRouter;
import rootjar.console.StdinRouter.Kind;
import rootjar.security.ActivityLog;
import rootjar.supervisor.BackoffPolicy;
import rootjar.supervisor.ChildProcess;
import rootjar.supervisor.CommandLineBuilder;
import rootjar.supervisor.PermissionRepair;
import rootjar.backup.BackupManager;
import rootjar.files.FileOps;
import rootjar.website.StaticSiteServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
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
        autoStartConfig();
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
        staticWebsite();
        fileOps();
        uploadLimits();
        backups();
        permissionRepair();
        bukkitDatapackRepair();
        portAlignment();
        frontendSync();
        frontendEncoding();
        eulaAgreement();
        releaseVersion();
        memoryAndLaunchers();
        storageAndPaging();
        playerTracking();
        activityFeed();
        geyserAndMultiPort();
        addonSources();
        panelNaming();
        System.out.println("unit checks: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    /** The upload handler's Content-Length guard, which stops a cut-short body being published. */
    private static void uploadLimits() {
        check(WebServer.declaredLength("1234") == 1234L, "a declared content length is read");
        check(WebServer.declaredLength(" 42 ") == 42L, "a padded content length is read");
        check(WebServer.declaredLength(null) == -1L, "a missing content length is unknown");
        check(WebServer.declaredLength("") == -1L, "a blank content length is unknown");
        check(WebServer.declaredLength("nope") == -1L, "an unparseable content length is unknown");
        check(WebServer.declaredLength("-9") == -1L, "a negative content length is unknown");
    }

    /** Console prefix stripping, join/leave detection, and the status-ping JSON reader. */
    /** The version has to agree in the code, the jar manifest recipe and the release notes. */
    private static void releaseVersion() throws Exception {
        String version = rootjar.Main.VERSION;
        check(version.matches("[0-9]+\\.[0-9]+\\.[0-9]+"), "the version is a three-part number");
        String readme = Files.readString(Path.of("README.md"));
        String newest = readme.lines().filter(line -> line.matches("## [0-9]+\\.[0-9]+\\.[0-9]+")).findFirst().orElse("");
        check(newest.equals("## " + version), "README.md leads with the version in Main.VERSION, got '" + newest + "'");
        check(Files.readString(Path.of("build.sh")).contains("Implementation-Version: " + version),
                "build.sh stamps the same version into the jar manifest");
        // The newest section has to actually describe the release, not just carry the heading. Asserting a
        // specific sentence here was too rigid: a patch release says "a fix release", not "everything below".
        String afterHeading = readme.substring(readme.indexOf(newest) + newest.length());
        int nextSection = afterHeading.indexOf("\n## ");
        String section = nextSection < 0 ? afterHeading : afterHeading.substring(0, nextSection);
        check(section.contains("- "), "the newest README section lists what changed");
    }

    /** Forge/NeoForge launch from a generated arguments file, and total RAM must not be a hardcoded fallback. */
    private static void memoryAndLaunchers() throws Exception {
        long reported = WebServer.availableRamMb();
        long physical = ((com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory
                .getOperatingSystemMXBean()).getTotalMemorySize() / (1024 * 1024);
        boolean cgroup = Files.exists(Path.of("/sys/fs/cgroup/memory.max"))
                || Files.exists(Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes"));
        check(reported > 0, "available ram is reported");
        check(cgroup || reported == physical, "outside a container the reported ram is physical memory, not a hardcoded 3072");

        Path dir = Files.createTempDirectory("loader");
        ChildConfig neo = new ChildConfig("neo1", "neoforge", dir, "server.jar", "4096M", true, 25565, "0.0.0.0",
                StopStrategy.STDIN, "stop", 30, 1, 1, true, List.of(5), List.of(), true, true, false, "", true, "", 0);
        // No loader installed yet: fall back to the jar form so the child can still start.
        var before = CommandLineBuilder.build(neo, "java");
        check(before.contains("-jar") && before.get(before.size() - 1).equals("nogui"), "a loader child with no libraries still launches from its jar");

        String loader = "libraries/net/neoforged/neoforge";
        String version = "21.4.1";
        Path versionDir = dir.resolve(loader).resolve(version);
        Files.createDirectories(versionDir);
        Files.writeString(versionDir.resolve("unix_args.txt"), "nogui\n");
        Files.writeString(versionDir.resolve("win_args.txt"), "nogui\n");

        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        var cmd = CommandLineBuilder.build(neo, "java");
        check(cmd.get(cmd.size() - 1).equals("@" + loader + "/" + version + "/" + (windows ? "win_args.txt" : "unix_args.txt")),
                "a neoforge child launches from its arguments file");
        check(!cmd.contains("-jar") && !cmd.contains("nogui"), "the arguments-file form does not also pass -jar or nogui");
        check(cmd.contains("-Xmx4096M") && cmd.contains("-XX:+UseG1GC"), "the heap and gc flags survive the arguments-file form");

        // A stray libraries folder must not change how a Paper server starts.
        ChildConfig paper = new ChildConfig("paper1", "paper", dir, "server.jar", "1024M", true, 25566, "0.0.0.0",
                StopStrategy.STDIN, "stop", 30, 1, 1, true, List.of(5), List.of(), true, true, false, "", true, "", 0);
        var paperCmd = CommandLineBuilder.build(paper, "java");
        check(paperCmd.contains("-jar") && !paperCmd.get(paperCmd.size() - 1).startsWith("@"), "a paper server ignores a stray loader directory");

        Path forgeDir = Files.createTempDirectory("forge");
        Path forgeVersion = forgeDir.resolve("libraries/net/minecraftforge/forge/1.20.1-47.1.3");
        Files.createDirectories(forgeVersion);
        Files.writeString(forgeVersion.resolve("unix_args.txt"), "nogui\n");
        Files.writeString(forgeVersion.resolve("win_args.txt"), "nogui\n");
        ChildConfig forge = new ChildConfig("forge1", "forge", forgeDir, "forge.jar", "4096M", true, 25567, "0.0.0.0",
                StopStrategy.STDIN, "stop", 30, 1, 1, true, List.of(5), List.of(), true, true, false, "", true, "", 0);
        check(CommandLineBuilder.build(forge, "java").get(0).equals("java")
                        && CommandLineBuilder.build(forge, "java").stream().anyMatch(a -> a.startsWith("@libraries/net/minecraftforge/forge/")),
                "a forge child uses the forge library root");

        // exit_on_oom is off unless the operator turns it on, and it has to survive both a with* copy and a
        // config round-trip, because every save path rewrites config.yml.
        check(!CommandLineBuilder.build(neo, "java").contains("-XX:+ExitOnOutOfMemoryError"), "exit_on_oom is off by default");
        ChildConfig oom = neo.withExitOnOom(true);
        check(CommandLineBuilder.build(oom, "java").contains("-XX:+ExitOnOutOfMemoryError"), "exit_on_oom adds the flag");
        check(CommandLineBuilder.build(oom, "java").contains("-Xmx4096M"), "the oom flag sits beside the heap flags");
        check(oom.withSilent(true).exitOnOom() && oom.withXmsEqualXmx(false).exitOnOom()
                        && oom.withUseAikarFlags(false).exitOnOom() && oom.withExtraPorts(List.of()).exitOnOom(),
                "exit_on_oom survives the other with* copies");
        Path cfgDir = Files.createTempDirectory("oom-cfg");
        RootConfig base = loadConfig(cfgDir, "placeholder", 25568, "127.0.0.1", 25566, 25565, true);
        check(!base.children().get(0).exitOnOom(), "a config without the key defaults to off");
        Path saved = cfgDir.resolve("saved.yml");
        ConfigSaver.save(saved, new RootConfig(base.server(), List.of(base.children().get(0).withExitOnOom(true)), base.baseDir()));
        check(Files.readString(saved).contains("exit_on_oom: true"), "exit_on_oom is written to config.yml");
        check(ConfigLoader.load(saved).children().get(0).exitOnOom(), "exit_on_oom survives a config round-trip");
    }

    /** Per-server storage limits, the quota walk, and activity paging. */
    private static void storageAndPaging() throws Exception {
        check(WebServer.parseStorageLimit(null) == 0, "an absent storage limit is unlimited");
        check(WebServer.parseStorageLimit("  ") == 0, "a blank storage limit is unlimited");
        check(WebServer.parseStorageLimit("0") == 0, "zero means unlimited");
        check(WebServer.parseStorageLimit("5") == 5, "a whole number of GB is accepted");
        check(WebServer.parseStorageLimit("-1") == -1, "a negative limit is rejected");
        check(WebServer.parseStorageLimit("2.5") == -1, "a fractional limit is rejected");
        check(WebServer.parseStorageLimit("lots") == -1, "a non-numeric limit is rejected");
        check(WebServer.parseStorageLimit("1048577") == -1, "an absurd limit is rejected");
        check(WebServer.storageLimitBytes(null) == 0, "no config means no limit");
        check(WebServer.storageLimitBytes(new ChildConfig("s", "paper", Path.of("."), "server.jar", "1G", true, 25565,
                "0.0.0.0", StopStrategy.STDIN, "stop", 30, 1, 1, true, List.of(5), List.of(), true, true, false, "",
                true, "", 0, List.of(), false, 5)) == 5L * 1024 * 1024 * 1024, "5 GB is five binary gigabytes");
        check(new ChildConfig("s", "paper", Path.of("."), "server.jar", "1G", true, 25565, "0.0.0.0",
                StopStrategy.STDIN, "stop", 30, 1, 1, true, List.of(5), List.of(), true, true, false, "",
                true, "", 0, List.of(), true, 7).withSilent(true).storageMaxGb() == 7,
                "the storage limit survives another with* copy");

        // Backups are excluded from the quota, so a full disk can never block a safety backup.
        Path dir = Files.createTempDirectory("quota");
        Files.createDirectories(dir.resolve("plugins"));
        Files.writeString(dir.resolve("plugins/plugin.jar"), "x".repeat(1000));
        Files.createDirectories(dir.resolve("backups"));
        Files.writeString(dir.resolve("backups/big.zip"), "y".repeat(5000));
        check(WebServer.quotaUsedBytes(dir) == 1000, "backups are not counted against the quota");
        check(WebServer.quotaUsedBytes(Files.createTempDirectory("empty-quota")) == 0, "an empty folder uses nothing");

        Path zip = Files.createTempDirectory("quota-zip").resolve("a.zip");
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new java.util.zip.ZipEntry("one.txt"));
            out.write("a".repeat(300).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new java.util.zip.ZipEntry("nested/two.txt"));
            out.write("b".repeat(700).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        check(WebServer.zipUncompressedBytes(zip) == 1000, "a zip's uncompressed size is summed before extracting");

        // Storage limits round-trip through config.yml, and an unlimited server writes no key at all.
        Path cfgDir = Files.createTempDirectory("storage-cfg");
        RootConfig base = loadConfig(cfgDir, "placeholder", 25568, "127.0.0.1", 25566, 25565, true);
        check(base.children().get(0).storageMaxGb() == 0, "a config without the key is unlimited");
        Path unlimited = cfgDir.resolve("unlimited.yml");
        ConfigSaver.save(unlimited, new RootConfig(base.server(), base.children(), base.baseDir()));
        check(!Files.readString(unlimited).contains("storage_max_gb"), "an unlimited server writes no storage key");
        Path limited = cfgDir.resolve("limited.yml");
        ConfigSaver.save(limited, new RootConfig(base.server(),
                List.of(base.children().get(0).withStorageMaxGb(12)), base.baseDir()));
        check(Files.readString(limited).contains("storage_max_gb: 12"), "a storage limit is written to config.yml");
        check(ConfigLoader.load(limited).children().get(0).storageMaxGb() == 12, "a storage limit survives a reload");

        // Activity paging: newest first, offset from the end, plus dedupe that survives a second boundary.
        Path act = Files.createTempDirectory("activity-page");
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            log.append("{\"time\":\"2026-11-10T02:00:").append(String.format("%02d", i % 60)).append(".000Z\",")
               .append("\"user\":\"ada\",\"action\":\"file.write\",\"details\":\"f").append(i).append(".yml\"}\n");
        }
        Files.writeString(act.resolve("activity.log"), log.toString());
        String first = ActivityLog.recentForServerPage(act, null, "s", 50, 0, "");
        check(first.contains("\"total\":120"), "the activity page reports the total");
        check((first.split("\\{\"time\"", -1).length - 1) == 50, "the first activity page holds the page size");
        check(first.contains("f119.yml") && !first.contains("f0.yml"), "the first page is the newest events");
        String last = ActivityLog.recentForServerPage(act, null, "s", 50, 100, "");
        check(last.contains("f0.yml") && (last.split("\\{\"time\"", -1).length - 1) == 20, "the last page holds the remainder");
        check(ActivityLog.recentForServerPage(act, null, "s", 50, 500, "").contains("\"items\":[]"), "an offset past the end is empty");

        Path twoLog = Files.createTempDirectory("activity-dedupe");
        Files.writeString(twoLog.resolve("activity.log"),
                "{\"time\":\"2026-11-10T03:00:59.900Z\",\"user\":\"ada\",\"action\":\"file.write\",\"details\":\"a.yml\"}\n"
                + "{\"time\":\"2026-11-10T03:01:00.100Z\",\"user\":\"ada\",\"action\":\"file.write\",\"details\":\"a.yml\"}\n"
                + "{\"time\":\"2026-11-10T03:05:00.000Z\",\"user\":\"ada\",\"action\":\"file.write\",\"details\":\"a.yml\"}\n");
        String deduped = ActivityLog.recentForServerPage(twoLog, null, "s", 50, 0, "");
        check(deduped.contains("\"total\":2"), "the same event across a second boundary collapses, a later one does not");
    }

    private static void playerTracking() throws Exception {
        check(rootjar.supervisor.ChildProcess.stripConsolePrefix("[12:34:56 INFO]: Steve joined the game").equals("Steve joined the game"), "a Paper console prefix is stripped");
        check(rootjar.supervisor.ChildProcess.stripConsolePrefix("[12:34:56] [Server thread/INFO]: Steve joined the game").equals("Steve joined the game"), "a vanilla two-bracket prefix is stripped");
        check(rootjar.supervisor.ChildProcess.stripConsolePrefix("Steve joined the game").equals("Steve joined the game"), "an unprefixed line is left alone");
        check(rootjar.supervisor.ChildProcess.playerEvent("[12:34:56 INFO]: Steve joined the game").equals("+Steve"), "a Paper join line is recognised");
        check(rootjar.supervisor.ChildProcess.playerEvent("[12:34:56] [Server thread/INFO]: Steve left the game").equals("-Steve"), "a vanilla leave line is recognised");
        check(rootjar.supervisor.ChildProcess.playerEvent("[12:34:56 INFO]: There are 3 of a max of 20 players online:") == null, "a list reply is not a join or a leave");
        check(rootjar.supervisor.ChildProcess.playerEvent("[12:34:56 INFO]: [Steve] joined the game") == null, "chat mentioning joining is not counted as a join");

        String status = "{\"version\":{\"name\":\"1.21.4\",\"protocol\":769},\"players\":{\"max\":20,\"online\":2,"
                + "\"sample\":[{\"name\":\"Steve\",\"id\":\"a\"},{\"name\":\"Alex\",\"id\":\"b\"}]},\"description\":\"hi\"}";
        rootjar.supervisor.ServerPing.Status ping = rootjar.supervisor.ServerPing.parse(status);
        check(ping != null && ping.online() == 2 && ping.max() == 20, "a status ping reports the count and the cap");
        check(ping != null && ping.version().equals("1.21.4"), "a status ping reports the version name");
        check(ping != null && ping.sample().equals(List.of("Steve", "Alex")), "a status ping lists the sampled names");
        check(ping != null && ping.completeSample(), "a sample covering every player is complete");

        // Paper caps its sample, so a short one must not replace names the console already knew.
        check(!rootjar.supervisor.ServerPing.parse("{\"players\":{\"max\":100,\"online\":50,\"sample\":[{\"name\":\"Steve\",\"id\":\"a\"}]}}").completeSample(), "a capped sample is not complete");
        check(rootjar.supervisor.ServerPing.parse("{\"players\":{\"max\":20,\"online\":7}}").online() == 7, "a server hiding its players still reports a count");
        check(rootjar.supervisor.ServerPing.parse("{\"players\":{\"max\":20,\"online\":7}}").sample().isEmpty(), "a hidden player list yields no names");
        check(rootjar.supervisor.ServerPing.parse("{\"players\":{\"max\":20,\"online\":0,\"sample\":[]}}").completeSample(), "an empty server has a complete sample");
        check(rootjar.supervisor.ServerPing.parse("{\"players\":{\"max\":20,\"online\":1,\"sample\":[{\"name\":\"<script>\",\"id\":\"a\"}]}}").sample().isEmpty(), "a malformed sampled name is dropped");

        check(rootjar.supervisor.ServerPing.parse("{\"description\":\"no players key\"}") == null, "a reply without a players object is not a status");
        check(rootjar.supervisor.ServerPing.parse("{\"players\":{\"max\":20,\"online\":-4}}") == null, "a negative count is rejected");
        check(rootjar.supervisor.ServerPing.parse("{\"players\":{\"max\":20,\"online\":99999999}}") == null, "an implausible count is rejected");
        check(rootjar.supervisor.ServerPing.parse("not json at all") == null, "junk is not a status");
        check(rootjar.supervisor.ServerPing.ping("127.0.0.1", 1, 250) == null, "pinging a closed port returns null instead of throwing");
        check(rootjar.supervisor.ServerPing.ping("", 25565, 250) == null, "an empty host returns null");
        check(rootjar.supervisor.ServerPing.ping("127.0.0.1", 0, 250) == null, "a zero port returns null");

        // VarInt is base 128. Encoding it by shifting five bits instead of seven wrote -1 as six bytes
        // (FF FF FF FF FF 7F) rather than five, which every server rejects as a bad VarInt. That is what made
        // Limbo log "Cannot decode packet PacketHandshake" every 30 seconds, and it meant the ping never
        // once succeeded on any server, because ping() swallows every failure and returns null.
        check(java.util.Arrays.equals(rootjar.supervisor.ServerPing.encodeVarInt(0), new byte[]{0x00}), "varint 0");
        check(java.util.Arrays.equals(rootjar.supervisor.ServerPing.encodeVarInt(127), new byte[]{0x7F}), "varint 127 is one byte");
        check(java.util.Arrays.equals(rootjar.supervisor.ServerPing.encodeVarInt(128), new byte[]{(byte) 0x80, 0x01}), "varint 128 is two bytes");
        check(java.util.Arrays.equals(rootjar.supervisor.ServerPing.encodeVarInt(25565), new byte[]{(byte) 0xDD, (byte) 0xC7, 0x01}), "varint 25565, an ordinary port");
        check(java.util.Arrays.equals(rootjar.supervisor.ServerPing.encodeVarInt(-1),
                new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F}), "varint -1 is five bytes, not six");
        check(java.util.Arrays.equals(rootjar.supervisor.ServerPing.encodeVarInt(Integer.MAX_VALUE),
                new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x07}), "varint max int");
        check(java.util.Arrays.equals(rootjar.supervisor.ServerPing.encodeVarInt(Integer.MIN_VALUE),
                new byte[]{(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x08}), "varint min int");
        for (int value : new int[]{0, 1, 63, 127, 128, 255, 25565, 16383, 16384, 2097151, -1, -2, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            byte[] encoded = rootjar.supervisor.ServerPing.encodeVarInt(value);
            check(encoded.length <= 5, "varint " + value + " fits in five bytes");
            check(rootjar.supervisor.ServerPing.decodeVarInt(encoded, new int[]{0}) == value, "varint " + value + " round-trips");
        }
        // The reader has to cope with a multi-byte length too: a real status reply's JSON is always longer
        // than 127 bytes, so its length prefix spans several bytes.
        check(rootjar.supervisor.ServerPing.decodeVarInt(new byte[]{(byte) 0xAC, 0x02}, new int[]{0}) == 300, "a multi-byte length is read back");

        // The exact handshake the panel opens with: packet length, id 0x00, protocol -1, address, port,
        // next state 1. Asserted byte for byte against the documented wire format.
        String handshakeHex = bytesHex(rootjar.supervisor.ServerPing.handshakePacket("127.0.0.1", 25565));
        check(handshakeHex.equals("1300ffffffff0f093132372e302e302e3163dd01"),
                "the handshake matches the documented wire format, got " + handshakeHex);
        // 0b = 11 bytes of body: id, protocol, address length, one address byte, two port bytes, next state.
        check(bytesHex(rootjar.supervisor.ServerPing.handshakePacket("h", 25566)).equals("0b00ffffffff0f016863de01"),
                "a one-character host produces a shorter handshake, got "
                        + bytesHex(rootjar.supervisor.ServerPing.handshakePacket("h", 25566)));
    }

    /** Lower-case hex of raw bytes, for asserting an exact wire format. */
    private static String bytesHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void portAlignment() {
        String props = "server-port=25565\nquery.port=25566\nrcon.port=25575\nenable-rcon=true\nmotd=Hi\n";
        String updated = ChildProcess.setProperty(props, "server-port", "30001");
        updated = ChildProcess.setPropertyWhenPresent(updated, "query.port", "30001");
        updated = ChildProcess.setProperty(updated, "rcon.port", "30002");
        check(updated.contains("server-port=30001"), "port alignment rewrites server-port");
        check(updated.contains("query.port=30001"), "port alignment rewrites query.port");
        check(updated.contains("rcon.port=30002"), "port alignment rewrites rcon.port");
        check(updated.contains("motd=Hi"), "port alignment keeps other settings");
        check(ChildProcess.hasTrueProperty(updated, "enable-rcon"), "port alignment detects enabled rcon");
        check(!ChildProcess.hasTrueProperty(ChildProcess.setProperty(updated, "enable-rcon", "false"), "enable-rcon"), "port alignment detects disabled rcon");

        String noQuery = ChildProcess.setPropertyWhenPresent("server-port=25565\n", "query.port", "30001");
        check(!noQuery.contains("query.port"), "port alignment leaves an absent query.port absent");
        String appended = ChildProcess.setProperty("server-port=25565\n", "server-port", "30001");
        check(appended.contains("server-port=30001") && !appended.contains("server-port=25565"), "port alignment replaces instead of duplicating");
        check(ChildProcess.setProperty("motd=Hi", "server-port", "30001").endsWith("server-port=30001\n"), "port alignment appends a missing key");
    }

    private static void addonSources() {
        String modrinth = "{\"hits\":[{\"project_id\":\"hXiIvTyT\",\"project_type\":\"mod\",\"all_project_types\":[\"plugin\"],"
                + "\"slug\":\"essentialsx\",\"author\":\"mdcfe\",\"title\":\"EssentialsX\",\"description\":\"The essential plugin suite\","
                + "\"categories\":[\"bukkit\",\"paper\"],\"versions\":[\"1.21.11\",\"26.1.2\"],\"downloads\":956849,"
                + "\"icon_url\":\"https://cdn.modrinth.com/data/hXiIvTyT/icon.webp\"}],\"offset\":0,\"limit\":10,\"total_hits\":56}";
        List<rootjar.addons.AddonSources.Item> modrinthItems = rootjar.addons.AddonSources.modrinth(modrinth);
        check(modrinthItems.size() == 1, "modrinth hits are parsed");
        check(modrinthItems.get(0).id().equals("hXiIvTyT") && modrinthItems.get(0).slug().equals("essentialsx"), "modrinth id and slug are read");
        check(modrinthItems.get(0).title().equals("EssentialsX"), "modrinth title is read");
        check(modrinthItems.get(0).downloads() == 956849L, "modrinth downloads are read");
        check(modrinthItems.get(0).versions().equals(List.of("1.21.11", "26.1.2")), "modrinth versions are read");
        check(rootjar.addons.AddonSources.totalCount(modrinth) == 56, "modrinth total_hits is read");

        String hangar = "{\"pagination\":{\"count\":340,\"limit\":2,\"offset\":0},\"result\":[{\"id\":7387,"
                + "\"name\":\"EssentialsX-Fixes\",\"namespace\":{\"owner\":\"YONN2222\",\"slug\":\"EssentialsX-Fixes\"},"
                + "\"stats\":{\"views\":142,\"downloads\":6},\"description\":\"Unofficial addon\","
                + "\"supportedPlatforms\":{\"PAPER\":[\"1.21.4\",\"26.2\"]},"
                + "\"avatarUrl\":\"https://hangarcdn.papermc.io/avatars/project/7387.webp?v=1\"}]}";
        List<rootjar.addons.AddonSources.Item> hangarItems = rootjar.addons.AddonSources.hangar(hangar);
        check(hangarItems.size() == 1, "hangar results are parsed");
        check(hangarItems.get(0).owner().equals("YONN2222") && hangarItems.get(0).slug().equals("EssentialsX-Fixes"), "hangar owner and slug are read");
        check(hangarItems.get(0).id().equals("7387"), "hangar numeric id becomes a string");
        check(hangarItems.get(0).downloads() == 6L, "hangar downloads come from the stats block");
        check(hangarItems.get(0).versions().contains("26.2"), "hangar paper versions are read");
        check(hangarItems.get(0).iconUrl().startsWith("https://hangarcdn.papermc.io/"), "hangar icon is read");
        check(rootjar.addons.AddonSources.totalCount(hangar) == 340, "hangar pagination count is read");

        String hangarVersions = "{\"pagination\":{\"count\":3},\"result\":[{\"name\":\"1.0.2\",\"channel\":{\"name\":\"Release\"},"
                + "\"downloads\":{\"PAPER\":{\"fileInfo\":{\"name\":\"XFixes-1.0.2.jar\",\"sizeBytes\":88850},"
                + "\"externalUrl\":null,\"downloadUrl\":\"https://hangarcdn.papermc.io/plugins/YONN2222/EssentialsX-Fixes/versions/1.0.2/PAPER/XFixes-1.0.2.jar\"}},"
                + "\"platformDependencies\":{\"PAPER\":[\"1.21\",\"26.2\"]}}]}";
        rootjar.addons.AddonSources.Download download = rootjar.addons.AddonSources.hangarDownload(hangarVersions);
        check(download != null && download.fileName().equals("XFixes-1.0.2.jar"), "hangar file name is taken from fileInfo");
        check(download != null && download.url().equals("https://hangarcdn.papermc.io/plugins/YONN2222/EssentialsX-Fixes/versions/1.0.2/PAPER/XFixes-1.0.2.jar"), "hangar download url is the cdn link");
        check(rootjar.addons.AddonSources.hangarDownload("{\"result\":[]}") == null, "no hangar version means no download");
        check(rootjar.addons.AddonSources.hangarDownload(hangar) == null, "a hangar search result is not a download");

        String spigot = "[{\"name\":\"EssentialsX\",\"tag\":\"The modern Essentials suite for Spigot and Paper.\","
                + "\"icon\":{\"data\":\"iVBORw0KGgo=\"},\"downloads\":5920254,\"premium\":false,\"id\":9089},"
                + "{\"name\":\"Paid Plugin\",\"tag\":\"Buy me\",\"icon\":{\"data\":\"\"},\"downloads\":10,\"premium\":true,\"id\":1}]";
        List<rootjar.addons.AddonSources.Item> spigotItems = rootjar.addons.AddonSources.spigot(spigot);
        check(spigotItems.size() == 2, "spigot resources are parsed");
        check(spigotItems.get(0).id().equals("9089") && spigotItems.get(0).title().equals("EssentialsX"), "spigot id and name are read");
        check(spigotItems.get(0).description().equals("The modern Essentials suite for Spigot and Paper."), "the spigot tag becomes the description");
        check(spigotItems.get(0).iconUrl().equals("data:image/png;base64,iVBORw0KGgo="), "the spigot icon becomes a data url");
        check(!spigotItems.get(0).premium() && spigotItems.get(1).premium(), "premium spigot resources are flagged");
        check(spigotItems.get(1).iconUrl().isEmpty(), "a missing spigot icon stays empty");
        check(rootjar.addons.AddonSources.totalCount(spigot) == -1, "spigot reports no total");

        String json = rootjar.addons.AddonSources.toJson(hangarItems, 340, 0, 10, "hangar");
        check(json.startsWith("{\"source\":\"hangar\",\"total\":340,\"offset\":0,\"limit\":10,\"items\":[{\"id\":\"7387\""), "normalized json has the shared shape");
        check(json.contains("\"versions\":[\"1.21.4\",\"26.2\"]"), "normalized json keeps versions");
        check(json.contains("\"source\":\"hangar\"}]}"), "normalized json is closed");
        // The picker routes on the per-item source. With the source only at the top level, item.source was
        // undefined, the version modal never opened, and every install sent source=undefined, which the
        // backend rejects with "Unknown source".
        check(json.split("\"source\"").length == 3, "every normalized item carries its own source");
        check(rootjar.addons.AddonSources.toJson(spigotItems, 0, 0, 10, "spigot").contains("\"source\":\"spigot\"}]}"), "a spigot result is stamped as spigot");
        // Premium resources need a SpigotMC login, so listing them offers an Install button that cannot work.
        check(rootjar.addons.AddonSources.withoutPremium(spigotItems).size() == 1, "premium spigot resources are dropped from results");
        check(rootjar.addons.AddonSources.withoutPremium(spigotItems).get(0).title().equals("EssentialsX"), "the free spigot result survives the premium filter");
        check(rootjar.addons.AddonSources.withoutPremium(List.of()).isEmpty(), "filtering an empty result stays empty");
        check(rootjar.addons.AddonSources.toJson(List.of(), 0, 0, 10, "spigot").contains("\"items\":[]"), "an empty result still returns valid json");
        check(rootjar.addons.AddonSources.escape("a\"b\\c\nd").equals("a\\\"b\\\\c d"), "values are escaped for json");
        check(rootjar.addons.AddonSources.objects("{\"result\":[]}", "result").isEmpty(), "an empty array yields no objects");
        check(rootjar.addons.AddonSources.str("{\"a\":\"x\"}", "missing").isEmpty(), "a missing key yields an empty string");
        check(rootjar.addons.AddonSources.valueBody("{\"a\":{\"b\":1}}", "a").equals("{\"b\":1}"), "a nested object body is read");
        check(rootjar.addons.AddonSources.valueBody("{\"a\":[1,2]}", "a").equals("[1,2]"), "a nested array body is read");
        check(rootjar.addons.AddonSources.objectBody("{\"a\":\"x\"}", "a").isEmpty(), "a scalar value has no object body");
        check(rootjar.addons.AddonSources.valueBody("{\"a\":{\"b\":\"}\"}}", "a").equals("{\"b\":\"}\"}"), "a brace inside a string does not end the body");
        addonVersions();
    }

    /** Version lists and dependency resolution across all three registries. */
    private static void addonVersions() {
        // Hangar nests the file under downloads.PAPER and keeps dependencies apart from the supported
        // Minecraft versions, which platformDependencies deceptively resembles.
        String hangar = "{\"result\":[{\"name\":\"5.12.3\",\"downloads\":{"
                + "\"VELOCITY\":{\"fileInfo\":{\"name\":\"Wrong-1.0.jar\"},\"downloadUrl\":\"https://hangarcdn.papermc.io/wrong.jar\"},"
                + "\"PAPER\":{\"fileInfo\":{\"name\":\"ViaVersion-5.12.3.jar\",\"sizeBytes\":6518457,"
                + "\"sha256Hash\":\"36b81cffa71862d0eae63ae9860dec3c49a4abfaca8e30438a59ccd96c1af37f\"},"
                + "\"externalUrl\":null,\"downloadUrl\":\"https://hangarcdn.papermc.io/plugins/ViaVersion.jar\"}},"
                + "\"pluginDependencies\":{\"PAPER\":[{\"name\":\"ViaBackwards\",\"projectId\":12,\"required\":false,"
                + "\"externalUrl\":null,\"platform\":\"PAPER\"}]},"
                + "\"platformDependencies\":{\"PAPER\":[\"1.21\",\"26.2\"]}}]}";
        List<rootjar.addons.AddonSources.Version> hangarVersions = rootjar.addons.AddonSources.hangarVersions(hangar);
        check(hangarVersions.size() == 1, "hangar versions are parsed");
        check(hangarVersions.get(0).id().equals("5.12.3") && hangarVersions.get(0).name().equals("5.12.3"), "the hangar version name is the picker id");
        check(hangarVersions.get(0).url().endsWith("ViaVersion.jar"), "the paper file is chosen over another platform listed first");
        check(hangarVersions.get(0).fileName().equals("ViaVersion-5.12.3.jar"), "the hangar file name comes from fileInfo");
        check(hangarVersions.get(0).size() == 6518457L, "the hangar file size is read");
        check(hangarVersions.get(0).sha256().length() == 64, "the hangar sha256 is read for verification");
        check(hangarVersions.get(0).dependencies().size() == 1, "hangar plugin dependencies are read");
        check(hangarVersions.get(0).dependencies().get(0).name().equals("ViaBackwards")
                && hangarVersions.get(0).dependencies().get(0).projectId().equals("12"), "a hangar dependency keeps its name and project id");
        check(!hangarVersions.get(0).dependencies().get(0).required(), "an optional hangar dependency is not marked required");
        check(rootjar.addons.AddonSources.hangarDownload(hangar).url().endsWith("ViaVersion.jar"), "hangarDownload resolves the same paper file");
        check(rootjar.addons.AddonSources.hangarVersions("{\"result\":[]}").isEmpty(), "no hangar version means no entries");
        // Hangar rejects "version" as a filter on the versions endpoint, so the install path fetches a chosen
        // version by URL, which returns the version object itself with no "result" wrapper.
        String single = "{\"name\":\"5.12.3\",\"downloads\":{\"PAPER\":{\"fileInfo\":{\"name\":\"ViaVersion.jar\",\"sizeBytes\":7},"
                + "\"downloadUrl\":\"https://hangarcdn.papermc.io/plugins/ViaVersion.jar\"}},\"pluginDependencies\":{\"PAPER\":[]}}";
        List<rootjar.addons.AddonSources.Version> one = rootjar.addons.AddonSources.hangarVersions(single);
        check(one.size() == 1 && one.get(0).name().equals("5.12.3"), "a single hangar version object parses like a list");
        check(rootjar.addons.AddonSources.hangarDownload(single) != null, "hangarDownload accepts the single-version response");
        check(rootjar.addons.AddonSources.hangarVersions("{}").isEmpty(), "an object with no version data yields no entries");

        String spigot = "[{\"downloads\":20859,\"url\":\"resources/essentialsx.9089/download?version=32729\","
                + "\"name\":\"2.0.1\",\"releaseDate\":1436209920,\"resource\":9089,\"id\":32729},"
                + "{\"downloads\":1,\"name\":\"\",\"id\":7}]";
        List<rootjar.addons.AddonSources.Version> spigotVersions = rootjar.addons.AddonSources.spigotVersions(spigot);
        check(spigotVersions.size() == 1, "spigot versions are parsed and a nameless one is dropped");
        check(spigotVersions.get(0).id().equals("32729"), "the spigot version id is the one ?version= takes");
        check(spigotVersions.get(0).name().equals("2.0.1"), "the spigot version name is shown in the picker");
        check(spigotVersions.get(0).dependencies().isEmpty(), "spiget reports no dependency data");

        String modrinth = "[{\"id\":\"vK9x2\",\"version_number\":\"1.2.3\",\"name\":\"Fabric API 1.2.3\","
                + "\"files\":[{\"url\":\"https://cdn.modrinth.com/extra.jar\",\"filename\":\"extra.jar\",\"size\":10,\"primary\":false},"
                + "{\"url\":\"https://cdn.modrinth.com/main.jar\",\"filename\":\"main.jar\",\"size\":2048,\"primary\":true}],"
                + "\"dependencies\":[{\"version_id\":null,\"project_id\":\"VOYxIjFI\",\"file_name\":null,\"dependency_type\":\"incompatible\"},"
                + "{\"version_id\":\"depV1\",\"project_id\":\"depP1\",\"file_name\":\"Vault.jar\",\"dependency_type\":\"required\"},"
                + "{\"version_id\":null,\"project_id\":\"depP2\",\"file_name\":null,\"dependency_type\":\"embedded\"}]}]";
        List<rootjar.addons.AddonSources.Version> modrinthVersions = rootjar.addons.AddonSources.modrinthVersions(modrinth);
        check(modrinthVersions.size() == 1, "modrinth versions are parsed");
        check(modrinthVersions.get(0).id().equals("vK9x2"), "the modrinth version id is the install target");
        check(modrinthVersions.get(0).name().equals("1.2.3"), "the modrinth version number is shown, not the display name");
        check(modrinthVersions.get(0).url().endsWith("main.jar"), "the primary modrinth file wins over an earlier extra");
        check(modrinthVersions.get(0).fileName().equals("main.jar") && modrinthVersions.get(0).size() == 2048L, "the modrinth file name and size are read");
        check(modrinthVersions.get(0).dependencies().size() == 1, "only installable modrinth dependencies are kept");
        check(modrinthVersions.get(0).dependencies().get(0).required(), "a required modrinth dependency is flagged");
        check(modrinthVersions.get(0).dependencies().get(0).projectId().equals("depP1")
                && modrinthVersions.get(0).dependencies().get(0).versionId().equals("depV1"), "a modrinth dependency keeps both identifiers");

        String versions = rootjar.addons.AddonSources.toJsonVersions(modrinthVersions, "modrinth");
        check(versions.startsWith("{\"source\":\"modrinth\",\"items\":[{\"id\":\"vK9x2\""), "the versions json has the shared shape");
        check(versions.contains("\"dependencies\":[{\"name\":\"Vault.jar\",\"projectId\":\"depP1\",\"versionId\":\"depV1\",\"required\":true"), "dependencies are rendered for the picker");
        check(!versions.contains("cdn.modrinth.com"), "the versions json never exposes a download url");
        check(rootjar.addons.AddonSources.toJsonVersions(List.of(), "spigot").contains("\"items\":[]"), "an empty version list is still valid json");

        // Modrinth names a dependency by project id, so the picker needs the project title to be readable.
        List<rootjar.addons.AddonSources.Version> anonymous = rootjar.addons.AddonSources.modrinthVersions(
                "[{\"id\":\"v2\",\"version_number\":\"2.0\",\"files\":[],\"dependencies\":["
                + "{\"version_id\":\"VHtnT9Q4\",\"project_id\":\"AANobbMI\",\"file_name\":null,\"dependency_type\":\"required\"}]}]");
        check(anonymous.get(0).dependencies().get(0).name().equals("AANobbMI"), "a nameless modrinth dependency falls back to its project id");
        List<rootjar.addons.AddonSources.Version> named = rootjar.addons.AddonSources.withDependencyNames(
                anonymous, java.util.Map.of("AANobbMI", "Sodium"));
        check(named.get(0).dependencies().get(0).name().equals("Sodium"), "a dependency known only by project id gets its title");
        check(named.get(0).dependencies().get(0).projectId().equals("AANobbMI")
                && named.get(0).dependencies().get(0).versionId().equals("VHtnT9Q4"), "renaming a dependency keeps its identifiers");
        check(named.get(0).id().equals("v2") && named.get(0).dependencies().get(0).required(), "renaming a dependency leaves the rest of the version alone");
        check(rootjar.addons.AddonSources.withDependencyNames(modrinthVersions, java.util.Map.of("depP1", "Sodium"))
                .get(0).dependencies().get(0).name().equals("Vault.jar"), "a dependency that already has a file name keeps it");
    }

    private static void geyserAndMultiPort() throws Exception {
        Path dir = Files.createTempDirectory("geyser");
        Path geyser = dir.resolve("plugins").resolve("Geyser-Spigot");
        Files.createDirectories(geyser);
        Files.writeString(geyser.resolve("config.yml"),
                "# Geyser config\nbedrock:\n  address: 0.0.0.0\n  port: 19132 # Bedrock port\n  clone-remote-port: false\nremote:\n  address: auto\n  port: 25565\n");
        check(rootjar.addons.GeyserConfig.find(dir) != null, "geyser config is discovered");
        check(rootjar.addons.GeyserConfig.find(dir).getParent().getFileName().toString().equals("Geyser-Spigot"), "geyser folder name is matched");
        check(rootjar.addons.GeyserConfig.bedrockPort(rootjar.addons.GeyserConfig.find(dir)) == 19132, "bedrock port is read");
        check(rootjar.addons.GeyserConfig.setBedrockPort(rootjar.addons.GeyserConfig.find(dir), 25572), "bedrock port is rewritten");
        String after = Files.readString(geyser.resolve("config.yml"));
        check(after.contains("port: 25572 # Bedrock port"), "bedrock port keeps its comment");
        check(after.contains("address: 0.0.0.0"), "other geyser settings survive");
        check(after.contains("remote:"), "the remote block survives");
        check(!after.contains("port: 25572 # Bedrock port\n  port:"), "only one bedrock port line is written");
        check(rootjar.addons.GeyserConfig.bedrockPort(rootjar.addons.GeyserConfig.find(dir)) == 25572, "the new bedrock port is read back");
        check(!after.contains("port: 25572\n  port:"), "the remote port is untouched");
        check(after.contains("  port: 25565"), "remote port keeps its value");
        check(rootjar.addons.GeyserConfig.find(dir.resolve("missing")) == null, "no geyser config means null");

        Path plain = Files.createTempDirectory("no-geyser");
        Files.createDirectories(plain.resolve("plugins").resolve("Other"));
        Files.writeString(plain.resolve("plugins").resolve("Other").resolve("config.yml"), "bedrock:\n  port: 1\n");
        check(rootjar.addons.GeyserConfig.find(plain) == null, "a non-geyser folder is not matched");

        check(rootjar.addons.GeyserConfig.parsePorts("19132, 19133 19132").equals(java.util.List.of(19132, 19133)), "extra ports are parsed and de-duplicated");
        check(rootjar.addons.GeyserConfig.parsePorts("0, 70000, abc").isEmpty(), "invalid extra ports are rejected");

        ChildConfig child = new ChildConfig("Event", "paper", dir, "server.jar", "2048M", true, 25566, "0.0.0.0",
                rootjar.config.StopStrategy.STDIN, "stop", 30, 1, 1, true, java.util.List.of(5), java.util.List.of(),
                true, true, false, "", true, "", 0, java.util.List.of(19132, 19133));
        check(child.usesPort(25566), "the primary port is in use");
        check(child.usesPort(19132) && child.usesPort(19133), "extra ports are in use");
        check(!child.usesPort(25567), "an unrelated port is free");
        check(child.withJavaPath("/opt/java/bin/java").extraPorts().equals(java.util.List.of(19132, 19133)), "changing java keeps extra ports");
        check(child.withIdleShutdownSeconds(600).extraPorts().equals(java.util.List.of(19132, 19133)), "changing idle shutdown keeps extra ports");
        check(child.withExtraPorts(java.util.List.of(19132)).extraPorts().equals(java.util.List.of(19132)), "extra ports can be replaced");

        Path roundTrip = Files.createTempDirectory("ports-config");
        Path configFile = roundTrip.resolve("config.yml");
        ServerConfig serverConfig = new ServerConfig("127.0.0.1", "127.0.0.1", 12035, 3600, 30, 10, 300,
                PasswordHasher.hash("correct horse".toCharArray()), java.util.List.of(), 0, 5, "", false, java.util.List.of());
        ConfigSaver.save(configFile, new RootConfig(serverConfig, java.util.List.of(child), roundTrip));
        String yaml = Files.readString(configFile);
        check(yaml.contains("extra_ports: [19132, 19133]"), "extra ports are written to config.yml");
        RootConfig reloaded = ConfigLoader.load(configFile);
        check(reloaded.children().get(0).extraPorts().equals(java.util.List.of(19132, 19133)), "extra ports survive a reload");
    }

    private static void panelNaming() {
        check(WebServer.sanitizePanelName("  Stellarfall Network  ").equals("Stellarfall Network"), "the panel name is trimmed");
        check(WebServer.sanitizePanelName("a\r\nb").equals("a  b"), "newlines cannot break the properties file");
        check(WebServer.sanitizePanelName(null).isEmpty(), "a missing panel name becomes empty");
        check(WebServer.sanitizePanelName("x".repeat(80)).length() == 48, "the panel name is capped at 48 characters");
    }

    private static void activityFeed() throws Exception {
        Path base = Files.createTempDirectory("activity-base");
        Path server = base.resolve("Event");
        Files.createDirectories(server);
        ActivityLog.append(server, "ada", "backup.create", "Event-backup-2026.zip");
        ActivityLog.append(base, "ada", "Event.file.write", "plugins/config.yml");
        ActivityLog.append(base, "ada", "Event.file.write", "plugins/config.yml");
        ActivityLog.append(base, "bob", "Other.console.command", "say hi");
        ActivityLog.append(base, "ada", "auth.login", "10.0.0.5");

        String merged = ActivityLog.recentForServer(server, base.resolve("activity.log"), "Event", 50, "");
        check(merged.contains("\"action\":\"file.write\""), "server feed strips the server prefix");
        check(!merged.contains("Event.file.write"), "server feed does not keep the prefixed action");
        check(!merged.contains("Other.console.command"), "server feed excludes other servers");
        check(merged.contains("backup.create"), "server feed keeps its own log entries");
        check(merged.indexOf("file.write") == merged.lastIndexOf("file.write"), "server feed collapses a double-written event");
        check(merged.contains("10.0.0.5") == false, "panel-only events stay out of the server feed");
        check(ActivityLog.recentForServer(server, base.resolve("activity.log"), "Event", 50, "config.yml").contains("file.write"), "server feed honours search");
        check(!ActivityLog.recentForServer(server, base.resolve("activity.log"), "Event", 50, "config.yml").contains("backup.create"), "server feed search filters out other events");
    }

    private static void frontendSync() throws Exception {
        Path dir = Files.createTempDirectory("frontend-sync");
        Path index = dir.resolve("index.html");
        String bundled = rootjar.Frontend.html();
        check("created".equals(rootjar.Frontend.syncToDisk(index)), "frontend sync creates a missing index.html");
        check(Files.readString(index).equals(bundled), "frontend sync writes the bundled panel");
        check("unchanged".equals(rootjar.Frontend.syncToDisk(index)), "frontend sync leaves a matching index.html alone");
        Files.writeString(index, "<html>stale panel</html>");
        check("refreshed".equals(rootjar.Frontend.syncToDisk(index)), "frontend sync refreshes a stale index.html");
        check(Files.readString(index).equals(bundled), "frontend sync replaces the stale panel");
        check(Files.isRegularFile(dir.resolve("index.html.old")), "frontend sync keeps the previous panel as index.html.old");
        check(Files.readString(dir.resolve("index.html.old")).contains("stale panel"), "frontend sync backup holds the old panel");
    }

    /**
     * The panel is one file carrying non-ASCII glyphs. An editor that assumes a single-byte charset rewrites
     * U+2193 as the three characters "â†“"; the build bundles that verbatim and every deployment then shows
     * mojibake, so this checks the copy the jar actually serves rather than trusting the source.
     */
    private static void frontendEncoding() throws Exception {
        String html = rootjar.Frontend.html();
        check(html.contains("mc-font"), "bundled panel is the real UI, not the missing-resource fallback");
        check(!html.startsWith("\uFEFF"), "bundled panel carries no UTF-8 byte-order mark");
        check(html.contains("<meta charset=\"UTF-8\">"), "bundled panel declares its charset");
        // None of these occur legitimately in the panel; each heads a UTF-8 sequence that was decoded as
        // CP1252 and re-encoded, which is how a clean arrow turns into visible garbage.
        String[] corrupted = {"\u00C2", "\u00C3", "\u00E2\u20AC", "\u00E2\u2020"};
        for (String mark : corrupted) {
            check(!html.contains(mark), "bundled panel has no double-encoded UTF-8 at U+" + codePoints(mark));
        }
        String[] glyphs = {"\u2190", "\u2191", "\u2192", "\u2193", "\u00B7", "\u2026", "\u2014"};
        for (String g : glyphs) check(html.contains(g), "bundled panel keeps U+" + codePoints(g) + " intact");
        // A byte-order mark on disk must not make an otherwise matching panel look stale on every start.
        Path dir = Files.createTempDirectory("frontend-bom");
        Path index = dir.resolve("index.html");
        Files.writeString(index, "\uFEFF" + html, StandardCharsets.UTF_8);
        check("unchanged".equals(rootjar.Frontend.syncToDisk(index)), "frontend sync ignores a byte-order mark when comparing");
        // The pixel font is inlined: the panel used to hotlink a CDN, which leaked operator IPs and left it
        // with no pixel font when offline. Minecraftia's cmap has no arrow glyphs, so arrows are wrapped in
        // .arrow and drawn from a font that does have them.
        check(!html.contains("cdnfonts"), "bundled panel does not hotlink a font CDN");
        check(html.contains("data:font/woff;base64,"), "bundled panel inlines the pixel font");
        check(!html.contains("<body class=\"mc-font\">"), "the pixel font comes from storage, not a hardcoded body class");
        check(html.contains(".arrow {"), "arrows have a font that contains them");
        check(html.contains("<span class=\"arrow\">\u2190</span>"), "the back button arrow is styled");
        // The right-click menu: every entry carries an icon, and permanent delete sits apart from Trash.
        check(count(html, "class=\"ctx-item") == count(html, "class=\"ctx-ico\""), "every context-menu item carries an icon");
        check(count(html, "class=\"ctx-item") == 8, "the context menu has eight entries");
        check(html.contains("ctxAction('move')"), "the context menu offers Move");
        check(html.contains("id=\"ctx-head\""), "the context menu can show how many items it will act on");
        check(html.contains("fileSelection.has(path)"), "right-clicking a selected row acts on the whole selection");
        check(html.contains("function runOnTargets("), "bulk context actions report per-file failures");
        check(html.contains("Download works on one file at a time"), "download explains itself on a multi-selection");
        check(html.contains("ctxAction('delete')"), "the context menu offers a permanent delete");
        check(html.contains("ctxAction('trash')"), "the context menu keeps the trash action");
        check(html.contains(">Trash</div>"), "the trash entry is labelled Trash");
        // Placement: the menu is fixed and clamped, so the last entries stay reachable on a short window.
        check(html.contains(".ctx-menu { position: fixed;"), "the context menu is fixed so it can be clamped to the viewport");
        check(html.contains("window.innerHeight"), "the context menu is clamped against the viewport height");
        check(html.contains("event.clientY - height"), "the context menu flips above the cursor when it would overflow");
        check(html.contains("max-height: calc(100vh - 16px)"), "the context menu caps its own height");
        check(!html.contains("menu.style.left = event.pageX"), "the context menu no longer uses page coordinates");
        // Console links are produced after escaping, and only in text between tags.
        check(html.contains("linkifyConsole(ansiToHtml(text))"), "console lines are linkified after they are escaped");
        check(!html.contains("html: ansiToHtml(text)"), "no console path skips linkifying");
        check(html.contains("rel=\"noopener noreferrer\""), "console links open a new tab without leaking the session");
        check(html.contains(".console-output a {"), "console links are styled");
        check(html.contains("CONSOLE_SOURCE_EXT"), "stack-trace frames are kept out of the console link matcher");
        // The console log area is themed; the raw config editor keeps its deliberate black-on-green look.
        check(html.contains(".console-container { flex: 1; background: var(--bg-input);"), "the console log area uses a theme colour");
        check(count(html, "background: #000") == 3, "only the console lost its hardcoded black");
        check(html.contains(".console-time { color: var(--text-muted); opacity: .8;"), "console timestamps are brightened for the lighter surface");
        check(html.contains("parts.push('opacity:.85')"), "ANSI dim text is brightened for the lighter surface");
        // Move uses a folder picker now, not a typed path.
        check(html.contains("id=\"move-modal\""), "the move picker modal is in the markup");
        check(html.contains("function openMovePicker("), "the move picker exists");
        check(html.contains("function bulkMove() { openMovePicker([...fileSelection]); }"), "bulk move opens the picker");
        check(!html.contains("promptModal({title:'Move Selected'"), "the typed destination prompt is gone");
        check(html.contains("Cannot move a folder into itself"), "the picker refuses to move a folder into itself");
        check(html.contains("movePickerBack") && html.contains("movePickerForward"), "the picker keeps back and forward history");
        check(html.contains("movePickerNewFolder"), "the picker can create a folder and move into it");
        check(html.contains("filter(entry => entry.dir)"), "the picker lists folders only");
        // A reverse proxy answers an oversized upload with its own HTML page before the panel sees it.
        check(html.contains("client_max_body_size"), "an oversized upload names the proxy limit to raise");
        check(html.contains("status === 429"), "a rate-limited upload is explained");
        // The browser keeps 1,000 lines, which is what the console backfills on connect; if either number
        // moves the other should move with it.
        check(html.contains("consoleLines.length > 1000"), "the browser keeps as many lines as the console backfills");
        // The Startup tab exposes both JVM switches and renders the command line the backend really built.
        check(html.contains("id=\"startup-xms-equal\""), "the startup tab exposes the xms toggle");
        check(html.contains("id=\"startup-aikar\""), "the startup tab exposes the aikar toggle");
        check(html.contains("useAikarFlags: document.getElementById('startup-aikar').checked"), "saving startup keeps the real aikar state");
        check(html.contains("xmsEqualXmx: document.getElementById('startup-xms-equal').checked"), "saving startup sends the xms toggle");
        check(html.contains("s.commandLine"), "the preview renders the command line the backend built");
        check(!html.contains("JVM_PRESETS['aikar-medium']"), "the managed flags are no longer copied into the flags box");
        check(html.contains("id=\"startup-exit-oom\""), "the startup tab exposes the exit-on-oom toggle");
        check(html.contains("exitOnOom: document.getElementById('startup-exit-oom').checked"), "saving startup sends the oom toggle");
        check(html.contains("ExitOnOutOfMemoryError"), "the toggle names the flag it adds");
        check(html.contains("if (!response.ok) showToast((await response.text())"), "a refused start shows the reason");
        // The sidebar carried a hardcoded "CONNECTED" label and a permanently green dot beside the real state
        // text, so it claimed everything was fine for a stopped, crashed or killed server.
        check(html.contains("function setStateIndicators("), "server state drives the indicators");
        check(html.contains("id=\"sidebar-state-dot\"") && html.contains("id=\"sidebar-state-text\""), "the sidebar indicator has a dot and label the code can update");
        check(!html.contains("id=\"sidebar-status\""), "the duplicated status text beside the indicator is gone");
        check(html.contains("setStateIndicators(s.status)"), "the status poll feeds the indicators");
        check(html.contains(".status-dot.state-running") && html.contains(".status-dot.state-crashed")
                && html.contains(".status-dot.state-stopped") && html.contains(".status-dot.state-starting"), "the dot has a colour per state");
        check(html.contains("border-radius: 50%; background: var(--text-muted); display: inline-block"), "the dot is neutral until a state is known");
        check(html.contains("id=\"live-badge-text\""), "the resources badge is driven rather than hardcoded");
        check(html.contains("'Live' : 'Idle'"), "the resources badge stops claiming Live for a server that is not running");
        // Opening a server backfills 1,000 console lines. Appending them one at a time cost one HTML parse and
        // two scrollHeight reads each, and every read forces a synchronous layout - that was the multi-second
        // freeze on Manage.
        check(html.contains("function flushConsole(") && html.contains("function scheduleConsoleFlush("), "console lines are buffered and flushed once per frame");
        check(html.contains("requestAnimationFrame("), "the flush runs on an animation frame");
        check(html.contains("out.insertAdjacentHTML('beforeend', pending.map(consoleLineHtml).join(''))"), "a burst is inserted as one html string");
        check(!html.contains("out.scrollTop = out.scrollHeight"), "the console no longer measures scrollHeight after appending");
        check(html.contains("out.scrollTop = 1e9"), "staying pinned to the bottom avoids a forced layout");
        check(html.contains("if (consolePending.length > 1000) consolePending.shift()"), "the buffer is capped for a hidden tab, which runs no frames");
        check(html.contains("Anything still buffered belongs to the server being left behind"), "switching servers drops the previous server's buffered lines");
        // Sidebar power buttons: Start and Stop merged into one, greyed when an action does not apply, and an
        // icon mode that reuses the dashboard's icons. Greyed buttons stay clickable by design.
        check(html.contains("function renderPowerButtons(") && html.contains("function powerAvailability("), "the power buttons are rendered from state, with one place deciding what applies");
        check(html.contains("function togglePowerIconMode(") && html.contains("rootjar.powerIconMode"), "icon mode is a per-browser preference");
        check(html.contains("id=\"power-mode-toggle\""), "the status box carries the mode switch");
        check(html.contains("id=\"sidebar-actions\""), "the button row is rendered rather than hardcoded");
        check(html.contains("POWER_ICONS"), "icon mode has its own icon set");
        check(html.contains(".status-btn.dim, .status-btn.dim:hover"), "the greyed style is declared after the hover rules so it wins");
        check(html.contains("dim(a.restart)") && html.contains("dim(a.kill)"), "restart and kill are greyed by state");
        check(html.contains("toggleAction: (running || state === 'STOPPING') ? 'stop' : 'start'"), "a stopping server still offers stop, greyed, rather than flipping to start");
        check(html.contains("if (key === powerButtonsKey) return"), "repeat status polls do not rewrite the buttons");
        check(!html.contains("onclick=\"powerAction('stop')\"") && !html.contains(">Start</button>"), "the four static buttons are gone");
        // Per-server storage limits and the confirm-and-retry on RAM.
        check(html.contains("id=\"wizard-storage\"") && html.contains("id=\"edit-server-storage\""), "the storage limit is settable at creation and in Edit Server");
        check(html.contains("storageMaxGb: storageMaxGb"), "the create payload carries the storage limit");
        check(html.contains("storageMaxGb, jar, type, displayHost"), "the edit payload carries the storage limit");
        check(html.contains("storageLimitBytes > 0"), "the storage bar uses the per-server limit when there is one");
        check(html.contains("detail.code === 'ram_limit'") && html.contains("powerAction(action, true)"), "an over-committed start asks first and retries with force");
        check(html.contains("dashboardPower(id, action, true)"), "the dashboard start asks the same way");
        // Activity paging.
        check(html.contains("function renderActivityPager(") && html.contains("id=\"activity-pager\"") && html.contains("id=\"audit-pager\""), "both activity lists have a pager");
        check(html.contains("&limit=' + ACTIVITY_PAGE_SIZE + '&offset='"), "activity requests a page with a limit and an offset");
        check(html.contains("function refusalMessage("), "a refused write shows the reason rather than raw JSON");
        check(html.contains("currentServer.type === 'website') ? 'public' : currentPath"), "importing at the server root does not go to public");
        check(!html.contains("if (res.ok) { showToast('Saved', 'success'); document.getElementById('fm-modal').classList.remove('active'); }"),
                "a failed save is reported instead of silently doing nothing");
    }

    /** Counts non-overlapping occurrences; used to keep markup and its decorations in step. */
    private static int count(String haystack, String needle) {
        int found = 0, at = 0;
        while ((at = haystack.indexOf(needle, at)) >= 0) { found++; at += needle.length(); }
        return found;
    }

    /** Every start agrees to the Minecraft EULA; imported and copied servers often arrive without it. */
    private static void eulaAgreement() throws Exception {
        Path fresh = Files.createTempDirectory("eula-missing");
        check(ChildProcess.ensureEulaFile(fresh), "a missing eula.txt is written");
        check(Files.readString(fresh.resolve("eula.txt")).contains("eula=true"), "the written file agrees to the EULA");
        check(!ChildProcess.ensureEulaFile(fresh), "an agreed eula.txt is left alone");

        Path refused = Files.createTempDirectory("eula-false");
        Files.writeString(refused.resolve("eula.txt"), "#By changing the setting below you agree to the EULA\neula=false\n");
        check(ChildProcess.ensureEulaFile(refused), "a refused eula.txt is rewritten");
        check(Files.readString(refused.resolve("eula.txt")).contains("eula=true"), "the rewrite agrees to the EULA");

        Path commented = Files.createTempDirectory("eula-comment");
        Files.writeString(commented.resolve("eula.txt"), "#eula=true is required\neula=false\n");
        check(ChildProcess.ensureEulaFile(commented), "a commented-out agreement still counts as refused");

        Path spaced = Files.createTempDirectory("eula-spaced");
        Files.writeString(spaced.resolve("eula.txt"), "eula = TRUE\n");
        check(!ChildProcess.ensureEulaFile(spaced), "an agreement with odd spacing is recognised");
    }

    private static String codePoints(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(Integer.toHexString(s.charAt(i)).toUpperCase());
        }
        return sb.toString();
    }

    private static void bukkitDatapackRepair() throws Exception {
        Path root = Files.createTempDirectory("datapack-check");
        Files.writeString(root.resolve("server.properties"), "level-name=Event_World\nserver-port=25565\n");
        String created = ChildProcess.ensureBukkitDatapack(root);
        check(created != null && created.equals("Event_World/datapacks/bukkit/pack.mcmeta"), "datapack repair uses level-name");
        Path meta = root.resolve("Event_World/datapacks/bukkit/pack.mcmeta");
        check(Files.isRegularFile(meta), "datapack repair creates pack.mcmeta");
        check(ChildProcess.ensureBukkitDatapack(root) == null, "datapack repair leaves an existing pack alone");

        Path traversal = Files.createTempDirectory("datapack-traversal");
        Files.writeString(traversal.resolve("server.properties"), "level-name=../../escape\n");
        check(ChildProcess.ensureBukkitDatapack(traversal) == null, "datapack repair rejects escaped level-name");
    }

    private static void staticWebsite() throws Exception {
        Path root = Files.createTempDirectory("website-check");
        Files.writeString(root.resolve("index.html"), "<h1 id='ok'>ready</h1>");
        Path outside = Files.createTempFile("website-secret", ".txt");
        Files.writeString(outside, "secret");
        boolean symlinkCreated = false;
        try {
            Files.createSymbolicLink(root.resolve("escape"), outside);
            symlinkCreated = true;
        } catch (IOException ignored) {}
        String port = "12091";
        Process process = new ProcessBuilder(System.getProperty("java.home") + java.io.File.separator
                + "bin" + java.io.File.separator + "java", "-cp", System.getProperty("java.class.path"),
                StaticSiteServer.class.getName(), "127.0.0.1", port, root.toString()).start();
        try {
            String body = "";
            for (int i = 0; i < 30 && body.isEmpty(); i++) {
                Thread.sleep(100);
                try (InputStream in = URI.create("http://127.0.0.1:" + port + "/").toURL().openStream()) {
                    body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException ignored) {}
            }
            check(body.contains("id='ok'"), "website serves index");
            if (symlinkCreated) {
                HttpURLConnection denied = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/escape").toURL().openConnection();
                check(denied.getResponseCode() == 403, "website rejects symlink escape");
            }
            HttpURLConnection traversal = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/%2e%2e/secret.txt").toURL().openConnection();
            check(traversal.getResponseCode() == 403, "website rejects traversal");
        } finally {
            process.destroyForcibly();
            process.waitFor();
            Files.deleteIfExists(root.resolve("escape"));
            Files.deleteIfExists(root.resolve("index.html"));
            Files.deleteIfExists(root);
            Files.deleteIfExists(outside);
        }
    }

    private static void fileOps() throws Exception {
        Path root = Files.createTempDirectory("file-ops");
        Files.writeString(root.resolve("index.html"), "home");
        Path nested = Files.createDirectories(root.resolve("public/assets"));
        Files.writeString(nested.resolve("logo.txt"), "logo");
        boolean rejected = false;
        try { FileOps.resolve(root, "../secret.txt"); } catch (IOException e) { rejected = true; }
        check(rejected, "file ops rejects traversal");
        Path zip = root.resolve("site.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("../evil.txt")); out.write("bad".getBytes(StandardCharsets.UTF_8)); out.closeEntry();
        }
        boolean zipRejected = false;
        try { FileOps.extract(zip, root.resolve("extracted")); } catch (IOException e) { zipRejected = true; }
        check(zipRejected && Files.notExists(root.resolve("evil.txt")), "file ops rejects zip slip");
        List<Path> found = FileOps.searchNames(root, "logo", 10);
        check(found.size() == 1 && found.get(0).endsWith("logo.txt"), "file ops finds nested names");
        FileOps.copyRecursive(nested, root.resolve("copy"));
        check(Files.readString(root.resolve("copy/logo.txt")).equals("logo"), "file ops copies directories");

        // Importing over an existing tree must replace the file rather than reuse its inode. Reusing it keeps a
        // read-only mode or a foreign owner, and the server then fails to save beside it after the import.
        Path target = Files.createDirectories(root.resolve("replace"));
        Path existing = target.resolve("paper-world.yml");
        Files.writeString(existing, "old");
        existing.toFile().setWritable(false);
        Path replaceZip = target.resolve("payload.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(replaceZip))) {
            out.putNextEntry(new ZipEntry("paper-world.yml"));
            out.write("new".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        FileOps.extract(replaceZip, target);
        check(Files.readString(existing).equals("new"), "extract replaces an existing read-only file");
        check(Files.isWritable(existing), "the replaced file is writable again");

        // Hidden files are normal in a server, and rejecting them used to abort the whole import part way.
        Path hiddenZip = root.resolve("hidden.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(hiddenZip))) {
            out.putNextEntry(new ZipEntry("plugins/.paper-remapped/a.txt"));
            out.write("x".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry(".rootjar-permissions-repaired"));
            out.write("fake".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        FileOps.Extracted hidden = FileOps.extract(hiddenZip, root.resolve("hidden"));
        check(Files.readString(root.resolve("hidden/plugins/.paper-remapped/a.txt")).equals("x"), "extract keeps hidden files");
        check(Files.notExists(root.resolve("hidden/.rootjar-permissions-repaired")), "extract cannot fake a repair marker");
        check(hidden.entries() == 2 && hidden.bytes() > 0, "extract reports what it produced");
        check(FileOps.MAX_ZIP_ENTRIES >= 100_000, "the entry limit fits a real server");
    }

    private static void backups() throws Exception {
        Path root = Files.createTempDirectory("backup-check");
        Files.writeString(root.resolve("world.txt"), "safe");
        ChildConfig child = new ChildConfig("paper1", "paper", root, "server.jar", "1024M", true, 25565, "127.0.0.1",
                StopStrategy.STDIN, "stop", 30, 1, 1, false, List.of(5), List.of(), true, false, false, "", true, "", 0);
        BackupManager.BackupInfo created = BackupManager.create(child, "manual", false, 5);
        check(BackupManager.NAME.matcher(created.name()).matches(), "backup name is safe");
        BackupManager.BackupInfo second = BackupManager.create(child, "manual", false, 5);
        check(!second.name().equals(created.name()), "backups created in the same second have distinct names");
        BackupManager.BackupInfo scheduled = BackupManager.create(child, "scheduled", true, 5);
        check(scheduled.name().startsWith("paper1-scheduled-") && !scheduled.name().equals("scheduled.zip"), "scheduled backups keep dated names");
        BackupManager.BackupInfo safety = BackupManager.create(child, "pre-restore", true, 5);
        check(safety.name().startsWith("paper1-pre-restore-"), "pre-restore backups keep dated names");
        try (var archive = new java.util.zip.ZipFile(BackupManager.resolve(child, second.name()).toFile())) {
            check(archive.getEntry("backup.json") != null, "backup includes metadata");
            check(archive.getEntry("world.txt") != null, "backup includes server files");
            check(archive.stream().noneMatch(entry -> entry.getName().startsWith("backups/")),
                    "new backup excludes earlier backups");
        }
        Files.writeString(root.resolve("world.txt"), "changed");
        BackupManager.restore(child, created.name(), "");
        check(Files.readString(root.resolve("world.txt")).equals("safe"), "backup restore replaces changed file");
        check(Files.readString(root.resolve("backup-old/world.txt")).equals("changed"), "restore keeps current files in backup-old");
        Files.writeString(root.resolve("world.txt"), "newer");
        BackupManager.restore(child, created.name(), "");
        check(Files.readString(root.resolve("backup-old/world.txt")).equals("newer"), "another restore replaces backup-old");
        Path evil = root.resolve("evil.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(evil))) {
            zip.putNextEntry(new ZipEntry("../outside.txt")); zip.write("bad".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        Files.copy(evil, BackupManager.directory(child).resolve("evil-import.zip"));
        boolean rejected = false;
        try { BackupManager.restore(child, "evil-import.zip", ""); } catch (IOException e) { rejected = true; }
        check(rejected && Files.notExists(root.getParent().resolve("outside.txt")), "backup restore rejects zip slip");
        Files.writeString(root.resolve(PermissionRepair.SUCCESS_MARKER), "repaired");
        Files.writeString(root.resolve(PermissionRepair.FAILURE_MARKER), "1 " + System.currentTimeMillis());
        BackupManager.BackupInfo markerBackup = BackupManager.create(child, "markers", false, 5);
        try (var archive = new java.util.zip.ZipFile(BackupManager.resolve(child, markerBackup.name()).toFile())) {
            check(archive.stream().noneMatch(entry -> entry.getName().equals(PermissionRepair.SUCCESS_MARKER)
                    || entry.getName().equals(PermissionRepair.FAILURE_MARKER)), "backup excludes permission repair markers");
        }
        BackupManager.restore(child, markerBackup.name(), "");
        check(Files.notExists(root.resolve(PermissionRepair.SUCCESS_MARKER))
                && Files.notExists(root.resolve(PermissionRepair.FAILURE_MARKER)), "backup restore removes permission repair markers");
        Path legacy = root.resolve("backups");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("old-legacy.zip"), "old");
        BackupManager.BackupInfo kept = BackupManager.create(child, "kept", false, 1);
        check(BackupManager.list(child).stream().anyMatch(info -> info.name().equals(kept.name())), "retention keeps newest backup");
        check(BackupManager.list(child).stream().noneMatch(info -> info.name().equals("old-legacy.zip")), "retention includes legacy backups");
        Files.writeString(root.resolve("backup-live").resolve("shared.zip"), "live");
        Files.writeString(root.resolve("backups").resolve("shared.zip"), "legacy");
        var shared = BackupManager.list(child).stream().filter(info -> info.name().equals("shared.zip")).toList();
        check(shared.size() == 2 && shared.stream().anyMatch(info -> info.folder().equals("backups")), "duplicate backup names keep their folders");
        check(Files.readString(BackupManager.resolve(child, "shared.zip", "backups")).equals("legacy"), "backup resolve honors the requested folder");
        Files.writeString(legacy.resolve("addons-plugins-old.zip"), "plugins");
        Files.createDirectories(BackupManager.addonDirectory(child));
        Files.writeString(BackupManager.addonDirectory(child).resolve("addons-mods-old.zip"), "mods");
        check(BackupManager.list(child).stream().noneMatch(info -> info.name().startsWith("addons-")), "addon archives stay out of server backups");
        boolean addonRejected = false;
        try { BackupManager.resolve(child, "addons-plugins-old.zip", "backups"); } catch (IOException e) { addonRejected = true; }
        check(addonRejected, "addon archives cannot be restored as server backups");
        Files.writeString(legacy.resolve("custom.zip"), "legacy-custom");
        BackupManager.BackupInfo renamed = BackupManager.create(child, "custom", false, 5);
        check(!renamed.name().equals("custom.zip"), "custom names avoid legacy backup names");
        Files.writeString(root.resolve("world.txt"), "old-world");
        BackupManager.BackupInfo oldWorld = BackupManager.create(child, "old-world", false, 5);
        Files.writeString(root.resolve("world.txt"), "before-old-restore");
        for (int i = 0; i < 6; i++) BackupManager.create(child, "newer-" + i, false, 20);
        BackupManager.create(child, "pre-restore", true, 1, oldWorld.name());
        check(Files.exists(root.resolve("backup-live").resolve(oldWorld.name())), "retention keeps the backup selected for restore");
        BackupManager.restore(child, oldWorld.name(), "backup-live");
        check(Files.readString(root.resolve("world.txt")).equals("old-world"), "an older backup can still be restored");
    }

    // ---- PermissionRepair: marker files stop repeated startup scans ---------------------------

    private static void permissionRepair() throws Exception {
        Path root = Files.createTempDirectory("perm-repair");
        Path nested = Files.createDirectories(root.resolve("world"));
        Files.writeString(nested.resolve("level.dat"), "map");
        boolean posix = root.getFileSystem().supportedFileAttributeViews().contains("posix");
        boolean repaired = PermissionRepair.repairOnce(root, "check");
        if (posix) {
            check(repaired, "permission repair reports changes on posix");
            var perms = Files.getPosixFilePermissions(nested.resolve("level.dat"));
            check(perms.contains(PosixFilePermission.OWNER_READ) && perms.contains(PosixFilePermission.OWNER_WRITE),
                    "permission repair grants owner read/write");
        } else {
            check(!repaired, "permission repair is a no-op without posix");
        }
        check(Files.exists(root.resolve(PermissionRepair.SUCCESS_MARKER)), "permission repair writes success marker");
        check(PermissionRepair.matchesHost(Files.readString(root.resolve(PermissionRepair.SUCCESS_MARKER)), PermissionRepair.hostIdentity()), "permission marker matches this host");
        Files.writeString(root.resolve(PermissionRepair.SUCCESS_MARKER), "repaired other-host");
        PermissionRepair.repairOnce(root, "check");
        check(PermissionRepair.matchesHost(Files.readString(root.resolve(PermissionRepair.SUCCESS_MARKER)), PermissionRepair.hostIdentity()), "copied permission marker from another host is repaired");
        check(!PermissionRepair.repairOnce(root, "check"), "success marker prevents rescan");

        long now = System.currentTimeMillis();
        check(PermissionRepair.shouldAttempt(null, now), "missing failure marker allows repair");
        check(PermissionRepair.shouldAttempt("  ", now), "blank failure marker allows repair");
        check(PermissionRepair.shouldAttempt("2 " + now, now), "failures under the cap allow retry");
        check(!PermissionRepair.shouldAttempt("3 " + now, now), "failures at the cap block retry");
        check(PermissionRepair.shouldAttempt("3 " + (now - 25L * 60 * 60 * 1000), now), "a day-old failure marker allows retry");
        check(PermissionRepair.shouldAttempt("not-a-number", now), "unreadable failure marker allows repair");
        check(!PermissionRepair.matchesHost("repaired other-host", PermissionRepair.hostIdentity()), "foreign permission marker does not match this host");
        check(!PermissionRepair.matchesHost("repaired", PermissionRepair.hostIdentity()), "legacy permission marker does not suppress repair");
        check(!PermissionRepair.matchesHost("no-op", PermissionRepair.hostIdentity()), "legacy no-op marker does not suppress repair");
    }

    private static void router() {
        StdinRouter r = new StdinRouter(Set.of("velocity", "nanolimbo"));
        check(r.parse("status").kind() == Kind.STATUS, "status");
        check(r.parse("LIST").kind() == Kind.LIST, "list case-insensitive");
        check(r.parse("list players").kind() == Kind.FALLBACK, "'list players' is not bare list");
        check(ConfigValidator.normalizeId(" Sky   Block ").equals("Sky_Block"), "server names turn spaces into underscores");
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
        c = r.parse("VELOCITY.RESTART");
        check(c.kind() == Kind.LIFECYCLE && c.childId().equals("velocity") && c.text().equals("restart"), "server commands ignore case");
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
        // The console asks for its last CONSOLE_BACKFILL_LINES lines on connect, so since() has to clamp that
        // window rather than return nothing when fewer lines exist or the buffer has wrapped.
        check(b.since(Math.max(0, b.lastSeq() - 1000)).size() == 3, "a backfill window is clamped to what a wrapped buffer still holds");
        LogRingBuffer big = new LogRingBuffer(10_000);
        for (int i = 1; i <= 2500; i++) big.append("l" + i);
        var window = big.since(Math.max(0, big.lastSeq() - 1000));
        check(window.size() == 1000, "a 1,000-line backfill window is retrievable");
        check(window.get(0).line().equals("l1501") && window.get(999).line().equals("l2500"), "the backfill window ends at the newest line");
        LogRingBuffer small = new LogRingBuffer(10_000);
        for (int i = 1; i <= 40; i++) small.append("s" + i);
        check(small.since(Math.max(0, small.lastSeq() - 1000)).size() == 40, "a short log backfills everything it has");
        check(new LogRingBuffer(10_000).since(Math.max(0, -1000)).isEmpty(), "an empty log backfills nothing");
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
        // Aikar's published set. AlwaysPreTouch pre-faults the heap, which is what makes an equal Xms and Xmx
        // pay off, and the two markers identify the tuning in a thread dump.
        check(cmd.contains("-XX:+AlwaysPreTouch"), "the managed set pre-touches the heap");
        check(cmd.contains("-Dusing.aikars.flags=https://mcflags.emc.gs") && cmd.contains("-Daikars.new.flags=true"), "the managed set carries aikar's markers");
        check(cmd.indexOf("-XX:+UnlockExperimentalVMOptions") < cmd.indexOf("-XX:+AlwaysPreTouch"), "unlock still precedes the flags that need it");
        check(CommandLineBuilder.GC_FLAGS.size() == CommandLineBuilder.GC_FLAGS_LARGE.size(), "both heap variants carry the same number of flags");
        // xms_equal_xmx off drops -Xms and leaves the JVM to grow the heap from its own default.
        var loose = CommandLineBuilder.build(cfg.children().get(0).withXmsEqualXmx(false), "java");
        check(!loose.contains("-Xms256M") && loose.contains("-Xmx256M"), "xms_equal_xmx false emits only -Xmx");
        check(loose.get(loose.size() - 1).equals("nogui"), "turning off xms leaves the rest of the line intact");
        // use_aikar_flags off drops the whole managed set, leaving only what the operator typed.
        var plain = CommandLineBuilder.build(cfg.children().get(0).withUseAikarFlags(false), "java");
        check(!plain.contains("-XX:+UseG1GC") && !plain.contains("-XX:MaxTenuringThreshold=1"), "use_aikar_flags false drops the managed gc set");
        check(plain.contains("-Xms256M") && plain.contains("-Xmx256M"), "turning off aikar leaves the heap flags alone");
        // Aikar publishes two variants; above 12 GB the young generation widens and the regions grow.
        check(CommandLineBuilder.gcFlagsFor("1024M").contains("-XX:G1NewSizePercent=30"), "a small heap gets the small-heap variant");
        check(CommandLineBuilder.gcFlagsFor("12287M").contains("-XX:G1NewSizePercent=30"), "just under 12 GB stays on the small variant");
        check(CommandLineBuilder.gcFlagsFor("12288M").contains("-XX:G1NewSizePercent=40"), "the switch happens at exactly 12 GB");
        check(CommandLineBuilder.gcFlagsFor("16G").contains("-XX:G1HeapRegionSize=16M"), "a large heap gets 16M regions");
        check(CommandLineBuilder.gcFlagsFor("16G").contains("-XX:InitiatingHeapOccupancyPercent=20"), "a large heap gets the higher occupancy target");
        check(CommandLineBuilder.gcFlagsFor("nonsense").contains("-XX:G1NewSizePercent=30"), "an unparseable heap falls back to the small variant");
        check(CommandLineBuilder.heapMegabytes("1024M") == 1024 && CommandLineBuilder.heapMegabytes("12G") == 12288, "heap sizes are parsed to megabytes");
        check(CommandLineBuilder.heapMegabytes("1073741824") == 1024, "a bare byte count is parsed");
        check(CommandLineBuilder.heapMegabytes(null) == 0 && CommandLineBuilder.heapMegabytes("") == 0, "a missing heap parses as zero");
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

    private static void autoStartConfig() throws Exception {
        Path dir = Files.createTempDirectory("auto-start");
        RootConfig base = loadConfig(dir, PasswordHasher.hash("password1".toCharArray()),
                25568, "127.0.0.1", 25566, 25565, true);
        check(base.children().get(0).autoStart(), "auto_start defaults to true");

        ChildConfig stopped = base.children().get(0).withAutoStart(false);
        Path saved = dir.resolve("stopped.yml");
        ConfigSaver.save(saved, new RootConfig(base.server(), List.of(stopped), base.baseDir()));
        check(Files.readString(saved).contains("auto_start: false"), "auto_start false is written");
        check(!ConfigLoader.load(saved).children().get(0).autoStart(),
                "auto_start false survives save/load");

        ChildConfig running = base.children().get(0).withAutoStart(true);
        Path runningSaved = dir.resolve("running.yml");
        ConfigSaver.save(runningSaved, new RootConfig(base.server(), List.of(running), base.baseDir()));
        check(!Files.readString(runningSaved).contains("auto_start:"),
                "default auto_start is omitted so old configs stay unchanged");
        check(ConfigLoader.load(runningSaved).children().get(0).autoStart(),
                "an omitted auto_start loads as true");
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
        check(WebServer.runtimeMajor("21.0.13") == 21, "runtime major from 21.0.13");
        check(WebServer.runtimeMajor("1.8.0_392") == 8, "runtime major from 1.8.0_392");
        check(WebServer.runtimeMajor("17") == 17, "runtime major from a bare 17");
        check(WebServer.runtimeMajor("") == 0, "runtime major from blank");
        check(WebServer.splitFlags("-XX:+UseG1GC -Xms512M").equals(List.of("-XX:+UseG1GC", "-Xms512M")), "split flags on spaces");
        check(WebServer.splitFlags("-Darg=\"a b\" -XX:+AlwaysPreTouch").equals(List.of("-Darg=a b", "-XX:+AlwaysPreTouch")), "split flags keeps quoted groups");
        check(WebServer.splitFlags("").isEmpty(), "split flags on blank input");
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
