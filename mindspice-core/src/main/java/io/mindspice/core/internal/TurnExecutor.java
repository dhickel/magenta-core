package io.mindspice.core.internal;

import io.mindspice.core.NonNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Framework-internal turn executor. Owns the threading strategy for running
 * turns. The concrete virtual-thread implementation is created in a later
 * phase; this phase defines only the interface.
 */
interface TurnExecutor extends AutoCloseable {

    <T> CompletableFuture<T> submit(@NonNull String sessionId, @NonNull Supplier<T> task);

    @Override
    void close();
}
