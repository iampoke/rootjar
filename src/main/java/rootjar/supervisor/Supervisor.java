package rootjar.supervisor;

import rootjar.config.ChildConfig;
import rootjar.config.RootConfig;
import rootjar.console.ConsoleOutput;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ToIntFunction;

public final class Supervisor implements ChildProcess.ExitListener {
    private static final long START_GAP_MS = 5_000;
    private static final long STABLE_RUN_MS = 10 * 60_000L;

    private final Map<String, ChildProcess> children = new LinkedHashMap<>();
    private final Map<String, BackoffPolicy> backoffs = new HashMap<>();
    private final Map<String, ExecutorService> lifecycle = new HashMap<>();
    private final Map<String, ScheduledFuture<?>> pendingRestarts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "restart-scheduler"));
            
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private final CountDownLatch shutdownSignal = new CountDownLatch(1);
    private final CountDownLatch shutdownDone = new CountDownLatch(1);

    public Supervisor(RootConfig config) {
        for (ChildConfig c : config.children()) {
            addChildInternal(c);
        }
    }

    public synchronized void addChild(ChildConfig c) {
        addChildInternal(c);
        ConsoleOutput.root("dynamically added new child: " + c.id());
    }
    
    public synchronized void updateChildConfig(String id, ChildConfig newConfig) {
        ChildProcess oldChild = children.get(id);
        if (oldChild != null) {
            if (oldChild.isAlive()) {
                oldChild.stop();
            }
            children.put(id, new ChildProcess(newConfig, this));
            ConsoleOutput.root("Updated config for " + id + ". Please start it.");
        }
    }

    public synchronized void removeChild(String id) {
        ChildProcess c = children.remove(id);
        if (c != null) {
            c.disableStart();
            if (c.isAlive()) c.stop();
            backoffs.remove(id);
            ExecutorService exec = lifecycle.remove(id);
            if (exec != null) exec.shutdownNow();
            cancelPendingRestart(id);
            ConsoleOutput.root("Removed child from supervisor: " + id);
        }
    }

    public synchronized boolean setSilent(String id, boolean silent) {
        ChildProcess c = children.get(id);
        if (c == null) return false;
        c.setSilent(silent);
        return true;
    }

    private void addChildInternal(ChildConfig c) {
        if (children.containsKey(c.id())) return;
        children.put(c.id(), new ChildProcess(c, this));
        backoffs.put(c.id(), new BackoffPolicy(c.restartBackoffSeconds()));
        lifecycle.put(c.id(), Executors.newSingleThreadExecutor(r -> daemon(r, "lifecycle-" + c.id())));
    }

    public List<ChildProcess> children() { return new ArrayList<>(children.values()); }
    public Optional<ChildProcess> child(String id) { return Optional.ofNullable(children.get(id)); }
    public Optional<ChildProcess> firstConsoleChild() { return children.values().stream().filter(c -> c.config().consoleEnabled()).findFirst(); }

    public void startAll() {
        List<ChildProcess> order = sortedBy(ChildConfig::startOrder);
        for (int i = 0; i < order.size(); i++) {
            if (shuttingDown.get()) return;
            startNow(order.get(i), true);
            if (i < order.size() - 1 && awaitShutdownSignal(START_GAP_MS)) return;
        }
    }

    private void startNow(ChildProcess c, boolean resetBackoff) {
        if (shuttingDown.get()) return;
        cancelPendingRestart(c.id());
        if (resetBackoff) backoffs.get(c.id()).reset();
        if (!c.start() && c.state() == ChildState.CRASHED) handleCrash(c, -1, 0);
    }

    public void requestLifecycle(String id, String verb) {
        ChildProcess c = children.get(id);
        if (c == null) return;
        try {
            lifecycle.get(id).execute(() -> runLifecycle(c, verb));
        } catch (RejectedExecutionException e) {
            ConsoleOutput.root("shutting down, ignoring " + id + "." + verb);
        }
    }

    private void runLifecycle(ChildProcess c, String verb) {
        if (shuttingDown.get()) return;
        String id = c.id();
        switch (verb) {
            case "start" -> {
                if (c.isAlive()) ConsoleOutput.root(id + " is already running");
                else startNow(c, true);
            }
            case "stop" -> {
                cancelPendingRestart(id);
                if (!c.isAlive() && c.state() == ChildState.STOPPED) ConsoleOutput.root(id + " is already stopped");
                else c.stop();
            }
            case "restart" -> {
                cancelPendingRestart(id);
                c.stop();
                startNow(c, true);
            }
            default -> { }
        }
    }

    @Override
    public void onExit(ChildProcess child, int exitCode, boolean intentional, long uptimeMillis) {
        if (intentional || shuttingDown.get()) return;
        handleCrash(child, exitCode, uptimeMillis);
    }

    private void handleCrash(ChildProcess c, int exitCode, long uptimeMillis) {
        String id = c.id();
        if (!c.config().restartOnCrash()) {
            ConsoleOutput.root(id + " crashed (exit code " + exitCode + "); restart_on_crash is false, leaving it stopped");
            return;
        }
        BackoffPolicy backoff = backoffs.get(id);
        if (uptimeMillis >= STABLE_RUN_MS) backoff.reset();
        int delay = backoff.next();
        c.markRestarting();
        ConsoleOutput.root(id + " crashed (exit code " + exitCode + ", ran " + formatDuration(uptimeMillis)
                + "); restarting in " + delay + "s (attempt " + backoff.attempts() + ")");
        try {
            ScheduledFuture<?> f = scheduler.schedule(() -> {
                try {
                    lifecycle.get(id).execute(() -> restartAfterCrash(c));
                } catch (RejectedExecutionException ignored) {}
            }, delay, TimeUnit.SECONDS);
            ScheduledFuture<?> old = pendingRestarts.put(id, f);
            if (old != null) old.cancel(false);
        } catch (RejectedExecutionException ignored) {}
    }

    private void restartAfterCrash(ChildProcess c) {
        if (shuttingDown.get() || c.state() != ChildState.RESTARTING) return;
        startNow(c, false);
    }

    private void cancelPendingRestart(String id) {
        ScheduledFuture<?> f = pendingRestarts.remove(id);
        if (f != null) f.cancel(false);
    }

    public void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            awaitShutdownDone();
            return;
        }
        try {
            ConsoleOutput.root("shutdown requested, stopping children in stop_order");
            shutdownSignal.countDown();
            for (ChildProcess c : children.values()) c.disableStart();
            pendingRestarts.values().forEach(f -> f.cancel(false));
            scheduler.shutdownNow();
            lifecycle.values().forEach(ExecutorService::shutdown);

            for (ChildProcess c : sortedBy(ChildConfig::stopOrder)) {
                boolean clean = c.stop();
                if (!clean) ConsoleOutput.root(c.id() + " had to be killed");
            }
            ConsoleOutput.root("all children stopped");
        } finally {
            shutdownDone.countDown();
        }
    }

    public boolean isShuttingDown() { return shuttingDown.get(); }

    private void awaitShutdownDone() {
        boolean interrupted = false;
        while (true) {
            try { shutdownDone.await(); break; } 
            catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private boolean awaitShutdownSignal(long millis) {
        try { return shutdownSignal.await(millis, TimeUnit.MILLISECONDS); } 
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return true; }
    }

    public void printStatus() {
        for (ChildProcess c : children.values()) {
            ChildConfig k = c.config();
            String pid = c.pid() < 0 ? "-" : String.valueOf(c.pid());
            ConsoleOutput.root(k.id() + ": " + c.state().label() + " | pid " + pid + " | up "
                    + formatDuration(c.uptimeMillis()) + " | " + k.bind() + ":" + k.port());
        }
    }

    private List<ChildProcess> sortedBy(ToIntFunction<ChildConfig> key) {
        List<ChildProcess> list = new ArrayList<>(children.values());
        list.sort(Comparator.comparingInt(c -> key.applyAsInt(c.config())));
        return list;
    }

    public static String formatDuration(long millis) {
        long s = millis / 1000;
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m " + (s % 60) + "s";
        long h = m / 60;
        if (h < 24) return h + "h " + (m % 60) + "m";
        return (h / 24) + "d " + (h % 24) + "h";
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}