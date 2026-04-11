package com.photographer.ptp.session;

/**
 * Describes the capabilities of a connected PTP camera.
 */
public record CameraCapabilities(
    boolean liveView,
    boolean capture,
    boolean focusDrive,
    boolean zoom,
    boolean propertiesRead,
    boolean propertiesWrite,
    boolean rawDownload
) {
    /** Camera with no known capabilities. */
    public static CameraCapabilities none() {
        return new CameraCapabilities(false, false, false, false, false, false, false);
    }

    /** Camera with all capabilities enabled. */
    public static CameraCapabilities all() {
        return new CameraCapabilities(true, true, true, true, true, true, true);
    }
}
