package rootjar.config;

import java.util.List;

/** Thrown when config.yml cannot be read or fails validation. Carries every problem found, not just the first. */
public class ConfigException extends Exception {
    private final List<String> problems;

    public ConfigException(String message) {
        super(message);
        this.problems = List.of(message);
    }

    public ConfigException(List<String> problems) {
        super(String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
