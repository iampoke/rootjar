package rootjar.config;

import java.nio.file.Path;
import java.util.List;

/** Whole parsed config. Children keep the order they have in the file. baseDir = folder holding config.yml. */
public record RootConfig(ServerConfig server, List<ChildConfig> children, Path baseDir) {
}
