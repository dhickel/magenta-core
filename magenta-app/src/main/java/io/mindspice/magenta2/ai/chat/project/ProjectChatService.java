package io.mindspice.magenta2.ai.chat.project;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import io.mindspice.magenta2.ai.chat.repository.ChatMemoryRepository;
import io.mindspice.magenta2.ai.chat.repository.ChatSessionMetadataRepository;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationService;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationService.FilesAuthorization;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectService;
import io.mindspice.magenta2.ai.chat.service.ChatModelRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Sole entry point for the server-created, fixed project-chat profile. */
@Service
public class ProjectChatService {
    private static final Logger log = LoggerFactory.getLogger(ProjectChatService.class);
    static final String FIXED_PROMPT = """
            You are a restricted project chat assistant. You may use only the supplied bounded,
            read-only project callbacks. You cannot write files, execute processes, activate or
            approve skills, install packages, access unrestricted files, or execute lifecycle actions.
            Never disclose credentials, tokens, absolute paths, raw failures, or claim/fence details.
            """.stripIndent();
    static final int FIXED_MAX_READ_CONTEXT = 16_000;
    private static final int MAX_MEMORY_MESSAGES = 24;

    private final ProjectChatProfilePreparationService preparationService;
    private final ChatModelRouter chatModelRouter;
    private final ProjectChatToolRegistry toolRegistry;
    private final ChatMemoryRepository memoryRepository;
    private final ChatSessionMetadataRepository metadataRepository;
    private final AgentMaterializationService materializationService;
    private final ProjectService projectService;
    private final ConcurrentHashMap<String, Object> conversationLocks = new ConcurrentHashMap<>();

    public ProjectChatService(ProjectChatProfilePreparationService preparationService,
                              ChatModelRouter chatModelRouter,
                              ProjectChatToolRegistry toolRegistry,
                              ChatMemoryRepository memoryRepository,
                              ChatSessionMetadataRepository metadataRepository,
                              AgentMaterializationService materializationService,
                              ProjectService projectService) {
        this.preparationService = preparationService;
        this.chatModelRouter = chatModelRouter;
        this.toolRegistry = toolRegistry;
        this.memoryRepository = memoryRepository;
        this.metadataRepository = metadataRepository;
        this.materializationService = materializationService;
        this.projectService = projectService;
    }

    public ProjectChatResult resolveProfile(String projectId, String memberAgentId) {
        try {
            return preparationService.prepare(projectId, memberAgentId);
        } catch (RuntimeException exception) {
            log.debug("Project-chat profile is unavailable");
            return ProjectChatResult.unavailable();
        }
    }

