package rootjar.addons;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    /** A resolved download: the URL to fetch, the file name to save it as, and the digest to verify it with. */
    public record Download(String url, String fileName, String sha256, long size) {}

    /**
     * One dependency of a chosen version. Modrinth identifies a dependency by project and optionally by an
     * exact version; Hangar identifies it by project id and a display name. Either may be blank, and a
     * dependency with neither cannot be installed automatically.
     */
    public record Dependency(String name, String projectId, String versionId, boolean required, String externalUrl) {}

    /** One selectable version of an addon, normalized across registries. */
    public record Version(String id, String name, String url, String fileName, long size,
                          String sha256, List<Dependency> dependencies) {}

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

    /** The newest downloadable Paper file in a Hangar versions response. */
    public static Download hangarDownload(String versionsJson) {
        for (Version version : hangarVersions(versionsJson)) {
            return new Download(version.url(), version.fileName(), version.sha256(), version.size());
        }
        return null;
    }

    /**
     * Every version in a Hangar versions response, newest first, each with its Paper file and its plugin
     * dependencies. Hangar nests the link two levels deep - {@code downloads.PAPER.downloadUrl} - so a flat
     * search for "downloadUrl" returns whichever platform the server listed first, or a dependency's link.
     */
    public static List<Version> hangarVersions(String json) {
        List<Version> versions = new ArrayList<>();
        for (String object : objects(json, "result")) {
            String name = str(object, "name");
            String platform = platformValue(object, "downloads", "PAPER");
            String url = str(platform, "downloadUrl");
            if (url.isEmpty() || !url.startsWith("https://")) continue;
            String fileInfo = objectBody(platform, "fileInfo");
            String fileName = str(fileInfo, "name");
            if (!fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
                fileName = (name.isBlank() ? "hangar-plugin" : name) + ".jar";
            }
            versions.add(new Version(name, name, url, fileName, num(fileInfo, "sizeBytes"),
                    str(fileInfo, "sha256Hash"), hangarDependencies(object)));
        }
        return versions;
    }

    /** Hangar keys its per-platform maps by platform name; fall back to the first entry when PAPER is absent. */
    private static String platformValue(String versionObject, String key, String platform) {
        String all = valueBody(versionObject, key);
        if (all.isEmpty()) return "";
        String wanted = valueBody(all, platform);
        return wanted.isEmpty() ? firstNestedValue(all) : wanted;
    }

    /**
     * The plugins a Hangar version says it needs. {@code platformDependencies} is *not* this - it lists the
     * Minecraft versions the plugin supports, which reads like a dependency list and is not one.
     */
    private static List<Dependency> hangarDependencies(String versionObject) {
        List<Dependency> dependencies = new ArrayList<>();
        for (String object : objects(platformValue(versionObject, "pluginDependencies", "PAPER"), null)) {
            String name = str(object, "name");
            long projectId = num(object, "projectId");
            if (name.isBlank() && projectId <= 0) continue;
            dependencies.add(new Dependency(name, projectId > 0 ? String.valueOf(projectId) : "", "",
                    bool(object, "required"), str(object, "externalUrl")));
        }
        return dependencies;
    }

    /**
     * Versions from SpiGet's {@code /resources/{id}/versions}. The numeric {@code id} is what SpiGet's
     * download endpoint takes as {@code ?version=}, so the caller builds the absolute URL itself and a
     * third-party string never becomes a request target.
     */
    public static List<Version> spigotVersions(String json) {
        List<Version> versions = new ArrayList<>();
        for (String object : objects(json, null)) {
            long id = num(object, "id");
            String name = str(object, "name");
            if (id <= 0 || name.isBlank()) continue;
            // SpiGet carries no dependency data at all, so the list stays empty rather than looking resolved.
            versions.add(new Version(String.valueOf(id), name, "", "", 0, "", List.of()));
        }
        return versions;
    }

    /** Versions from Modrinth's {@code /project/{id}/version}, each with its primary file and dependencies. */
    public static List<Version> modrinthVersions(String json) {
        List<Version> versions = new ArrayList<>();
        for (String object : objects(json, null)) {
            String id = str(object, "id");
            if (id.isEmpty()) continue;
            String url = "", fileName = "";
            long size = 0;
            for (String file : objects(valueBody(object, "files"), null)) {
                String fileUrl = str(file, "url");
                String name = str(file, "filename");
                if (fileUrl.isEmpty() || name.isEmpty()) continue;
                if (url.isEmpty() || bool(file, "primary")) {
                    url = fileUrl;
                    fileName = name;
                    size = num(file, "size");
                    if (bool(file, "primary")) break;
                }
            }
            String number = str(object, "version_number");
            String name = str(object, "name");
            versions.add(new Version(id, number.isBlank() ? name : number, url, fileName, size, "",
                    modrinthDependencies(object)));
        }
        return versions;
    }

    private static List<Dependency> modrinthDependencies(String versionObject) {
        List<Dependency> dependencies = new ArrayList<>();
        for (String object : objects(valueBody(versionObject, "dependencies"), null)) {
            String type = str(object, "dependency_type");
            // "embedded" already ships inside the jar and "incompatible" is a conflict, not something to add.
            if (!type.equals("required") && !type.equals("optional")) continue;
            String projectId = str(object, "project_id");
            String versionId = str(object, "version_id");
            if (projectId.isEmpty() && versionId.isEmpty()) continue;
            String fileName = str(object, "file_name");
            dependencies.add(new Dependency(fileName.isBlank() ? projectId : fileName,
                    projectId, versionId, type.equals("required"), ""));
        }
        return dependencies;
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
            json.append("],\"source\":\"").append(escape(source)).append("\"}");
        }
        return json.append("]}").toString();
    }

    /**
     * Renders normalized versions as the JSON the version picker expects. The download URL is deliberately
     * left out: the browser has no business fetching a registry file directly, and the install route rebuilds
     * it server-side from the version id.
     */
    public static String toJsonVersions(List<Version> versions, String source) {
        StringBuilder json = new StringBuilder("{\"source\":\"").append(escape(source)).append("\",\"items\":[");
        boolean first = true;
        for (Version version : versions) {
            if (!first) json.append(',');
            first = false;
            json.append("{\"id\":\"").append(escape(version.id()))
                .append("\",\"name\":\"").append(escape(version.name()))
                .append("\",\"fileName\":\"").append(escape(version.fileName()))
                .append("\",\"size\":").append(version.size())
                .append(",\"dependencies\":[");
            boolean firstDependency = true;
            for (Dependency dependency : version.dependencies()) {
                if (!firstDependency) json.append(',');
                firstDependency = false;
                json.append("{\"name\":\"").append(escape(dependency.name()))
                    .append("\",\"projectId\":\"").append(escape(dependency.projectId()))
                    .append("\",\"versionId\":\"").append(escape(dependency.versionId()))
                    .append("\",\"required\":").append(dependency.required())
                    .append(",\"externalUrl\":\"").append(escape(dependency.externalUrl())).append("\"}");
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

    /**
     * Fills in dependency display names from a project-id to title map, leaving every other field alone.
     * Modrinth names a dependency by project id and only sometimes supplies a file name, so without this the
     * picker shows "AANobbMI" where the user expects "Sodium".
     */
    public static List<Version> withDependencyNames(List<Version> versions, Map<String, String> titles) {
        if (titles == null || titles.isEmpty()) return versions;
        List<Version> renamed = new ArrayList<>();
        for (Version version : versions) {
            List<Dependency> dependencies = new ArrayList<>();
            for (Dependency dependency : version.dependencies()) {
                String title = titles.get(dependency.projectId());
                boolean unnamed = dependency.name().isBlank() || dependency.name().equals(dependency.projectId());
                dependencies.add(title != null && unnamed
                        ? new Dependency(title, dependency.projectId(), dependency.versionId(),
                                dependency.required(), dependency.externalUrl())
                        : dependency);
            }
            renamed.add(new Version(version.id(), version.name(), version.url(), version.fileName(),
                    version.size(), version.sha256(), dependencies));
        }
        return renamed;
    }

    /**
     * The balanced text of the array or object stored under {@code key}, brackets included, or "" when the key
     * is absent or holds a scalar. Hangar nests two levels deep ({@code downloads.PAPER.fileInfo.name}), which
     * the flat readers below cannot reach.
     */
    public static String valueBody(String json, String key) {
        int at = valueStart(json, key);
        if (at < 0) return "";
        char open = json.charAt(at);
        if (open != '{' && open != '[') return "";
        return balanced(json, at);
    }

    /** The balanced object stored under {@code key}, or "" when it is absent or is not an object. */
    public static String objectBody(String json, String key) {
        String body = valueBody(json, key);
        return body.startsWith("{") ? body : "";
    }

    /** The first nested array or object inside {@code body}, whatever its key happens to be called. */
    private static String firstNestedValue(String body) {
        boolean inString = false;
        for (int i = 1; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == '{' || c == '[') return balanced(body, i);
        }
        return "";
    }

    /** The balanced {@code {...}} or {@code [...]} run starting at {@code at}, or "" if it never closes. */
    private static String balanced(String json, int at) {
        int depth = 0;
        boolean inString = false;
        for (int i = at; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') {
                depth--;
                if (depth == 0) return json.substring(at, i + 1);
            }
        }
        return "";
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
