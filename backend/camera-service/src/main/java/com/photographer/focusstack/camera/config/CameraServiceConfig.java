package com.photographer.focusstack.camera.config;

import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.camera.backend.CanonCcapiBackend;
import com.photographer.focusstack.camera.backend.MockCameraBackend;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
}
