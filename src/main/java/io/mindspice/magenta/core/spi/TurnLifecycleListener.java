package io.mindspice.magenta.core.spi;

import io.mindspice.magenta.core.NonNull;
import io.mindspice.magenta.core.TurnFailure;
import io.mindspice.magenta.core.TurnResponse;

/**
 * Observes expected terminal outcomes and unexpected execution failures.
 * Listener exceptions are isolated by the framework and do not alter the
 * caller-visible result.
 */
@FunctionalInterface
public interface TurnLifecycleListener {

    /** Called exactly once for each expected terminal outcome. */
    void onOutcome(@NonNull TurnResponse.Outcome outcome);

    /** Called exactly once for each unexpected execution failure. */
    default void onFailure(@NonNull TurnFailure failure) {
    }
}
