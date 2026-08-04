package io.mindspice.magenta2.core.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "magenta.root")
public record MagentaRootProperties(Path path) {

    public MagentaRootProperties {
        if (path == null) {
            path = Path.of(System.getProperty("user.home"), ".magenta");
        }
        path = path.toAbsolutePath().normalize();
    }

    public Path defaultDataRoot() {
        return path.resolve("root").normalize();
    }

    public Path skillsRoot() {
        return path.resolve("skills").normalize();
    }
}
