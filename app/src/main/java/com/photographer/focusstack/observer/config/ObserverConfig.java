package com.photographer.focusstack.observer.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables the {@link ObserverProperties} configuration properties.
 */
@Configuration
@EnableConfigurationProperties(ObserverProperties.class)
public class ObserverConfig {
}
