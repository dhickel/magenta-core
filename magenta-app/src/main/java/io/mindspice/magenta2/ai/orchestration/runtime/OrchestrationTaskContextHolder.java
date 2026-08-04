package io.mindspice.magenta2.ai.orchestration.runtime;

/**
 * Thread-local holder for the current {@link OrchestrationTaskContext}.
 * Set by the orchestration runner before model-backed task execution and
 * cleared in a finally block.
 */
public final class OrchestrationTaskContextHolder {
    private static final ThreadLocal<OrchestrationTaskContext> CONTEXT = new ThreadLocal<>();

    private OrchestrationTaskContextHolder() {
    }

    public static void set(OrchestrationTaskContext context) {
        CONTEXT.set(context);
    }

    public static OrchestrationTaskContext current() {
        return CONTEXT.get();
    }

    public static void recordActiveRuntimePath(String activeRuntimePath) {
        OrchestrationTaskContext current = CONTEXT.get();
        if (current != null && current.hasContext()) {
            CONTEXT.set(current.withActiveRuntimePath(activeRuntimePath));
        }
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
