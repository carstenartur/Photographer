package com.photographer.focusstack.app.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Lightweight plugin manager using Java {@link ServiceLoader} and {@link URLClassLoader}.
 *
 * <p>Each plugin JAR is loaded in its own isolated classloader. Plugins declare
 * their implementations via the standard {@code META-INF/services/} SPI mechanism.
 *
 * <p>Plugin JAR manifests should contain these optional headers:
 * <ul>
 *   <li>{@code Plugin-Id} — unique identifier (defaults to file name without .jar)</li>
 *   <li>{@code Plugin-Version} — semver string (defaults to "0.0.0")</li>
 *   <li>{@code Plugin-Description} — short description</li>
 *   <li>{@code Plugin-Provider} — author or organisation</li>
 *   <li>{@code Plugin-License} — SPDX identifier</li>
 * </ul>
 */
public class FocusStackPluginManager implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(FocusStackPluginManager.class);

    private final Path pluginsDirectory;
    private final Map<String, LoadedPlugin> plugins = new ConcurrentHashMap<>();

    public FocusStackPluginManager(Path pluginsDirectory) {
        this.pluginsDirectory = pluginsDirectory;
    }

    /**
     * Scans the plugins directory for JAR files and loads each one.
     */
    public void loadPlugins() throws IOException {
        if (!Files.isDirectory(pluginsDirectory)) {
            log.debug("Plugins directory does not exist: {}", pluginsDirectory);
            return;
        }
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(pluginsDirectory, "*.jar")) {
            for (Path jarPath : jars) {
                loadPlugin(jarPath);
            }
        }
    }

    /**
     * Loads a single plugin JAR and returns its plugin ID, or {@code null} on failure.
     */
    public String loadPlugin(Path jarPath) {
        try {
            PluginInfo info = readPluginInfo(jarPath);
            if (plugins.containsKey(info.id())) {
                log.warn("Plugin {} already loaded — skipping {}", info.id(), jarPath);
                return null;
            }

            URL jarUrl = jarPath.toUri().toURL();
            URLClassLoader classLoader = new URLClassLoader(
                    new URL[]{jarUrl}, getClass().getClassLoader());

            LoadedPlugin loaded = new LoadedPlugin(info, jarPath, classLoader, true);
            plugins.put(info.id(), loaded);
            log.info("Loaded plugin: {} v{} from {}", info.id(), info.version(), jarPath.getFileName());
            return info.id();
        } catch (Exception e) {
            log.error("Failed to load plugin from {}", jarPath, e);
            return null;
        }
    }

    /**
     * Discovers all implementations of {@code extensionType} from loaded and enabled plugins.
     */
    public <T> List<T> getExtensions(Class<T> extensionType) {
        List<T> extensions = new ArrayList<>();
        for (LoadedPlugin loaded : plugins.values()) {
            if (!loaded.enabled()) {
                continue;
            }
            ServiceLoader<T> loader = ServiceLoader.load(extensionType, loaded.classLoader());
            for (T extension : loader) {
                extensions.add(extension);
            }
        }
        return Collections.unmodifiableList(extensions);
    }

    /** Returns info for all loaded plugins. */
    public List<LoadedPlugin> getPlugins() {
        return List.copyOf(plugins.values());
    }

    /** Returns info for a specific plugin, or empty. */
    public Optional<LoadedPlugin> getPlugin(String pluginId) {
        return Optional.ofNullable(plugins.get(pluginId));
    }

    /** Enables a plugin. Returns {@code true} on success. */
    public boolean enablePlugin(String pluginId) {
        LoadedPlugin plugin = plugins.get(pluginId);
        if (plugin == null) {
            return false;
        }
        plugins.put(pluginId, plugin.withEnabled(true));
        log.info("Plugin {} enabled", pluginId);
        return true;
    }

    /** Disables a plugin. Returns {@code true} on success. */
    public boolean disablePlugin(String pluginId) {
        LoadedPlugin plugin = plugins.get(pluginId);
        if (plugin == null) {
            return false;
        }
        plugins.put(pluginId, plugin.withEnabled(false));
        log.info("Plugin {} disabled", pluginId);
        return true;
    }

    /** Unloads a plugin and deletes its JAR. Returns {@code true} on success. */
    public boolean deletePlugin(String pluginId) {
        LoadedPlugin plugin = plugins.remove(pluginId);
        if (plugin == null) {
            return false;
        }
        try {
            plugin.classLoader().close();
        } catch (IOException e) {
            log.warn("Could not close classloader for plugin {}: {}", pluginId, e.getMessage());
        }
        try {
            Files.deleteIfExists(plugin.jarPath());
            log.info("Plugin {} uninstalled and JAR deleted", pluginId);
            return true;
        } catch (IOException e) {
            log.error("Failed to delete JAR for plugin {}: {}", pluginId, e.getMessage());
            return false;
        }
    }

    @Override
    public void close() {
        for (LoadedPlugin plugin : plugins.values()) {
            try {
                plugin.classLoader().close();
            } catch (IOException e) {
                log.warn("Could not close classloader for plugin {}: {}",
                        plugin.info().id(), e.getMessage());
            }
        }
        plugins.clear();
    }

    private static PluginInfo readPluginInfo(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Manifest manifest = jar.getManifest();
            String baseName = jarPath.getFileName().toString().replaceFirst("\\.jar$", "");

            if (manifest == null) {
                return new PluginInfo(baseName, "0.0.0", "", "", "");
            }

            Attributes attrs = manifest.getMainAttributes();
            String id = getOrDefault(attrs, "Plugin-Id", baseName);
            String version = getOrDefault(attrs, "Plugin-Version", "0.0.0");
            String description = getOrDefault(attrs, "Plugin-Description", "");
            String provider = getOrDefault(attrs, "Plugin-Provider", "");
            String license = getOrDefault(attrs, "Plugin-License", "");

            return new PluginInfo(id, version, description, provider, license);
        }
    }

    private static String getOrDefault(Attributes attrs, String name, String defaultValue) {
        String value = attrs.getValue(name);
        return value != null ? value : defaultValue;
    }

    /** Immutable metadata read from the plugin JAR manifest. */
    public record PluginInfo(
            String id,
            String version,
            String description,
            String provider,
            String license) {
    }

    /** A loaded plugin with its classloader and enabled state. */
    public record LoadedPlugin(
            PluginInfo info,
            Path jarPath,
            URLClassLoader classLoader,
            boolean enabled) {

        public LoadedPlugin withEnabled(boolean enabled) {
            return new LoadedPlugin(info, jarPath, classLoader, enabled);
        }
    }
}
