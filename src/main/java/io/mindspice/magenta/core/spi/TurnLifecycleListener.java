package io.mindspice.magenta.core.spi;

import io.mindspice.magenta.core.NonNull;
import io.mindspice.magenta.core.TurnResult;

/**
 * Callback invoked after every completed turn.
 */
@FunctionalInterface
public interface TurnLifecycleListener {

    void onTurnCompleted(@NonNull TurnResult result);
}
