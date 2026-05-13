package com.photographer.focusstack.observer.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayServiceTest {

    @Test
    void shutdownExecutorTerminatesReplayPool() throws Exception {
        ReplayService replayService = new ReplayService();

        replayService.shutdownExecutor();

        ThreadPoolExecutor executor = getExecutor(replayService);
        assertTrue(executor.isShutdown());
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS) || executor.isTerminated());
    }

    private static ThreadPoolExecutor getExecutor(ReplayService replayService) throws Exception {
        Field field = ReplayService.class.getDeclaredField("executor");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(replayService);
    }
}
