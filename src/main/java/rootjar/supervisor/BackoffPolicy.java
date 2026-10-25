package rootjar.supervisor;

import java.util.List;

/** Walks through restart_backoff_seconds one step per crash and stays on the last value. Thread-safe. */
public final class BackoffPolicy {
    private final List<Integer> delays;
    private int attempts;

    public BackoffPolicy(List<Integer> delays) {
        if (delays.isEmpty()) throw new IllegalArgumentException("backoff list must not be empty");
        this.delays = List.copyOf(delays);
    }

    /** Delay to use for the next restart; advances the escalation. */
    public synchronized int next() {
        int delay = delays.get(Math.min(attempts, delays.size() - 1));
        attempts++;
        return delay;
    }

    /** Restart counter since the last reset (1 for the first restart). */
    public synchronized int attempts() {
        return attempts;
    }

    /** Back to the first (shortest) delay. */
    public synchronized void reset() {
        attempts = 0;
    }
}
