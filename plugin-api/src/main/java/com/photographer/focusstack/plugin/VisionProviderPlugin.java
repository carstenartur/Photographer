package com.photographer.focusstack.plugin;

/**
 * Extension point for AI vision provider plugins.
 *
 * <p>This interface defines the contract that external vision provider plugins
 * must implement to integrate with the Focus Stack plugin system.
 *
 * <p>Built-in vision providers (like Gemini, Mock) can also implement this
 * interface to provide plugin metadata alongside their existing
 * {@code VisionProvider} functionality.
 */
public interface VisionProviderPlugin {

    /** Returns metadata describing this vision provider plugin. */
    PluginMetadata metadata();

    /**
     * Returns the unique provider name (e.g. {@code "gemini"}, {@code "openai"}).
     */
    String providerName();

    /**
     * Returns {@code true} when this provider is configured and available.
     */
    boolean isAvailable();
}
