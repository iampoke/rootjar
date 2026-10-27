package rootjar.tasks;

import rootjar.config.MiniYaml;
import rootjar.console.ConsoleOutput;
import rootjar.supervisor.Supervisor;

import rootjar.config.ConfigException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Runs daily restart and command tasks from tasks.yml. */
public final class TaskScheduler {
    private final Supervisor supervisor;
    private final Path file;
    private final Map<String, LocalDate> lastRun = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "task-scheduler");
        thread.setDaemon(true);
        return thread;
    });

    public TaskScheduler(Supervisor supervisor, Path file) {
        this.supervisor = supervisor;
        this.file = file;
        executor.scheduleAtFixedRate(this::tick, 30, 30, TimeUnit.SECONDS);
    }

    public String read() throws IOException {
        return Files.exists(file) ? Files.readString(file) : "tasks: []\n";
    }

    public void save(String text) throws IOException, ConfigException {
        Object parsed = MiniYaml.parse(text);
        if (!(parsed instanceof Map<?, ?> map) || !(map.get("tasks") instanceof List<?>)) throw new IOException("tasks.yml must contain a tasks list");
        Files.writeString(file, text);
    }

    private void tick() {
        if (!Files.exists(file)) return;
        try {
            Object parsed = MiniYaml.parse(read());
            if (!(parsed instanceof Map<?, ?> map) || !(map.get("tasks") instanceof List<?> tasks)) return;
            LocalTime now = LocalTime.now().withSecond(0).withNano(0);
            for (Object raw : tasks) {
                if (!(raw instanceof Map<?, ?> task)) continue;
                String server = String.valueOf(task.get("server"));
                String type = String.valueOf(task.get("type"));
                LocalTime scheduled = LocalTime.parse(String.valueOf(task.get("time")));
                String key = server + "|" + type + "|" + scheduled;
                if (!now.equals(scheduled) || LocalDate.now().equals(lastRun.get(key))) continue;
                if ("restart".equals(type)) supervisor.requestLifecycle(server, "restart");
                else if ("backup".equals(type)) supervisor.child(server).ifPresent(child -> { try { rootjar.backup.BackupManager.create(child.config(), "scheduled", true, 5); } catch (IOException e) { ConsoleOutput.root("scheduled backup failed: " + e.getMessage()); } });
                else if ("command".equals(type)) supervisor.child(server).ifPresent(child -> child.sendLine(String.valueOf(task.get("command"))));
                lastRun.put(key, LocalDate.now());
            }
        } catch (Exception e) {
            ConsoleOutput.root("scheduled task check failed: " + e.getMessage());
        }
    }
}
