package io.mindspice.magenta2.ai.chat.service;

import java.util.List;
import java.util.Objects;

import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDescriptor;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind;

/**
 * Authoritative profile for a single chat invocation turn.
 *
 * <p>Normal chat usage flows through ordinary mode/prompt/tool resolution.
 * System chat is a read-only deny-by-default profile constructed positively
 * only by {@code SystemChatService} and carries no mutable configuration
 * from runtime settings, request fields, or persisted metadata.</p>
 */
public final class ChatInvocationProfile {

    private final String model;
    private final String systemPrompt;
    private final List<String> callbackNames;
    private final boolean systemChat;
    private final SystemChatProfile systemChatProfile;

    private ChatInvocationProfile(String model, String systemPrompt,
                                  List<String> callbackNames,
                                  SystemChatProfile systemChatProfile) {
        this.model = Objects.requireNonNull(model, "model");
        this.systemPrompt = systemPrompt;
        this.callbackNames = callbackNames == null ? List.of() : List.copyOf(callbackNames);
        this.systemChat = systemChatProfile != null;
        this.systemChatProfile = systemChatProfile;
    }

    /** Construct a normal (non-system) invocation profile. */
    public static ChatInvocationProfile normal(String model, String systemPrompt,
                                               List<String> callbackNames) {
        return new ChatInvocationProfile(model, systemPrompt, callbackNames, null);
    }

    /** Construct a system-chat profile. Only {@code SystemChatService} may call this. */
    static ChatInvocationProfile system(SystemChatProfile profile) {
        Objects.requireNonNull(profile, "systemChatProfile");
        return new ChatInvocationProfile(profile.model(), profile.fixedPrompt(),
                profile.fixedCallbackNames(), profile);
    }

    public String model() { return model; }
    public String systemPrompt() { return systemPrompt; }
    public List<String> callbackNames() { return callbackNames; }
    public boolean isSystemChat() { return systemChat; }
    public SystemChatProfile systemChatProfile() { return systemChatProfile; }

    /**
     * Immutable, deny-by-default system-chat profile.
     *
     * <p>Carries a server-resolved model, fixed prompt, fixed callback names,
     * immutable context binding, bound agent, and captured read scope.
     * No prompt, tool, skill, or fallback configuration from runtime settings,
     * request payloads, or conversation metadata is authoritative.
     * Disabled, missing, or unusable model capability is {@code UNAVAILABLE};
     * it never falls back to normal chat configuration.</p>
     */
    public record SystemChatProfile(
            String model,
            String fixedPrompt,
            List<String> fixedCallbackNames,
            String bindingKey,
            String agentId,
            int maxReadTokens,
            WorkspaceDescriptor agentDescriptor
    ) {
        public SystemChatProfile {
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(fixedPrompt, "fixedPrompt");
            Objects.requireNonNull(fixedCallbackNames, "fixedCallbackNames");
            Objects.requireNonNull(bindingKey, "bindingKey");
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(agentDescriptor, "agentDescriptor");
            fixedCallbackNames = List.copyOf(fixedCallbackNames);
            if (model.isBlank() || fixedPrompt.isBlank() || bindingKey.isBlank() || agentId.isBlank()) {
                throw new IllegalArgumentException("System-chat profile fields must be nonblank");
            }
            if (maxReadTokens < 1) {
                throw new IllegalArgumentException("maxReadTokens must be >= 1");
            }
            if (agentDescriptor.kind() != WorkspaceKind.AGENT || !agentId.equals(agentDescriptor.key())) {
                throw new IllegalArgumentException("agentDescriptor must be the bound AGENT descriptor");
            }
        }
    }
}
