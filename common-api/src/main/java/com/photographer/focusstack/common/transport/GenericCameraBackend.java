package com.photographer.focusstack.common.transport;

import com.photographer.focusstack.common.camera.LiveViewFrame;
import com.photographer.focusstack.common.capability.CapabilitySet;
import com.photographer.focusstack.common.device.BatteryInfo;
import com.photographer.focusstack.common.device.DeviceInfo;
import com.photographer.focusstack.common.device.LensInfo;
import com.photographer.focusstack.common.device.StorageInfo;
import com.photographer.focusstack.common.media.MediaItem;
import com.photographer.focusstack.common.media.TransferResult;

import java.io.OutputStream;
import java.util.List;

/**
 * Generic, transport-independent camera backend.
 *
 * <p>The contract every transport (CCAPI/WLAN, USB/PTP, future Wi-Fi 6E,
 * folder replay …) must satisfy so that GUI and workflow code can be
 * written once.
 *
 * <p>This interface intentionally lives next to (not inside) the legacy
 * {@link com.photographer.focusstack.common.camera.CameraBackend}. The
 * legacy interface remains the pluggable extension point used by existing
 * controllers; new feature work targets this richer surface and existing
 * backends are migrated incrementally via adapters.
 *
 * <p>Lifecycle: callers obtain a {@link #connect()} handle, use it to scope
 * activity (capabilities, media listing, captures), and {@link CameraConnection#close()}
 * it when done. Backends are allowed to cache idempotent results between
 * connect/close pairs.
 */
public interface GenericCameraBackend {

    // --- Lifecycle ------------------------------------------------------------

    /** Opens a connection to the camera. */
    CameraConnection connect();

    /** Closes the supplied connection — equivalent to {@code connection.close()}. */
    default void disconnect(CameraConnection connection) {
        if (connection != null) connection.close();
    }

    // --- Identity & status ----------------------------------------------------

    DeviceInfo getDeviceInfo(CameraConnection connection);

    CapabilitySet getCapabilities(CameraConnection connection);

    StorageInfo getStorageInfo(CameraConnection connection);

    BatteryInfo getBatteryInfo(CameraConnection connection);

    LensInfo getLensInfo(CameraConnection connection);

    // --- Media ----------------------------------------------------------------

    /** Lists every media item currently visible on the camera. */
    List<MediaItem> listMedia(CameraConnection connection);

    /** Streams a single item to {@code sink} and returns the verified result. */
    TransferResult downloadMedia(CameraConnection connection, MediaItem item, OutputStream sink);

    /** Requests deletion of the camera-side item. */
    boolean deleteMedia(CameraConnection connection, MediaItem item);

    // --- Imaging --------------------------------------------------------------

    /** Releases the shutter and returns the resulting media descriptor. */
    MediaItem captureStill(CameraConnection connection);

    /** Starts the live-view stream; subsequent frames are read via {@link #nextLiveViewFrame}. */
    void startLiveView(CameraConnection connection);

    /** Stops the live-view stream. Must be called before {@link #captureStill}. */
    void stopLiveView(CameraConnection connection);

    /** Pulls the next live-view frame, or {@code null} if none is currently available. */
    default LiveViewFrame nextLiveViewFrame(CameraConnection connection) { return null; }
}
