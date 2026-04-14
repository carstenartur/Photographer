package com.photographer.focusstack.plugin;

import com.photographer.focusstack.common.camera.CameraBackend;

/**
 * Extension point for camera backend plugins.
 *
 * <p>This interface extends {@link CameraBackend} so that external plugins are
 * fully functional camera backends. Implementations must provide both the
 * camera operations (inherited from {@code CameraBackend}) and the plugin
 * metadata required by the Focus Stack plugin system.
 *
 * <p>External plugin JARs declare implementations in
 * {@code META-INF/services/com.photographer.focusstack.plugin.CameraBackendPlugin}.
 *
 * <p>Built-in camera backends (like CCAPI, PTP, Mock) do <em>not</em> need to
 * implement this interface — they are wired directly via Spring. Only external
 * runtime-loaded plugins use this SPI.
 */
public interface CameraBackendPlugin extends CameraBackend {

    /** Returns metadata describing this camera backend plugin. */
    PluginMetadata metadata();

    /**
     * Returns the unique backend type identifier (e.g. {@code "sony-wifi"}, {@code "gphoto2"}).
     * This is used to match the plugin with camera profile configuration.
     */
    String backendType();
}
