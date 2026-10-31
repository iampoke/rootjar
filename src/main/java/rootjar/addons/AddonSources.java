package rootjar.addons;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns Modrinth, Hangar and SpigotMC search responses into one shape the panel can render, so the addons
 * tab does not have to know which registry answered. Parsing is deliberately minimal and defensive: these
 * are third-party payloads, and a missing or renamed field must degrade to an empty value, never to a
 * thrown exception that blanks the whole result list.
 */
public final class AddonSources {

    private AddonSources() {}

    /** One normalized search result. */
    public record Item(String id, String slug, String owner, String title, String description,
                       String iconUrl, long downloads, List<String> versions, boolean premium) {}

    /** A resolved download: the URL to fetch and the file name to save it as. */
    public record Download(String url, String fileName) {}

    // ---- normalization ---------------------------------------------------------------------

    public static List<Item> modrinth(String json) {
        List<Item> items = new ArrayList<>();
        for (String object : objects(json, "hits")) {
            items.add(new Item(str(object, "project_id"), str(object, "slug"), "", str(object, "title"),
                    str(object, "description"), str(object, "icon_url"), num(object, "downloads"),
                    strings(object, "versions"), false));
        }
        return items;
    }

    public static List<Item> hangar(String json) {
        List<Item> items = new ArrayList<>();
        for (String object : objects(json, "result")) {
            String id = String.valueOf(num(object, "id"));
            items.add(new Item(id, str(object, "slug"), str(object, "owner"), str(object, "name"),
                    str(object, "description"), str(object, "avatarUrl"), num(object, "downloads"),
                    strings(object, "PAPER"), false));
        }
        return items;
    }

    public static List<Item> spigot(String json) {
        List<Item> items = new ArrayList<>();
        for (String object : objects(json, null)) {
            String id = String.valueOf(num(object, "id"));
            String base64 = str(object, "data");
            String icon = base64.isEmpty() ? "" : "data:image/png;base64," + base64;
            boolean premium = bool(object, "premium");
            items.add(new Item(id, "", "", str(object, "name"), str(object, "tag"), icon,
                    num(object, "downloads"), List.of(), premium));
        }
        return items;
    }

    /** Total result count when the source reports one, otherwise -1. */
    public static long totalCount(String json) {
        long hits = num(json, "total_hits");
        if (hits > 0) return hits;
        long count = num(json, "count");
        return count > 0 ? count : -1;
    }

    /** The first downloadable Paper file in a Hangar versions response. */
    public static Download hangarDownload(String versionsJson) {
        List<String> versions = objects(versionsJson, "result");
        if (versions.isEmpty()) return null;
        String first = versions.get(0);
        String url = str(first, "downloadUrl");
        if (url.isEmpty() || !url.startsWith("https://")) return null;
        // The version object's own "name" is the version number, so the file name must come from fileInfo.
        int fileInfo = first.indexOf("\"fileInfo\"");
        String fileName = fileInfo >= 0 ? str(first.substring(fileInfo), "name") : "";
        if (!fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
            String fallback = str(first, "name");
            fileName = (fallback.isBlank() ? "hangar-plugin" : fallback) + ".jar";
        }
        return new Download(url, fileName);
    }

    private static String first(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Renders normalized items as the JSON the panel expects. */
    public static String toJson(List<Item> items, long total, int offset, int limit, String source) {
        StringBuilder json = new StringBuilder("{\"source\":\"").append(escape(source))
                .append("\",\"total\":").append(total)
                .append(",\"offset\":").append(offset)
                .append(",\"limit\":").append(limit)
                .append(",\"items\":[");
        boolean first = true;
        for (Item item : items) {
            if (!first) json.append(',');
            first = false;
            json.append("{\"id\":\"").append(escape(item.id()))
                .append("\",\"slug\":\"").append(escape(item.slug()))
                .append("\",\"owner\":\"").append(escape(item.owner()))
                .append("\",\"title\":\"").append(escape(item.title()))
                .append("\",\"description\":\"").append(escape(item.description()))
                .append("\",\"iconUrl\":\"").append(escape(item.iconUrl()))
                .append("\",\"downloads\":").append(item.downloads())
                .append(",\"premium\":").append(item.premium())
                .append(",\"versions\":[");
            boolean firstVersion = true;
            for (String version : item.versions()) {
                if (!firstVersion) json.append(',');
                firstVersion = false;
                json.append('"').append(escape(version)).append('"');
            }
            json.append("]}");
        }
        return json.append("]}").toString();
    }

    // ---- minimal JSON reading ----------------------------------------------------------------

    /** Object bodies of a JSON array, either the whole document or the value of one key. */
    public static List<String> objects(String json, String key) {
        List<String> found = new ArrayList<>();
        if (json == null) return found;
        int start = key == null ? json.indexOf('[') : valueStart(json, key);
        if (start < 0 || json.charAt(start) != '[') return found;
        int depth = 0;
        boolean inString = false;
        int objectStart = -1;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == '{') { if (depth == 0) objectStart = i; depth++; continue; }
            if (c == '}') {
                depth--;
                if (depth == 0 && objectStart >= 0) { found.add(json.substring(objectStart, i + 1)); objectStart = -1; }
                continue;
            }
            if (c == ']' && depth == 0) break;
        }
        return found;
    }

    public static String str(String json, String key) {
        int at = valueStart(json, key);
        if (at < 0 || json.charAt(at) != '"') return "";
        StringBuilder value = new StringBuilder();
        for (int i = at + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(++i);
                switch (next) {
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'u' -> {
                        if (i + 4 < json.length()) {
                            try { value.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16)); }
                            catch (NumberFormatException ignored) { value.append('?'); }
                            i += 4;
                        }
                    }
                    default -> value.append(next);
                }
                continue;
            }
            if (c == '"') return value.toString();
            value.append(c);
        }
        return "";
    }

    public static long num(String json, String key) {
        int at = valueStart(json, key);
        if (at < 0) return 0;
        int end = at;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-' || json.charAt(end) == '.')) end++;
        try { return (long) Double.parseDouble(json.substring(at, end)); }
        catch (NumberFormatException e) { return 0; }
    }

    public static boolean bool(String json, String key) {
        int at = valueStart(json, key);
        return at >= 0 && json.startsWith("true", at);
    }

    public static List<String> strings(String json, String key) {
        List<String> values = new ArrayList<>();
        int at = valueStart(json, key);
        if (at < 0 || json.charAt(at) != '[') return values;
        int depth = 0;
        boolean inString = false;
        StringBuilder current = null;
        for (int i = at; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') { i++; continue; }
                if (c == '"') { inString = false; values.add(current.toString()); current = null; continue; }
                current.append(c);
                continue;
            }
            if (c == '"') { inString = true; current = new StringBuilder(); continue; }
            if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) break; }
        }
        return values;
    }

    /** Index of the first non-space character after {@code "key":}, or -1. */
    private static int valueStart(String json, String key) {
        if (json == null) return -1;
        String needle = "\"" + key + "\"";
        int from = 0;
        while (true) {
            int at = json.indexOf(needle, from);
            if (at < 0) return -1;
            int colon = json.indexOf(':', at + needle.length());
            if (colon < 0) return -1;
            int value = colon + 1;
            while (value < json.length() && Character.isWhitespace(json.charAt(value))) value++;
            if (value < json.length()) return value;
            from = at + needle.length();
        }
    }

    public static String escape(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append(' ');
                case '\r' -> out.append(' ');
                case '\t' -> out.append(' ');
                default -> { if (c >= 0x20) out.append(c); }
            }
        }
        return out.toString();
    }
}
