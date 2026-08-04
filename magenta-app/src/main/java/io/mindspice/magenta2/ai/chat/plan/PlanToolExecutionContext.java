package io.mindspice.magenta2.ai.chat.plan;

public final class PlanToolExecutionContext {
    private static final ThreadLocal<PlanToolContext> CONTEXT = new ThreadLocal<>();

    private PlanToolExecutionContext() {
    }

    public static void set(PlanToolContext context) {
        CONTEXT.set(context);
    }

    public static PlanToolContext current() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
