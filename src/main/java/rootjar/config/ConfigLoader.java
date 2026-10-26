package rootjar.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ConfigLoader {
    private ConfigLoader() {}

    public static RootConfig load(Path file) throws ConfigException {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            throw new ConfigException("config file not found: " + file.toAbsolutePath());
        } catch (IOException e) {
            throw new ConfigException("cannot read " + file.toAbsolutePath() + ": " + e.getMessage());
        }
        Object root = MiniYaml.parse(text);
        if (!(root instanceof Map)) throw new ConfigException("config.yml must be a mapping");
        Map<String, Object> top = asMap(root, "config");
        Path baseDir = file.toAbsolutePath().getParent();

        ServerConfig server = readServer(top.get("server") == null ? Map.of() : asMap(top.get("server"), "server"));
        Object rawChildren = top.get("children");
        List<ChildConfig> children = new ArrayList<>();
        if (rawChildren instanceof List) {
            List<?> items = (List<?>) rawChildren;
            for (int i = 0; i < items.size(); i++) {
                children.add(readChild(asMap(items.get(i), "children[" + i + "]"), i, baseDir));
            }
        }
        return new RootConfig(server, children, baseDir);
    }

    private static ServerConfig readServer(Map<String, Object> m) throws ConfigException {
        String w = "server";
        List<ServerConfig.UserConfig> users = new ArrayList<>();
        Object rawUsers = m.get("users");
        if (rawUsers instanceof List) {
            for (Object u : (List<?>) rawUsers) {
                Map<String, Object> um = asMap(u, "users");
                users.add(new ServerConfig.UserConfig(
                    str(um, "username", null, "users"), str(um, "password_hash", null, "users"),
                    str(um, "role", "admin", "users"), strList(um, "allowed_servers", List.of("*"), "users")
                ));
            }
        }
        return new ServerConfig(
                str(m, "bind", "0.0.0.0", w), "127.0.0.1", num(m, "port", 25568, w), num(m, "session_ttl_seconds", 86400, w),
                num(m, "login_rate_limit_per_ip_per_minute", 5, w), num(m, "login_lockout_after_failures", 10, w),
                num(m, "login_lockout_seconds", 300, w), str(m, "password_hash", "placeholder", w),
                strList(m, "trusted_proxies", List.of("*"), w), num(m, "disk_max_gb", 0, w),
                Math.max(1, Math.min(50, num(m, "backup_retention", 5, w))), users);
    }

    private static ChildConfig readChild(Map<String, Object> m, int index, Path baseDir) throws ConfigException {
        String w = "children[" + index + "]";
        String id = str(m, "id", null, w);
        StopStrategy strategy = StopStrategy.parse(str(m, "stop_strategy", "stdin", w));
        if (strategy == null) throw new ConfigException(w + ".stop_strategy must be stdin, sigterm or both");
        List<Integer> backoff = new ArrayList<>();
        for (Object o : list(m, "restart_backoff_seconds", List.of(5L, 10L, 30L, 60L, 300L), w)) {
            if (!(o instanceof Long l) || l > Integer.MAX_VALUE) throw new ConfigException(w + ".restart_backoff_seconds must be a list");
            backoff.add((int) (long) l);
        }
        return new ChildConfig(
                id, str(m, "type", id, w), baseDir.resolve(str(m, "dir", null, w)).normalize(),
                str(m, "jar", null, w), str(m, "heap", null, w), bool(m, "xms_equal_xmx", true, w),
                num(m, "port", null, w), str(m, "bind", "0.0.0.0", w), strategy,
                str(m, "stop_command", "stop", w), num(m, "stop_timeout_seconds", 30, w),
                num(m, "start_order", index + 1, w), num(m, "stop_order", index + 1, w),
                bool(m, "restart_on_crash", true, w), backoff, strList(m, "extra_flags", List.of(), w),
                bool(m, "console_enabled", true, w), bool(m, "use_aikar_flags", true, w),
                bool(m, "silent", false, w), // NEW
                str(m, "java", "", w), // NEW: "" means the panel's own JVM
                bool(m, "auto_start", true, w) // NEW: old configs keep starting on boot
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o, String where) throws ConfigException {
        if (o instanceof Map) return (Map<String, Object>) o;
        throw new ConfigException(where + " must be a mapping");
    }
    private static String str(Map<String, Object> m, String key, String def, String where) throws ConfigException {
        Object v = m.get(key);
        if (v == null) { if (def == null) throw new ConfigException(where + "." + key + " is required"); return def; }
        if (v instanceof Map || v instanceof List) throw new ConfigException(where + "." + key + " must be a single value");
        return String.valueOf(v);
    }
    private static int num(Map<String, Object> m, String key, Integer def, String where) throws ConfigException {
        Object v = m.get(key);
        if (v == null) { if (def == null) throw new ConfigException(where + "." + key + " is required"); return def; }
        if (v instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return (int) (long) l;
        throw new ConfigException(where + "." + key + " must be a whole number");
    }
    private static boolean bool(Map<String, Object> m, String key, boolean def, String where) throws ConfigException {
        Object v = m.get(key);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        throw new ConfigException(where + "." + key + " must be true or false");
    }
    private static List<?> list(Map<String, Object> m, String key, List<?> def, String where) throws ConfigException {
        Object v = m.get(key);
        if (v == null) return def;
        if (v instanceof List<?> l) return l;
        throw new ConfigException(where + "." + key + " must be a list");
    }
    private static List<String> strList(Map<String, Object> m, String key, List<String> def, String where) throws ConfigException {
        Object v = m.get(key);
        if (v == null) return def;
        List<String> out = new ArrayList<>();
        for (Object o : list(m, key, def, where)) out.add(String.valueOf(o));
        return out;
    }
}