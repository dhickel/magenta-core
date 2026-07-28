package io.mindspice.magenta2.ai.chat.service.turn;

import io.mindspice.magenta2.ai.chat.model.ChatMessage;
import io.mindspice.magenta2.ai.chat.model.ChatToolActivity;
import io.mindspice.magenta2.ai.chat.model.ContextUsage;
import io.mindspice.magenta2.ai.chat.model.PlanMode;
import io.mindspice.magenta2.ai.chat.service.ResolvedChatRequest;
import io.mindspice.magenta2.ai.execution.ActiveTurnRegistry.ActiveTurn;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Mutable carrier holding all data flowing between turn execution stages.
 * One instance per turn. Not thread-safe by design — single-threaded turn execution.
 */
public class TurnContext {

    // ── Inputs (set during assembly) ──
    private ResolvedChatRequest resolvedRequest;
    private ActiveTurn activeTurn;
    private PlanMode interactionMode;
    private String systemPrompt;
    private List<Message> turnInstructions;
    private List<ToolCallback> approvedTools;
    private boolean toolsEnabled;

    // ── Trusted context state ──
    private boolean terminalBindingFailure;
    private String terminalBindingReason;

    // ── Tool loop state (set during execution) ──
    private List<Message> conversationHistory;
    private List<Message> activeToolMessages;
    private List<Message> messagesToPersist;
    private List<ChatToolActivity> toolActivities;
    private List<String> thinkingParts;
    private boolean compactionNoticeEmitted;
    private boolean planCompletionDetected;
    private String validatedFinalMessage;
    private String forcedPlanningQuestion;
    private AssistantMessage finalAssistantMessage;
    private ContextUsage storedContextUsage;

    public TurnContext() {
        this.toolActivities = new ArrayList<>();
        this.thinkingParts = new ArrayList<>();
        this.messagesToPersist = new ArrayList<>();
        this.activeToolMessages = new ArrayList<>();
    }

    // ── Trusted context accessors ──

    public boolean terminalBindingFailure() { return terminalBindingFailure; }
    public void terminalBindingFailure(boolean v) { this.terminalBindingFailure = v; }

    public String terminalBindingReason() { return terminalBindingReason; }
    public void terminalBindingReason(String v) { this.terminalBindingReason = v; }

    // ── Getters and setters ──

    public ResolvedChatRequest resolvedRequest() { return resolvedRequest; }
    public void resolvedRequest(ResolvedChatRequest v) { this.resolvedRequest = v; }

    public ActiveTurn activeTurn() { return activeTurn; }
    public void activeTurn(ActiveTurn v) { this.activeTurn = v; }

    public PlanMode interactionMode() { return interactionMode; }
    public void interactionMode(PlanMode v) { this.interactionMode = v; }

    public String systemPrompt() { return systemPrompt; }
    public void systemPrompt(String v) { this.systemPrompt = v; }

    public List<Message> turnInstructions() { return turnInstructions; }
    public void turnInstructions(List<Message> v) { this.turnInstructions = v; }

    public List<ToolCallback> approvedTools() { return approvedTools; }
    public void approvedTools(List<ToolCallback> v) { this.approvedTools = v; }

    public boolean toolsEnabled() { return toolsEnabled; }
    public void toolsEnabled(boolean v) { this.toolsEnabled = v; }

    public List<Message> conversationHistory() { return conversationHistory; }
    public void conversationHistory(List<Message> v) { this.conversationHistory = v; }

    public List<Message> activeToolMessages() { return activeToolMessages; }
    public void activeToolMessages(List<Message> v) { this.activeToolMessages = v; }

    public List<Message> messagesToPersist() { return messagesToPersist; }
    public void messagesToPersist(List<Message> v) { this.messagesToPersist = v; }

    public List<ChatToolActivity> toolActivities() { return toolActivities; }
    public void toolActivities(List<ChatToolActivity> v) { this.toolActivities = v; }

    public List<String> thinkingParts() { return thinkingParts; }
    public void thinkingParts(List<String> v) { this.thinkingParts = v; }

    public boolean compactionNoticeEmitted() { return compactionNoticeEmitted; }
    public void compactionNoticeEmitted(boolean v) { this.compactionNoticeEmitted = v; }

    public boolean planCompletionDetected() { return planCompletionDetected; }
    public void planCompletionDetected(boolean v) { this.planCompletionDetected = v; }

    public String validatedFinalMessage() { return validatedFinalMessage; }
    public void validatedFinalMessage(String v) { this.validatedFinalMessage = v; }

    public String forcedPlanningQuestion() { return forcedPlanningQuestion; }
    public void forcedPlanningQuestion(String v) { this.forcedPlanningQuestion = v; }

    public AssistantMessage finalAssistantMessage() { return finalAssistantMessage; }
    public void finalAssistantMessage(AssistantMessage v) { this.finalAssistantMessage = v; }

    public ContextUsage storedContextUsage() { return storedContextUsage; }
    public void storedContextUsage(ContextUsage v) { this.storedContextUsage = v; }

    // ── Convenience ──

    public String conversationId() { return resolvedRequest.conversationId(); }
    public String model() { return resolvedRequest.model(); }

    public List<Message> systemInstructions() {
        return turnInstructions.stream()
            .filter(SystemMessage.class::isInstance)
            .collect(java.util.stream.Collectors.toList());
    }
}