    /**
     * Runs one serialized turn outside a database transaction. Visible messages
     * are committed only after the profile tuple is revalidated.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public InvocationResult invoke(String projectId, String memberAgentId, String message) {
        if (!StringUtils.hasText(message) || message.length() > 16_000) {
            return InvocationResult.unavailable();
        }
        ProjectChatResult resolved = resolveProfile(projectId, memberAgentId);
        if (!resolved.available()) {
            return InvocationResult.unavailable();
        }
        Object lock = conversationLocks.computeIfAbsent(resolved.conversationId(), ignored -> new Object());
        synchronized (lock) {
            try {
                if (!revalidate(resolved)) {
                    return InvocationResult.unavailable();
                }
                ChatClient client = chatModelRouter.chatClient(resolved.profile().model());
                if (client == null) {
                    return InvocationResult.unavailable();
                }
                List<Message> promptMessages = promptMessages(resolved.conversationId(), message);
                ProjectChatProfileHolder.set(resolved.profile());
                String response = client.prompt().messages(promptMessages)
                        .toolCallbacks(toolRegistry.callbacksFor(resolved.profile())).call().content();
                if (!revalidate(resolved)) {
                    return InvocationResult.unavailable();
                }
                String safeResponse = redactModelResponse(response);
                persistVisibleTurn(resolved.conversationId(), message, safeResponse);
                return InvocationResult.available(resolved.conversationId(), safeResponse);
            } catch (RuntimeException exception) {
                log.debug("Project-chat invocation is unavailable", exception);
                return InvocationResult.unavailable();
            } finally {
                ProjectChatProfileHolder.clear();
            }
        }
    }

    public List<HistoryMessage> history(String projectId, String memberAgentId) {
        ProjectChatResult resolved = resolveProfile(projectId, memberAgentId);
        if (!resolved.available() || !revalidate(resolved)) {
            return List.of();
        }
        return memoryRepository.findByConversationId(resolved.conversationId()).stream()
                .filter(message -> message instanceof UserMessage || message instanceof AssistantMessage)
                .map(message -> new HistoryMessage(message instanceof UserMessage ? "user" : "assistant",
                        boundedText(message.getText())))
                .toList();
    }

    private boolean revalidate(ProjectChatResult result) {
        ProjectChatProfile profile = result.profile();
        if (profile == null || !metadataRepository.findProjectChatSession(result.conversationId())
                .filter(session -> session.projectId().equals(profile.projectId())
                        && session.memberAgentId().equals(profile.agentId())
                        && session.materializationKey().equals(profile.materializationKey())
                        && session.materializationGeneration() == profile.materializationGeneration()
                        && session.ownerBindingKey().equals(profile.ownerBindingKey()))
                .isPresent()) {
            return false;
        }
        try {
            projectService.requireActiveProjectWithMember(profile.projectId(), profile.agentId());
            FilesAuthorization authorization = materializationService.authorizeProjectFiles(
                    profile.agentId(), profile.projectId(), profile.materializationGeneration());
            return authorization instanceof FilesAuthorization.Authorized authorized
                    && profile.materializationKey().equals(authorized.generation().projectKey())
                    && profile.materializationGeneration() == authorized.generation().generation();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private List<Message> promptMessages(String conversationId, String message) {
        List<Message> stored = memoryRepository.findByConversationId(conversationId).stream()
                .filter(item -> item instanceof UserMessage || item instanceof AssistantMessage)
                .toList();
        int first = Math.max(0, stored.size() - MAX_MEMORY_MESSAGES);
        List<Message> messages = new ArrayList<>(stored.subList(first, stored.size()));
        messages.addFirst(new SystemMessage(FIXED_PROMPT));
        messages.add(new UserMessage(message));
        return messages;
    }

    private void persistVisibleTurn(String conversationId, String message, String response) {
        List<Message> messages = new ArrayList<>(memoryRepository.findByConversationId(conversationId));
        messages.add(new UserMessage(message));
        messages.add(new AssistantMessage(response));
        memoryRepository.saveAll(conversationId, messages);
    }

    private static String boundedText(String text) {
        if (text == null) return "";
        return text.length() <= 16_000 ? text : text.substring(0, 16_000);
    }

    private static String redactModelResponse(String response) {
        if (response == null) return "";
        return response.replaceAll("(?i)(?:bearer\\s+|token[=:\\s]+|api[_-]?key[=:\\s]+|password[=:\\s]+)[^\\s,;]+", "[redacted]")
                .replaceAll("(?i)[a-z][a-z0-9+.-]*://[^\\s]+", "[url]")
                .replaceAll("/(?:[A-Za-z0-9_.-]+/){2,}[A-Za-z0-9_.-]*", "[path]");
    }

    public record ProjectChatResult(ProjectChatProfile profile, String conversationId, boolean available) {
        static ProjectChatResult unavailable() { return new ProjectChatResult(null, null, false); }
        static ProjectChatResult available(ProjectChatProfile profile, String conversationId) {
            return new ProjectChatResult(profile, conversationId, true);
        }
    }
    public record InvocationResult(String conversationId, String response, boolean available) {
        static InvocationResult unavailable() { return new InvocationResult(null, null, false); }
        static InvocationResult available(String conversationId, String response) {
            return new InvocationResult(conversationId, response, true);
        }
    }
    public record HistoryMessage(String role, String text) { }
}
