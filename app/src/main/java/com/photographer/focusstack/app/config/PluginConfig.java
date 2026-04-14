package com.photographer.focusstack.app.config;

import org.pf4j.DefaultPluginManager;
import org.pf4j.PluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Spring configuration for the PF4J plugin system.
 *
 * <p>Creates a {@link PluginManager} bean that scans the configured plugins
 * directory for external plugin JARs. The directory is created automatically
 * if it does not exist.
 */
@Configuration
public class PluginConfig {

    private static final Logger log = LoggerFactory.getLogger(PluginConfig.class);

    @Bean(destroyMethod = "stopPlugins")
    public PluginManager pluginManager(PluginProperties pluginProperties) throws IOException {
        if (!pluginProperties.enabled()) {
            log.info("Plugin system is disabled via configuration");
            return new DefaultPluginManager(pluginProperties.directory()) {
                @Override
                public void loadPlugins() {
                    // no-op when disabled
                }
            };
        }

        Path pluginsDir = pluginProperties.directory();
        if (!Files.exists(pluginsDir)) {
            Files.createDirectories(pluginsDir);
            log.info("Created plugins directory: {}", pluginsDir.toAbsolutePath());
        }

        DefaultPluginManager manager = new DefaultPluginManager(pluginsDir);
        manager.loadPlugins();
        manager.startPlugins();

        int count = manager.getStartedPlugins().size();
        log.info("Plugin system started: {} plugin(s) loaded from {}",
                count, pluginsDir.toAbsolutePath());

        return manager;
    }
}
