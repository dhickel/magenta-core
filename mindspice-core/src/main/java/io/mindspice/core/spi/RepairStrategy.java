package io.mindspice.core.spi;

import io.mindspice.core.NonNull;
import io.mindspice.core.TurnContext;
import io.mindspice.core.TurnMessage;

import java.util.List;

/**
 * Produces control messages when a model response is empty, incomplete,
 * or a tool loop guard trips.
 */
public interface RepairStrategy {

    @NonNull
    List<TurnMessage> repair(@NonNull TurnContext context, @NonNull RepairReason reason);

    enum RepairReason {
        EMPTY_RESPONSE,
        TOOL_LOOP_EXCEEDED,
        COMPLETION_NOT_DETECTED,
        CUSTOM
    }
}
