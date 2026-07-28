package io.mindspice.magenta2.ai.orchestration.settings;

import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.config.user.ModelConfig;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RuntimeSettingsService {
    private final RuntimeSettingsRepository repository;
    private final AiConfig aiConfig;
    private final AgentProfileService agentProfileService;

    public RuntimeSettingsService(
        RuntimeSettingsRepository repository,
        AiConfig aiConfig,
        AgentProfileService agentProfileService
    ) {
        this.repository = repository;
        this.aiConfig = aiConfig;
        this.agentProfileService = agentProfileService;
    }

    public RuntimeSettings get() {
        return repository.find().orElseGet(() -> new RuntimeSettings(
            null,
            legacyDefaultAgentName(),
            aiConfig.resolvedDefaultModelKey(),
            aiConfig.resolvedPlanningModelKey(),
            aiConfig.resolvedSummaryModelKey(),
            aiConfig.resolvedCompactionModelKey(),
            aiConfig.resolvedContextBufferPercent(),
            null,
            null,
            null,
            null,
            true,
            -1,
            false
        ));
    }

    public RuntimeSettings save(RuntimeSettings settings) {
        validateModel(settings.defaultModel(), "defaultModel");
        validateModel(settings.planningModel(), "planningModel");
        validateModel(settings.summaryModel(), "summaryModel");
        validateModel(settings.compactionModel(), "compactionModel");
        validateModel(settings.systemChatModel(), "systemChatModel");
        Integer buffer = settings.contextBufferPercent();
        if (buffer != null && (buffer < 1 || buffer > 50)) {
            throw new IllegalArgumentException("contextBufferPercent must be between 1 and 50");
        }
        Integer systemChatLimit = settings.systemChatContextLimit();
        if (systemChatLimit != null && (systemChatLimit < 1 || systemChatLimit > 100)) {
            throw new IllegalArgumentException("systemChatContextLimit must be between 1 and 100");
        }
        Integer purgeDays = settings.assignmentHistoryAutoPurgeDays();
        if (purgeDays != null && purgeDays != -1 && purgeDays < 1) {
            throw new IllegalArgumentException("assignmentHistoryAutoPurgeDays must be -1 or at least 1");
        }
        if (StringUtils.hasText(settings.defaultAgentId())) {
            agentProfileService.get(settings.defaultAgentId());
        }
        return repository.save(settings);
    }

    public String resolveModel(String explicitRequestModel) {
        return remoteModelName(resolveModelKey(explicitRequestModel, null));
    }

    public String resolveModel(String explicitRequestModel, String agentDefaultModel) {
        return remoteModelName(resolveModelKey(explicitRequestModel, agentDefaultModel));
    }

    public String defaultModel() {
        return resolveModel(null);
    }

    public String planningModel() {
        RuntimeSettings settings = get();
        String key = StringUtils.hasText(settings.planningModel())
            ? settings.planningModel()
            : aiConfig.resolvedPlanningModelKey();
        return remoteModelName(key);
    }

    public String compactionModel() {
        RuntimeSettings settings = get();
        String key = StringUtils.hasText(settings.compactionModel())
            ? settings.compactionModel()
            : (StringUtils.hasText(settings.summaryModel())
                ? settings.summaryModel()
                : aiConfig.resolvedCompactionModelKey());
        return remoteModelName(key);
    }

    public String summaryModel() {
        RuntimeSettings settings = get();
        String key = StringUtils.hasText(settings.summaryModel())
            ? settings.summaryModel()
            : aiConfig.resolvedSummaryModelKey();
        return remoteModelName(key);
    }

    public int contextBufferPercent() {
        Integer configured = get().contextBufferPercent();
        return configured == null ? aiConfig.resolvedContextBufferPercent() : configured;
    }

    public String defaultSystemPrompt() {
        RuntimeSettings settings = get();
        try {
            return agentProfileService.systemPrompt(settings.defaultAgentId(), settings.defaultAgentName());
        } catch (IllegalStateException exception) {
            String agentName = legacyDefaultAgentName();
            return aiConfig.agents() == null || aiConfig.agents().get(agentName) == null
                ? null
                : aiConfig.agents().get(agentName).systemPrompt();
        }
    }

    /**
     * Resolves only the explicitly configured system-chat model. Normal chat
     * defaults are deliberately not a fallback authority for this capability.
     */
    public String systemChatModel() {
        String key = get().systemChatModel();
        return StringUtils.hasText(key) ? remoteModelName(key) : null;
    }

    public boolean systemChatEnabled() {
        Boolean enabled = get().systemChatEnabled();
        return enabled == null || enabled;
    }

    public boolean retainTempWork() {
        return Boolean.TRUE.equals(get().retainTempWork());
    }

    public java.util.List<String> approvedTools() {
        RuntimeSettings settings = get();
        try {
            return agentProfileService.approvedTools(settings.defaultAgentId(), settings.defaultAgentName());
        } catch (IllegalStateException exception) {
            String agentName = legacyDefaultAgentName();
            return aiConfig.agents() == null || aiConfig.agents().get(agentName) == null
                ? java.util.List.of()
                : aiConfig.agents().get(agentName).approvedTools();
        }
    }

    public java.util.List<String> allowedShellCommands() {
        RuntimeSettings settings = get();
        try {
            return agentProfileService.allowedShellCommands(settings.defaultAgentId(), settings.defaultAgentName());
        } catch (IllegalStateException exception) {
            String agentName = legacyDefaultAgentName();
            return aiConfig.agents() == null || aiConfig.agents().get(agentName) == null
                ? java.util.List.of()
                : aiConfig.agents().get(agentName).allowedShellCommands();
        }
    }

    public AgentProfile defaultAgentProfile() {
        RuntimeSettings settings = get();
        return agentProfileService.defaultAgent(settings.defaultAgentId(), settings.defaultAgentName());
    }

    private String resolveModelKey(String explicitRequestModel, String agentDefaultModel) {
        if (StringUtils.hasText(explicitRequestModel)) {
            return keyForModelOrRemoteName(explicitRequestModel);
        }
        if (StringUtils.hasText(agentDefaultModel)) {
            return agentDefaultModel;
        }
        RuntimeSettings settings = get();
        if (StringUtils.hasText(settings.defaultModel())) {
            return settings.defaultModel();
        }
        if (StringUtils.hasText(aiConfig.resolvedDefaultModelKey())) {
            return aiConfig.resolvedDefaultModelKey();
        }
        return legacyDefaultModelKey();
    }

    private String remoteModelName(String modelReference) {
        ModelConfig model = aiConfig.models().get(modelReference);
        if (model != null) {
            return model.remoteModelName();
        }
        boolean configuredRemoteName = aiConfig.models().values().stream()
            .anyMatch(config -> modelReference.equals(config.remoteModelName()));
        if (configuredRemoteName) {
            return modelReference;
        }
        throw new IllegalArgumentException("Unknown configured model: " + modelReference);
    }

    private String keyForModelOrRemoteName(String value) {
        if (aiConfig.models().containsKey(value)) {
            return value;
        }
        return aiConfig.models().entrySet().stream()
            .filter(entry -> value.equals(entry.getValue().remoteModelName()))
            .map(java.util.Map.Entry::getKey)
            .findFirst()
            .orElse(value);
    }

    private String legacyDefaultModelKey() {
        String agentName = legacyDefaultAgentName();
        if (StringUtils.hasText(agentName) && aiConfig.agents() != null && aiConfig.agents().get(agentName) != null) {
            return aiConfig.agents().get(agentName).model();
        }
        return aiConfig.resolvedSummaryModelKey();
    }

    private String legacyDefaultAgentName() {
        return StringUtils.hasText(aiConfig.defaultAgent()) ? aiConfig.defaultAgent() : "magenta";
    }

    private void validateModel(String modelKey, String field) {
        if (StringUtils.hasText(modelKey) && !aiConfig.models().containsKey(modelKey)) {
            throw new IllegalArgumentException(field + " references missing model: " + modelKey);
        }
    }
}
