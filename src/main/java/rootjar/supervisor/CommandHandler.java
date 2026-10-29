package rootjar.supervisor;

import rootjar.console.ConsoleOutput;
import rootjar.console.StdinRouter;

import java.util.Optional;

public final class CommandHandler {
    private final Supervisor supervisor;
    private final StdinRouter router;
    private final Runnable stopEverything;
    private final Runnable persist;

    public CommandHandler(Supervisor supervisor, Runnable stopEverything, Runnable persist) {
        this.supervisor = supervisor;
        // Live supplier: children created through the web panel become console-routable immediately.
        this.router = new StdinRouter(() -> supervisor.children().stream().map(ChildProcess::id)
                .collect(java.util.stream.Collectors.toSet()));
        this.stopEverything = stopEverything;
        this.persist = persist;
    }

    public void handleLine(String line) {
        StdinRouter.Command cmd = router.parse(line);
        switch (cmd.kind()) {
            case NONE -> { }
            case STATUS -> supervisor.printStatus();
            case HELP -> printHelp();
            case STOP_ALL -> stopEverything.run();
            case LIST -> supervisor.printPlayers();
            case LIFECYCLE -> handleLifecycle(cmd);
            case SEND -> send(cmd.childId(), cmd.text());
            case FALLBACK -> {
                Optional<ChildProcess> target = supervisor.firstConsoleChild();
                if (target.isPresent()) send(target.get().id(), cmd.text());
                else ConsoleOutput.root("no child has console_enabled: true; address one explicitly: <id> <command>");
            }
        }
    }

    /** Lifecycle verbs control the process; mute/unmute only affects what reaches the main console. */
    private void handleLifecycle(StdinRouter.Command cmd) {
        String verb = cmd.text();
        if (verb.equals("mute") || verb.equals("unmute")) {
            boolean silent = verb.equals("mute");
            if (supervisor.setSilent(cmd.childId(), silent)) {
                ConsoleOutput.root(cmd.childId() + " main console logs " + (silent ? "muted" : "unmuted"));
                persist.run();
            }
        } else {
            supervisor.requestLifecycle(cmd.childId(), verb);
            if (verb.equals("start") || verb.equals("stop") || verb.equals("restart")) {
                supervisor.child(cmd.childId()).ifPresent(c -> {
                    supervisor.setChildConfig(cmd.childId(), c.config().withAutoStart(!verb.equals("stop")));
                    persist.run();
                });
            }
        }
    }

    private void send(String id, String text) {
        ChildProcess c = supervisor.child(id).orElse(null);
        if (c == null) return;
        if (!c.sendLine(text)) ConsoleOutput.root(id + " is not running (" + c.state().label() + "); command not sent");
    }

    private void printHelp() {
        ConsoleOutput.root("commands:");
        ConsoleOutput.root("  <id> <command>        send <command> to that child's console (e.g. velocity plist)");
        ConsoleOutput.root("  <id>.start|stop|restart   control one child");
        ConsoleOutput.root("  <id>.mute|unmute      mute or unmute that child's logs on the main console");
        ConsoleOutput.root("  status                show every child's state");
        ConsoleOutput.root("  list                  show the player count of every running child");
        ConsoleOutput.root("  stop                  stop ALL children gracefully, then exit the root");
        ConsoleOutput.root("  help                  this list");
        ConsoleOutput.root("  anything else         sent to the first console-enabled child");
        StringBuilder ids = new StringBuilder();
        for (ChildProcess c : supervisor.children()) {
            if (ids.length() > 0) ids.append(", ");
            ids.append(c.id()).append(c.config().consoleEnabled() ? "" : " (console_enabled: false)");
        }
        ConsoleOutput.root("children: " + ids);
    }
}