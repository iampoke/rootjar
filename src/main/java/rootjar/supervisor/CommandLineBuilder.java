package rootjar.supervisor;

import rootjar.config.ChildConfig;

import java.util.ArrayList;
import java.util.List;

/** Builds a child's JVM command line from its config. Flags mirror Aikar's G1 tuning, per the spec. */
public final class CommandLineBuilder {
    // Order matters: UnlockExperimentalVMOptions must come before the experimental G1 flags that follow it.
    static final List<String> GC_FLAGS = List.of(
            "-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=200",
            "-XX:+UnlockExperimentalVMOptions", "-XX:+DisableExplicitGC",
            "-XX:G1NewSizePercent=30", "-XX:G1MaxNewSizePercent=40",
            "-XX:G1HeapRegionSize=8M", "-XX:G1ReservePercent=20",
            "-XX:G1HeapWastePercent=5", "-XX:G1MixedGCCountTarget=4",
            "-XX:InitiatingHeapOccupancyPercent=15",
            "-XX:G1MixedGCLiveThresholdPercent=90",
            "-XX:G1RSetUpdatingPauseTimePercent=5", "-XX:SurvivorRatio=32",
            "-XX:+PerfDisableSharedMem", "-XX:MaxTenuringThreshold=1");

    private CommandLineBuilder() {}

    /** @param javaBinary path to the java executable (the root's own JVM by default). */
    public static List<String> build(ChildConfig c, String javaBinary) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBinary);
        // With xms_equal_xmx the heap is fixed-size: predictable memory use, simpler GC behaviour.
        if (c.xmsEqualXmx()) cmd.add("-Xms" + c.heap());
        cmd.add("-Xmx" + c.heap());
        if (c.useAikarFlags()) cmd.addAll(GC_FLAGS);
        for (String entry : c.extraFlags()) {
            // One list entry may hold several space-separated flags; the JVM needs them as separate arguments.
            for (String flag : entry.trim().split("\\s+")) if (!flag.isEmpty()) cmd.add(flag);
        }
        cmd.add("-jar");
        cmd.add(c.jar());
        cmd.add("nogui");
        return cmd;
    }
}
