package com.photographer.focusstack.common.camera;

import com.photographer.focusstack.common.device.BatteryInfo;
import com.photographer.focusstack.common.device.StorageInfo;

public interface CameraBackend {

    CameraInfo getInfo();

    LiveViewFrame getLiveView();

    /**
     * Stops the remote live-view stream on the camera.
     *
     * <p>On Canon CCAPI cameras the live-view stream locks the camera into a
     * "shooting or recording" state that blocks shutter release and SD-card
     * access. This method must be called before {@link #capture()} to avoid
     * {@code 503 "Device busy"} errors.
     *
     * <p>The default implementation is a no-op for backends that do not
     * require explicit live-view management.
     */
    default void stopLiveView() { /* no-op */ }

    CaptureResult capture();

    void setFocus(FocusCommand command);

    CameraState getState();

    boolean supports(Operation operation);

    /**
     * Returns transport-neutral battery details when the backend exposes them.
     *
     * <p>The legacy {@link CameraState} percentage remains available for
     * compatibility, while this richer contract preserves vendor-reported level,
     * kind and quality without forcing callers to know the transport.</p>
     */
    default BatteryInfo getBatteryInfo() {
        return BatteryInfo.unknown();
    }

    /**
     * Returns every storage volume visible to the backend.
     *
     * <p>Backends that only implement the legacy aggregate free-space field may
     * keep returning {@link StorageInfo#empty()}; adapters can retain a temporary
     * compatibility mapping from {@link CameraState#storageAvailable()}.</p>
     */
    default StorageInfo getStorageInfo() {
        return StorageInfo.empty();
    }

    /**
     * Returns the current exposure settings (aperture, shutter speed, ISO, EV).
     * The default returns an empty snapshot for backends that don't support reads.
     */
    default ExposureInfo getExposureInfo() {
        return ExposureInfo.ofStrings("unknown", "unknown", "unknown");
    }

    /**
     * Sets the ISO value. Pass the <em>raw</em> vendor property code.
     * The default is a no-op for backends that don't support writes.
     */
    default void setIso(int rawValue) { /* no-op */ }

    /**
     * Sets the shutter speed. Pass the <em>raw</em> vendor property code.
     * The default is a no-op for backends that don't support writes.
     */
    default void setShutterSpeed(int rawValue) { /* no-op */ }

    /**
     * Sets the aperture. Pass the <em>raw</em> vendor property code.
     * The default is a no-op for backends that don't support writes.
     */
    default void setAperture(int rawValue) { /* no-op */ }
}
