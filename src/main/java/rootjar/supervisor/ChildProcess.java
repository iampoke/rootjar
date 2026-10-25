package rootjar.supervisor;

import rootjar.config.ChildConfig;
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

    private ChildConfig cfg;
    private final ExitListener listener;
    private final LogRingBuffer log = new LogRingBuffer(10_000);
    private final Object stdinLock = new Object();

    private volatile boolean silent;
    private volatile ChildState state = ChildState.STOPPED;
    private volatile OutputStream stdin;
    private volatile boolean startDisabled;
    private volatile int playerCount = 0;

    private Process process;
    private Thread monitor;
    private long startedAtMillis;
    private boolean intentionalStop;
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

    public void setSilent(boolean isSilent) {
        this.silent = isSilent;
        this.cfg = cfg.withSilent(isSilent);
    }

    public synchronized boolean isAlive() { return process != null && process.isAlive(); }
    public synchronized long pid() { return process == null ? -1 : process.pid(); }
    public synchronized long uptimeMillis() { return isAlive() ? System.currentTimeMillis() - startedAtMillis : 0; }

    public void disableStart() { startDisabled = true; }

    public synchronized void markRestarting() {
        if (state == ChildState.CRASHED) state = ChildState.RESTARTING;
    }

    public synchronized boolean start() {
        if (startDisabled) return false;
        if (process != null && process.isAlive()) return false;

        List<String> cmd = CommandLineBuilder.build(cfg, javaBinary());
        ConsoleOutput.root("starting " + id() + " in " + cfg.dir() + ": " + String.join(" ", cmd));
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
        stdin = p.getOutputStream();
        startedAtMillis = System.currentTimeMillis();
        intentionalStop = false;
        stopInProgress = false;
        playerCount = 0;
        state = ChildState.STARTING;

        Thread pump = daemon(() -> pumpOutput(p), "out-" + id());
        monitor = daemon(() -> watch(p, pump), "mon-" + id());
        pump.start();
        monitor.start();
        return true;
    }
    
    public void kill() {
        Process p;
        synchronized (this) { p = process; }
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
        if (line.contains(" joined the game")) playerCount++;
        if (line.contains(" left the game")) playerCount = Math.max(0, playerCount - 1);

        if (!silent) {
            ConsoleOutput.line(id(), line);
        }
        log.append(line);
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
        long uptime;
        synchronized (this) {
            if (process != p) return;
            intentional = intentionalStop;
            uptime = System.currentTimeMillis() - startedAtMillis;
            state = intentional ? ChildState.STOPPED : ChildState.CRASHED;
        }
        ConsoleOutput.root(id() + " exited with code " + code + (intentional ? "" : " (unexpected)"));
        listener.onExit(this, code, intentional, uptime);
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    private static String javaBinary() {
        Path p = Paths.get(System.getProperty("java.home"), "bin", "java");
        return Files.isExecutable(p) ? p.toString() : "java";
    }
}