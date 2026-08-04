package io.mindspice.magenta2.ai.execution;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ConversationTurnCoordinator {
    private final MagentaWorkExecutor workExecutor;
    private final Map<String, Deque<QueuedTurn<?>>> queues = new HashMap<>();

    public ConversationTurnCoordinator(MagentaWorkExecutor workExecutor) {
        this.workExecutor = workExecutor;
    }

    public <T> CompletableFuture<T> submit(String conversationId, int priority, String description, Callable<T> work) {
        if (!StringUtils.hasText(conversationId)) {
            return workExecutor.submitChat(conversationId, priority, description, work);
        }
        QueuedTurn<T> turn = new QueuedTurn<>(conversationId, priority, description, work);
        boolean shouldSchedule;
        synchronized (queues) {
            Deque<QueuedTurn<?>> queue = queues.computeIfAbsent(conversationId, ignored -> new ArrayDeque<>());
            shouldSchedule = queue.isEmpty();
            queue.addLast(turn);
        }
        if (shouldSchedule) {
            scheduleNext(conversationId);
        }
        return turn.result;
    }

    private void scheduleNext(String conversationId) {
        QueuedTurn<?> next;
        synchronized (queues) {
            Deque<QueuedTurn<?>> queue = queues.get(conversationId);
            while (queue != null && !queue.isEmpty() && queue.peekFirst().isCancelled()) {
                queue.pollFirst();
            }
            if (queue != null && queue.isEmpty()) {
                queues.remove(conversationId);
                queue = null;
            }
            next = queue == null ? null : queue.peekFirst();
        }
        if (next == null) {
            return;
        }
        next.submit(workExecutor).whenComplete((ignored, error) -> {
            synchronized (queues) {
                Deque<QueuedTurn<?>> queue = queues.get(conversationId);
                if (queue != null) {
                    queue.pollFirst();
                    if (queue.isEmpty()) {
                        queues.remove(conversationId);
                    }
                }
            }
            scheduleNext(conversationId);
        });
    }

    private static final class QueuedTurn<T> {
        private final String conversationId;
        private final int priority;
        private final String description;
        private final Callable<T> work;
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private boolean submitted;

        private QueuedTurn(String conversationId, int priority, String description, Callable<T> work) {
            this.conversationId = conversationId;
            this.priority = priority;
            this.description = description;
            this.work = work;
        }

        private CompletableFuture<T> submit(MagentaWorkExecutor workExecutor) {
            synchronized (this) {
                if (submitted) {
                    return result;
                }
                if (result.isCancelled()) {
                    return result;
                }
                submitted = true;
            }
            CompletableFuture<T> submittedFuture = workExecutor.submitChat(conversationId, priority, description, work);
            result.whenComplete((ignored, error) -> {
                if (result.isCancelled()) {
                    submittedFuture.cancel(true);
                }
            });
            submittedFuture.whenComplete((value, error) -> {
                if (error != null) {
                    result.completeExceptionally(error);
                } else {
                    result.complete(value);
                }
            });
            return result;
        }

        private boolean isCancelled() {
            return result.isCancelled();
        }
    }
}
