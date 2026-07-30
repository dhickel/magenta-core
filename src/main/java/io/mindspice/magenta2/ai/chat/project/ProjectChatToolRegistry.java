package io.mindspice.magenta2.ai.chat.project;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationService;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectOperationsAccessService;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectOutputService;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectService;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Fixed positive allowlist of bounded, read-only project-chat callbacks. */
@Component
public class ProjectChatToolRegistry {
    public static final List<String> FIXED_CALLBACK_NAMES = List.of(
            "project_project_status", "project_member_fact", "project_assignment_fact",
            "project_job_fact", "project_schedule_fact", "project_reaction_fact",
            "project_output_fact", "project_transcript_fact");

    private final Map<String, ToolCallback> callbacks;

    public ProjectChatToolRegistry(JdbcTemplate jdbc, AgentMaterializationService materializationService,
                                   ProjectService projectService, ProjectOutputService projectOutputService,
                                   ProjectOperationsAccessService projectAccessService, ObjectMapper objectMapper) {
        ProjectChatBoundedReadService reads = new ProjectChatBoundedReadService(
                jdbc, materializationService, projectService, projectOutputService, projectAccessService, objectMapper);
        Map<String, ToolCallback> fixed = new LinkedHashMap<>();
        fixed.put("project_project_status", reads.projectStatusCallback());
        fixed.put("project_member_fact", reads.memberFactCallback());
        fixed.put("project_assignment_fact", reads.assignmentFactCallback());
        fixed.put("project_job_fact", reads.jobFactCallback());
        fixed.put("project_schedule_fact", reads.scheduleFactCallback());
        fixed.put("project_reaction_fact", reads.reactionFactCallback());
        fixed.put("project_output_fact", reads.outputFactCallback());
        fixed.put("project_transcript_fact", reads.transcriptFactCallback());
        callbacks = Map.copyOf(fixed);
        if (!callbacks.keySet().equals(java.util.Set.copyOf(FIXED_CALLBACK_NAMES))) {
            throw new IllegalStateException("Project-chat callback registry must contain the fixed allowlist only");
        }
    }

    List<ToolCallback> callbacksFor(ProjectChatProfile profile) {
        requireFixedProfile(profile);
        return FIXED_CALLBACK_NAMES.stream().map(callbacks::get).toList();
    }

    void requireFixedProfile(ProjectChatProfile profile) {
        if (profile == null || !FIXED_CALLBACK_NAMES.equals(profile.fixedCallbackNames())) {
            throw new IllegalArgumentException("Project-chat profile does not carry the fixed callback allowlist");
        }
    }
}
