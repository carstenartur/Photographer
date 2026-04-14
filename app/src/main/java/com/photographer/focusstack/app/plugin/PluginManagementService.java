package com.photographer.focusstack.app.plugin;

import com.photographer.focusstack.app.config.PluginProperties;
import org.pf4j.PluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Service for managing the PF4J plugin lifecycle (install, enable, disable, uninstall).
 *
 * <p>Plugin JAR files are stored in the configured plugins directory and managed
 * through the PF4J {@link PluginManager}.
 */
@Service
public class PluginManagementService {

    private static final Logger log = LoggerFactory.getLogger(PluginManagementService.class);

    private final PluginManager pluginManager;
    private final PluginProperties pluginProperties;

    public PluginManagementService(PluginManager pluginManager, PluginProperties pluginProperties) {
        this.pluginManager = pluginManager;
        this.pluginProperties = pluginProperties;
    }

    /**
     * Lists all installed plugins with their metadata and state.
     */
    public List<Map<String, Object>> listPlugins() {
        return pluginManager.getPlugins().stream()
                .map(this::toPluginInfo)
                .toList();
    }

    /**
     * Returns details for a single plugin by ID.
     */
    public Optional<Map<String, Object>> getPlugin(String pluginId) {
        PluginWrapper wrapper = pluginManager.getPlugin(pluginId);
        if (wrapper == null) {
            return Optional.empty();
        }
        return Optional.of(toPluginInfo(wrapper));
    }

    /**
     * Installs a plugin from a JAR input stream.
     *
     * @param fileName     the original file name (must end with {@code .jar})
     * @param inputStream  the JAR content
     * @return the plugin ID of the newly installed plugin
     * @throws IOException              if the file cannot be written
     * @throws IllegalArgumentException if the file name is invalid
     */
    public String installPlugin(String fileName, InputStream inputStream) throws IOException {
        validateFileName(fileName);

        Path pluginsDir = pluginProperties.directory();
        Files.createDirectories(pluginsDir);

        // Sanitize file name to prevent path traversal
        String safeName = Path.of(fileName).getFileName().toString();
        Path targetPath = pluginsDir.resolve(safeName);

        // Verify the resolved path is within the plugins directory
        if (!targetPath.normalize().startsWith(pluginsDir.normalize())) {
            throw new IllegalArgumentException("Invalid file name: path traversal detected");
        }

        Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
        log.info("Plugin JAR written to: {}", targetPath);

        String pluginId = pluginManager.loadPlugin(targetPath);
        if (pluginId == null) {
            Files.deleteIfExists(targetPath);
            throw new IllegalArgumentException("Failed to load plugin from: " + safeName);
        }

        pluginManager.startPlugin(pluginId);
        log.info("Plugin installed and started: {}", pluginId);
        return pluginId;
    }

    /**
     * Enables (starts) a plugin by ID.
     */
    public boolean enablePlugin(String pluginId) {
        PluginWrapper wrapper = pluginManager.getPlugin(pluginId);
        if (wrapper == null) {
            return false;
        }
        if (wrapper.getPluginState() == PluginState.STARTED) {
            return true; // already running
        }
        PluginState state = pluginManager.startPlugin(pluginId);
        log.info("Plugin {} enabled → state: {}", pluginId, state);
        return state == PluginState.STARTED;
    }

    /**
     * Disables (stops) a plugin by ID.
     */
    public boolean disablePlugin(String pluginId) {
        PluginWrapper wrapper = pluginManager.getPlugin(pluginId);
        if (wrapper == null) {
            return false;
        }
        if (wrapper.getPluginState() == PluginState.STOPPED
                || wrapper.getPluginState() == PluginState.DISABLED) {
            return true; // already stopped
        }
        PluginState state = pluginManager.stopPlugin(pluginId);
        log.info("Plugin {} disabled → state: {}", pluginId, state);
        return state == PluginState.STOPPED;
    }

    /**
     * Uninstalls a plugin by ID — stops it, unloads it, and deletes its JAR.
     */
    public boolean uninstallPlugin(String pluginId) {
        PluginWrapper wrapper = pluginManager.getPlugin(pluginId);
        if (wrapper == null) {
            return false;
        }
        boolean deleted = pluginManager.deletePlugin(pluginId);
        log.info("Plugin {} uninstalled: {}", pluginId, deleted ? "success" : "failed");
        return deleted;
    }

    private Map<String, Object> toPluginInfo(PluginWrapper wrapper) {
        return Map.of(
                "id", wrapper.getPluginId(),
                "version", wrapper.getDescriptor().getVersion(),
                "description", Optional.ofNullable(wrapper.getDescriptor().getPluginDescription()).orElse(""),
                "provider", Optional.ofNullable(wrapper.getDescriptor().getProvider()).orElse(""),
                "state", wrapper.getPluginState().toString(),
                "license", Optional.ofNullable(wrapper.getDescriptor().getLicense()).orElse("")
        );
    }

    private static void validateFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("File name must not be empty");
        }
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IllegalArgumentException("Only .jar plugin files are accepted");
        }
        // Reject any path separator to prevent traversal
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            throw new IllegalArgumentException("Invalid file name: must not contain path separators");
        }
    }
}
