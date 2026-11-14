package com.photographer.focusstack.cr3.raw;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class Cr3WorkersTest {

    @Test
    void automaticAndExplicitCountsRespectAvailableProcessorsAndWork() {
        int cpus = Math.min(32_767, Runtime.getRuntime().availableProcessors());
        assertEquals(cpus, Cr3Workers.count(0, Integer.MAX_VALUE));
        assertEquals(1, Cr3Workers.count(1, Integer.MAX_VALUE));
        assertEquals(1, Cr3Workers.count(0, 1));
        assertEquals(Math.min(7, cpus), Cr3Workers.count(7, 24));
        assertEquals(Math.min(3, cpus), Cr3Workers.count(7, 3));
        assertEquals(cpus, Cr3Workers.count(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> Cr3Workers.count(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> Cr3Workers.count(0, 0));
    }

    @Test
    void aSingleTaskUsesTheCaller() {
        Thread caller = Thread.currentThread();
        Cr3Workers.run(1, i -> {
            assertEquals(0, i);
            assertSame(caller, Thread.currentThread());
        });
    }

    @Test
    @Timeout(10)
    void independentTasksCanUseAllSelectedWorkers() throws Exception {
        int workers = Cr3Workers.count(0, 8);
        CountDownLatch started = new CountDownLatch(workers);
        CountDownLatch release = new CountDownLatch(1);
        FutureTask<Void> read = new FutureTask<>(() -> {
            Cr3Workers.run(workers, i -> {
                started.countDown();
                await(release);
            });
            return null;
        });
        Thread.ofVirtual().start(read);
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
        read.get(5, TimeUnit.SECONDS);
    }

    @Test
    @Timeout(10)
    void overlappingCallsAndNestedWorkUseOnePrivatePool() throws Exception {
        Set<ForkJoinPool> pools = ConcurrentHashMap.newKeySet();
        AtomicInteger visits = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FutureTask<Void> first = new FutureTask<>(() -> {
            Cr3Workers.run(2, i -> {
                entered.countDown();
                await(release);
                pools.add(ForkJoinTask.getPool());
            });
            return null;
        });
        Thread.ofVirtual().start(first);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            FutureTask<Void> second = new FutureTask<>(() -> {
                Cr3Workers.run(2, i -> Cr3Workers.run(3, j -> {
                    pools.add(ForkJoinTask.getPool());
                    visits.incrementAndGet();
                }));
                return null;
            });
            Thread.ofVirtual().start(second);
            release.countDown();
            second.get(5, TimeUnit.SECONDS);
            first.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        assertEquals(6, visits.get());
        assertEquals(1, pools.size());
        assertFalse(pools.contains(ForkJoinPool.commonPool()));
    }

    @Test
    @Timeout(10)
    void waitsForSiblingsAfterFailureAndCallerInterrupt() throws Exception {
        IllegalStateException failure = new IllegalStateException("decode failed");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        AtomicReference<Throwable> observed = new AtomicReference<>();
        FutureTask<Boolean> read = new FutureTask<>(() -> {
            try {
                Cr3Workers.run(3, i -> {
                    if (started.getAndIncrement() == 0) {
                        entered.countDown();
                        throw failure;
                    }
                    await(release);
                    finished.incrementAndGet();
                });
            } catch (RuntimeException e) {
                observed.set(e);
            }
            assertEquals(2, finished.get());
            return Thread.currentThread().isInterrupted();
        });
        Thread caller = Thread.ofVirtual().start(read);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            caller.interrupt();
            assertThrows(TimeoutException.class, () -> read.get(100, TimeUnit.MILLISECONDS));
        } finally {
            release.countDown();
        }
        assertTrue(read.get(5, TimeUnit.SECONDS));
        assertSame(failure, observed.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }
}
