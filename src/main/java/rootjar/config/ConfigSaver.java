package rootjar.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ConfigSaver {
    public static void save(Path file, RootConfig config) throws IOException {
        if (Files.exists(file)) {
            Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        StringBuilder sb = new StringBuilder();
        ServerConfig s = config.server();
        sb.append("server:\n");
        sb.append("  bind: ").append(s.bind()).append("\n");
        sb.append("  port: ").append(s.port()).append("\n");
        sb.append("  session_ttl_seconds: ").append(s.sessionTtlSeconds()).append("\n");
        sb.append("  login_rate_limit_per_ip_per_minute: ").append(s.loginRateLimitPerIpPerMinute()).append("\n");
        sb.append("  login_lockout_after_failures: ").append(s.loginLockoutAfterFailures()).append("\n");
        sb.append("  login_lockout_seconds: ").append(s.loginLockoutSeconds()).append("\n");
        sb.append("  password_hash: \"").append(s.passwordHash()).append("\"\n");
        sb.append("  trusted_proxies: [");
        for (int i = 0; i < s.trustedProxies().size(); i++) {
            sb.append("\"").append(s.trustedProxies().get(i)).append("\"");
            if (i < s.trustedProxies().size() - 1) sb.append(", ");
        }
        sb.append("]\n\n");
        sb.append("  disk_max_gb: ").append(s.diskMaxGb()).append("\n"); // 0 = unknown quota (storage shown without a max)
        if (s.backupRetention() != 5) sb.append("  backup_retention: ").append(s.backupRetention()).append("\n");
        if (s.webhookUrl() != null && !s.webhookUrl().isBlank()) sb.append("  webhook_url: \"").append(s.webhookUrl().replace("\"", "")).append("\"\n");
        if (s.publicStatus()) sb.append("  public_status: true\n");
        if (!s.users().isEmpty()) {
            sb.append("  users:\n");
            for (ServerConfig.UserConfig u : s.users()) {
                sb.append("    - username: \"").append(u.username()).append("\"\n");
                sb.append("      password_hash: \"").append(u.passwordHash()).append("\"\n");
                sb.append("      role: \"").append(u.role()).append("\"\n");
                sb.append("      allowed_servers: [");
                for (int i = 0; i < u.allowedServers().size(); i++) {
                    sb.append("\"").append(u.allowedServers().get(i)).append("\"");
                    if (i < u.allowedServers().size() - 1) sb.append(", ");
                }
                sb.append("]\n");
            }
            sb.append("\n");
        }
        sb.append("children:\n");
        for (ChildConfig c : config.children()) {
            sb.append("  - id: ").append(c.id()).append("\n");
            sb.append("    type: ").append(c.type()).append("\n");
            sb.append("    dir: ./").append(c.dir().getFileName().toString()).append("\n");
            sb.append("    jar: ").append(c.jar()).append("\n");
            sb.append("    heap: ").append(c.heap()).append("\n");
            sb.append("    xms_equal_xmx: ").append(c.xmsEqualXmx()).append("\n");
            sb.append("    port: ").append(c.port()).append("\n");
            sb.append("    bind: ").append(c.bind()).append("\n");
            sb.append("    stop_strategy: ").append(c.stopStrategy().name().toLowerCase()).append("\n");
            sb.append("    stop_command: \"").append(c.stopCommand()).append("\"\n");
            sb.append("    stop_timeout_seconds: ").append(c.stopTimeoutSeconds()).append("\n");
            sb.append("    start_order: ").append(c.startOrder()).append("\n");
            sb.append("    stop_order: ").append(c.stopOrder()).append("\n");
            sb.append("    restart_on_crash: ").append(c.restartOnCrash()).append("\n");
            sb.append("    restart_backoff_seconds: ").append(c.restartBackoffSeconds()).append("\n");
            sb.append("    extra_flags: ").append(c.extraFlags()).append("\n");
            sb.append("    console_enabled: ").append(c.consoleEnabled()).append("\n");
            sb.append("    use_aikar_flags: ").append(c.useAikarFlags()).append("\n");
            sb.append("    silent: ").append(c.silent()).append("\n"); // NEW
            // Written unquoted on purpose: the YAML reader unescapes quoted scalars, which would eat the
            // backslashes in Windows paths like C:\Program Files\Java\bin\java.exe. JavaRuntimes.validate()
            // rejects values that cannot round-trip as a plain scalar (newlines, control chars, " #").
            String javaPath = c.javaPath() == null ? "" : c.javaPath().replace("\r", "").replace("\n", "").trim();
            if (!javaPath.isEmpty()) sb.append("    java: ").append(javaPath).append("\n"); // NEW
            if (!c.autoStart()) sb.append("    auto_start: false\n"); // only written when the operator stopped it
        }
        Files.writeString(file, sb.toString());
    }
}