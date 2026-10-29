package rootjar.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Server-side support for the visual config editor.
 *
 * <p>The editor lists the config files a particular server actually uses (Paper gets paper/bukkit/spigot,
 * Purpur also gets purpur.yml, etc.), flattens the editable scalar values into path/value rows, and writes
 * changes back <b>in place</b> so comments, ordering and formatting survive. It intentionally only edits
 * existing scalar values; structural changes are still done by hand or with the raw editor.</p>
 */
public final class ConfigEditor {
    public enum Format {
        YAML, PROPERTIES, TOML, RAW;

        public String id() { return name().toLowerCase(Locale.ROOT); }
    }

    public record FileInfo(String path, String scope, String format, boolean exists) {}

    public record Entry(String path, String section, String key, String type, String value) {}

    public record ReadResult(String file, String format, boolean exists, String raw, List<Entry> entries) {}

    /** A value's character span inside one original line, plus enough type information to re-serialize it. */
    private record Span(int line, int start, int end, String type, boolean quoted, char quote) {}

    private record Frame(int indent, String path, boolean listItem) {}

    private record Parsed(List<Entry> entries, Map<String, Span> spans) {}

    private static final Pattern NUMBER = Pattern.compile("[-+]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][-+]?\\d+)?");
    private static final Set<String> EDITABLE_EXTENSIONS = Set.of(
        ".yml", ".yaml", ".properties", ".toml", ".json", ".conf", ".ini", ".txt");
    private static final Set<String> RESERVED = Set.of(
        "eula.txt", "ops.json", "whitelist.json", "banned-ips.json", "banned-players.json",
        "usercache.json", "forwarding.secret", "logs", "cache", "crash-reports");

    private ConfigEditor() {}

    public static Format formatOf(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        if (n.endsWith(".yml") || n.endsWith(".yaml")) return Format.YAML;
        if (n.endsWith(".properties") || n.endsWith(".conf") || n.endsWith(".ini")) return Format.PROPERTIES;
        if (n.endsWith(".toml")) return Format.TOML;
        return Format.RAW;
    }

    public static boolean isEditable(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        for (String ext : EDITABLE_EXTENSIONS) if (n.endsWith(ext)) return true;
        return false;
    }

    // ------------------------------------------------------------------ file catalog

