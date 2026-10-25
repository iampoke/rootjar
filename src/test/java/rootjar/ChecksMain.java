package rootjar;

import rootjar.addons.Addons;
import rootjar.config.ConfigException;
import rootjar.config.ConfigLoader;
import rootjar.config.ConfigValidator;
import rootjar.config.RootConfig;
import rootjar.config.PasswordHasher;
import rootjar.config.StopStrategy;
import rootjar.console.LogRingBuffer;
import rootjar.console.StdinRouter;
import rootjar.console.StdinRouter.Kind;
import rootjar.supervisor.BackoffPolicy;
import rootjar.supervisor.CommandLineBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Dependency-free unit checks (JUnit is not available offline). Exits 1 if anything fails. */
public final class ChecksMain {
    private static int passed, failed;

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
