package com.photographer.focusstack.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "focusstack.camera")
public record CameraConfig(
        URI ccapiBaseUrl,
        boolean mockEnabled,
        Duration connectionTimeout
) {
    public CameraConfig {
        if (ccapiBaseUrl == null) {
            ccapiBaseUrl = URI.create("http://192.168.1.2:8080");
        }
        if (connectionTimeout == null) {
            connectionTimeout = Duration.ofSeconds(10);
        }
    }
}
