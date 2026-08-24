package io.mindspice.magenta.core.internal;

import io.mindspice.magenta.core.NonNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Framework-internal turn executor. Owns the threading strategy for running
 * turns. The concrete virtual-thread implementation is created in a later
 * phase; this phase defines only the interface.
 */
public interface TurnExecutor extends AutoCloseable {

    <T> CompletableFuture<T> submit(@NonNull String sessionId, @NonNull Supplier<T> task);

    @Override
    void close();
}
