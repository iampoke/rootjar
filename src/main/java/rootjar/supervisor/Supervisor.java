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
    // Mutated under {@code synchronized(this)} but also read from the child monitor thread
    // ({@code handleCrash}) and the scheduler lambda, so these two must be concurrent maps.
    private final Map<String, BackoffPolicy> backoffs = new ConcurrentHashMap<>();
    private final Map<String, ExecutorService> lifecycle = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pendingRestarts = new ConcurrentHashMap<>();
    // Serialises schedule/cancel of crash restarts and publishes a per-child epoch *before* the task
    // is scheduled; the task re-checks it so a cancel in the schedule/publish gap is never missed.
    private final Object restartLock = new Object();
    private final Map<String, Long> restartEpoch = new ConcurrentHashMap<>();
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

    public synchronized void reorder(List<String> ids) {
        Map<String, ChildProcess> ordered = new LinkedHashMap<>();
        for (String id : ids) {
            ChildProcess child = children.get(id);
            if (child != null) ordered.put(id, child);
        }
        for (var entry : children.entrySet()) ordered.putIfAbsent(entry.getKey(), entry.getValue());
        children.clear();
        children.putAll(ordered);
    }

    public synchronized void addChild(ChildConfig c) {
        addChildInternal(c);
        ConsoleOutput.root("dynamically added new child: " + c.id());
    }
    
    public synchronized void updateChildConfig(String id, ChildConfig newConfig) {
        cancelPendingRestart(id);
        ChildProcess oldChild = children.get(id);
        if (oldChild != null) {
            if (oldChild.isAlive()) {
                oldChild.stop();
            }
            oldChild.disableStart(); // a queued crash restart must not resurrect the replaced process
            children.put(id, new ChildProcess(newConfig, this));
            if (!newConfig.restartBackoffSeconds().isEmpty()) {
                backoffs.put(id, new BackoffPolicy(newConfig.restartBackoffSeconds()));
            } else {
                BackoffPolicy backoff = backoffs.get(id);
                if (backoff != null) backoff.reset();
            }
            ConsoleOutput.root("Updated config for " + id + ". Please start it.");
        }
    }

    /**
     * Updates a child's config in place. The running process is left alone (the new settings apply on the
     * next start); use {@link #updateChildConfig(String, ChildConfig)} when the process should be replaced.
     */
    public synchronized void setChildConfig(String id, ChildConfig cfg) {
        ChildProcess child = children.get(id);
        if (child != null) child.setConfig(cfg);
    }

    /**
     * Sets the Java executable for a child in place: no process replace, no stop. The current child is
     * looked up under this supervisor's lock, so the update is applied to the live config rather than a
     * caller-side snapshot that may already be stale. The new path applies on the next start.
     */
    public synchronized void setJavaPath(String id, String path) {
        ChildProcess child = children.get(id);
        if (child != null) child.setConfig(child.config().withJavaPath(path));
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
            restartEpoch.remove(id);
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

    public synchronized List<ChildProcess> children() { return List.copyOf(children.values()); }
    public synchronized Optional<ChildProcess> child(String id) { return Optional.ofNullable(children.get(id)); }
    public synchronized Optional<ChildProcess> firstConsoleChild() {
        return List.copyOf(children.values()).stream().filter(c -> c.config().consoleEnabled()).findFirst();
    }

    public void startAll() {
        List<ChildProcess> order = sortedBy(ChildConfig::startOrder);
        ChildProcess last = null;
        for (ChildProcess c : order) {
            if (shuttingDown.get()) return;
            if (!c.config().autoStart()) {
                ConsoleOutput.root("not starting " + c.id() + ": it was stopped before the panel restarted (auto_start: false)");
                continue;
            }
            if (last != null && awaitShutdownSignal(START_GAP_MS)) return;
            startNow(c, true);
            last = c;
        }
    }

    private void startNow(ChildProcess c, boolean resetBackoff) {
        if (shuttingDown.get()) return;
        cancelPendingRestart(c.id());
        if (resetBackoff) {
            BackoffPolicy backoff = backoffs.get(c.id());
            if (backoff != null) backoff.reset();
        }
        if (!c.start() && c.state() == ChildState.CRASHED) handleCrash(c, -1, 0);
    }

    public void requestLifecycle(String id, String verb) {
        ChildProcess c;
        ExecutorService exec;
        synchronized (this) {
            c = children.get(id);
            exec = lifecycle.get(id);
        }
        if (c == null || exec == null) {
            ConsoleOutput.root("ignoring " + id + "." + verb + ": child is no longer registered");
            return;
        }
        try {
            exec.execute(() -> runLifecycle(c, verb));
        } catch (RejectedExecutionException e) {
            ConsoleOutput.root("shutting down, ignoring " + id + "." + verb);
        }
    }

    private void runLifecycle(ChildProcess c, String verb) {
        if (shuttingDown.get()) return;
        String id = c.id();
        synchronized (this) {
            if (children.get(id) != c) {
                ConsoleOutput.root("ignoring " + id + "." + verb + ": child was removed or replaced");
                return;
            }
        }
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
        if (backoff == null) {
            ConsoleOutput.root(id + " crashed (exit code " + exitCode + "); it is no longer registered, not restarting");
            return;
        }
        if (uptimeMillis >= STABLE_RUN_MS) backoff.reset();
        int delay = backoff.next();
        c.markRestarting();
        ConsoleOutput.root(id + " crashed (exit code " + exitCode + ", ran " + formatDuration(uptimeMillis)
                + "); restarting in " + delay + "s (attempt " + backoff.attempts() + ")");
        try {
            synchronized (restartLock) {
                // The epoch is bumped and stored before the task is scheduled and re-checked inside
                // it, so a cancel racing with scheduler.schedule()/the map publish cannot be missed.
                long epoch = restartEpoch.merge(id, 1L, Long::sum);
                ScheduledFuture<?> f = scheduler.schedule(() -> {
                    if (restartEpoch.getOrDefault(id, Long.MIN_VALUE) != epoch) return;
                    ExecutorService exec = lifecycle.get(id);
                    if (exec == null) return;
                    try {
                        exec.execute(() -> restartAfterCrash(c));
                    } catch (RejectedExecutionException ignored) {}
                }, delay, TimeUnit.SECONDS);
                ScheduledFuture<?> old = pendingRestarts.put(id, f);
                if (old != null) old.cancel(false);
            }
        } catch (RejectedExecutionException ignored) {}
    }

    private void restartAfterCrash(ChildProcess c) {
        if (shuttingDown.get() || c.state() != ChildState.RESTARTING) return;
        startNow(c, false);
    }

    /**
     * Cancels a scheduled crash restart for a child; public so the web layer can stop a pending restart.
     * Sharing {@link #restartLock} with the scheduling side closes the race where a restart was scheduled
     * but not yet published; the epoch bump also invalidates a task that already made it into the queue.
     * A restart that has already started is unaffected.
     */
    public void cancelPendingRestart(String id) {
        synchronized (restartLock) {
            restartEpoch.merge(id, 1L, Long::sum);
            ScheduledFuture<?> f = pendingRestarts.remove(id);
            if (f != null) f.cancel(false);
        }
    }

    public void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            awaitShutdownDone();
            return;
        }
        try {
            ConsoleOutput.root("shutdown requested, stopping children in stop_order");
            shutdownSignal.countDown();
            for (ChildProcess c : children()) c.disableStart();
            pendingRestarts.values().forEach(f -> f.cancel(false));
            scheduler.shutdownNow();
            List<ExecutorService> executors;
            synchronized (this) {
                executors = List.copyOf(lifecycle.values());
            }
            executors.forEach(ExecutorService::shutdown);

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
        for (ChildProcess c : children()) {
            ChildConfig k = c.config();
            String pid = c.pid() < 0 ? "-" : String.valueOf(c.pid());
            ConsoleOutput.root(k.id() + ": " + c.state().label() + " | pid " + pid + " | up "
                    + formatDuration(c.uptimeMillis()) + " | " + k.bind() + ":" + k.port());
        }
    }

    private synchronized List<ChildProcess> sortedBy(ToIntFunction<ChildConfig> key) {
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