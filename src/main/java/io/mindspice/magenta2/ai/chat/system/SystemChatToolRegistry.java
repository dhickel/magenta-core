package io.mindspice.magenta2.ai.chat.system;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.chat.service.ChatInvocationProfile;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceExplorerService;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Fixed positive allowlist of the four system-chat callbacks. */
@Component
public class SystemChatToolRegistry {
    public static final List<String> FIXED_CALLBACK_NAMES = List.of(
            "system_agent_status",
            "system_workspace_file_fact",
            "system_assignment_fact",
            "system_proposal_create");

    private final Map<String, ToolCallback> callbacks;

    public SystemChatToolRegistry(JdbcTemplate jdbc,
                                  WorkspaceExplorerService workspaceExplorerService,
                                  WorkspaceDirectoryService workspaceDirectoryService,
                                  AgentContextService agentContextService,
                                  ObjectMapper objectMapper,
                                  SystemChatActionProposalService proposalService) {
        SystemChatBoundedReadService reads = new SystemChatBoundedReadService(
                jdbc, workspaceExplorerService, workspaceDirectoryService, agentContextService, objectMapper);
        Map<String, ToolCallback> fixed = new LinkedHashMap<>();
        fixed.put("system_agent_status", reads.agentStatusCallback());
        fixed.put("system_workspace_file_fact", reads.workspaceFileFactCallback());
        fixed.put("system_assignment_fact", reads.assignmentFactCallback());
        fixed.put("system_proposal_create", Objects.requireNonNull(proposalService).proposalCreateCallback());
        this.callbacks = Map.copyOf(fixed);
        if (!callbacks.keySet().equals(java.util.Set.copyOf(FIXED_CALLBACK_NAMES))) {
            throw new IllegalStateException("System-chat callback registry must contain the fixed allowlist only");
        }
    }

    List<ToolCallback> callbacksFor(ChatInvocationProfile.SystemChatProfile profile) {
        requireFixedProfile(profile);
        return FIXED_CALLBACK_NAMES.stream().map(callbacks::get).toList();
    }

    void requireFixedProfile(ChatInvocationProfile.SystemChatProfile profile) {
        if (profile == null || !FIXED_CALLBACK_NAMES.equals(profile.fixedCallbackNames())) {
            throw new IllegalArgumentException("System-chat profile does not carry the fixed callback allowlist");
        }
    }
}
