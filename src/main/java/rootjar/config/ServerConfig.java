package rootjar.config;

import java.util.List;
import java.util.Map;

public record ServerConfig(
        String bind,
        String publicIp,
        int port,
        int sessionTtlSeconds,
        int loginRateLimitPerIpPerMinute,
        int loginLockoutAfterFailures,
        int loginLockoutSeconds,
        String passwordHash,
        List<String> trustedProxies,
        int diskMaxGb,
        int backupRetention,
        String webhookUrl,
        boolean publicStatus,
        List<UserConfig> users) {

    public record UserConfig(String username, String passwordHash, String role, List<String> allowedServers) {}
}