package io.mindspice.magenta2.ai.chat.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Owns prompt assembly for agent-initiated chat interactions.
 * Extracted from AgentOrchestrationController to keep prompt construction
 * in the domain layer, consistent with how ChatService handles plan/task prompts.
 */
@Service
public class AgentChatPromptService {
    static final String DEFAULT_PAGE_CONTEXT = "orchestration page";

    public String buildPrompt(String pageContext, String message) {
        String effectiveContext = (pageContext == null || pageContext.isBlank())
            ? DEFAULT_PAGE_CONTEXT
            : pageContext;
        return "Agent page context: " + effectiveContext + "\n\n" + message;
    }
}
