package com.photographer.focusstack.plugin;

import org.pf4j.ExtensionPoint;

/**
 * Extension point for camera backend plugins.
 *
 * <p>This interface defines the contract that external camera backend plugins
 * must implement to integrate with the Focus Stack plugin system.
 *
 * <p>Built-in camera backends (like CCAPI, PTP) can also implement this
 * interface to provide plugin metadata alongside their existing
 * {@code CameraBackend} functionality.
 */
public interface CameraBackendPlugin extends ExtensionPoint {

    /** Returns metadata describing this camera backend plugin. */
    PluginMetadata metadata();

    /**
     * Returns the unique backend type identifier (e.g. {@code "ccapi"}, {@code "ptp"}).
     * This is used to match the plugin with camera discovery and configuration.
     */
    String backendType();

    /**
     * Returns {@code true} if this camera backend is available and operational
     * in the current environment (e.g. required native libraries are present).
     */
    boolean isAvailable();
}
