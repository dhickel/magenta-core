package io.mindspice.core.spi;

import io.mindspice.core.CompactionContext;
import io.mindspice.core.CompactionResult;
import io.mindspice.core.NonNull;

/**
 * Compacts a set of older messages into a summary that preserves
 * context while reducing token usage.
 */
public interface CompactionStrategy {

    @NonNull
    CompactionResult compact(@NonNull CompactionContext context);
}
