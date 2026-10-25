package rootjar.console;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixed-size in-memory log: keeps the newest N lines. Each line gets a sequence number (1, 2, 3, ...) that never
 * repeats, so a reader can say "give me everything after line 4182" (used by the live console in M3).
 */
public final class LogRingBuffer {
    /** Longest line kept; anything longer is cut so memory stays bounded (10,000 x 2 KB = ~20 MB worst case). */
    public static final int MAX_LINE_CHARS = 2048;

    public record Entry(long seq, String line) {}

    private final String[] slots;
    private long nextSeq = 1;

    public LogRingBuffer(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        this.slots = new String[capacity];
    }

    /** Stores a line and returns its sequence number. */
    public synchronized long append(String line) {
        if (line.length() > MAX_LINE_CHARS) line = line.substring(0, MAX_LINE_CHARS) + "...[truncated]";
        slots[(int) ((nextSeq - 1) % slots.length)] = line;
        return nextSeq++;
    }

    /** Sequence number of the newest line, or 0 if empty. */
    public synchronized long lastSeq() {
        return nextSeq - 1;
    }

    /** All still-held lines with seq greater than afterSeq, oldest first. Use afterSeq = 0 for everything. */
    public synchronized List<Entry> since(long afterSeq) {
        long oldestHeld = Math.max(1, nextSeq - slots.length);
        long start = Math.max(Math.max(afterSeq, 0) + 1, oldestHeld);
        List<Entry> out = new ArrayList<>();
        for (long seq = start; seq < nextSeq; seq++) {
            out.add(new Entry(seq, slots[(int) ((seq - 1) % slots.length)]));
        }
        return out;
    }
}
