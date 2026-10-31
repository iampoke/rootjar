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
        List<Integer> extraPorts // Additional ports reserved for this server, such as a Bedrock/Geyser UDP port.
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
                silent, javaPath, autoStart, displayHost, idleShutdownSeconds, List.of());
    }

    public ChildConfig withSilent(boolean isSilent) {
        return new ChildConfig(id, type, dir, jar, heap, xmsEqualXmx, port, bind, stopStrategy, stopCommand, stopTimeoutSeconds, startOrder, stopOrder, restartOnCrash, restartBackoffSeconds, extraFlags, consoleEnabled, useAikarFlags, isSilent, javaPath, autoStart, displayHost, idleShutdownSeconds, extraPorts);
    }

    public ChildConfig withJavaPath(String path) {
        return new ChildConfig(id, type, dir, jar, heap, xmsEqualXmx, port, bind, stopStrategy, stopCommand, stopTimeoutSeconds, startOrder, stopOrder, restartOnCrash, restartBackoffSeconds, extraFlags, consoleEnabled, useAikarFlags, silent, path == null ? "" : path, autoStart, displayHost, idleShutdownSeconds, extraPorts);
    }

    public ChildConfig withAutoStart(boolean enabled) {
        return new ChildConfig(id, type, dir, jar, heap, xmsEqualXmx, port, bind, stopStrategy, stopCommand, stopTimeoutSeconds, startOrder, stopOrder, restartOnCrash, restartBackoffSeconds, extraFlags, consoleEnabled, useAikarFlags, silent, javaPath, enabled, displayHost, idleShutdownSeconds, extraPorts);
    }

    public ChildConfig withOrders(int newStartOrder, int newStopOrder) {
        return new ChildConfig(id, type, dir, jar, heap, xmsEqualXmx, port, bind, stopStrategy, stopCommand, stopTimeoutSeconds, newStartOrder, newStopOrder, restartOnCrash, restartBackoffSeconds, extraFlags, consoleEnabled, useAikarFlags, silent, javaPath, autoStart, displayHost, idleShutdownSeconds, extraPorts);
    }

    public ChildConfig withDisplayHost(String host) {
        return new ChildConfig(id, type, dir, jar, heap, xmsEqualXmx, port, bind, stopStrategy, stopCommand, stopTimeoutSeconds, startOrder, stopOrder, restartOnCrash, restartBackoffSeconds, extraFlags, consoleEnabled, useAikarFlags, silent, javaPath, autoStart, host == null ? "" : host, idleShutdownSeconds, extraPorts);
    }

    public ChildConfig withIdleShutdownSeconds(int seconds) {
        return new ChildConfig(id, type, dir, jar, heap, xmsEqualXmx, port, bind, stopStrategy, stopCommand, stopTimeoutSeconds, startOrder, stopOrder, restartOnCrash, restartBackoffSeconds, extraFlags, consoleEnabled, useAikarFlags, silent, javaPath, autoStart, displayHost, Math.max(0, seconds), extraPorts);
    }

    public ChildConfig withExtraPorts(List<Integer> ports) {
        return new ChildConfig(id, type, dir, jar, heap, xmsEqualXmx, port, bind, stopStrategy, stopCommand, stopTimeoutSeconds, startOrder, stopOrder, restartOnCrash, restartBackoffSeconds, extraFlags, consoleEnabled, useAikarFlags, silent, javaPath, autoStart, displayHost, idleShutdownSeconds, ports == null ? List.of() : List.copyOf(ports));
    }

    /** True when this server holds the given port, either as its primary port or as an extra one. */
    public boolean usesPort(int candidate) {
        if (port == candidate) return true;
        return extraPorts != null && extraPorts.contains(candidate);
    }
}
