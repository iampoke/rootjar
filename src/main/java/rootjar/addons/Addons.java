package rootjar.addons;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Pure helpers for the Mods/Plugins tab: which server types take which addons, which Modrinth loaders
 * to search, jar naming/hashing, and best-effort metadata reads from inside an addon jar.
 * No HTTP here, so every rule is unit-testable.
 */
public final class Addons {
    private Addons() {}

    private static final Pattern SAFE_LOADER = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final int MAX_META_BYTES = 512 * 1024;

    /** Metadata read from an addon jar. {@code mcRange} is a Modrinth/Forge-style range, or blank. */
    public record JarMeta(String name, String mcRange, String format) {}

    // ---- server type rules -------------------------------------------------------------------

    /** Server types whose {@code mods/} folder is actually loaded at runtime. */
    public static boolean supportsMods(String type) {
        return switch (normalise(type)) {
            case "fabric", "quilt", "forge", "neoforge", "arclight", "mohist", "magma", "ketting" -> true;
            default -> false;
        };
    }

    public static boolean supportsPlugins(String type) {
        return switch (normalise(type)) {
            case "paper", "purpur", "spigot", "folia", "velocity", "waterfall", "bungeecord",
                 "travertine", "arclight" -> true;
            default -> false;
        };
    }

    /**
     * Modrinth loader facets for a server type, in preference order. Paper-family servers get the whole
     * OR group so Spigot/Bukkit plugins keep showing up.
     */
    public static List<String> loaders(String type, boolean mods) {
        String t = normalise(type);
        if (mods) {
            if (!supportsMods(t)) return List.of();
            return switch (t) {
                case "fabric" -> List.of("fabric");
                case "quilt" -> List.of("quilt", "fabric");
                case "forge" -> List.of("forge");
                case "neoforge" -> List.of("neoforge");
                // Hybrid servers can run several loader families; the jar name decides at runtime.
                case "arclight", "mohist", "magma", "ketting" -> List.of("fabric", "forge", "neoforge");
                default -> List.of();
            };
        }
        return switch (t) {
            case "paper" -> List.of("paper", "purpur", "spigot", "bukkit", "folia");
            case "purpur" -> List.of("purpur", "paper", "spigot", "bukkit", "folia");
            case "folia" -> List.of("folia", "paper", "spigot", "bukkit");
            case "spigot" -> List.of("spigot", "bukkit");
            case "velocity" -> List.of("velocity");
            case "waterfall" -> List.of("waterfall", "bungeecord");
            case "bungeecord" -> List.of("bungeecord");
            case "travertine" -> List.of("travertine", "waterfall", "bungeecord");
            case "arclight" -> List.of("paper", "spigot", "bukkit");
            default -> List.of();
        };
    }

    /** Folder name for the addon kind: {@code plugins} or {@code mods}. */
    public static String folder(boolean mods) { return mods ? "mods" : "plugins"; }

    public static boolean validLoader(String loader) {
        return loader != null && SAFE_LOADER.matcher(loader).matches();
    }

    // ---- file names and hashes ---------------------------------------------------------------

    /**
     * Reduces a Modrinth filename (or any client string) to a safe bare jar name. Returns null when the
     * result is not a usable {@code .jar} file.
     */
    public static String sanitizeJar(String filename) {
        if (filename == null) return null;
        String name = filename.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[\\x00-\\x1f]", "").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) return null;
        if (name.length() > 200) name = name.substring(0, 200);
        if (!name.toLowerCase(Locale.ROOT).endsWith(".jar")) return null;
        return name;
    }

