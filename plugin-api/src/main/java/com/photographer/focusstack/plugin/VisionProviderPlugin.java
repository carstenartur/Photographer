package com.photographer.focusstack.plugin;

import com.photographer.focusstack.ai.VisionProvider;

/**
 * Extension point for AI vision provider plugins.
 *
 * <p>This interface extends {@link VisionProvider} so that external plugins are
 * fully functional vision providers. Implementations must provide both the
 * vision operations (inherited from {@code VisionProvider}) and the plugin
 * metadata required by the Focus Stack plugin system.
 *
 * <p>External plugin JARs declare implementations in
 * {@code META-INF/services/com.photographer.focusstack.plugin.VisionProviderPlugin}.
 *
 * <p>Built-in vision providers (like Gemini, Mock) do <em>not</em> need to
 * implement this interface — they are wired directly via Spring. Only external
 * runtime-loaded plugins use this SPI.
 */
public interface VisionProviderPlugin extends VisionProvider {

    /** Returns metadata describing this vision provider plugin. */
    PluginMetadata metadata();

    /**
     * Returns the unique provider name (e.g. {@code "openai"}, {@code "anthropic"}).
     * This overrides {@link VisionProvider#name()} to serve as both the SPI
     * identifier and the display name.
     */
    @Override
    String name();
}
