package io.mindspice.core.internal;

import io.mindspice.core.NonNull;
import io.mindspice.core.ToolCallback;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.List;

/**
 * Framework-internal mapping boundary to Spring AI.
 * This is the <strong>sole</strong> point where Spring AI types appear in the
 * framework. The interface is package-private so embedding applications never
 * see Spring AI types through any public API surface.
 */
public interface ModelRouter {

    @NonNull
    ChatModel springAiChatModel(@NonNull String modelAlias);

    @NonNull
    String remoteModelName(@NonNull String modelAlias);

    @NonNull
    ToolCallingChatOptions toolCallingOptions(@NonNull String modelAlias,
                                              @NonNull List<ToolCallback> tools);

    @NonNull
    ToolCallingChatOptions basicChatOptions(@NonNull String modelAlias);

    boolean supportsStreaming(@NonNull String modelAlias);
}
