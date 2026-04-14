package com.photographer.focusstack.app.plugin;

import com.photographer.focusstack.app.config.PluginProperties;
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
 * Service for managing the plugin lifecycle (install, enable, disable, uninstall).
 *
 * <p>Plugin JAR files are stored in the configured plugins directory and managed
 * through the {@link FocusStackPluginManager}.
 */
@Service
public class PluginManagementService {

    private static final Logger log = LoggerFactory.getLogger(PluginManagementService.class);

    private final FocusStackPluginManager pluginManager;
    private final PluginProperties pluginProperties;

    public PluginManagementService(FocusStackPluginManager pluginManager, PluginProperties pluginProperties) {
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
        return pluginManager.getPlugin(pluginId)
                .map(this::toPluginInfo);
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

        // Write to a temporary file first so we never overwrite an existing
        // plugin JAR before verifying the new one loads successfully.
        Path tempFile = Files.createTempFile(pluginsDir, "plugin-upload-", ".tmp");
        try {
            Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);

            String pluginId = pluginManager.loadPlugin(tempFile);
            if (pluginId == null) {
                throw new IllegalArgumentException("Failed to load plugin from: " + safeName);
            }

            // Load succeeded — move the temp file to the final location
            Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("Plugin installed: {} → {}", pluginId, targetPath);
            return pluginId;
        } finally {
            // Clean up the temp file if it still exists (i.e. load failed or move was skipped)
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * Enables a plugin by ID.
     */
    public boolean enablePlugin(String pluginId) {
        return pluginManager.enablePlugin(pluginId);
    }

    /**
     * Disables a plugin by ID.
     */
    public boolean disablePlugin(String pluginId) {
        return pluginManager.disablePlugin(pluginId);
    }

    /**
     * Uninstalls a plugin by ID — closes its classloader and deletes its JAR.
     */
    public boolean uninstallPlugin(String pluginId) {
        return pluginManager.deletePlugin(pluginId);
    }

    private Map<String, Object> toPluginInfo(FocusStackPluginManager.LoadedPlugin plugin) {
        FocusStackPluginManager.PluginInfo info = plugin.info();
        return Map.of(
                "id", info.id(),
                "version", info.version(),
                "description", info.description(),
                "provider", info.provider(),
                "state", plugin.enabled() ? "ENABLED" : "DISABLED",
                "license", info.license()
        );
    }

    static void validateFileName(String fileName) {
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
