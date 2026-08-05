package io.mindspice.magenta2.ai.chat.service.turn;

import java.util.ArrayList;
import java.util.List;

import io.mindspice.magenta2.ai.chat.service.ResolvedChatRequest;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.StringUtils;

/**
 * Composes the system prompt for chat interactions.
 *
 * <p>Order conveys provenance; it grants no authority.
 */
public class PromptContextAssembler implements PromptContributor {

    private final AiConfig aiConfig;

    public PromptContextAssembler(AiConfig aiConfig) {
        this.aiConfig = aiConfig;
    }

    // ── PromptContributor SPI ──

    @Override
    public String contribute(TurnContext context) {
        return defaultSystemPrompt();
    }

    // ── Backward-compatible assemble entry point ──

    /**
     * Assembles the turn context without trusted policy.
     */
    public void assemble(TurnContext ctx) {
        ResolvedChatRequest request = ctx.resolvedRequest();
        String systemPrompt = defaultSystemPrompt();
        ctx.systemPrompt(systemPrompt);
        ctx.turnInstructions(assembleTurnInstructions(request, systemPrompt));
    }

    // ── Prompt composition ──

    /**
     * Returns the default system prompt for normal-mode chat.
     */
    public String mergeModePrompt(String conversationId) {
        return defaultSystemPrompt();
    }

    // ── Helpers ──

    /**
     * Builds the ordered instruction list: system message (if present)
     * + user message.
     */
    public List<Message> assembleTurnInstructions(
            ResolvedChatRequest request, String systemPrompt) {
        List<Message> messages = new ArrayList<>();
        if (StringUtils.hasText(systemPrompt)) {
            messages.add(new SystemMessage(systemPrompt));
        }
        messages.add(new UserMessage(request.message()));
        return messages;
    }

    public String defaultSystemPrompt() {
        if (aiConfig == null) {
            return null;
        }
        return null; // No agent config — system prompt comes from callers
    }
}
