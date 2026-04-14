package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.app.plugin.FocusStackPluginManager;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.plugin.CameraBackendPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registry that collects camera backend plugins from external plugin JARs
 * and makes them available to the {@link CameraBackendFactory}.
 *
 * <p>External plugins implement {@link CameraBackendPlugin} (which extends
 * {@link CameraBackend}) and are discovered via {@code ServiceLoader} through
 * the {@link FocusStackPluginManager}.
 *
 * <p>Built-in backends (Mock, CCAPI, Folder, PTP) are <em>not</em> managed
 * by this registry — they are created directly by {@link CameraBackendFactory}.
 * This registry only adds external plugin-provided backends.
 */
@Service
public class CameraBackendRegistry {

    private static final Logger log = LoggerFactory.getLogger(CameraBackendRegistry.class);

    private final FocusStackPluginManager pluginManager;

    public CameraBackendRegistry(FocusStackPluginManager pluginManager) {
        this.pluginManager = pluginManager;
        List<CameraBackendPlugin> externalPlugins = pluginManager.getExtensions(CameraBackendPlugin.class);
        if (!externalPlugins.isEmpty()) {
            log.info("Discovered {} external CameraBackendPlugin(s): {}",
                    externalPlugins.size(),
                    externalPlugins.stream().map(CameraBackendPlugin::backendType).toList());
        }
    }

    /**
     * Returns all currently available external camera backend plugins,
     * indexed by their {@link CameraBackendPlugin#backendType()}.
     */
    public Map<String, CameraBackendPlugin> getExternalBackends() {
        List<CameraBackendPlugin> plugins = pluginManager.getExtensions(CameraBackendPlugin.class);
        Map<String, CameraBackendPlugin> result = new LinkedHashMap<>();
        for (CameraBackendPlugin plugin : plugins) {
            String type = plugin.backendType();
            if (result.containsKey(type)) {
                log.warn("Duplicate CameraBackendPlugin backendType '{}' — " +
                        "keeping first, ignoring {}", type, plugin.getClass().getName());
                continue;
            }
            result.put(type, plugin);
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Looks up an external camera backend plugin by its backend type.
     *
     * @param backendType the backend type identifier (e.g. {@code "sony-wifi"})
     * @return the plugin if found and enabled
     */
    public Optional<CameraBackendPlugin> findByType(String backendType) {
        return pluginManager.getExtensions(CameraBackendPlugin.class).stream()
                .filter(p -> backendType.equalsIgnoreCase(p.backendType()))
                .findFirst();
    }

    /**
     * Returns all registered backend type identifiers (external plugins only).
     */
    public List<String> listBackendTypes() {
        return pluginManager.getExtensions(CameraBackendPlugin.class).stream()
                .map(CameraBackendPlugin::backendType)
                .toList();
    }
}
