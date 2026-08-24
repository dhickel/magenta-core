package io.mindspice.magenta.core.spi;

import io.mindspice.magenta.core.CompactionContext;
import io.mindspice.magenta.core.CompactionResult;
import io.mindspice.magenta.core.NonNull;

/**
 * Compacts a set of older messages into a summary that preserves
 * context while reducing token usage.
 */
public interface CompactionStrategy {

    @NonNull
    CompactionResult compact(@NonNull CompactionContext context);
}
