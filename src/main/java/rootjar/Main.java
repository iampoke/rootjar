package rootjar;

import rootjar.config.ChildConfig;
import rootjar.config.ConfigException;
import rootjar.config.ConfigLoader;
import rootjar.config.ConfigSaver;
import rootjar.config.ConfigValidator;
import rootjar.config.RootConfig;
import rootjar.config.ServerConfig;
import rootjar.config.PasswordHasher;
import rootjar.console.ConsoleOutput;
import rootjar.supervisor.ChildProcess;
import rootjar.supervisor.CommandHandler;
import rootjar.supervisor.Supervisor;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

public final class Main {
    public static final String VERSION = "1.0.1";

    private Main() {}

    public static void main(String[] args) throws Exception {
        Path configPath = Paths.get("config.yml");
        boolean hashPassword = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--hash-password" -> hashPassword = true;
                case "--config" -> {
                    if (i + 1 >= args.length) usage("--config needs a file path");
                    configPath = Paths.get(args[++i]);
                }
                case "--help", "-h" -> usage(null);
                default -> usage("unknown argument: " + args[i]);
            }
        }
        if (hashPassword) System.exit(hashPasswordCli());

        Path indexFile = Paths.get("index.html");
        if (Files.notExists(indexFile)) {
            try {
                Files.writeString(indexFile, Frontend.html());
                ConsoleOutput.root("index.html not found. Generated a default index.html.");
            } catch (IOException e) {
                ConsoleOutput.root("Failed to create default index.html: " + e.getMessage());
            }
        }

        if (Files.notExists(configPath)) {
            ServerConfig defaultServer = new ServerConfig(
                "0.0.0.0", "127.0.0.1", 12035, 86400, 5, 10, 300, "placeholder", List.of("*"), 0, 5, "", false, List.of()
            );
            RootConfig defaultConfig = new RootConfig(defaultServer, List.of(), configPath.toAbsolutePath().getParent());
            try {
                ConfigSaver.save(configPath, defaultConfig);
                ConsoleOutput.root("config.yml not found. Generated a default config.yml.");
            } catch (IOException e) {
                ConsoleOutput.root("Failed to create default config.yml: " + e.getMessage());
                System.exit(1);
                return;
            }
        }

        RootConfig config;
        try {
            config = ConfigLoader.load(configPath);
            config = autoFixConfig(config, configPath); // <--- AUTO-FIX DUPLICATES
            
            String hash = config.server().passwordHash();
            if (hash == null || hash.isBlank() || hash.equals("placeholder")) {
                String newPass = generateRandomPassword();
                String newHash = PasswordHasher.hash(newPass.toCharArray());
                
                ServerConfig oldServer = config.server();
                ServerConfig newServer = new ServerConfig(
                    oldServer.bind(), oldServer.publicIp(), oldServer.port(), oldServer.sessionTtlSeconds(),
                    oldServer.loginRateLimitPerIpPerMinute(), oldServer.loginLockoutAfterFailures(),
                    oldServer.loginLockoutSeconds(), newHash, oldServer.trustedProxies(), oldServer.diskMaxGb(), oldServer.backupRetention(), oldServer.webhookUrl(), oldServer.publicStatus(), oldServer.users()
                );
                
                config = new RootConfig(newServer, config.children(), config.baseDir());
                ConfigSaver.save(configPath, config);
                
                Path passFile = config.baseDir().resolve("ADMIN_PASSWORD.txt");
                Files.writeString(passFile, "Username: admin\nPassword: " + newPass + "\n\nYou can delete this file after logging in.\n");
                
                ConsoleOutput.root("==========================================================");
                ConsoleOutput.root("FIRST RUN DETECTED: Generated a random Admin password.");
                ConsoleOutput.root("Username: admin");
                ConsoleOutput.root("Password: " + newPass);
                ConsoleOutput.root("Saved to ADMIN_PASSWORD.txt");
                ConsoleOutput.root("==========================================================");
            }
            
            ConfigValidator.validate(config);
        } catch (ConfigException e) {
            ConsoleOutput.root("CONFIG ERROR - refusing to start:");
            for (String problem : e.problems()) ConsoleOutput.root("  - " + problem);
            System.exit(1);
            return;
        }

        ConsoleOutput.root("root jar " + VERSION + " on Java " + System.getProperty("java.version") + ", config " + configPath.toAbsolutePath());
        
        String detectedIp = fetchPublicIp();
        if (detectedIp != null) {
            System.setProperty("rootjar.publicIp", detectedIp);
            ServerConfig old = config.server();
            ServerConfig updated = new ServerConfig(
                old.bind(), detectedIp, old.port(), old.sessionTtlSeconds(),
                old.loginRateLimitPerIpPerMinute(), old.loginLockoutAfterFailures(),
                old.loginLockoutSeconds(), old.passwordHash(), old.trustedProxies(), old.diskMaxGb(), old.backupRetention(), old.webhookUrl(), old.publicStatus(), old.users()
            );
            config = new RootConfig(updated, config.children(), config.baseDir());
            ConsoleOutput.root("Detected public IP: " + detectedIp);
        } else {
            ConsoleOutput.root("Could not automatically detect public IP (network restricted).");
        }

        Supervisor supervisor = new Supervisor(config);

        WebServer webServer = null;
        try {
            webServer = new WebServer(config, supervisor, configPath);
            webServer.start();
            
            String displayIp = config.server().publicIp();
            if (displayIp.equals("0.0.0.0") || displayIp.equals("127.0.0.1")) {
                ConsoleOutput.root("Web Panel available at: http://" + config.server().bind() + ":" + config.server().port() + " (Behind proxy?)");
            } else {
                ConsoleOutput.root("Web Panel available at: http://" + displayIp + ":" + config.server().port());
            }
        } catch (IOException e) {
            ConsoleOutput.root("Failed to start Web Panel: " + e.getMessage());
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            supervisor.shutdown(); 
            ConsoleOutput.root("bye");
            Runtime.getRuntime().halt(0);
        }, "shutdown-hook"));

        Thread starter = new Thread(supervisor::startAll, "startup");
        starter.setDaemon(true);
        starter.start();

        WebServer ws = webServer;
        CommandHandler handler = new CommandHandler(supervisor, () -> {
            supervisor.shutdown();
            System.exit(0);
        }, () -> {
            if (ws != null) {
                try { ws.persistConfig(); } catch (IOException ignored) {}
            }
        });
        ConsoleOutput.root("children: " + supervisor.children().stream().map(ChildProcess::id).toList() + " - type 'help' for console commands");

        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) handler.handleLine(line);
        } catch (IOException e) {
            ConsoleOutput.root("console input failed: " + e.getMessage());
        }
        ConsoleOutput.root("console input closed; children keep running. Send SIGTERM to stop.");
        new CountDownLatch(1).await();
    }

    // --- AUTO-FIXER ---
    private static RootConfig autoFixConfig(RootConfig config, Path configPath) {
        List<ChildConfig> fixedChildren = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        Set<Integer> usedStart = new HashSet<>();
        Set<Integer> usedStop = new HashSet<>();
        Set<Integer> usedPorts = new HashSet<>();
        boolean changed = false;

        int maxStart = 0;
        int maxStop = 0;
        for (ChildConfig c : config.children()) {
            maxStart = Math.max(maxStart, c.startOrder());
            maxStop = Math.max(maxStop, c.stopOrder());
        }

        for (ChildConfig c : config.children()) {
            String newId = c.id();
            Path newDir = c.dir();
            int newStart = c.startOrder();
            int newStop = c.stopOrder();
            int newPort = c.port();
            boolean childChanged = false;

            // Fix duplicate ID
            if (seenIds.contains(newId)) {
                int suffix = 2;
                while (seenIds.contains(newId + "_" + suffix)) suffix++;
                String oldId = newId;
                newId = newId + "_" + suffix;
                
                Path newDirPath = newDir.getParent().resolve(newId);
                try {
                    Files.createDirectories(newDirPath);
                    Files.writeString(newDirPath.resolve("eula.txt"), "eula=true");
                } catch (Exception ignored) {}
                newDir = newDirPath;
                childChanged = true;
                ConsoleOutput.root("Auto-Fix: Renamed duplicate server ID '" + oldId + "' to '" + newId + "'");
            }
            seenIds.add(newId);

            // Fix duplicate start_order
            if (usedStart.contains(newStart)) {
                maxStart++;
                newStart = maxStart;
                childChanged = true;
                ConsoleOutput.root("Auto-Fix: Adjusted start_order for '" + newId + "' to " + newStart);
            }
            usedStart.add(newStart);

            // Fix duplicate stop_order
            if (usedStop.contains(newStop)) {
                maxStop++;
                newStop = maxStop;
                childChanged = true;
                ConsoleOutput.root("Auto-Fix: Adjusted stop_order for '" + newId + "' to " + newStop);
            }
            usedStop.add(newStop);

            // Fix duplicate ports. Port 0 means a Discord bot has no listening port, so several bots may share it.
            if (newPort != 0 && (usedPorts.contains(newPort) || newPort == config.server().port())) {
                newPort = 25565;
                while (usedPorts.contains(newPort) || newPort == config.server().port()) newPort++;
                childChanged = true;
                ConsoleOutput.root("Auto-Fix: Changed conflicting port for '" + newId + "' to " + newPort);
            }
            if (newPort != 0) usedPorts.add(newPort);

            if (childChanged) {
                changed = true;
                c = new ChildConfig(newId, c.type(), newDir, c.jar(), c.heap(), c.xmsEqualXmx(),
                    newPort, c.bind(), c.stopStrategy(), c.stopCommand(), c.stopTimeoutSeconds(),
                    newStart, newStop, c.restartOnCrash(), c.restartBackoffSeconds(),
                    c.extraFlags(), c.consoleEnabled(), c.useAikarFlags(), c.silent(), c.javaPath(), c.autoStart(), c.displayHost());
            }
            fixedChildren.add(c);
        }

        if (changed) {
            RootConfig newConfig = new RootConfig(config.server(), fixedChildren, config.baseDir());
            try {
                ConfigSaver.save(configPath, newConfig);
                ConsoleOutput.root("Auto-Fix: Duplicate IDs, Orders, or Ports detected. Config has been auto-corrected and saved.");
            } catch (Exception e) {
                ConsoleOutput.root("Auto-Fix: Failed to save updated config: " + e.getMessage());
            }
            return newConfig;
        }
        return config;
    }

    private static String fetchPublicIp() {
        String[] apis = { "https://api.ipify.org", "https://ifconfig.me/ip", "https://checkip.amazonaws.com" };
        for (String api : apis) {
            try {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(
                        URI.create(api).toURL().openStream(), StandardCharsets.UTF_8))) {
                    String ip = in.readLine();
                    if (ip != null && ip.trim().matches("^([0-9]{1,3}\\.){3}[0-9]{1,3}$")) return ip.trim();
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static String generateRandomPassword() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#%^&*";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) sb.append(chars.charAt(random.nextInt(chars.length())));
        return sb.toString();
    }

    private static int hashPasswordCli() throws IOException {
        char[] password;
        Console console = System.console();
        if (console != null) {
            password = console.readPassword("Password: ");
            char[] again = console.readPassword("Repeat password: ");
            if (password == null || (again != null && !Arrays.equals(password, again))) {
                System.err.println("Passwords did not match.");
                return 1;
            }
        } else {
            String line = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
            if (line == null) { System.err.println("No password received on stdin."); return 1; }
            password = line.toCharArray();
        }
        if (password.length < 8) { System.err.println("Password must be at least 8 characters."); return 1; }
        System.out.println(PasswordHasher.hash(password));
        return 0;
    }

    private static void usage(String error) {
        if (error != null) System.err.println("error: " + error);
        System.err.println("usage: java -jar server.jar [--config <file>] | --hash-password");
        System.exit(error == null ? 0 : 2);
    }
}