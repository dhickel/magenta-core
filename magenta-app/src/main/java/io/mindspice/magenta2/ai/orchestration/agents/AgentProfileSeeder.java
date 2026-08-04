package io.mindspice.magenta2.ai.orchestration.agents;

import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import io.mindspice.magenta2.ai.config.user.AgentConfig;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.orchestration.settings.RuntimeSettings;
import io.mindspice.magenta2.ai.orchestration.settings.RuntimeSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AgentProfileSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AgentProfileSeeder.class);

    private final AgentProfileRepository repository;
    private final AgentProfileService service;
    private final RuntimeSettingsRepository settingsRepository;
    private final AiConfig aiConfig;

    public AgentProfileSeeder(
        AgentProfileRepository repository,
        AgentProfileService service,
        RuntimeSettingsRepository settingsRepository,
        AiConfig aiConfig
    ) {
        this.repository = repository;
        this.service = service;
        this.settingsRepository = settingsRepository;
        this.aiConfig = aiConfig;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!repository.isEmpty()) {
            return;
        }
        String id = UUID.randomUUID().toString();
        String legacyName = StringUtils.hasText(aiConfig.defaultAgent()) ? aiConfig.defaultAgent() : "magenta";
        AgentConfig legacy = aiConfig.agents() == null ? null : aiConfig.agents().get(legacyName);
        String model = legacy != null && StringUtils.hasText(legacy.model())
            ? legacy.model()
            : (StringUtils.hasText(aiConfig.resolvedDefaultModelKey())
                ? aiConfig.resolvedDefaultModelKey()
                : aiConfig.resolvedSummaryModelKey());
        String prompt = legacy == null ? fallbackPrompt() : legacy.systemPrompt();
        AgentProfile seeded = service.create(new AgentProfile(
            id,
            "magenta",
            AgentProfileStatus.ACTIVE,
            model,
            prompt,
            legacyApprovedTools(legacy),
            legacyAllowedShellCommands(legacy),
            true,
            null,
            null
        ));
        settingsRepository.save(new RuntimeSettings(
            seeded.id(),
            seeded.name(),
            model,
            aiConfig.resolvedPlanningModelKey(),
            aiConfig.resolvedSummaryModelKey(),
            aiConfig.resolvedCompactionModelKey(),
            aiConfig.resolvedContextBufferPercent()
        ));
    }

    private List<String> legacyApprovedTools(AgentConfig legacy) {
        if (legacy == null || legacy.approvedTools() == null) {
            return List.of();
        }
        if (legacy.approvedTools().contains("*")) {
            log.warn("Ignoring legacy wildcard approvedTools during agent profile seeding; configure explicit tools instead");
            return legacy.approvedTools().stream()
                .filter(tool -> !"*".equals(tool))
                .toList();
        }
        return legacy.approvedTools();
    }

    private List<String> legacyAllowedShellCommands(AgentConfig legacy) {
        if (legacy == null || legacy.allowedShellCommands() == null) {
            return List.of();
        }
        if (legacy.allowedShellCommands().contains("*") && !aiConfig.unsafeAllowWildcardShellCommandsEnabled()) {
            log.warn("Ignoring legacy wildcard allowedShellCommands during agent profile seeding; set unsafeAllowWildcardShellCommands=true to opt in");
            return legacy.allowedShellCommands().stream()
                .filter(command -> !"*".equals(command))
                .toList();
        }
        return legacy.allowedShellCommands();
    }

    private String fallbackPrompt() {
        java.nio.file.Path prompt = java.nio.file.Path.of("config/prompts/system.md");
        if (Files.isRegularFile(prompt)) {
            try {
                return Files.readString(prompt);
            } catch (java.io.IOException ignored) {
            }
        }
        return "You are Magenta, a helpful operational assistant.";
    }
}
