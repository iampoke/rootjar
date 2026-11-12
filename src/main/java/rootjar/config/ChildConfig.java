package rootjar.config;

import java.nio.file.Path;
import java.util.List;

public record ChildConfig(
        String id,
        String type,
        Path dir,
        String jar,
        String heap,
        boolean xmsEqualXmx,
        int port,
        String bind,
        StopStrategy stopStrategy,
        String stopCommand,
        int stopTimeoutSeconds,
        int startOrder,
        int stopOrder,
        boolean restartOnCrash,
        List<Integer> restartBackoffSeconds,
        List<String> extraFlags,
        boolean consoleEnabled,
        boolean useAikarFlags,
        boolean silent,
        String javaPath, // absolute path to a java executable, "" or "java" for the panel default
        boolean autoStart, // false when the operator stopped it; keeps it stopped across panel restarts
        String displayHost, // Optional hostname shown instead of the numeric public IP. Empty uses the detected IP.
        int idleShutdownSeconds, // Stop after this many seconds with zero players. Zero disables it.
        List<Integer> extraPorts, // Additional ports reserved for this server, such as a Bedrock/Geyser UDP port.
        boolean exitOnOom, // Add -XX:+ExitOnOutOfMemoryError so an exhausted heap kills the process instead of hanging
        long storageMaxGb // Disk quota for this server's folder, backups excluded. 0 means unlimited.
) {
    /** Servers created before multiple ports existed have only their primary port. */
    public ChildConfig(
            String id, String type, Path dir, String jar, String heap, boolean xmsEqualXmx, int port, String bind,
            StopStrategy stopStrategy, String stopCommand, int stopTimeoutSeconds, int startOrder, int stopOrder,
            boolean restartOnCrash, List<Integer> restartBackoffSeconds, List<String> extraFlags, boolean consoleEnabled,
            boolean useAikarFlags, boolean silent, String javaPath, boolean autoStart, String displayHost,
            int idleShutdownSeconds) {
        this(id, type, dir, jar, heap, xmsEqualXmx, port, bind, stopStrategy, stopCommand, stopTimeoutSeconds,
                startOrder, stopOrder, restartOnCrash, restartBackoffSeconds, extraFlags, consoleEnabled, useAikarFlags,
                silent, javaPath, autoStart, displayHost, idleShutdownSeconds, List.of(), false, 0);
    }

    /** Servers predating exit_on_oom and storage_max_gb. */
    public ChildConfig(
            String id, String type, Path dir, String jar, String heap, boolean xmsEqualXmx, int port, String bind,
            StopStrategy stopStrategy, String stopCommand, int stopTimeoutSeconds, int startOrder, int stopOrder,
            boolean restartOnCrash, List<Integer> restartBackoffSeconds, List<String> extraFlags, boolean consoleEnabled,
            boolean useAikarFlags, boolean silent, String javaPath, boolean autoStart, String displayHost,
            int idleShutdownSeconds, List<Integer> extraPorts) {
        this(id, type, dir, jar, heap, xmsEqualXmx, port, bind, stopStrategy, stopCommand, stopTimeoutSeconds,
                startOrder, stopOrder, restartOnCrash, restartBackoffSeconds, extraFlags, consoleEnabled, useAikarFlags,
                silent, javaPath, autoStart, displayHost, idleShutdownSeconds, extraPorts, false, 0);
    }

    public ChildConfig withSilent(boolean isSilent) {
        return new ChildConfig(id, type, dir, jar, heap, xmsEqualXmx, port, bind, stopStrategy, stopCommand, stopTimeoutSeconds, startOrder, stopOrder, restartOnCrash, restartBackoffSeconds, extraFlags, consoleEnabled, useAikarFlags, isSilent, javaPath, autoStart, displayHost, idleShutdownSeconds, extraPorts, exitOnOom, storageMaxGb);
    }

    /** Whether -Xms is pinned to -Xmx. Off lets the JVM grow the heap from its own default. */
    public ChildConfig withXmsEqualXmx(boolean equal) {
        return new ChildConfig(id, type, dir, jar, heap, equal, port, bind, stopStrategy, stopCommand, stopTimeoutSeconds, startOrder, stopOrder, restartOnCrash, restartBackoffSeconds, extraFlags, consoleEnabled, useAikarFlags, silent, javaPath, autoStart, displayHost, idleShutdownSeconds, extraPorts, exitOnOom, storageMaxGb);
    }

    /** Whether the managed Aikar G1 set is appended ahead of {@code extraFlags}. */
    public ChildConfig withUseAikarFlags(boolean enabled) {
        return new ChildConfig(id, type, dir, jar, heap, xmsEqualXmx, port, bind, stopStrategy, stopCommand, stopTimeoutSeconds, startOrder, stopOrder, restartOnCrash, restartBackoffSeconds, extraFlags, consoleEnabled, enabled, silent, javaPath, autoStart, displayHost, idleShutdownSeconds, extraPorts, exitOnOom, storageMaxGb);
    }

    /** Whether an exhausted heap ends the process, so restart-on-crash can bring it back cleanly. */
    public ChildConfig withExitOnOom(boolean enabled) {
        return new ChildConfig(id, type, dir, jar, heap, xmsEqualXmx, port, bind, stopStrategy, stopCommand, stopTimeoutSeconds, startOrder, stopOrder, restartOnCrash, restartBackoffSeconds, extraFlags, consoleEnabled, useAikarFlags, silent, javaPath, autoStart, displayHost, idleShutdownSeconds, extraPorts, enabled, storageMaxGb);
    }

    /** Disk quota for this server in GB. Zero means unlimited. */
    public ChildConfig withStorageMaxGb(long gb) {
        return new ChildConfig(id, type, dir, jar, heap, xmsEqualXmx, port, bind, stopStrategy, stopCommand, stopTimeoutSeconds, startOrder, stopOrder, restartOnCrash, restartBackoffSeconds, extraFlags, consoleEnabled, useAikarFlags, silent, javaPath, autoStart, displayHost, idleShutdownSeconds, extraPorts, exitOnOom, Math.max(0, gb));
    }

    public ChildConfig withJavaPath(String path) {
        return new ChildConfig(id, type, dir, jar, heap, xmsEqualXmx, port, bind, stopStrategy, stopCommand, stopTimeoutSeconds, startOrder, stopOrder, restartOnCrash, restartBackoffSeconds, extraFlags, consoleEnabled, useAikarFlags, silent, path == null ? "" : path, autoStart, displayHost, idleShutdownSeconds, extraPorts, exitOnOom, storageMaxGb);
    }

    public ChildConfig withAutoStart(boolean enabled) {
        return new ChildConfig(id, type, dir, jar, heap, xmsEqualXmx, port, bind, stopStrategy, stopCommand, stopTimeoutSeconds, startOrder, stopOrder, restartOnCrash, restartBackoffSeconds, extraFlags, consoleEnabled, useAikarFlags, silent, javaPath, enabled, displayHost, idleShutdownSeconds, extraPorts, exitOnOom, storageMaxGb);
    }

    public ChildConfig withOrders(int newStartOrder, int newStopOrder) {
        return new ChildConfig(id, type, dir, jar, heap, xmsEqualXmx, port, bind, stopStrategy, stopCommand, stopTimeoutSeconds, newStartOrder, newStopOrder, restartOnCrash, restartBackoffSeconds, extraFlags, consoleEnabled, useAikarFlags, silent, javaPath, autoStart, displayHost, idleShutdownSeconds, extraPorts, exitOnOom, storageMaxGb);
    }

    public ChildConfig withDisplayHost(String host) {
        return new ChildConfig(id, type, dir, jar, heap, xmsEqualXmx, port, bind, stopStrategy, stopCommand, stopTimeoutSeconds, startOrder, stopOrder, restartOnCrash, restartBackoffSeconds, extraFlags, consoleEnabled, useAikarFlags, silent, javaPath, autoStart, host == null ? "" : host, idleShutdownSeconds, extraPorts, exitOnOom, storageMaxGb);
    }

    public ChildConfig withIdleShutdownSeconds(int seconds) {
        return new ChildConfig(id, type, dir, jar, heap, xmsEqualXmx, port, bind, stopStrategy, stopCommand, stopTimeoutSeconds, startOrder, stopOrder, restartOnCrash, restartBackoffSeconds, extraFlags, consoleEnabled, useAikarFlags, silent, javaPath, autoStart, displayHost, Math.max(0, seconds), extraPorts, exitOnOom, storageMaxGb);
    }

    public ChildConfig withExtraPorts(List<Integer> ports) {
        return new ChildConfig(id, type, dir, jar, heap, xmsEqualXmx, port, bind, stopStrategy, stopCommand, stopTimeoutSeconds, startOrder, stopOrder, restartOnCrash, restartBackoffSeconds, extraFlags, consoleEnabled, useAikarFlags, silent, javaPath, autoStart, displayHost, idleShutdownSeconds, ports == null ? List.of() : List.copyOf(ports), exitOnOom, storageMaxGb);
    }

    /** True when this server holds the given port, either as its primary port or as an extra one. */
    public boolean usesPort(int candidate) {
        if (port == candidate) return true;
        return extraPorts != null && extraPorts.contains(candidate);
    }
}
