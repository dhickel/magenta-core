package io.mindspice.core;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Threading strategy for the turn engine executor.
 */
public enum ExecutorStrategy {
    VIRTUAL {
        @Override
        public ExecutorService create(int threadCount) {
            return Executors.newVirtualThreadPerTaskExecutor();
        }
    },
    FIXED {
        @Override
        public ExecutorService create(int threadCount) {
            int threads = Math.max(threadCount, 1);
            return Executors.newFixedThreadPool(threads, r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("turnloop-executor-" + t.threadId());
                return t;
            });
        }
    };

    public abstract ExecutorService create(int threadCount);
}
