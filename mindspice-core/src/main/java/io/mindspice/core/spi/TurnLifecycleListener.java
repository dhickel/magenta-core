package io.mindspice.core.spi;

import io.mindspice.core.NonNull;
import io.mindspice.core.TurnResult;

/**
 * Callback invoked after every completed turn.
 */
@FunctionalInterface
public interface TurnLifecycleListener {

    void onTurnCompleted(@NonNull TurnResult result);
}