    /** Lists the config files relevant to this child, known files first, then any other config-ish files found. */
    public static List<FileInfo> listFiles(ChildConfig cfg) {
        Path dir = cfg.dir();
        String type = detectType(cfg);
        LinkedHashMap<String, String> known = new LinkedHashMap<>();

        if (minecraftLike(type)) {
            known.put("server.properties", "Server");
            known.put("ops.json", "Operators");
            known.put("whitelist.json", "Whitelist");
            known.put("banned-players.json", "Banned players");
        }
        if (type.equals("paper") || type.equals("purpur") || type.equals("spigot") || type.equals("bukkit")) {
            known.put("bukkit.yml", "Bukkit");
            known.put("spigot.yml", "Spigot");
        }
        if (type.equals("paper") || type.equals("purpur")) {
            boolean modern = Files.exists(dir.resolve("config/paper-global.yml"))
                || Files.exists(dir.resolve("config/paper-world-defaults.yml"));
            boolean legacy = Files.exists(dir.resolve("paper.yml"));
            if (legacy && !modern) {
                known.put("paper.yml", "Paper (legacy)");
            } else {
                known.put("config/paper-global.yml", "Paper");
                known.put("config/paper-world-defaults.yml", "Paper");
                if (legacy) known.put("paper.yml", "Paper (legacy)");
            }
            if (Files.exists(dir.resolve("world/paper-world.yml"))) known.put("world/paper-world.yml", "Paper World");
        }
        if (type.equals("purpur")) known.put("purpur.yml", "Purpur");
        if (type.equals("velocity")) known.put("velocity.toml", "Velocity");
        if (type.equals("nanolimbo")) known.put("settings.yml", "NanoLimbo");

        List<FileInfo> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : known.entrySet()) {
            String path = e.getKey();
            out.add(new FileInfo(path, e.getValue(), formatOf(path).id(), Files.isRegularFile(dir.resolve(path))));
            seen.add(path);
        }
        addExtras(dir, "", "Other", out, seen);
        addExtras(dir, "config", "Config", out, seen);
        return out;
    }

    private static void addExtras(Path base, String relDir, String scope, List<FileInfo> out, Set<String> seen) {
        Path dir = relDir.isEmpty() ? base : base.resolve(relDir);
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                .forEach(p -> {
                    String name = p.getFileName().toString();
                    String rel = relDir.isEmpty() ? name : relDir + "/" + name;
                    if (seen.contains(rel) || RESERVED.contains(name) || !isEditable(name)) return;
                    out.add(new FileInfo(rel, scope, formatOf(name).id(), true));
                });
        } catch (IOException ignored) {}
    }

    private static boolean minecraftLike(String type) {
        return Set.of("minecraft", "paper", "purpur", "spigot", "bukkit", "fabric", "quilt").contains(type);
    }

    private static String detectType(ChildConfig cfg) {
        String type = cfg.type() == null ? "" : cfg.type().toLowerCase(Locale.ROOT);
        String jar = cfg.jar() == null ? "" : cfg.jar().toLowerCase(Locale.ROOT);
        Path dir = cfg.dir();
        if (Set.of("paper", "purpur", "spigot", "bukkit", "fabric", "quilt", "velocity", "nanolimbo",
                "discord_bot", "custom").contains(type)) {
            return type;
        }
        if (jar.contains("purpur")) return "purpur";
        if (jar.contains("paper")) return "paper";
        if (jar.contains("velocity")) return "velocity";
        if (jar.contains("nanolimbo")) return "nanolimbo";
        if (jar.contains("fabric")) return "fabric";
        if (jar.contains("quilt")) return "quilt";
        if (jar.contains("spigot")) return "spigot";
        if (Files.exists(dir.resolve("purpur.yml"))) return "purpur";
        if (Files.exists(dir.resolve("config/paper-global.yml")) || Files.exists(dir.resolve("paper.yml"))) return "paper";
        if (Files.exists(dir.resolve("spigot.yml")) || Files.exists(dir.resolve("bukkit.yml"))) return "spigot";
        return type.isEmpty() ? "minecraft" : type;
    }

    // ------------------------------------------------------------------ read

    public static ReadResult read(Path dir, String fileName) throws IOException {
        Format format = formatOf(fileName);
        Path file = dir.resolve(fileName).normalize();
        if (!file.startsWith(dir.normalize())) throw new IOException("Invalid config path");
        boolean exists = Files.isRegularFile(file);
        String raw = exists ? Files.readString(file, StandardCharsets.UTF_8) : "";
        List<Entry> entries = exists ? parse(raw, format) : List.of();
        return new ReadResult(fileName, format.id(), exists, raw, entries);
    }

    public static List<Entry> parse(String content, Format format) {
        return switch (format) {
            case YAML -> parseYaml(content).entries();
            case PROPERTIES -> parseProperties(content).entries();
            case TOML -> parseToml(content).entries();
            case RAW -> List.of();
        };
    }

    // ------------------------------------------------------------------ write

    /** Applies path -> new value updates, preserving comments/formatting. Returns the number of changes. */
    public static int patch(Path file, Format format, Map<String, String> updates) throws IOException {
        if (format == Format.RAW) throw new IllegalArgumentException("Raw files must be saved as raw text");
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String patched = switch (format) {
            case YAML -> patchYaml(content, updates);
            case PROPERTIES -> patchProperties(content, updates);
            case TOML -> patchToml(content, updates);
            case RAW -> content;
        };
        Files.writeString(file, patched, StandardCharsets.UTF_8);
        return updates.size();
    }

    // ------------------------------------------------------------------ YAML

    private static Parsed parseYaml(String content) {
        List<Entry> entries = new ArrayList<>();
        Map<String, Span> spans = new LinkedHashMap<>();
        Deque<Frame> stack = new ArrayDeque<>();
        Map<String, Integer> listCounts = new LinkedHashMap<>();
        String[] lines = content.split("\n", -1);
        int skipIndent = -1;

        for (int lineNo = 0; lineNo < lines.length; lineNo++) {
            String line = lineText(lines[lineNo]);
            int bom = lineNo == 0 && line.startsWith("\uFEFF") ? 1 : 0;
            String body = line.substring(bom);
            int indent = leadingWhitespaceChars(body);
            String trimmed = body.stripLeading();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.equals("---") || trimmed.equals("...")) continue;
            if (skipIndent >= 0) {
                if (indent > skipIndent) continue;
                skipIndent = -1;
            }
            int contentStart = bom + indent;
            boolean listItem = trimmed.equals("-") || trimmed.startsWith("- ");

            if (listItem) {
                while (!stack.isEmpty() && (stack.peek().indent() > indent
                        || (stack.peek().indent() == indent && stack.peek().listItem()))) {
                    stack.pop();
                }
            } else {
                while (!stack.isEmpty() && stack.peek().indent() >= indent) stack.pop();
            }
            String parent = stack.isEmpty() ? "" : stack.peek().path();

            if (listItem) {
                int afterDash = contentStart + 1;
                int restStart = afterDash;
                while (restStart < line.length() && (line.charAt(restStart) == ' ' || line.charAt(restStart) == '\t')) restStart++;
                String rest = line.substring(restStart);
                int index = listCounts.merge(parent + "@" + indent, 1, Integer::sum) - 1;
                String itemPath = parent.isEmpty() ? "[" + index + "]" : parent + "[" + index + "]";
                int textIndent = indent + 1 + (restStart - afterDash);

                if (rest.isEmpty()) {
                    stack.push(new Frame(indent, itemPath, true));
                    continue;
                }
                int colon = findColon(line, restStart);
                if (colon >= 0) {
                    String key = unquoteKey(line.substring(restStart, colon).trim());
                    if (key.isEmpty()) continue;
                    String keyPath = itemPath + "." + key;
                    int vs = skipSpaces(line, colon + 1);
                    int ve = valueEnd(line, vs);
                    if (vs >= ve) {
                        stack.push(new Frame(indent, itemPath, true));
                        stack.push(new Frame(textIndent, keyPath, false));
                    } else {
                        String rawVal = line.substring(vs, ve);
                        if (rawVal.startsWith("|") || rawVal.startsWith(">")) {
                            skipIndent = indent;
                            continue;
                        }
                        Span span = span(lineNo, vs, ve, rawVal);
                        addEntry(entries, spans, keyPath, itemPath, key, rawVal, span);
                        stack.push(new Frame(indent, itemPath, true));
                    }
                    continue;
                }

                int ve = valueEnd(line, restStart);
                String rawVal = line.substring(restStart, ve);
                Span span = span(lineNo, restStart, ve, rawVal);
                addEntry(entries, spans, itemPath, parent, "[" + index + "]", rawVal, span);
                continue;
            }

            int colon = findColon(line, contentStart);
            if (colon < 0) continue;
            String key = unquoteKey(line.substring(contentStart, colon).trim());
            if (key.isEmpty()) continue;
            String path = parent.isEmpty() ? key : parent + "." + key;
            int vs = skipSpaces(line, colon + 1);
            int ve = valueEnd(line, vs);
            if (vs >= ve) {
                stack.push(new Frame(indent, path, false));
                continue;
            }
            String rawVal = line.substring(vs, ve);
            if (rawVal.startsWith("|") || rawVal.startsWith(">")) {
                skipIndent = indent;
                continue;
            }
            Span span = span(lineNo, vs, ve, rawVal);
            addEntry(entries, spans, path, parent, key, rawVal, span);
        }
        return new Parsed(entries, spans);
    }

    private static String patchYaml(String content, Map<String, String> updates) {
        Parsed parsed = parseYaml(content);
        String[] lines = content.split("\n", -1);
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> update : updates.entrySet()) {
            Span span = parsed.spans().get(update.getKey());
            if (span == null || span.line() >= lines.length) { missing.add(update.getKey()); continue; }
            String line = lines[span.line()];
            String value = serializeYaml(update.getValue(), span);
            lines[span.line()] = line.substring(0, span.start()) + value + line.substring(span.end());
        }
        if (!missing.isEmpty()) throw new IllegalArgumentException("Unknown config key(s): " + String.join(", ", missing));
        return String.join("\n", lines);
    }

    private static void addEntry(List<Entry> entries, Map<String, Span> spans, String path,
                                 String section, String key, String rawValue, Span span) {
        if (path.isEmpty() || spans.containsKey(path)) return;
        String type = span.type();
        String display = displayValue(rawValue, type);
        entries.add(new Entry(path, section, key, type, display));
        spans.put(path, span);
    }

    // ------------------------------------------------------------------ properties

    private static Parsed parseProperties(String content) {
        List<Entry> entries = new ArrayList<>();
        Map<String, Span> spans = new LinkedHashMap<>();
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lineText(lines[i]);
            String trimmed = line.stripLeading();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) continue;
            int sep = findPropertySeparator(line);
            if (sep < 0) continue;
            String key = line.substring(0, sep).trim();
            if (key.isEmpty()) continue;
            int vs = skipSpaces(line, sep + 1);
            int ve = line.length();
            while (ve > vs && Character.isWhitespace(line.charAt(ve - 1))) ve--;
            String rawVal = line.substring(vs, ve);
            String type = detectType(rawVal);
            String display = type.equals("string") ? unescapeProperties(rawVal) : rawVal.trim();
            Span span = new Span(i, vs, ve, type, false, (char) 0);
            if (!spans.containsKey(key)) {
                entries.add(new Entry(key, "", key, type, display));
                spans.put(key, span);
            }
        }
        return new Parsed(entries, spans);
    }

    private static String patchProperties(String content, Map<String, String> updates) {
        Parsed parsed = parseProperties(content);
        String[] lines = content.split("\n", -1);
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> update : updates.entrySet()) {
            Span span = parsed.spans().get(update.getKey());
            if (span == null || span.line() >= lines.length) { missing.add(update.getKey()); continue; }
            String line = lines[span.line()];
            String value = serializeProperties(update.getValue());
            lines[span.line()] = line.substring(0, span.start()) + value + line.substring(span.end());
        }
        if (!missing.isEmpty()) throw new IllegalArgumentException("Unknown config key(s): " + String.join(", ", missing));
        return String.join("\n", lines);
    }

    private static int findPropertySeparator(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == '=' || c == ':') return i;
        }
        return -1;
    }

    private static String serializeProperties(String value) {
        String v = value == null ? "" : value;
        if (v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) throw new IllegalArgumentException("Value cannot contain a newline");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\f' -> sb.append("\\f");
                case ' ' -> { if (i == 0) sb.append("\\ "); else sb.append(' '); }
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String unescapeProperties(String value) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) { sb.append(c); continue; }
            char n = value.charAt(++i);
            switch (n) {
                case 't' -> sb.append('\t');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    if (i + 4 < value.length()) {
                        try {
                            sb.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException e) { sb.append('u'); }
                    } else sb.append('u');
                }
                default -> sb.append(n);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ TOML

    private static Parsed parseToml(String content) {
        List<Entry> entries = new ArrayList<>();
        Map<String, Span> spans = new LinkedHashMap<>();
        String[] lines = content.split("\n", -1);
        String section = "";
        char multiClose = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lineText(lines[i]);
            String trimmed = line.stripLeading();
            if (multiClose != 0) {
                if (trimmed.endsWith(String.valueOf(multiClose))) multiClose = 0;
                continue;
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            if (trimmed.startsWith("[")) {
                int end = trimmed.indexOf(']');
                if (end > 0) {
                    section = trimmed.substring(1, end);
                    if (section.startsWith("[")) section = section.substring(1);
                }
                continue;
            }
            int eq = findEquals(line);
            if (eq < 0) continue;
            String key = line.substring(0, eq).trim();
            if (key.isEmpty()) continue;
            int vs = skipSpaces(line, eq + 1);
            int ve = valueEnd(line, vs);
            if (vs >= ve) continue;
            String rawVal = line.substring(vs, ve);
            String type = detectType(rawVal);
            if ((type.equals("list") && !rawVal.trim().endsWith("]"))
                || (type.equals("map") && !rawVal.trim().endsWith("}"))) continue;
            if ((rawVal.trim().startsWith("[") && !rawVal.trim().endsWith("]"))
                || (rawVal.trim().startsWith("{") && !rawVal.trim().endsWith("}"))) {
                multiClose = rawVal.trim().startsWith("[") ? ']' : '}';
                continue;
            }
            String path = section.isEmpty() ? key : section + "." + key;
            String display = displayValue(rawVal, type);
            Span span = new Span(i, vs, ve, type, isQuoted(rawVal), quoteChar(rawVal));
            if (!spans.containsKey(path)) {
                entries.add(new Entry(path, section, key, type, display));
                spans.put(path, span);
            }
        }
        return new Parsed(entries, spans);
    }

    private static String patchToml(String content, Map<String, String> updates) {
        Parsed parsed = parseToml(content);
        String[] lines = content.split("\n", -1);
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> update : updates.entrySet()) {
            Span span = parsed.spans().get(update.getKey());
            if (span == null || span.line() >= lines.length) { missing.add(update.getKey()); continue; }
            String line = lines[span.line()];
            String value = serializeToml(update.getValue(), span);
            lines[span.line()] = line.substring(0, span.start()) + value + line.substring(span.end());
        }
        if (!missing.isEmpty()) throw new IllegalArgumentException("Unknown config key(s): " + String.join(", ", missing));
        return String.join("\n", lines);
    }

    private static int findEquals(String line) {
        char q = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (q != 0) {
                if (q == '"' && c == '\\') i++;
                else if (c == q) q = 0;
                continue;
            }
            if (c == '"' || c == '\'') { q = c; continue; }
            if (c == '=') return i;
        }
        return -1;
    }

    private static String serializeToml(String value, Span span) {
        String v = value == null ? "" : value;
        if (v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) throw new IllegalArgumentException("Value cannot contain a newline");
        return switch (span.type()) {
            case "boolean" -> isTrue(v) ? "true" : "false";
            case "number" -> {
                if (!NUMBER.matcher(v.trim()).matches()) throw new IllegalArgumentException("Expected a number");
                yield v.trim();
            }
            case "list", "map" -> {
                String t = v.trim();
                if (span.type().equals("list") && !(t.startsWith("[") && t.endsWith("]"))) {
                    throw new IllegalArgumentException("Expected a TOML list");
                }
                if (span.type().equals("map") && !(t.startsWith("{") && t.endsWith("}"))) {
                    throw new IllegalArgumentException("Expected a TOML table");
                }
                yield t;
            }
            default -> "\"" + escapeToml(v) + "\"";
        };
    }

    private static String escapeToml(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    // ------------------------------------------------------------------ shared parsing helpers

    private static Span span(int line, int start, int end, String rawValue) {
        boolean quoted = isQuoted(rawValue);
        String type = detectType(rawValue);
        return new Span(line, start, end, type, quoted, quoteChar(rawValue));
    }

    private static String detectType(String rawValue) {
        String v = rawValue.trim();
        if (isQuoted(v)) return "string";
        if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) return "boolean";
        if (NUMBER.matcher(v).matches()) return "number";
        if (v.equals("~") || v.equalsIgnoreCase("null")) return "string";
        if (v.startsWith("[") && v.endsWith("]")) return "list";
        if (v.startsWith("{") && v.endsWith("}")) return "map";
        if (v.startsWith("|") || v.startsWith(">")) return "block";
        return "string";
    }

    private static String displayValue(String rawValue, String type) {
        String v = rawValue.trim();
        return switch (type) {
            case "string" -> unquote(v);
            case "boolean" -> v.toLowerCase(Locale.ROOT);
            case "number", "list", "map" -> v;
            default -> v;
        };
    }

    private static boolean isQuoted(String v) {
        return v.length() >= 2
            && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")));
    }

    private static char quoteChar(String v) {
        return isQuoted(v) ? v.charAt(0) : (char) 0;
    }

    private static String unquote(String v) {
        if (!isQuoted(v)) return v;
        char q = v.charAt(0);
        String inner = v.substring(1, v.length() - 1);
        StringBuilder sb = new StringBuilder();
        if (q == '\'') {
            for (int i = 0; i < inner.length(); i++) {
                char c = inner.charAt(i);
                if (c == '\'' && i + 1 < inner.length() && inner.charAt(i + 1) == '\'') { sb.append('\''); i++; }
                else sb.append(c);
            }
            return sb.toString();
        }
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c != '\\' || i + 1 >= inner.length()) { sb.append(c); continue; }
            char n = inner.charAt(++i);
            switch (n) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'u' -> {
                    if (i + 4 < inner.length()) {
                        try {
                            sb.append((char) Integer.parseInt(inner.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException e) { sb.append('u'); }
                    } else sb.append('u');
                }
                default -> sb.append(n);
            }
        }
        return sb.toString();
    }

    private static String unquoteKey(String key) {
        String k = key.trim();
        if (isQuoted(k)) return unquote(k);
        return k;
    }

    private static String lineText(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private static int leadingWhitespaceChars(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        return i;
    }

    private static int skipSpaces(String line, int from) {
        int i = from;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        return i;
    }

    private static int valueEnd(String line, int from) {
        int end = findComment(line, from);
        while (end > from && Character.isWhitespace(line.charAt(end - 1))) end--;
        return end;
    }

    private static int findColon(String line, int from) {
        char q = 0;
        for (int i = from; i < line.length(); i++) {
            char c = line.charAt(i);
            if (q != 0) {
                if (q == '"' && c == '\\') i++;
                else if (c == q) q = 0;
                continue;
            }
            if ((c == '"' || c == '\'') && (i == from || Character.isWhitespace(line.charAt(i - 1)))) {
                q = c;
                continue;
            }
            if (c == ':' && (i + 1 == line.length() || Character.isWhitespace(line.charAt(i + 1)))) return i;
        }
        return -1;
    }

    private static int findComment(String line, int from) {
        char q = 0;
        for (int i = from; i < line.length(); i++) {
            char c = line.charAt(i);
            if (q != 0) {
                if (q == '"' && c == '\\') i++;
                else if (c == q) q = 0;
                continue;
            }
            if ((c == '"' || c == '\'')
                && (i == from || Character.isWhitespace(line.charAt(i - 1))
                    || line.charAt(i - 1) == '[' || line.charAt(i - 1) == ',')) {
                q = c;
                continue;
            }
            if (c == '#' && (i == from || Character.isWhitespace(line.charAt(i - 1)))) return i;
        }
        return line.length();
    }

    // ------------------------------------------------------------------ YAML serialization

    private static String serializeYaml(String value, Span span) {
        String v = value == null ? "" : value;
        if (v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) throw new IllegalArgumentException("Value cannot contain a newline");
        return switch (span.type()) {
            case "boolean" -> isTrue(v) ? "true" : "false";
            case "number" -> {
                if (!NUMBER.matcher(v.trim()).matches()) throw new IllegalArgumentException("Expected a number");
                yield v.trim();
            }
            case "list" -> {
                String t = v.trim();
                if (!(t.startsWith("[") && t.endsWith("]"))) throw new IllegalArgumentException("Expected a YAML list");
                yield t;
            }
            case "map" -> {
                String t = v.trim();
                if (!(t.startsWith("{") && t.endsWith("}"))) throw new IllegalArgumentException("Expected a YAML map");
                yield t;
            }
            case "string" -> {
                if (span.quoted()) {
                    yield quoteYaml(v, span.quote());
                }
                yield needsYamlQuote(v) ? quoteYaml(v, '"') : v;
            }
            default -> v;
        };
    }

    private static boolean isTrue(String v) {
        String s = v.trim().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("on") || s.equals("yes") || s.equals("1");
    }

    private static boolean needsYamlQuote(String v) {
        if (v.isEmpty()) return true;
        if (!v.equals(v.trim())) return true;
        if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")
            || v.equalsIgnoreCase("null") || v.equals("~")) return true;
        if (NUMBER.matcher(v).matches()) return true;
        if (v.indexOf('\t') >= 0) return true;
        char first = v.charAt(0);
        if ("-?:,[]{}#&*!|>'\"%@`".indexOf(first) >= 0) return true;
        return v.contains(": ") || v.contains(" #");
    }

    private static String quoteYaml(String v, char quote) {
        if (quote == '\'') return "'" + v.replace("'", "''") + "'";
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}
