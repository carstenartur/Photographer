package com.photographer.focusstack.camera.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.core.task.TaskExecutor;

@Configuration
public class CameraServiceConfig {

    /**
     * Dedicated thread pool for capture-loop tasks. Using a named bean avoids
     * ambiguity with the WebSocket executor beans and ensures capture jobs run
     * on a properly sized, named thread pool rather than the shared async executor.
     */
    @Bean("stackingJobTaskExecutor")
    public TaskExecutor stackingJobTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("stacking-job-");
        executor.initialize();
        return executor;
    }
}