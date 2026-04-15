package com.photographer.focusstack.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for automatic data retention cleanup.
 *
 * <p>Example configuration in {@code application.yml}:
 * <pre>
 * focusstack:
 *   retention:
 *     enabled: true
 *     jobs-max-age-days: 90
 *     events-max-age-days: 180
 *     cron: "0 0 3 * * *"
 * </pre>
 *
 * @param enabled           whether automatic cleanup is enabled (default: false)
 * @param jobsMaxAgeDays    completed/failed/cancelled jobs older than this are deleted (0 = never)
 * @param eventsMaxAgeDays  observation events older than this are deleted (0 = never)
 * @param astroMaxAgeDays   terminal astro sessions older than this are deleted (0 = never)
 * @param cron              cron expression for the cleanup schedule
 */
@ConfigurationProperties(prefix = "focusstack.retention")
public record RetentionConfig(
        boolean enabled,
        int jobsMaxAgeDays,
        int eventsMaxAgeDays,
        int astroMaxAgeDays,
        String cron
) {
    public RetentionConfig {
        if (cron == null || cron.isBlank()) {
            cron = "0 0 3 * * *"; // default: 3 AM daily
        }
    }
}
