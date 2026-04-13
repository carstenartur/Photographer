package com.photographer.focusstack.astro.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Configuration for the Astro mode.
 * Enables async execution for the astro session pipeline.
 */
@Configuration
@EnableAsync
public class AstroConfig {
}
