package io.mindspice.magenta2.api.web;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.mindspice.magenta2.ai.chat.project.ProjectChatService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectAgentMembership;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletResponse;

/** Thin project-scoped adapter for the restricted project-chat profile. */
@Controller
@RequestMapping("/api/projects/{projectId}/chat")
class ProjectChatController {
    private static final Logger log = LoggerFactory.getLogger(ProjectChatController.class);
    private final ProjectService projectService;
    private final AgentProfileService agentProfileService;
    private final ProjectChatService projectChatService;

    ProjectChatController(ProjectService projectService, AgentProfileService agentProfileService,
                          ProjectChatService projectChatService) {
        this.projectService = projectService;
        this.agentProfileService = agentProfileService;
        this.projectChatService = projectChatService;
    }

    @GetMapping("/panel")
    @ResponseBody
    public String panel(@PathVariable String projectId, @RequestParam String memberAgentId,
                        HttpServletResponse response) {
        if (!projectExists(projectId)) {
            response.setStatus(404);
            return ProjectChatFragments.panel(projectId, memberAgentId, null, false);
        }
        try {
            var result = projectChatService.resolveProfile(projectId, memberAgentId);
            return ProjectChatFragments.panel(projectId, memberAgentId, result.conversationId(), result.available());
        } catch (RuntimeException exception) {
            response.setStatus(503);
            return ProjectChatFragments.panel(projectId, memberAgentId, null, false);
        }
    }

    @GetMapping("/panel/history")
    @ResponseBody
    public String history(@PathVariable String projectId, @RequestParam String memberAgentId,
                          HttpServletResponse response) {
        if (!projectExists(projectId)) {
            response.setStatus(404);
            return ProjectChatFragments.history(projectId, memberAgentId, List.of());
        }
        try {
            return ProjectChatFragments.history(projectId, memberAgentId,
                    projectChatService.history(projectId, memberAgentId).stream()
                            .map(message -> new ProjectChatFragments.MessageRecord(
                                    message.role(), message.text(), null)).toList());
        } catch (RuntimeException exception) {
            response.setStatus(503);
            return ProjectChatFragments.history(projectId, memberAgentId, List.of());
        }
    }

    @PostMapping("/stream")
    @ResponseBody
    public SseEmitter stream(@PathVariable String projectId, @RequestParam String memberAgentId,
                             @RequestParam String message) {
        SseEmitter emitter = new SseEmitter(300_000L);
        if (!projectExists(projectId) || !StringUtils.hasText(message) || message.length() > 16_000) {
            return completeWithError(emitter, "project_chat_unavailable");
        }
        var profile = projectChatService.resolveProfile(projectId, memberAgentId);
        if (!profile.available()) {
            return completeWithError(emitter, "project_chat_unavailable");
        }
        String operationId = UUID.randomUUID().toString();
        try {
            emitter.send(SseEmitter.event().name("start").data(Map.of(
                    "projectId", projectId, "memberAgentId", memberAgentId,
                    "conversationId", profile.conversationId(), "operationId", operationId)));
            var invocation = projectChatService.invoke(projectId, memberAgentId, message);
            if (!invocation.available() || !profile.conversationId().equals(invocation.conversationId())) {
                emitter.send(SseEmitter.event().name("error").data(eventIdentity(profile.conversationId(), operationId)));
            } else {
                emitter.send(SseEmitter.event().name("message").data(Map.of(
                        "role", "assistant", "text", invocation.response(),
                        "conversationId", profile.conversationId(), "operationId", operationId)));
                emitter.send(SseEmitter.event().name("done").data(eventIdentity(profile.conversationId(), operationId)));
            }
            emitter.complete();
        } catch (IOException exception) {
            log.debug("Project-chat SSE stream interrupted for project {}", projectId);
            emitter.completeWithError(exception);
        } catch (RuntimeException exception) {
            try {
                emitter.send(SseEmitter.event().name("error").data(eventIdentity(profile.conversationId(), operationId)));
                emitter.complete();
            } catch (IOException ignored) {
                emitter.completeWithError(exception);
            }
        }
        return emitter;
    }

    @GetMapping("/members")
    @ResponseBody
    public String memberSelector(@PathVariable String projectId,
                                 @RequestParam(required = false) String memberAgentId,
                                 HttpServletResponse response) {
        if (!projectExists(projectId)) {
            response.setStatus(404);
            return new io.mindspice.simplypages.components.Div().withClass("orch-error")
                    .withInnerText("Project not found.").render();
        }
        try {
            List<ProjectChatFragments.MemberOption> options = projectService.listMembers(projectId).stream()
                    .sorted(Comparator.comparing(ProjectAgentMembership::agentId,
                            Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                    .map(this::memberOption).toList();
            String selected = StringUtils.hasText(memberAgentId) ? memberAgentId
                    : (options.isEmpty() ? "" : options.getFirst().agentId());
            return ProjectChatFragments.memberSelector(projectId, selected, options);
        } catch (RuntimeException exception) {
            response.setStatus(503);
            return new io.mindspice.simplypages.components.Div().withClass("orch-error")
                    .withInnerText("Member list is temporarily unavailable.").render();
        }
    }

    private ProjectChatFragments.MemberOption memberOption(ProjectAgentMembership membership) {
        String displayName = membership.agentId();
        AgentProfile profile = agentProfileService.get(membership.agentId());
        if (profile != null && StringUtils.hasText(profile.name())) {
            displayName = profile.name() + " (" + membership.agentId() + ")";
        }
        return new ProjectChatFragments.MemberOption(membership.agentId(), displayName);
    }

    private boolean projectExists(String projectId) {
        try {
            return !projectService.getProject(projectId).archived();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static Map<String, Object> eventIdentity(String conversationId, String operationId) {
        return Map.of("reason", "project_chat_unavailable", "conversationId", conversationId,
                "operationId", operationId);
    }

    private static SseEmitter completeWithError(SseEmitter emitter, String reason) {
        try {
            emitter.send(SseEmitter.event().name("error").data(Map.of("reason", reason)));
        } catch (IOException ignored) {
            // Client disconnects are terminal.
        }
        emitter.complete();
        return emitter;
    }
}
