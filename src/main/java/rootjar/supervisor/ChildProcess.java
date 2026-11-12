package rootjar.supervisor;

import rootjar.config.ChildConfig;
import rootjar.Main;
import rootjar.console.ConsoleOutput;
import rootjar.console.LogRingBuffer;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ChildProcess {
    public interface ExitListener {
        void onExit(ChildProcess child, int exitCode, boolean intentional, long uptimeMillis);
    }

    private static final long RUNNING_AFTER_MS = 5_000;
    private static final long REAP_WAIT_MS = 5_000;
    private static final int MAX_READ_LINE = 8192;
    /** Connect and read budget for one status ping; it runs on a background thread against localhost. */
    private static final int PING_TIMEOUT_MS = 1500;
    /** Compiled once rather than per console line, which the player tracking used to do twice per line. */
    private static final java.util.regex.Pattern JOINED =
            java.util.regex.Pattern.compile("^([A-Za-z0-9_]{1,16}) joined the game");
    private static final java.util.regex.Pattern LEFT =
            java.util.regex.Pattern.compile("^([A-Za-z0-9_]{1,16}) left the game");
    private static final java.util.regex.Pattern LIST_REPLY =
            java.util.regex.Pattern.compile("There are (\\d+) of a max of (\\d+) players online(?::\\s*(.*))?");

    private ChildConfig cfg;
    private final ExitListener listener;
    private final LogRingBuffer log = new LogRingBuffer(10_000);
    private final Object stdinLock = new Object();

    private volatile boolean silent;
    private volatile ChildState state = ChildState.STOPPED;
    private volatile OutputStream stdin;
    private volatile boolean startDisabled;
    private volatile int playerCount = 0;
    /**
     * Counts {@code list} commands Rootjar sent itself to refresh the player count. Their replies are parsed
     * but kept out of the console, so polling the Players tab does not spam the log.
     */
    private final java.util.concurrent.atomic.AtomicInteger internalListPending = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.Set<String> onlinePlayers = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** When the last {@code list} reply was parsed, so a caller can wait for the one it just asked for. */
    private volatile long lastListReplyAtMillis;
    private volatile long idleSinceMillis = System.currentTimeMillis();
    /** Java executable the live process was started with; used to tell whether a restart is needed. */
    private volatile String runningJava;

    private Process process;
    private Thread monitor;
    private long startedAtMillis;
    private boolean intentionalStop;
    /** Set by kill(); the exit was forced, so it is reported as KILLED and never restarts. */
    private boolean killed;
    private boolean stopInProgress;

    public ChildProcess(ChildConfig cfg, ExitListener listener) {
        this.cfg = cfg;
        this.listener = listener;
        this.silent = cfg.silent();
    }

    public String id() { return cfg.id(); }
    public synchronized ChildConfig config() { return cfg; }
    public ChildState state() { return state; }
    public LogRingBuffer log() { return log; }
    public int playerCount() { return playerCount; }

    /** Refreshes the player list without echoing the server's reply into the console. */
    public void requestPlayerList() {
        internalListPending.incrementAndGet();
        sendLine("list");
    }

    /**
     * Sends {@code list} and waits briefly for the reply. The handler used to fire the poll and immediately
     * return the stale count, so opening the Players tab showed the numbers from the request before it.
     * Returns true when a reply arrived inside the budget; false is not an error, just a slow or dead server.
     */
    public boolean requestPlayerListAndWait(long timeoutMs) {
        long before = lastListReplyAtMillis;
        requestPlayerList();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (lastListReplyAtMillis > before) return true;
            try { Thread.sleep(25); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return false;
    }

    /**
     * Asks the server itself how many players are on, using the multiplayer menu's status ping. Console
     * parsing can only see join and leave lines, so anything that rewrites them - a plugin, a proxy, custom
     * join text - left the count stuck at whatever it last was.
     */
    public void refreshPlayersByPing() {
        ChildConfig c = config();
        if (!isAlive() || c.port() <= 0 || "website".equals(c.type())) return;
        ServerPing.Status status = ServerPing.ping(pingHost(c), c.port(), PING_TIMEOUT_MS);
        if (status != null) applyPing(status);
    }

    /** A child bound to one interface has to be pinged on it; a wildcard bind answers on loopback. */
    private static String pingHost(ChildConfig c) {
        String bind = c.bind();
        if (bind == null || bind.isBlank() || bind.equals("0.0.0.0") || bind.equals("::") || bind.equals("*")) return "127.0.0.1";
        return bind;
    }

    /**
     * Applies a status ping. The count is authoritative. The name list is only replaced when the sample
     * accounts for every player, because Paper caps its sample and a short one would throw away names the
     * console already knew about.
     */
    public void applyPing(ServerPing.Status status) {
        int previous = playerCount;
        playerCount = status.online();
        if (status.completeSample()) {
            onlinePlayers.clear();
            onlinePlayers.addAll(status.sample());
        }
        if (previous > 0 && playerCount == 0) idleSinceMillis = System.currentTimeMillis();
    }

    /**
     * Strips the timestamp, thread and level prefix a Minecraft server puts in front of every console line.
     * Paper prints {@code [12:34:56 INFO]: Steve joined the game} and vanilla prints
     * {@code [12:34:56] [Server thread/INFO]: Steve joined the game}. The old code removed one bracket group
     * and left {@code ": "} or a second {@code "["} in front of a pattern anchored at the start, so join and
     * leave detection never fired and the count stayed 0 until someone ran {@code list} by hand.
     */
    public static String stripConsolePrefix(String line) {
        String out = line;
        for (int i = 0; i < 3; i++) {
            String next = out.replaceFirst("^\\[[^\\]]*]\\s*", "");
            if (next.equals(out)) break;
            out = next;
        }
        return out.replaceFirst("^:\\s*", "");
    }

    /**
     * The player a console line says joined or left, as {@code +Name} or {@code -Name}, or null when the line
     * is neither. Kept static and prefix-stripped so it can be tested without a running child.
     */
    public static String playerEvent(String line) {
        String text = stripConsolePrefix(line);
        java.util.regex.Matcher joined = JOINED.matcher(text);
        if (joined.find()) return "+" + joined.group(1);
        java.util.regex.Matcher left = LEFT.matcher(text);
        if (left.find()) return "-" + left.group(1);
        return null;
    }
    public java.util.List<String> onlinePlayers() { return onlinePlayers.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList(); }

    public void setSilent(boolean isSilent) {
        synchronized (this) {
            this.silent = isSilent;
            this.cfg = cfg.withSilent(isSilent);
        }
    }

    /**
     * Replaces the config of a stopped (or running) child without touching the process. A running process
     * keeps the command line it was started with; call {@link #javaRestartRequired()} to see whether the
     * configured Java changed and a restart is needed. The id must not change.
     */
    public synchronized void setConfig(ChildConfig newConfig) {
        if (newConfig == null) return;
        if (!cfg.id().equals(newConfig.id())) {
            ConsoleOutput.root("ignoring config update for " + cfg.id() + ": id would change to " + newConfig.id());
            return;
        }
        this.cfg = newConfig;
        this.silent = newConfig.silent();
    }

    /** True when the live process runs a different Java executable than the one currently configured. */
    public synchronized boolean javaRestartRequired() {
        if (!isAlive()) return false;
        String running = runningJava;
        return running != null && !running.equals(javaFor(cfg));
    }

    /** Java executable the live (or last) process was started with, or {@code null} before the first start. */
    public String runningJava() { return runningJava; }

    public synchronized boolean isAlive() { return process != null && process.isAlive(); }
    public synchronized long pid() { return process == null ? -1 : process.pid(); }
    public synchronized long uptimeMillis() { return isAlive() ? System.currentTimeMillis() - startedAtMillis : 0; }
    public long idleShutdownRemainingMillis() {
        int limit = cfg.idleShutdownSeconds();
        if (limit <= 0 || !isAlive()) return -1;
        if (playerCount > 0) return -2;
        return Math.max(0, limit * 1000L - (System.currentTimeMillis() - idleSinceMillis));
    }

    public void disableStart() { startDisabled = true; }

    public synchronized void markRestarting() {
        if (state == ChildState.CRASHED) state = ChildState.RESTARTING;
    }

    /**
     * Minecraft refuses to boot while eula.txt is missing or says false, and the panel agrees on the user's
     * behalf when it creates a server. Servers that were imported, copied, or created before that rule still
     * carry the old file, which shows up as a start that dies instantly with nothing useful in the log, so
     * every start makes sure the file says true.
     */
    private void ensureEula() {
        if (cfg.type().equals("website")) return;
        if (ensureEulaFile(cfg.dir())) ConsoleOutput.root("agreed to the Minecraft EULA for " + id());
    }

    /** Writes eula=true unless the file already agrees. Returns true when it had to write. */
    public static boolean ensureEulaFile(Path dir) {
        try {
            Path eula = dir.resolve("eula.txt");
            boolean agreed = false;
            if (Files.isRegularFile(eula)) {
                // Match the assignment, not the word: a commented "#eula=true is required" line above a
                // "eula=false" still means the server refuses to start.
                for (String line : Files.readAllLines(eula)) {
                    if (line.trim().matches("(?i)eula\\s*=\\s*true.*")) { agreed = true; break; }
                }
            }
            if (agreed) return false;
            Files.createDirectories(dir);
            Files.writeString(eula, "eula=true\n");
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Says plainly when the server folder is not writable by this process, naming its owner. That is the usual
     * cause of the AccessDenied a game logs when it saves a config, and it is invisible from inside that game's
     * own console. A folder extracted by a hosting panel often ends up owned by root while the server runs as
     * another user, and retrying the game never fixes it.
     */
    private void warnIfUnwritable() {
        try {
            if (Files.isWritable(cfg.dir())) return;
            String owner = "unknown";
            try {
                Object value = Files.getAttribute(cfg.dir(), "owner");
                if (value != null) owner = value.toString();
            } catch (Exception ignored) {}
            ConsoleOutput.root("WARNING: " + id() + " cannot write to " + cfg.dir() + " (owned by " + owner
                    + "). The server will fail to save its configuration. Re-upload or re-extract the files as the"
                    + " user that runs the server, or ask the host to give that folder to it.");
        } catch (Exception ignored) {}
    }

    public synchronized boolean start() {
        if (startDisabled) return false;
        if (process != null && process.isAlive()) return false;

        List<String> cmd;
        if (cfg.type().equals("website")) {
            String codeSource;
            try { codeSource = Paths.get(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
            catch (Exception e) { ConsoleOutput.root("could not locate website server code: " + e.getMessage()); state = ChildState.CRASHED; return false; }
            cmd = List.of(javaFor(cfg), "-cp", codeSource, "rootjar.website.StaticSiteServer",
                    cfg.bind(), String.valueOf(cfg.port()), cfg.dir().toAbsolutePath().toString(),
                    System.getProperty("rootjar.publicIp", ""));
        } else {
            cmd = CommandLineBuilder.build(cfg, javaFor(cfg));
        }
        ConsoleOutput.root("starting " + id() + " in " + cfg.dir() + ": " + String.join(" ", cmd));
        alignServerPort();
        ensureBukkitDatapack();
        ensureEula();
        if (PermissionRepair.repairOnce(cfg.dir(), id())) ConsoleOutput.root("repaired copied file permissions in " + id());
        warnIfUnwritable();
        Process p;
        try {
            p = new ProcessBuilder(cmd)
                    .directory(cfg.dir().toFile())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            ConsoleOutput.root("could not start " + id() + ": " + e.getMessage());
            state = ChildState.CRASHED;
            return false;
        }
        process = p;
        runningJava = cmd.get(0);
        stdin = p.getOutputStream();
        startedAtMillis = System.currentTimeMillis();
        intentionalStop = false;
        killed = false;
        stopInProgress = false;
        playerCount = 0;
        onlinePlayers.clear();
        idleSinceMillis = System.currentTimeMillis();
        state = ChildState.STARTING;

        Thread pump = daemon(() -> pumpOutput(p), "out-" + id());
        monitor = daemon(() -> watch(p, pump), "mon-" + id());
        pump.start();
        monitor.start();
        return true;
    }

    public void kill() {
        Process p;
        synchronized (this) {
            p = process;
            // A forced kill is still an intentional exit: it must not be treated as a crash or restarted.
            if (p != null && p.isAlive()) { intentionalStop = true; killed = true; }
        }
        if (p != null && p.isAlive()) {
            ConsoleOutput.root("Force killing " + id() + " (SIGKILL)");
            p.destroyForcibly();
        }
    }

    public boolean stop() {
        final Process p;
        final Thread mon;
        final boolean joinOnly;
        synchronized (this) {
            p = process;
            mon = monitor;
            if (p == null || !p.isAlive()) {
                if (p != null) intentionalStop = true;
                state = ChildState.STOPPED;
                return true;
            }
            joinOnly = stopInProgress;
            if (!joinOnly) {
                stopInProgress = true;
                intentionalStop = true;
                state = ChildState.STOPPING;
            }
        }

        boolean clean;
        if (joinOnly) {
            waitUntil(p, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                    cfg.stopTimeoutSeconds() * 1000L + REAP_WAIT_MS + 2_000));
            clean = !p.isAlive();
        } else {
            clean = doStop(p);
        }
        if (mon != null && mon != Thread.currentThread()) {
            try { mon.join(3_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return clean;
    }

    private boolean doStop(Process p) {
        long timeoutMs = cfg.stopTimeoutSeconds() * 1000L;
        long start = System.nanoTime();
        long deadline = start + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        ConsoleOutput.root("stopping " + id() + " (strategy " + cfg.stopStrategy().name().toLowerCase(java.util.Locale.ROOT)
                + ", timeout " + cfg.stopTimeoutSeconds() + "s)");

        switch (cfg.stopStrategy()) {
            case STDIN -> writeStdin(cfg.stopCommand());
            case SIGTERM -> p.destroy();
            case BOTH -> {
                writeStdin(cfg.stopCommand());
                if (!waitUntil(p, start + TimeUnit.MILLISECONDS.toNanos(timeoutMs / 2))) {
                    ConsoleOutput.root(id() + " still running after " + (timeoutMs / 2000.0) + "s, sending SIGTERM");
                    p.destroy();
                }
            }
        }

        if (waitUntil(p, deadline)) return true;

        ConsoleOutput.root(id() + " did not exit within " + cfg.stopTimeoutSeconds() + "s, sending SIGKILL");
        p.destroyForcibly();
        if (!waitUntil(p, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REAP_WAIT_MS))) {
            ConsoleOutput.root("WARNING: " + id() + " is still alive after SIGKILL");
        }
        return false;
    }

    private static boolean waitUntil(Process p, long deadlineNanos) {
        try {
            long left = Math.max(0, deadlineNanos - System.nanoTime());
            return p.waitFor(left, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !p.isAlive();
        }
    }

    public boolean sendLine(String text) {
        if (!isAlive()) return false;
        return writeStdin(text);
    }

    private boolean writeStdin(String text) {
        OutputStream out = stdin;
        if (out == null) return false;
        String oneLine = text.replace('\n', ' ').replace('\r', ' ');
        synchronized (stdinLock) {
            try {
                out.write((oneLine + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                return true;
            } catch (IOException e) {
                ConsoleOutput.root("could not write to " + id() + " stdin: " + e.getMessage());
                return false;
            }
        }
    }

    private void pumpOutput(Process p) {
        try (InputStream in = p.getInputStream();
             Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) {
                for (int i = 0; i < n; i++) {
                    char c = buf[i];
                    if (c == '\n') {
                        emit(sb);
                    } else if (c != '\r') {
                        sb.append(c);
                        if (sb.length() >= MAX_READ_LINE) emit(sb);
                    }
                }
            }
            if (sb.length() > 0) emit(sb);
        } catch (IOException ignored) {}
    }

    private void emit(StringBuilder sb) {
        String line = sb.toString();
        sb.setLength(0);
        
        // Track players
        int previousPlayers = playerCount;
        String event = playerEvent(line);
        if (event != null) {
            String name = event.substring(1);
            if (event.charAt(0) == '+') { playerCount++; onlinePlayers.add(name); }
            else { playerCount = Math.max(0, playerCount - 1); onlinePlayers.remove(name); }
        }
        java.util.regex.Matcher listed = LIST_REPLY.matcher(line);
        boolean listReply = listed.find();
        if (listReply) {
            playerCount = Integer.parseInt(listed.group(1));
            onlinePlayers.clear();
            if (listed.group(3) != null) java.util.Arrays.stream(listed.group(3).split(",")).map(String::trim).filter(name -> name.matches("[A-Za-z0-9_]{1,16}")).forEach(onlinePlayers::add);
            lastListReplyAtMillis = System.currentTimeMillis();
        }
        // A reply to Rootjar's own poll is parsed but never shown; a list the operator typed still is.
        boolean hideReply = listReply && internalListPending.getAndUpdate(value -> value > 0 ? value - 1 : 0) > 0;
        if (previousPlayers > 0 && playerCount == 0) idleSinceMillis = System.currentTimeMillis();

        if (!silent && !hideReply) {
            ConsoleOutput.line(id(), line);
        }
        if (!hideReply) log.append(line);
    }

    private void watch(Process p, Thread pump) {
        int code;
        try {
            if (!p.waitFor(RUNNING_AFTER_MS, TimeUnit.MILLISECONDS)) {
                synchronized (this) {
                    if (process == p && state == ChildState.STARTING) state = ChildState.RUNNING;
                }
                code = p.waitFor();
            } else {
                code = p.exitValue();
            }
            pump.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        boolean intentional;
        boolean wasKilled;
        long uptime;
        synchronized (this) {
            if (process != p) return;
            wasKilled = killed;
            intentional = intentionalStop || wasKilled;
            uptime = System.currentTimeMillis() - startedAtMillis;
            state = wasKilled ? ChildState.KILLED : (intentional ? ChildState.STOPPED : ChildState.CRASHED);
            killed = false;
        }
        ConsoleOutput.root(id() + " exited with code " + code + (wasKilled ? " (killed)" : intentional ? "" : " (unexpected)"));
        listener.onExit(this, code, intentional, uptime);
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    /** Makes server.properties use the port assigned in Rootjar before the process starts. */
    private void alignServerPort() {
        if (cfg.port() <= 0 || "website".equals(cfg.type()) || "discord_bot".equals(cfg.type())) return;
        Path file = cfg.dir().resolve("server.properties");
        if (!Files.isRegularFile(file)) return;
        try {
            String text = Files.readString(file);
            String updated = setProperty(text, "server-port", String.valueOf(cfg.port()));
            boolean rcon = hasTrueProperty(updated, "enable-rcon");
            updated = setPropertyWhenPresent(updated, "query.port", String.valueOf(cfg.port()));
            int rconPort = rcon ? freeTcpPort(cfg.port() + 1) : -1;
            if (rconPort > 0) updated = setProperty(updated, "rcon.port", String.valueOf(rconPort));
            else if (!rcon) updated = setPropertyWhenPresent(updated, "rcon.port", String.valueOf(cfg.port()));
            if (updated.equals(text)) return;
            Files.writeString(file, updated);
            ConsoleOutput.root(id() + " ports set: server-port " + cfg.port()
                    + (rconPort > 0 ? ", rcon.port " + rconPort : "") + ", query.port " + cfg.port());
        } catch (IOException e) {
            ConsoleOutput.root(id() + " could not update server-port: " + e.getMessage());
        }
    }

    /** Rewrites one properties key, appending it when absent. */
    public static String setProperty(String text, String key, String value) {
        java.util.regex.Pattern line = java.util.regex.Pattern.compile("(?m)^" + java.util.regex.Pattern.quote(key) + "=.*$");
        if (line.matcher(text).find()) return line.matcher(text).replaceFirst(java.util.regex.Matcher.quoteReplacement(key + "=" + value));
        return text.stripTrailing() + "\n" + key + "=" + value + "\n";
    }

    /** Rewrites one properties key only when it already exists. */
    public static String setPropertyWhenPresent(String text, String key, String value) {
        java.util.regex.Pattern line = java.util.regex.Pattern.compile("(?m)^" + java.util.regex.Pattern.quote(key) + "=.*$");
        if (!line.matcher(text).find()) return text;
        return line.matcher(text).replaceFirst(java.util.regex.Matcher.quoteReplacement(key + "=" + value));
    }

    public static boolean hasTrueProperty(String text, String key) {
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("(?m)^" + java.util.regex.Pattern.quote(key) + "=(?i:true)\\s*$").matcher(text);
        return match.find();
    }

    /** First free TCP port at or above {@code from}, skipping the panel and the other children. */
    private int freeTcpPort(int from) {
        java.util.Set<Integer> busy = listener instanceof Supervisor s ? s.busyPorts(id()) : java.util.Set.of();
        for (int port = Math.max(1, from); port <= 65535 && port < Math.max(1, from) + 1000; port++) {
            if (port == cfg.port() || busy.contains(port)) continue;
            return port;
        }
        return -1;
    }

    /**
     * Paper builds its built-in datapack at world/datapacks/bukkit/pack.mcmeta on every start.
     * A copied or partly restored world can miss that folder, and Paper then stops with
     * "Could not initialize Bukkit datapack". Recreate the empty pack before launch.
     */
    private void ensureBukkitDatapack() {
        if ("website".equals(cfg.type()) || "discord_bot".equals(cfg.type())
                || java.util.Set.of("velocity", "waterfall", "bungeecord", "travertine", "nanolimbo").contains(cfg.type())) return;
        try {
            String created = ensureBukkitDatapack(cfg.dir());
            if (created != null) ConsoleOutput.root(id() + " recreated " + created);
        } catch (IOException e) {
            ConsoleOutput.root(id() + " could not create the bukkit datapack: " + e.getMessage());
        }
    }

    /** Creates world/datapacks/bukkit/pack.mcmeta when missing. Returns the path it wrote, or null. */
    public static String ensureBukkitDatapack(Path serverDir) throws IOException {
        String levelName = "world";
        Path props = serverDir.resolve("server.properties");
        if (Files.isRegularFile(props)) {
            java.util.regex.Matcher level = java.util.regex.Pattern.compile("(?m)^level-name=(.+)$").matcher(Files.readString(props));
            if (level.find() && !level.group(1).isBlank()) levelName = level.group(1).trim();
        }
        Path base = serverDir.toAbsolutePath().normalize();
        Path world = base.resolve(levelName).normalize();
        if (!world.startsWith(base)) return null;
        Path pack = world.resolve("datapacks/bukkit");
        Path meta = pack.resolve("pack.mcmeta");
        if (Files.isRegularFile(meta)) return null;
        Files.createDirectories(pack);
        Files.writeString(meta, "{\"pack\":{\"pack_format\":12,\"description\":\"Bukkit data pack\"}}\n");
        return levelName + "/datapacks/bukkit/pack.mcmeta";
    }

    /** Java executable for a child: an explicit configured path, else the panel's own JVM. */
    public static String javaFor(ChildConfig cfg) {
        if (cfg == null) return javaBinary();
        String path = cfg.javaPath();
        return (path == null || path.isBlank()) ? javaBinary() : path.trim();
    }

    private static String javaBinary() {
        Path p = Paths.get(System.getProperty("java.home"), "bin", "java");
        return Files.isExecutable(p) ? p.toString() : "java";
    }
}
