package com.photographer.focusstack.app.config;

import com.photographer.focusstack.app.plugin.FocusStackPluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Spring configuration for the Focus Stack plugin system.
 *
 * <p>Creates a {@link FocusStackPluginManager} bean that scans the configured
 * plugins directory for external plugin JARs. Uses Java {@code ServiceLoader}
 * and {@code URLClassLoader} for classloader-isolated plugin loading.
 */
@Configuration
public class PluginConfig {

    private static final Logger log = LoggerFactory.getLogger(PluginConfig.class);

    @Bean(destroyMethod = "close")
    public FocusStackPluginManager pluginManager(PluginProperties pluginProperties) throws IOException {
        Path pluginsDir = pluginProperties.directory();

        if (!pluginProperties.enabled()) {
            log.info("Plugin system is disabled via configuration");
            return new FocusStackPluginManager(pluginsDir);
        }

        if (!Files.exists(pluginsDir)) {
            Files.createDirectories(pluginsDir);
            log.info("Created plugins directory: {}", pluginsDir.toAbsolutePath());
        }

        FocusStackPluginManager manager = new FocusStackPluginManager(pluginsDir);
        manager.loadPlugins();

        int count = manager.getPlugins().size();
        log.info("Plugin system started: {} plugin(s) loaded from {}",
                count, pluginsDir.toAbsolutePath());

        return manager;
    }
}
