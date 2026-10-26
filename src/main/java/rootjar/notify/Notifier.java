package rootjar.notify;

import rootjar.config.ServerConfig;
import rootjar.console.ConsoleOutput;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** Sends best-effort webhook notifications without delaying panel requests. */
public final class Notifier {
    private final Supplier<ServerConfig> config;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "notifier");
        thread.setDaemon(true);
        return thread;
    });

    public Notifier(Supplier<ServerConfig> config) { this.config = config; }

    public void send(String event, String message) {
        String url = config.get().webhookUrl();
        if (url == null || url.isBlank()) return;
        executor.execute(() -> {
            try {
                String body = url.contains("discord.com/api/webhooks")
                        ? "{\"content\":\"" + escape(event + ": " + message) + "\"}"
                        : "{\"text\":\"" + escape(event + ": " + message) + "\"}";
                HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
                client.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                ConsoleOutput.root("webhook notification failed: " + e.getMessage());
            }
        });
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
