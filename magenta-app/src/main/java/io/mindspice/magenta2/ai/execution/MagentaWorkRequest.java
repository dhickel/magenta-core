package io.mindspice.magenta2.ai.execution;

import java.util.concurrent.Callable;

public record MagentaWorkRequest<T>(
    MagentaWorkKind kind,
    String conversationId,
    int priority,
    String description,
    Callable<T> work
) {
    public static final int CHAT_PRIORITY = 100;
    public static final int DELEGATION_PRIORITY = 70;
    public static final int BACKGROUND_PRIORITY = 10;
}
