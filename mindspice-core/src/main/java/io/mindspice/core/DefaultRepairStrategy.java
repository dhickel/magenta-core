package io.mindspice.core;

import io.mindspice.core.spi.RepairStrategy;

import java.util.List;

/**
 * Default {@link RepairStrategy} that only handles {@link RepairReason#EMPTY_RESPONSE}
 * by injecting a control message asking the model to produce a visible response.
 *
 * <p>All other repair reasons produce an empty list (the engine handles
 * them internally via its own repair path).</p>
 */
public final class DefaultRepairStrategy implements RepairStrategy {

    private static final DefaultRepairStrategy INSTANCE = new DefaultRepairStrategy();

    private DefaultRepairStrategy() {}

    /** Returns the singleton instance. */
    public static DefaultRepairStrategy instance() {
        return INSTANCE;
    }

    @Override
    @NonNull
    public List<TurnMessage> repair(@NonNull TurnContext context, @NonNull RepairReason reason) {
        return switch (reason) {
            case EMPTY_RESPONSE -> List.of(TurnMessage.system(
                "Your previous response had no user-visible message and no tool calls. "
                + "Continue the turn now with a concise user-visible answer or an appropriate tool call. "
                + "Do not return an empty assistant message."
            ));
            case TOOL_LOOP_EXCEEDED -> List.of(TurnMessage.system(
                "Tool loop guard triggered. Continue without further tool calls."
            ));
            default -> List.of();
        };
    }
}
