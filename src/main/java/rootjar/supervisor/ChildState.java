package rootjar.supervisor;

import java.util.Locale;

/** Lifecycle state of a child. The lower-case name is what the API/UI will show. */
public enum ChildState {
    STARTING, RUNNING, STOPPING, STOPPED, CRASHED, RESTARTING;

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
