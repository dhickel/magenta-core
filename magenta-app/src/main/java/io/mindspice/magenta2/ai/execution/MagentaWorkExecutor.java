package io.mindspice.magenta2.ai.execution;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MagentaWorkExecutor {
    private final Map<MagentaWorkKind, Lane> lanes;

    @Autowired
    public MagentaWorkExecutor(
        @Value("${magenta.executor.chat-threads:8}") int chatThreads,
        @Value("${magenta.executor.chat-queue:200}") int chatQueue,
        @Value("${magenta.executor.delegation-threads:2}") int delegationThreads,
        @Value("${magenta.executor.delegation-queue:100}") int delegationQueue,
        @Value("${magenta.executor.background-threads:1}") int backgroundThreads,
        @Value("${magenta.executor.background-queue:100}") int backgroundQueue
    ) {
        this(Map.of(
            MagentaWorkKind.CHAT_TURN, new LaneSettings("magenta-chat-", chatThreads, chatQueue),
            MagentaWorkKind.DELEGATION, new LaneSettings("magenta-delegation-", delegationThreads, delegationQueue),
            MagentaWorkKind.BACKGROUND_JOB, new LaneSettings("magenta-background-", backgroundThreads, backgroundQueue)
        ));
    }

    public MagentaWorkExecutor(Map<MagentaWorkKind, LaneSettings> settings) {
        EnumMap<MagentaWorkKind, Lane> configured = new EnumMap<>(MagentaWorkKind.class);
        for (Map.Entry<MagentaWorkKind, LaneSettings> entry : settings.entrySet()) {
            configured.put(entry.getKey(), new Lane(entry.getValue()));
        }
        this.lanes = Map.copyOf(configured);
    }

    public <T> CompletableFuture<T> submit(MagentaWorkRequest<T> request) {
        if (request == null || request.work() == null || request.kind() == null) {
            throw new IllegalArgumentException("work request, kind, and callable are required");
        }
        Lane lane = lanes.get(request.kind());
        if (lane == null) {
            throw new IllegalArgumentException("No executor lane configured for " + request.kind());
        }
        return lane.submit(request.priority(), request.description(), request.work());
    }

    public <T> CompletableFuture<T> submitChat(String conversationId, int priority, String description, Callable<T> work) {
        return submit(new MagentaWorkRequest<>(MagentaWorkKind.CHAT_TURN, conversationId, priority, description, work));
    }

    public <T> CompletableFuture<T> submitBackground(String conversationId, int priority, String description, Callable<T> work) {
        return submit(new MagentaWorkRequest<>(MagentaWorkKind.BACKGROUND_JOB, conversationId, priority, description, work));
    }

    @PreDestroy
    public void shutdown() {
        lanes.values().forEach(Lane::shutdown);
    }

    public record LaneSettings(String threadNamePrefix, int maxThreads, int queueCapacity) { }

    private static final class Lane {
        private final AtomicLong sequence = new AtomicLong();
        private final Semaphore capacity;
        private final ExecutorService executor;

        private Lane(LaneSettings settings) {
            this.capacity = new Semaphore(Math.max(settings.maxThreads(), 1) + Math.max(settings.queueCapacity(), 0));
            this.executor = new ThreadPoolExecutor(
                settings.maxThreads(),
                settings.maxThreads(),
                0L,
                TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(),
                threadFactory(settings.threadNamePrefix())
            );
        }

        private <T> CompletableFuture<T> submit(int priority, String description, Callable<T> work) {
            if (!capacity.tryAcquire()) {
                throw new RejectedExecutionException("Magenta work queue is full: " + description);
            }
            CompletableFuture<T> result = new CompletableFuture<>();
            PrioritizedWork<T> task = new PrioritizedWork<>(
                priority,
                sequence.getAndIncrement(),
                work,
                result,
                capacity
            );
            result.whenComplete((ignored, error) -> {
                if (result.isCancelled()) {
                    task.cancel(true);
                }
            });
            executor.execute(task);
            return result;
        }

        private static ThreadFactory threadFactory(String prefix) {
            ThreadFactory delegate = Executors.defaultThreadFactory();
            AtomicLong count = new AtomicLong();
            return runnable -> {
                Thread thread = delegate.newThread(runnable);
                thread.setName(prefix + count.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            };
        }

        private void shutdown() {
            executor.shutdownNow();
        }
    }

    private static final class PrioritizedWork<T> implements Runnable, Comparable<PrioritizedWork<?>> {
        private final int priority;
        private final long sequence;
        private final Callable<T> work;
        private final CompletableFuture<T> result;
        private final Semaphore capacity;
        private volatile Thread runner;

        private PrioritizedWork(
            int priority,
            long sequence,
            Callable<T> work,
            CompletableFuture<T> result,
            Semaphore capacity
        ) {
            this.priority = priority;
            this.sequence = sequence;
            this.work = work;
            this.result = result;
            this.capacity = capacity;
        }

        @Override
        public void run() {
            if (result.isCancelled()) {
                capacity.release();
                return;
            }
            runner = Thread.currentThread();
            try {
                result.complete(work.call());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            } finally {
                runner = null;
                Thread.interrupted(); // clear flag so pool thread is clean for next task
                capacity.release();
            }
        }

        private void cancel(boolean mayInterruptIfRunning) {
            Thread runningThread = runner;
            if (mayInterruptIfRunning && runningThread != null) {
                runningThread.interrupt();
            }
        }

        @Override
        public int compareTo(PrioritizedWork<?> other) {
            int byPriority = Integer.compare(other.priority, priority);
            return byPriority != 0 ? byPriority : Long.compare(sequence, other.sequence);
        }
    }
}
