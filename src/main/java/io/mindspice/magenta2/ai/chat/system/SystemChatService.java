package io.mindspice.magenta2.ai.chat.system;

import io.mindspice.magenta2.ai.chat.service.ChatInvocationProfile;
import io.mindspice.magenta2.ai.chat.service.ChatModelRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Sole entry point for the server-created, fixed system-chat profile.
 *
 * <p>Preparation is delegated to {@link SystemChatProfilePreparationService}
 * so it commits (or rolls back) in a dedicated transaction before
 * transaction-free model inference begins.</p>
 */
@Service
public class SystemChatService {
    private static final Logger log = LoggerFactory.getLogger(SystemChatService.class);

    static final String FIXED_PROMPT = """
            You are a restricted system chat assistant. Your only capabilities are:
            - Read bounded agent status, workspace file facts, and assignment lifecycle facts.
            - Create non-executing lifecycle action proposals (PAUSE, RESUME, RETRY, REPLAY, CANCEL).

            You cannot write files, execute shell commands or processes, approve or activate
            skills, access other agents, or execute any lifecycle action directly. Every
            response must be secret-free: no credentials, tokens, absolute paths, raw failure
            details, or internal claim/fence owner identifiers.

            When you recommend a lifecycle action, you must call the proposal tool. You cannot
            confirm or execute a proposal; only an explicit user confirmation does that.
            """.stripIndent();
    static final int FIXED_MAX_READ_CONTEXT = 16_000;

    private final SystemChatProfilePreparationService preparationService;
    private final ChatModelRouter chatModelRouter;
    private final SystemChatToolRegistry toolRegistry;

    public SystemChatService(SystemChatProfilePreparationService preparationService,
                             ChatModelRouter chatModelRouter,
                             SystemChatToolRegistry toolRegistry) {
        this.preparationService = preparationService;
        this.chatModelRouter = chatModelRouter;
        this.toolRegistry = toolRegistry;
    }

    /**
     * Resolves a profile by delegating to the transactional preparation
     * collaborator. A returned unavailable result or an escaped exception
     * from the collaborator's transactional boundary both yield unavailable.
     */
    public SystemChatResult resolveProfile(String agentId) {
        try {
            return preparationService.prepare(agentId);
        } catch (RuntimeException exception) {
            log.debug("System-chat profile is unavailable");
            return SystemChatResult.unavailable();
        }
    }

    /**
     * Runs a turn without normal chat memory, prompt, skills, or tool resolution.
     * This method deliberately owns the profile holder lifetime so direct callbacks
     * have no caller-supplied authority path.
     *
     * <p>Marked {@code NOT_SUPPORTED} so no SQLite transaction encloses client
     * acquisition, callback execution, or model/provider work, even under an
     * ambient caller transaction. Preparation committed (or rolled back) in its
     * own transaction before inference begins.</p>
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public InvocationResult invoke(String agentId, String message) {
        try {
            if (!StringUtils.hasText(agentId) || !StringUtils.hasText(message)) {
                return InvocationResult.unavailable();
            }
            SystemChatResult resolved = resolveProfile(agentId);
            if (!resolved.available()) {
                return InvocationResult.unavailable();
            }
            ChatClient client = chatModelRouter.chatClient(resolved.profile().model());
            if (client == null) {
                return InvocationResult.unavailable();
            }
            SystemChatProfileHolder.set(resolved.profile());
            String response = client.prompt()
                    .system(resolved.profile().fixedPrompt())
                    .user(message)
                    .tools(toolRegistry.callbacksFor(resolved.profile()))
                    .call()
                    .content();
            return InvocationResult.available(resolved.conversationId(), redactModelResponse(response));
        } catch (RuntimeException exception) {
            log.debug("System-chat invocation is unavailable");
            return InvocationResult.unavailable();
        } finally {
            SystemChatProfileHolder.clear();
        }
    }

    private static String redactModelResponse(String response) {
        if (response == null) {
            return "";
        }
        return response
                .replaceAll("(?i)(?:bearer\\s+|token[=:\\s]+|api[_-]?key[=:\\s]+|password[=:\\s]+)[^\\s,;]+", "[redacted]")
                .replaceAll("(?i)[a-z][a-z0-9+.-]*://[^\\s]+", "[url]")
                .replaceAll("/(?:[A-Za-z0-9_.-]+/){2,}[A-Za-z0-9_.-]*", "[path]");
    }

    public record SystemChatResult(ChatInvocationProfile.SystemChatProfile profile,
                                   String conversationId, boolean available) {
        static SystemChatResult unavailable() { return new SystemChatResult(null, null, false); }
        static SystemChatResult available(ChatInvocationProfile.SystemChatProfile profile, String conversationId) {
            return new SystemChatResult(profile, conversationId, true);
        }
    }

    public record InvocationResult(String conversationId, String response, boolean available) {
        static InvocationResult unavailable() { return new InvocationResult(null, null, false); }
        static InvocationResult available(String conversationId, String response) {
            return new InvocationResult(conversationId, response, true);
        }
    }
}
