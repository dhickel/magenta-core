package io.mindspice.magenta2.ai.config.user;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.mindspice.magenta2.core.config.MagentaRootProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class AiUserConfigConfiguration {

    @Bean
    AiConfig aiConfig(
        @Value("${app.ai.config-path:./config/ai-config.example.json}") String configPath,
        MagentaRootProperties magentaRootProperties
    ) throws IOException {
        AiConfig config = withResolvedDataRoot(
            ExternalAiConfigLoader.load(Path.of(configPath)),
            magentaRootProperties.path()
        );
        Files.createDirectories(config.dataRoot());
        return config;
    }

    static AiConfig withResolvedDataRoot(AiConfig config, Path magentaRoot) {
        Path dataRoot = config.dataRoot();
        Path resolvedDataRoot = dataRoot == null || !StringUtils.hasText(dataRoot.toString())
            ? magentaRoot.resolve("root")
            : (dataRoot.isAbsolute() ? dataRoot : magentaRoot.resolve(dataRoot));
        resolvedDataRoot = resolvedDataRoot.normalize();

        return new AiConfig(
            config.defaultAgent(),
            config.defaultModel(),
            config.summaryModel(),
            config.summeryModel(),
            config.planningModel(),
            config.compactionModel(),
            config.contextBufferPercent(),
            resolvedDataRoot,
            config.webSearch(),
            config.models(),
            config.agents(),
            config.unsafeAllowWildcardShellCommands()
        );
    }
}
