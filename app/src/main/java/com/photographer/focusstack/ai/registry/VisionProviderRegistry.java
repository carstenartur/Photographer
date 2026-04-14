package com.photographer.focusstack.ai.registry;

import com.photographer.focusstack.ai.VisionProvider;
import com.photographer.focusstack.app.plugin.FocusStackPluginManager;
import com.photographer.focusstack.plugin.VisionProviderPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Registry that collects all {@link VisionProvider} implementations —
 * both Spring-managed beans (built-in providers like Gemini, Mock) and
 * external runtime-loaded plugins discovered via {@link FocusStackPluginManager}.
 *
 * <p>Since {@link VisionProviderPlugin} extends {@link VisionProvider},
 * external plugins are fully functional vision providers that can be used
 * interchangeably with built-in providers.
 */
@Service
public class VisionProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(VisionProviderRegistry.class);

    private final List<VisionProvider> springProviders;
    private final FocusStackPluginManager pluginManager;

    public VisionProviderRegistry(List<VisionProvider> springProviders,
                                  FocusStackPluginManager pluginManager) {
        this.springProviders = springProviders;
        this.pluginManager = pluginManager;

        List<VisionProviderPlugin> externalPlugins = pluginManager.getExtensions(VisionProviderPlugin.class);
        if (!externalPlugins.isEmpty()) {
            log.info("Discovered {} external VisionProviderPlugin(s): {}",
                    externalPlugins.size(),
                    externalPlugins.stream().map(VisionProviderPlugin::name).toList());
        }

        log.info("VisionProviderRegistry: {} built-in + {} external provider(s)",
                springProviders.size(), externalPlugins.size());
    }

    /**
     * Returns all available vision providers (built-in + external plugins).
     * The list is rebuilt on each call so that enable/disable changes in
     * the plugin manager are reflected immediately.
     */
    public List<VisionProvider> listAll() {
        List<VisionProvider> all = new ArrayList<>(springProviders);
        all.addAll(pluginManager.getExtensions(VisionProviderPlugin.class));
        return Collections.unmodifiableList(all);
    }

    /**
     * Looks up a vision provider by name.
     *
     * @param name provider name (e.g. {@code "gemini"}, {@code "openai"})
     * @return the provider if found
     */
    public Optional<VisionProvider> findByName(String name) {
        return listAll().stream()
                .filter(p -> name.equalsIgnoreCase(p.name()))
                .findFirst();
    }

    /**
     * Returns only providers that report {@link VisionProvider#isAvailable()} as {@code true}.
     */
    public List<VisionProvider> listAvailable() {
        return listAll().stream()
                .filter(VisionProvider::isAvailable)
                .toList();
    }

    /**
     * Returns all provider names (built-in + external).
     */
    public List<String> listProviderNames() {
        return listAll().stream()
                .map(VisionProvider::name)
                .toList();
    }
}
