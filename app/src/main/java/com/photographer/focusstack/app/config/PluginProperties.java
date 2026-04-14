package com.photographer.focusstack.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Configuration properties for the Focus Stack plugin system.
 *
 * <p>Bound to the {@code focusstack.plugins} prefix in {@code application.yml}.
 */
@ConfigurationProperties(prefix = "focusstack.plugins")
public record PluginProperties(
        Path directory,
        boolean enabled
) {
    public PluginProperties {
        if (directory == null) {
            directory = Path.of("./plugins");
        }
    }
}
