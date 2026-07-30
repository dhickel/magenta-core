package io.mindspice.magenta2.ai.chat.project;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import io.mindspice.magenta2.ai.chat.repository.ChatSessionMetadataRepository;
import io.mindspice.magenta2.ai.chat.repository.ChatSessionMetadataRepository.ProjectChatSession;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationService;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationService.FilesAuthorization;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectOperationsAccessService;
import io.mindspice.magenta2.ai.orchestration.settings.RuntimeSettingsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Creates or verifies an immutable project-chat tuple without provisioning a
 * workspace or materialization. The current READY generation is captured once;
 * later generation changes select a different conversation rather than
 * retargeting the existing one.
 */
@Service
class ProjectChatProfilePreparationService {
    private final RuntimeSettingsService runtimeSettingsService;
    private final AgentMaterializationService materializationService;
    private final ProjectOperationsAccessService projectAccessService;
    private final ChatSessionMetadataRepository metadataRepository;
    private final ProjectChatToolRegistry toolRegistry;

    ProjectChatProfilePreparationService(RuntimeSettingsService runtimeSettingsService,
                                         AgentMaterializationService materializationService,
                                         ProjectOperationsAccessService projectAccessService,
                                         ChatSessionMetadataRepository metadataRepository,
                                         ProjectChatToolRegistry toolRegistry) {
        this.runtimeSettingsService = runtimeSettingsService;
        this.materializationService = materializationService;
        this.projectAccessService = projectAccessService;
        this.metadataRepository = metadataRepository;
        this.toolRegistry = toolRegistry;
    }

    @Transactional
    public ProjectChatService.ProjectChatResult prepare(String projectId, String memberAgentId) {
        if (!runtimeSettingsService.systemChatEnabled()
                || !StringUtils.hasText(projectId) || !StringUtils.hasText(memberAgentId)
                || !StringUtils.hasText(runtimeSettingsService.systemChatModel())) {
            return ProjectChatService.ProjectChatResult.unavailable();
        }

        FilesAuthorization authorization;
        try {
            if (!projectAccessService.isTrustedDeployment()) {
                return ProjectChatService.ProjectChatResult.unavailable();
            }
            projectAccessService.requireActiveProjectWithMember(projectId, memberAgentId);
            authorization = materializationService.authorizeProjectFiles(memberAgentId, projectId, 0);
        } catch (RuntimeException ignored) {
            return ProjectChatService.ProjectChatResult.unavailable();
        }
        if (!(authorization instanceof FilesAuthorization.Authorized authorized)) {
            return ProjectChatService.ProjectChatResult.unavailable();
        }

        String materializationKey = authorized.generation().projectKey();
        int generation = authorized.generation().generation();
        String conversationId = conversationId(projectId, memberAgentId, materializationKey, generation);
        String ownerBindingKey = "project-chat-owner:" + conversationId;
        ProjectChatSession session = new ProjectChatSession(conversationId, projectId, memberAgentId,
                materializationKey, generation, ownerBindingKey);
        if (!metadataRepository.createProjectChatSession(session)
                || !metadataRepository.findProjectChatSession(conversationId).filter(session::equals).isPresent()) {
            return ProjectChatService.ProjectChatResult.unavailable();
        }

        ProjectChatProfile profile = new ProjectChatProfile(runtimeSettingsService.systemChatModel(),
                ProjectChatService.FIXED_PROMPT, ProjectChatToolRegistry.FIXED_CALLBACK_NAMES,
                ownerBindingKey, memberAgentId, projectId, materializationKey, generation,
                ProjectChatService.FIXED_MAX_READ_CONTEXT);
        toolRegistry.requireFixedProfile(profile);
        return ProjectChatService.ProjectChatResult.available(profile, conversationId);
    }

    private static String conversationId(String projectId, String agentId, String materializationKey,
                                         int generation) {
        byte[] tuple = (projectId + "\u0000" + agentId + "\u0000" + materializationKey + "\u0000" + generation)
                .getBytes(StandardCharsets.UTF_8);
        return "project-chat:" + UUID.nameUUIDFromBytes(tuple);
    }
}
