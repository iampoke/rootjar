package rootjar.supervisor;

import rootjar.config.ChildConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds a child's JVM command line from its config. Flags mirror Aikar's G1 tuning, per the spec. */
public final class CommandLineBuilder {
    /** Above this heap Aikar's own guidance switches to a wider young generation and larger regions. */
    static final long LARGE_HEAP_MB = 12 * 1024;
    // Order matters: UnlockExperimentalVMOptions must come before the experimental G1 flags that follow it.
    public static final List<String> GC_FLAGS = gcFlags("30", "40", "8M", "20", "15");
    /** Aikar's large-heap variant, for servers above {@link #LARGE_HEAP_MB}. */
    public static final List<String> GC_FLAGS_LARGE = gcFlags("40", "50", "16M", "15", "20");

    /**
     * Aikar's published set, with only the five values that differ between the small and large heap variants
     * passed in. {@code AlwaysPreTouch} pre-faults the heap at startup, which is what makes an equal Xms and
     * Xmx pay off; the two {@code -D} markers are part of the published set and identify the tuning in a
     * thread dump.
     */
    private static List<String> gcFlags(String newSizePercent, String maxNewSizePercent, String regionSize,
                                        String reservePercent, String heapOccupancyPercent) {
        return List.of(
                "-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=200",
                "-XX:+UnlockExperimentalVMOptions", "-XX:+DisableExplicitGC", "-XX:+AlwaysPreTouch",
                "-XX:G1NewSizePercent=" + newSizePercent, "-XX:G1MaxNewSizePercent=" + maxNewSizePercent,
                "-XX:G1HeapRegionSize=" + regionSize, "-XX:G1ReservePercent=" + reservePercent,
                "-XX:G1HeapWastePercent=5", "-XX:G1MixedGCCountTarget=4",
                "-XX:InitiatingHeapOccupancyPercent=" + heapOccupancyPercent,
                "-XX:G1MixedGCLiveThresholdPercent=90",
                "-XX:G1RSetUpdatingPauseTimePercent=5", "-XX:SurvivorRatio=32",
                "-XX:+PerfDisableSharedMem", "-XX:MaxTenuringThreshold=1",
                "-Dusing.aikars.flags=https://mcflags.emc.gs", "-Daikars.new.flags=true");
    }

    /** Aikar's flags for a heap of this size; an unparseable heap gets the small variant. */
    public static List<String> gcFlagsFor(String heap) {
        return heapMegabytes(heap) >= LARGE_HEAP_MB ? GC_FLAGS_LARGE : GC_FLAGS;
    }

    /** Parses a heap value such as {@code 1024M}, {@code 12G} or a bare byte count into megabytes. */
    public static long heapMegabytes(String heap) {
        if (heap == null) return 0;
        String value = heap.trim().toUpperCase(java.util.Locale.ROOT);
        if (value.isEmpty()) return 0;
        try {
            char unit = value.charAt(value.length() - 1);
            if (unit == 'G') return Math.round(Double.parseDouble(value.substring(0, value.length() - 1)) * 1024);
            if (unit == 'M') return Math.round(Double.parseDouble(value.substring(0, value.length() - 1)));
            if (unit == 'K') return Math.round(Double.parseDouble(value.substring(0, value.length() - 1)) / 1024);
            return Long.parseLong(value) / (1024 * 1024);
        } catch (Exception e) {
            return 0;
        }
    }

    private CommandLineBuilder() {}

    /** @param javaBinary path to the java executable (the root's own JVM by default). */
    public static List<String> build(ChildConfig c, String javaBinary) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBinary);
        // With xms_equal_xmx the heap is fixed-size: predictable memory use, simpler GC behaviour.
        if (c.xmsEqualXmx()) cmd.add("-Xms" + c.heap());
        cmd.add("-Xmx" + c.heap());
        if (c.useAikarFlags()) cmd.addAll(gcFlagsFor(c.heap()));
        for (String entry : c.extraFlags()) {
            // One list entry may hold several space-separated flags; the JVM needs them as separate arguments.
            for (String flag : entry.trim().split("\\s+")) if (!flag.isEmpty()) cmd.add(flag);
        }
        String argsFile = modLoaderArgsFile(c);
        if (argsFile != null) {
            // Forge 1.17+ and every NeoForge version launch from an arguments file the installer generates,
            // not from a jar, so "-jar server.jar nogui" cannot start them. The file already carries the
            // program arguments, nogui included, so nothing is appended.
            cmd.add("@" + argsFile);
        } else {
            cmd.add("-jar");
            cmd.add(c.jar());
            cmd.add("nogui");
        }
        return cmd;
    }

    /** Loader library roots to check, NeoForge first: a server has one or the other, never both. */
    private static final List<String> MOD_LOADER_LIBRARIES =
            List.of("libraries/net/neoforged/neoforge", "libraries/net/minecraftforge/forge");

    /**
     * The {@code @arguments} file a Forge or NeoForge child must be launched with, relative to the server
     * directory, or null when the child is neither type or has no loader installed. Gated on the configured
     * type rather than on the directory's contents, so a stray {@code libraries} folder left behind by a
     * previous install cannot change how a Paper server starts. The installer writes one directory per
     * version, so the most recently written one is the version in use.
     */
    static String modLoaderArgsFile(ChildConfig c) {
        if (c == null || c.dir() == null) return null;
        String type = c.type() == null ? "" : c.type().toLowerCase(Locale.ROOT);
        if (!type.equals("forge") && !type.equals("neoforge")) return null;
        String name = isWindows() ? "win_args.txt" : "unix_args.txt";
        for (String library : MOD_LOADER_LIBRARIES) {
            Path root = c.dir().resolve(library);
            if (!Files.isDirectory(root)) continue;
            for (Path version : newestFirst(root)) {
                if (Files.isRegularFile(version.resolve(name))) {
                    return library + "/" + version.getFileName() + "/" + name;
                }
            }
        }
        return null;
    }

    /** Sub-directories of {@code root}, most recently modified first. */
    private static List<Path> newestFirst(Path root) {
        List<Path> versions = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(root)) {
            stream.filter(Files::isDirectory).forEach(versions::add);
        } catch (Exception e) {
            return List.of();
        }
        versions.sort((a, b) -> Long.compare(lastModified(b), lastModified(a)));
        return versions;
    }

    private static long lastModified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (Exception e) { return 0; }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
