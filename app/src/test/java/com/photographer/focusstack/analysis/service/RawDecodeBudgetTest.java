package com.photographer.focusstack.analysis.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawDecodeBudgetTest {

    @Test
    void reservationsShareTheCpuAndMemoryBudget() throws Exception {
        var budget = new RawDecodeBudget(1, 100);
        var attempting = new CountDownLatch(1);
        try (var tasks = Executors.newSingleThreadExecutor(); var first = budget.acquire(60, 1)) {
            var second = tasks.submit(() -> {
                attempting.countDown();
                try (var lease = budget.acquire(50, 1)) {
                    return lease.workers();
                }
            });
            try {
                assertTrue(attempting.await(5, TimeUnit.SECONDS));
                assertFalse(second.isDone());
            } finally {
                first.close();
            }
            assertEquals(1, second.get(5, TimeUnit.SECONDS));
        }
        try (var all = budget.acquire(100, 1)) {
            assertEquals(100, all.bytes());
        }
    }

    @Test
    void reservesTheAvailablePartOfAPreferredAllocation() throws Exception {
        var budget = new RawDecodeBudget(1, 100);
        try (var lease = budget.acquire(50, 200, 1)) {
            assertEquals(100, lease.bytes());
        }
        assertThrows(IOException.class, () -> budget.acquire(101, 1));
    }
}
