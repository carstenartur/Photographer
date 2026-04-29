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

    /**
     * Returns the capability flags advertised by this backend.
     *
     * <p>The frontend uses these flags to gate UI controls — actions a backend
     * does not support are hidden or disabled rather than failing at runtime.
     * Higher-level services (e.g. the safe "delete after download" workflow)
     * also consult these flags before invoking potentially destructive
     * operations.</p>
     *
     * <p>The default returns {@link BackendCapabilities#none()}; plugins should
     * override this method to advertise the flags they actually implement.</p>
     */
    default BackendCapabilities capabilities() {
        return BackendCapabilities.none();
    }
}
