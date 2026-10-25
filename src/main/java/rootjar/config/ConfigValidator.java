package rootjar.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Meaning-level checks. Collects ALL problems so the operator can fix everything in one pass. */
public final class ConfigValidator {
    private static final Pattern HEAP = Pattern.compile("\\d+[KkMmGg]");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Set<String> RESERVED_IDS = Set.of("status", "help", "stop");

    private ConfigValidator() {}

    public static void validate(RootConfig cfg) throws ConfigException {
        List<String> problems = new ArrayList<>();
        ServerConfig s = cfg.server();

        // --- password ---
        String hash = s.passwordHash();
        if (hash == null || hash.isBlank() || hash.equals("placeholder")) {
            problems.add("server.password_hash is still the placeholder. Run: java -jar server.jar --hash-password"
                    + " and paste the result into config.yml");
        } else if (!PasswordHasher.isWellFormed(hash)) {
            problems.add("server.password_hash is not a valid hash. Generate one with: java -jar server.jar --hash-password");
        }

        // --- panel ---
        if (!validPort(s.port())) problems.add("server.port must be 1-65535 (got " + s.port() + ")");
        if (s.bind().isBlank()) problems.add("server.bind must not be empty");
        if (s.sessionTtlSeconds() < 1) problems.add("server.session_ttl_seconds must be at least 1");
        if (s.loginRateLimitPerIpPerMinute() < 1) problems.add("server.login_rate_limit_per_ip_per_minute must be at least 1");
        if (s.loginLockoutAfterFailures() < 1) problems.add("server.login_lockout_after_failures must be at least 1");
        if (s.loginLockoutSeconds() < 1) problems.add("server.login_lockout_seconds must be at least 1");

        // --- children ---
        Set<String> ids = new HashSet<>();
        Set<Integer> ports = new HashSet<>();
        Set<Integer> startOrders = new HashSet<>();
        Set<Integer> stopOrders = new HashSet<>();

        for (ChildConfig c : cfg.children()) {
            String w = "child '" + c.id() + "'";
            if (!ID.matcher(c.id()).matches()) {
                problems.add(w + ": id may only contain letters, digits, '-' and '_'");
            } else if (RESERVED_IDS.contains(c.id().toLowerCase(Locale.ROOT))) {
                problems.add(w + ": id is reserved for a console command (status, help, stop)");
            }
            if (!ids.add(c.id())) problems.add(w + ": duplicate id");
            if (!validPort(c.port())) problems.add(w + ": port must be 1-65535 (got " + c.port() + ")");
            if (!ports.add(c.port())) problems.add(w + ": port " + c.port() + " is used by another child");
            if (c.port() == s.port()) {
                problems.add(w + ": port " + c.port() + " collides with the web panel port (server.port)");
            }
            if (!startOrders.add(c.startOrder())) problems.add(w + ": start_order " + c.startOrder() + " is used twice");
            if (!stopOrders.add(c.stopOrder())) problems.add(w + ": stop_order " + c.stopOrder() + " is used twice");
            if (!HEAP.matcher(c.heap()).matches()) {
                problems.add(w + ": heap must look like 256M or 2G (got '" + c.heap() + "')");
            } else if (c.useAikarFlags() && heapMegabytes(c.heap()) < 256
                    && c.extraFlags().stream().noneMatch(f -> f.contains("SurvivorRatio"))) {
                problems.add(w + ": heap " + c.heap() + " is too small for the default GC flags");
            }
            if (c.stopTimeoutSeconds() < 1) problems.add(w + ": stop_timeout_seconds must be at least 1");
            if (c.stopStrategy() != StopStrategy.SIGTERM && c.stopCommand().isBlank()) {
                problems.add(w + ": stop_command must not be empty for stop_strategy " + c.stopStrategy());
            }
            if (c.restartBackoffSeconds().isEmpty() || c.restartBackoffSeconds().stream().anyMatch(n -> n < 1)) {
                problems.add(w + ": restart_backoff_seconds must be a non-empty list of numbers >= 1");
            }

            if (c.type().equalsIgnoreCase("nanolimbo") && !isLoopback(c.bind())) {
                problems.add(w + ": NanoLimbo must bind to loopback (127.0.0.1), but bind is '" + c.bind() + "'");
            }
            
            // Removed the missing directory and missing jar checks here so the panel can boot.
        }
        if (!problems.isEmpty()) throw new ConfigException(problems);
    }

    private static long heapMegabytes(String heap) {
        long n = Long.parseLong(heap.substring(0, heap.length() - 1));
        switch (Character.toUpperCase(heap.charAt(heap.length() - 1))) {
            case 'G': return n * 1024;
            case 'M': return n;
            default: return n / 1024;
        }
    }

    private static boolean validPort(int p) {
        return p >= 1 && p <= 65535;
    }

    static boolean isLoopback(String bind) {
        try {
            return InetAddress.getByName(bind.trim()).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}