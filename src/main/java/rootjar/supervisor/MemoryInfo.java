package rootjar.supervisor;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * How much RAM is available and how much a heap asks for. Kept apart from {@code WebServer} so the supervisor
 * can refuse an over-allocated start without depending on the HTTP layer.
 */
public final class MemoryInfo {

    private MemoryInfo() {}

    /**
     * Total RAM the panel may use, in MB. Inside a container - Pterodactyl, Docker - the cgroup limit is the
     * real ceiling and is usually far smaller than host RAM, so it wins; cgroup v2 and v1 keep it in different
     * files, and only v2 used to be read. Outside a container this is physical memory. Callers used to fall
     * back to a hardcoded 3072 MB, so a cgroup v1 host reported 3 GB whatever it actually had.
     */
    public static long availableMb() {
        for (String path : new String[]{"/sys/fs/cgroup/memory.max", "/sys/fs/cgroup/memory/memory.limit_in_bytes"}) {
            try {
                String value = Files.readString(Paths.get(path)).trim();
                if (value.isEmpty() || value.equals("max")) continue;
                long bytes = Long.parseLong(value);
                // cgroup v1 writes a page-aligned Long.MAX_VALUE sentinel when there is no limit.
                if (bytes > 0 && bytes < Long.MAX_VALUE / 2) return bytes / (1024 * 1024);
            } catch (Exception ignored) {}
        }
        try {
            com.sun.management.OperatingSystemMXBean os = (com.sun.management.OperatingSystemMXBean)
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            long total = os.getTotalMemorySize();
            if (total > 0) return total / (1024 * 1024);
        } catch (Exception ignored) {}
        return 3072;
    }

    /** Megabytes a heap setting such as {@code 1024M} or {@code 12G} asks for; 0 when it cannot be parsed. */
    public static long heapMb(String heap) {
        return CommandLineBuilder.heapMegabytes(heap);
    }
}
