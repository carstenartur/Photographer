package com.photographer.focusstack.analysis.plugin;

import com.photographer.focusstack.app.plugin.FocusStackPluginManager;
import com.photographer.focusstack.plugin.AnalysisPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Spring bean that collects all {@link AnalysisPlugin} implementations and
 * provides look-up by name.
 *
 * <p>Collects both Spring-managed beans (built-in plugins) and ServiceLoader
 * extensions from external runtime-loaded plugin JARs.
 */
@Service
public class AnalysisPluginRegistry {

    private static final Logger log = LoggerFactory.getLogger(AnalysisPluginRegistry.class);

    private final Map<String, AnalysisPlugin> plugins;

    public AnalysisPluginRegistry(List<AnalysisPlugin> springPlugins,
                                  FocusStackPluginManager pluginManager) {
        List<AnalysisPlugin> allPlugins = new ArrayList<>(springPlugins);

        // Collect extensions from external plugin JARs via ServiceLoader
        List<AnalysisPlugin> externalPlugins = pluginManager.getExtensions(AnalysisPlugin.class);
        allPlugins.addAll(externalPlugins);

        if (!externalPlugins.isEmpty()) {
            log.info("Loaded {} external AnalysisPlugin(s) from plugin JARs: {}",
                    externalPlugins.size(),
                    externalPlugins.stream().map(AnalysisPlugin::name).toList());
        }

        this.plugins = buildPluginMap(allPlugins);
        log.info("AnalysisPluginRegistry: {} plugin(s) registered: {}",
                plugins.size(), plugins.keySet());
    }

    private static Map<String, AnalysisPlugin> buildPluginMap(List<AnalysisPlugin> pluginList) {
        Map<String, List<AnalysisPlugin>> byName = pluginList.stream()
                .collect(Collectors.groupingBy(AnalysisPlugin::name,
                        LinkedHashMap::new, Collectors.toList()));

        List<Map.Entry<String, List<AnalysisPlugin>>> duplicates = byName.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .toList();

        if (!duplicates.isEmpty()) {
            String details = duplicates.stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> "'" + e.getKey() + "' registered by: " + e.getValue().stream()
                            .map(p -> p.getClass().getName())
                            .sorted()
                            .collect(Collectors.joining(", ")))
                    .collect(Collectors.joining("; "));
            throw new IllegalStateException(
                    "Duplicate AnalysisPlugin name(s) detected — each plugin must have a unique name. "
                    + details);
        }

        Map<String, AnalysisPlugin> result = new LinkedHashMap<>();
        byName.forEach((name, list) -> result.put(name, list.get(0)));
        return Collections.unmodifiableMap(result);
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
