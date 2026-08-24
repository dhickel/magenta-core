package io.mindspice.magenta.core;

import io.mindspice.magenta.core.NonNull;
import io.mindspice.magenta.core.Nullable;
import io.mindspice.magenta.core.ToolCallback;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.List;

/**
 * Internal mapping boundary to Spring AI. Public callers use Magenta-owned
 * model, request, and tool contracts instead of this interface.
 */
interface ModelRouter {

    @NonNull
    ChatModel springAiChatModel(@NonNull String modelAlias);

    @NonNull
    String remoteModelName(@NonNull String modelAlias);

    /** Resolves request → session → first-registration model precedence. */
    @NonNull
    String selectModelAlias(@Nullable String requestedAlias, @Nullable String sessionAlias);

    /** Returns the configured model context length, or zero when unspecified. */
    int contextLength(@NonNull String modelAlias);

    @NonNull
    ToolCallingChatOptions toolCallingOptions(@NonNull String modelAlias,
                                              @NonNull List<ToolCallback> tools);

    @NonNull
    ToolCallingChatOptions basicChatOptions(@NonNull String modelAlias);

    boolean supportsStreaming(@NonNull String modelAlias);

    /** Closes all owned model clients. Safe to call repeatedly. */
    void closeChatModels();
}
