package com.photographer.focusstack.observer.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import com.photographer.focusstack.ai.local.LocalBirdVisionProvider;

import java.nio.file.Path;

/**
 * Enables the {@link ObserverProperties} configuration properties.
 */
@Configuration
@EnableConfigurationProperties(ObserverProperties.class)
public class ObserverConfig {
    @Bean(destroyMethod = "close")
    public LocalBirdVisionProvider localBirdVisionProvider(
            @Value("${observer.local-bird.model-path:}") String modelPath) {
        return modelPath == null || modelPath.isBlank()
                ? new LocalBirdVisionProvider() : new LocalBirdVisionProvider(Path.of(modelPath));
    }
}
