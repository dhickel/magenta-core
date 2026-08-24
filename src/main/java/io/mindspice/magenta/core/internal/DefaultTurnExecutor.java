package io.mindspice.magenta.core.internal;

import io.mindspice.magenta.core.ExecutorStrategy;
import io.mindspice.magenta.core.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Default {@link TurnExecutor} implementation using {@link ExecutorStrategy}
 * to create the backing {@link ExecutorService}.
 *
 * <p>Used by both the engine (for async compaction) and the facade
 * (for isolated turn execution). The executor is shut down during
 * {@link #close()} with a bounded await-termination period.</p>
 */
public final class DefaultTurnExecutor implements TurnExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultTurnExecutor.class);

    private final ExecutorService executor;

    /**
     * Creates a virtual-thread-per-task executor (the production default).
     */
    public DefaultTurnExecutor() {
        this(ExecutorStrategy.VIRTUAL, 0);
    }

    /**
     * Creates an executor with the given strategy and thread count.
     *
     * @param strategy    the threading strategy
     * @param threadCount thread count (ignored for {@link ExecutorStrategy#VIRTUAL})
     */
    public DefaultTurnExecutor(@NonNull ExecutorStrategy strategy, int threadCount) {
        this.executor = strategy.create(threadCount);
    }

    // ── TurnExecutor ──

    @Override
    public <T> CompletableFuture<T> submit(@NonNull String sessionId, @NonNull Supplier<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.get();
            } catch (Exception e) {
                // Wrap in CompletionException for consistent async error handling
                throw new CompletionException(e);
            }
        }, executor);
    }

    @Override
    public void close() {
        log.info("Shutting down TurnExecutor...");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                log.warn("TurnExecutor did not terminate within 60s — forcing shutdown");
                executor.shutdownNow();
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.error("TurnExecutor did not terminate after forced shutdown");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            log.warn("TurnExecutor shutdown interrupted — forced shutdown");
        }
        log.info("TurnExecutor shut down");
    }
}
