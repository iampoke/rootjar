package rootjar.addons;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Reads and rewrites the Bedrock port in a Geyser config.yml. The panel assigns the ports, so binding one of
 * them to Geyser is a single-value edit: everything else in the file, including comments and ordering, is kept.
 */
public final class GeyserConfig {
    private GeyserConfig() {}

    /** The first Geyser plugin folder's config.yml, or null when Geyser is not installed. */
    public static Path find(Path serverDir) {
        Path plugins = serverDir.resolve("plugins");
        if (!Files.isDirectory(plugins)) return null;
        try (Stream<Path> entries = Files.list(plugins)) {
            return entries.filter(Files::isDirectory)
                    .filter(dir -> dir.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("geyser"))
                    .map(dir -> dir.resolve("config.yml"))
                    .filter(Files::isRegularFile)
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** The bedrock port in the file, or -1 when it is absent or unreadable. */
    public static int bedrockPort(Path file) throws IOException {
        int[] at = portLine(Files.readAllLines(file, StandardCharsets.UTF_8));
        if (at[0] < 0) return -1;
        return at[1];
    }

    /** Rewrites bedrock.port in place. Returns false when the key was not found, leaving the file untouched. */
    public static boolean setBedrockPort(Path file, int port) throws IOException {
        if (port < 1 || port > 65535) return false;
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int[] at = portLine(lines);
        if (at[0] < 0) return false;
        String line = lines.get(at[0]);
        String indent = line.substring(0, line.length() - line.stripLeading().length());
        String rest = line.trim().substring("port:".length()).trim();
        String comment = "";
        int hash = rest.indexOf('#');
        if (hash >= 0) comment = " " + rest.substring(hash).trim();
        lines.set(at[0], indent + "port: " + port + comment);
        Files.write(file, lines, StandardCharsets.UTF_8);
        return true;
    }

    /** Finds the first {@code port:} nested under a top-level {@code bedrock:} block. Returns {lineIndex, value}. */
    private static int[] portLine(List<String> lines) {
        boolean inBedrock = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            int indent = line.length() - line.stripLeading().length();
            String trimmed = line.trim();
            if (indent == 0) {
                inBedrock = trimmed.startsWith("bedrock:");
                continue;
            }
            if (inBedrock && trimmed.startsWith("port:")) {
                String value = trimmed.substring("port:".length()).trim();
                int hash = value.indexOf('#');
                if (hash >= 0) value = value.substring(0, hash).trim();
                try {
                    return new int[]{i, Integer.parseInt(value)};
                } catch (NumberFormatException e) {
                    return new int[]{i, -1};
                }
            }
        }
        return new int[]{-1, -1};
    }

    /** Ports listed in a comma or space separated field, validated and de-duplicated. */
    public static List<Integer> parsePorts(String text) {
        List<Integer> ports = new ArrayList<>();
        if (text == null) return ports;
        for (String part : text.split("[,\\s]+")) {
            if (part.isBlank()) continue;
            try {
                int port = Integer.parseInt(part.trim());
                if (port >= 1 && port <= 65535 && !ports.contains(port)) ports.add(port);
            } catch (NumberFormatException ignored) {}
        }
        return ports;
    }
}
