package com.photographer.focusstack.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * Type-safe configuration for camera settings.
 *
 * <p>Bound to the {@code focusstack.camera} prefix in {@code application.yml}.
 * Override via environment variables using Spring Boot's relaxed-binding rules,
 * e.g. {@code FOCUSSTACK_CAMERA_CCAPI_BASE_URL=http://192.168.1.2:8080}.
 *
 * @param ccapiBaseUrl        base URL for Canon CCAPI (default: {@code http://192.168.1.2:8080})
 * @param mockEnabled         when {@code true} a simulated camera backend is used (default: {@code true})
 * @param connectionTimeout   HTTP connection timeout for CCAPI calls (default: {@code 3s})
 * @param focusStepStrategy   focus step strategy: {@code adaptive} or {@code linear} (default: {@code adaptive})
 */
@ConfigurationProperties(prefix = "focusstack.camera")
public record CameraConfig(
        URI ccapiBaseUrl,
        boolean mockEnabled,
        Duration connectionTimeout,
        String focusStepStrategy
) {
    public CameraConfig {
        if (ccapiBaseUrl == null) {
            ccapiBaseUrl = URI.create("http://192.168.1.2:8080");
        }
        if (connectionTimeout == null) {
            connectionTimeout = Duration.ofSeconds(3);
        }
        if (focusStepStrategy == null || focusStepStrategy.isBlank()) {
            focusStepStrategy = "adaptive";
        }
    }
}
