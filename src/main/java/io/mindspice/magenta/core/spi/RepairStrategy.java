package io.mindspice.magenta.core.spi;

import io.mindspice.magenta.core.NonNull;
import io.mindspice.magenta.core.TurnContext;
import io.mindspice.magenta.core.TurnMessage;

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
