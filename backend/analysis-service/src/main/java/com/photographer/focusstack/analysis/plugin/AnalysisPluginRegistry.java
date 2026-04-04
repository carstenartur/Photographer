package com.photographer.focusstack.analysis.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Spring bean that collects all {@link AnalysisPlugin} implementations and
 * provides look-up by name.
 *
 * <p>New plugins become available automatically as soon as they are annotated
 * with {@code @Component} (or any meta-annotation) and placed on the classpath.
 */
@Service
public class AnalysisPluginRegistry {

    private static final Logger log = LoggerFactory.getLogger(AnalysisPluginRegistry.class);

    private final Map<String, AnalysisPlugin> plugins;

    public AnalysisPluginRegistry(List<AnalysisPlugin> pluginList) {
        this.plugins = pluginList.stream()
                .collect(Collectors.toUnmodifiableMap(AnalysisPlugin::name, Function.identity()));
        log.info("AnalysisPluginRegistry: {} plugin(s) registered: {}",
                plugins.size(), plugins.keySet());
    }

    /** Returns all registered plugins (unmodifiable). */
    public List<AnalysisPlugin> listAll() {
        return Collections.unmodifiableList(List.copyOf(plugins.values()));
    }

    /** Looks up a plugin by its {@link AnalysisPlugin#name()}. */
    public Optional<AnalysisPlugin> findByName(String name) {
        return Optional.ofNullable(plugins.get(name));
    }
}
