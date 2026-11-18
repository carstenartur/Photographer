package com.photographer.focusstack.cr3.raw;

import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

/** Internal CPU scheduler shared by CR3 decode and image processing stages. */
public final class Cr3Workers {
    private Cr3Workers() {}

    /**
     * Returns the number of workers for a stage with positive, independent work items.
     * A maximum of zero selects the JVM's available processors. A positive maximum
     * also caps this read, with one selecting serial execution.
     */
    public static int count(int maxWorkers, int workItems) {
        if (maxWorkers < 0 || workItems < 1) {
            throw new IllegalArgumentException("maxWorkers must be non-negative and workItems positive");
        }
        int available = Math.min(capacity(), workItems);
        return maxWorkers == 0 ? available : Math.min(maxWorkers, available);
    }

    /**
     * Runs each index in {@code [0, taskCount)} once. A single task runs on the caller.
     * Parallel calls share one CPU pool. All submitted tasks finish before the
     * caller returns or observes an action's exception. An interrupted caller still
     * waits for submitted work and retains its interrupted status.
     */
    public static void run(int taskCount, IntConsumer action) {
        Objects.requireNonNull(action);
        if (taskCount < 1) {
            throw new IllegalArgumentException("taskCount must be positive");
        }
        if (taskCount == 1) {
            action.accept(0);
            return;
        }

        ForkJoinPool pool = Shared.POOL;
        int available = capacity();
        if (available != pool.getParallelism()) {
            pool.setParallelism(available);
        }
        // Nested work runs on the current worker so a full pool cannot wait on itself.
        boolean nested = ForkJoinTask.getPool() == pool;
        Semaphore finished = new Semaphore(0);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        int submitted = 0;
        try {
            for (; submitted < taskCount; submitted++) {
                int index = submitted;
                Runnable task = () -> {
                    try {
                        action.accept(index);
                    } catch (RuntimeException | Error e) {
                        failure.compareAndSet(null, e);
                    } finally {
                        finished.release();
                    }
                };
                if (nested) {
                    task.run();
                } else {
                    pool.execute(task);
                }
            }
        } finally {
            finished.acquireUninterruptibly(submitted);
        }
        switch (failure.get()) {
            case RuntimeException e -> throw e;
            case Error e -> throw e;
            case null, default -> { }
        }
    }

    private static int capacity() {
        // ForkJoinPool's documented maximum parallelism.
        return Math.min(Runtime.getRuntime().availableProcessors(), 32_767);
    }

    private static final class Shared {
        private static final int INITIAL_CAPACITY = capacity();
        // Zero spare threads keep blocking workers from increasing the CPU budget.
        private static final ForkJoinPool POOL = new ForkJoinPool(INITIAL_CAPACITY,
                ForkJoinPool.defaultForkJoinWorkerThreadFactory, null, false, 0,
                INITIAL_CAPACITY, 0, pool -> true, 60, TimeUnit.SECONDS);
    }
}
