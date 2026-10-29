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
        boolean silent, // NEW
        String javaPath, // NEW: absolute path to a java executable, "" or "java" for the panel default
        boolean autoStart, // NEW: false when the operator stopped it; keeps it stopped across panel restarts
        String displayHost // Optional hostname shown instead of the numeric public IP. Empty uses the detected IP.
) {
    public ChildConfig withSilent(boolean isSilent) {
        return new ChildConfig(id, type, dir, jar, heap, xmsEqualXmx, port, bind, stopStrategy, stopCommand, stopTimeoutSeconds, startOrder, stopOrder, restartOnCrash, restartBackoffSeconds, extraFlags, consoleEnabled, useAikarFlags, isSilent, javaPath, autoStart, displayHost);
    }

    public ChildConfig withJavaPath(String path) {
        return new ChildConfig(id, type, dir, jar, heap, xmsEqualXmx, port, bind, stopStrategy, stopCommand, stopTimeoutSeconds, startOrder, stopOrder, restartOnCrash, restartBackoffSeconds, extraFlags, consoleEnabled, useAikarFlags, silent, path == null ? "" : path, autoStart, displayHost);
    }

    public ChildConfig withAutoStart(boolean enabled) {
        return new ChildConfig(id, type, dir, jar, heap, xmsEqualXmx, port, bind, stopStrategy, stopCommand, stopTimeoutSeconds, startOrder, stopOrder, restartOnCrash, restartBackoffSeconds, extraFlags, consoleEnabled, useAikarFlags, silent, javaPath, enabled, displayHost);
    }

    public ChildConfig withOrders(int newStartOrder, int newStopOrder) {
        return new ChildConfig(id, type, dir, jar, heap, xmsEqualXmx, port, bind, stopStrategy, stopCommand, stopTimeoutSeconds, newStartOrder, newStopOrder, restartOnCrash, restartBackoffSeconds, extraFlags, consoleEnabled, useAikarFlags, silent, javaPath, autoStart, displayHost);
    }

    public ChildConfig withDisplayHost(String host) {
        return new ChildConfig(id, type, dir, jar, heap, xmsEqualXmx, port, bind, stopStrategy, stopCommand, stopTimeoutSeconds, startOrder, stopOrder, restartOnCrash, restartBackoffSeconds, extraFlags, consoleEnabled, useAikarFlags, silent, javaPath, autoStart, host == null ? "" : host);
    }
}
