package com.photographer.focusstack.camera.config;

import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.camera.backend.CanonCcapiBackend;
import com.photographer.focusstack.camera.backend.MockCameraBackend;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.web.client.RestTemplate;

@Configuration
public class CameraServiceConfig {

    @Bean
    @ConditionalOnProperty(name = "camera.mock.enabled", havingValue = "true", matchIfMissing = true)
    public CameraBackend mockCameraBackend(
            @Value("${camera.mock.delay-ms:50}") long delayMs) {
        return new MockCameraBackend(delayMs);
    }

    @Bean
    @ConditionalOnProperty(name = "camera.mock.enabled", havingValue = "false")
    public CameraBackend canonCcapiBackend(
            @Value("${camera.ccapi.base-url:http://192.168.1.2:8080}") String baseUrl) {
        return new CanonCcapiBackend(baseUrl, new RestTemplate());
    }

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