    /** Lower-case hex SHA-512 of a file, used for Modrinth hash lookups. */
    public static String sha512(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder(128);
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-512 unavailable", e);
        }
    }

    // ---- jar metadata ------------------------------------------------------------------------

    /**
     * Best-effort read of an addon's declared Minecraft compatibility. Understands
     * {@code fabric.mod.json}, {@code META-INF/mods.toml}, {@code META-INF/neoforge.mods.toml},
     * {@code plugin.yml} and {@code paper-plugin.yml}. Returns null when the jar carries nothing useful.
     */
    public static JarMeta metadata(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            String fabric = readEntry(zip, "fabric.mod.json");
            if (fabric != null) {
                String range = firstGroup(fabric, "\"minecraft\"\\s*:\\s*\"([^\"]+)\"");
                String name = firstGroup(fabric, "\"name\"\\s*:\\s*\"([^\"]+)\"");
                if (name == null) name = firstGroup(fabric, "\"id\"\\s*:\\s*\"([^\"]+)\"");
                return new JarMeta(name, range == null ? "" : range, "fabric");
            }
            String toml = readEntry(zip, "META-INF/neoforge.mods.toml");
            String format = "neoforge";
            if (toml == null) { toml = readEntry(zip, "META-INF/mods.toml"); format = "forge"; }
            if (toml != null) {
                String range = minecraftRangeFromToml(toml);
                String name = firstGroup(toml, "displayName\\s*=\\s*\"([^\"]+)\"");
                if (name == null) name = firstGroup(toml, "modId\\s*=\\s*\"([^\"]+)\"");
                return new JarMeta(name, range == null ? "" : range, format);
            }
            String plugin = readEntry(zip, "paper-plugin.yml");
            if (plugin == null) plugin = readEntry(zip, "plugin.yml");
            if (plugin != null) {
                String api = firstGroup(plugin, "(?m)^\\s*api-version\\s*:\\s*[\"']?([0-9][0-9.]*)");
                String name = firstGroup(plugin, "(?m)^\\s*name\\s*:\\s*[\"']?([^\"'\\r\\n]+)");
                // api-version is the minimum Bukkit API the plugin targets, so treat it as a floor.
                String range = api == null ? "" : ">=" + api;
                return new JarMeta(name == null ? null : name.trim(), range, "bukkit");
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String minecraftRangeFromToml(String toml) {
        // Only the dependency block whose modId is "minecraft" matters; versionRange may precede or follow it.
        Matcher block = Pattern.compile("(?s)\\[\\[dependencies[^\\]]*\\]\\](.*?)(?=\\[\\[|\\z)").matcher(toml);
        while (block.find()) {
            String body = block.group(1);
            if (!body.matches("(?s).*modId\\s*=\\s*\"minecraft\".*")) continue;
            Matcher range = Pattern.compile("versionRange\\s*=\\s*\"([^\"]+)\"").matcher(body);
            if (range.find()) return range.group(1);
        }
        return null;
    }

    private static String readEntry(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory()) return null;
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] bytes = in.readNBytes(MAX_META_BYTES);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String firstGroup(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    // ---- version ranges ----------------------------------------------------------------------

    /**
     * True when {@code target} (e.g. "1.21.4") satisfies a Modrinth/Forge range such as
     * {@code >=1.20.1}, {@code [1.20.1,1.21)}, {@code ~1.20} or {@code *}. Unknown syntax falls back
     * to "does not contradict the target", i.e. true, so the check stays best-effort.
     */
    public static boolean versionMatches(String range, String target) {
        if (range == null || range.isBlank() || target == null || target.isBlank()) return true;
        String r = range.trim();
        if (r.equals("*") || r.equalsIgnoreCase("any") || r.equalsIgnoreCase("latest")) return true;
        for (String token : r.split("\\s+")) {
            if (token.isEmpty()) continue;
            if (matchesToken(token, target.trim())) return true;
        }
        return false;
    }

    private static boolean matchesToken(String token, String target) {
        if (token.startsWith("[") || token.startsWith("(")) {
            int comma = token.indexOf(',');
            if (comma < 0) return true;
            char open = token.charAt(0);
            char close = token.charAt(token.length() - 1);
            String lo = token.substring(1, comma).trim();
            String hi = token.substring(comma + 1, token.length() - 1).trim();
            int loCmp = lo.isEmpty() ? 1 : compareVersions(target, lo);
            int hiCmp = hi.isEmpty() ? -1 : compareVersions(target, hi);
            boolean loOk = lo.isEmpty() || (open == '[' ? loCmp >= 0 : loCmp > 0);
            boolean hiOk = hi.isEmpty() || (close == ']' ? hiCmp <= 0 : hiCmp < 0);
            return loOk && hiOk;
        }
        if (token.startsWith(">=")) return compareVersions(target, token.substring(2)) >= 0;
        if (token.startsWith("<=")) return compareVersions(target, token.substring(2)) <= 0;
        if (token.startsWith(">")) return compareVersions(target, token.substring(1)) > 0;
        if (token.startsWith("<")) return compareVersions(target, token.substring(1)) < 0;
        if (token.startsWith("=")) return compareVersions(target, token.substring(1)) == 0;
        if (token.startsWith("~") || token.startsWith("^")) {
            // Treat 1.x modifiers as "this minor or newer"; precise semver is not meaningful for MC versions.
            return compareVersions(target, token.substring(1)) >= 0;
        }
        if (!token.matches("[0-9][0-9A-Za-z.\\-+]*")) return true;
        return compareVersions(target, token) == 0;
    }

    /** Compares dot-separated versions numerically; trailing components are ignored, gaps count as 0. */
    public static int compareVersions(String a, String b) {
        int[] left = numbers(a);
        int[] right = numbers(b);
        int len = Math.max(left.length, right.length);
        for (int i = 0; i < len; i++) {
            int l = i < left.length ? left[i] : 0;
            int r = i < right.length ? right[i] : 0;
            if (l != r) return Integer.compare(l, r);
        }
        return 0;
    }

    private static int[] numbers(String version) {
        if (version == null) return new int[0];
        String cleaned = version.trim().replaceAll("[^0-9.]", ".");
        List<Integer> out = new ArrayList<>();
        for (String part : cleaned.split("\\.")) {
            if (part.isEmpty()) continue;
            try {
                out.add(Integer.parseInt(part));
            } catch (NumberFormatException e) {
                out.add(0);
            }
            if (out.size() >= 4) break;
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    private static String normalise(String type) {
        return type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
    }
}
