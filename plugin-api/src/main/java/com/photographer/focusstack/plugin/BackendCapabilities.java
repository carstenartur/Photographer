package com.photographer.focusstack.plugin;

/**
 * Capability flags describing what a {@link CameraBackendPlugin} supports.
 *
 * <p>The flags form a stable contract between the camera backend (CCAPI, PTP,
 * Sony, Nikon, gphoto2, …) and the rest of the application:</p>
 * <ul>
 *   <li>The frontend uses these flags to gate UI controls — actions a backend
 *       does not support are hidden or rendered as disabled hints rather than
 *       failing at runtime with a cryptic error.</li>
 *   <li>Higher-level services (e.g. the safe "delete after download" workflow,
 *       see Sprint 3) refuse to perform potentially destructive operations
 *       unless the backend explicitly advertises support.</li>
 * </ul>
 *
 * <p>All flags default to {@code false} via {@link #none()}; backends opt-in
 * to each capability they implement.</p>
 *
 * @param supportsFocusDrive           backend can drive autofocus to a relative or absolute position
 * @param supportsContentsListing      backend can enumerate files stored on the camera
 * @param supportsDeleteAfterDownload  backend can delete files from the camera after a successful download
 * @param supportsVideoCapture         backend can start/stop video recording
 * @param supportsLocalVisionAnalysis  backend can perform AI vision analysis on-device (no cloud)
 * @param supportsUSB                  backend can connect over USB
 * @param supportsWiFi                 backend can connect over Wi-Fi (or other IP network transport)
 */
public record BackendCapabilities(
        boolean supportsFocusDrive,
        boolean supportsContentsListing,
        boolean supportsDeleteAfterDownload,
        boolean supportsVideoCapture,
        boolean supportsLocalVisionAnalysis,
        boolean supportsUSB,
        boolean supportsWiFi
) {

    /** Returns a capabilities descriptor with every flag set to {@code false}. */
    public static BackendCapabilities none() {
        return new BackendCapabilities(false, false, false, false, false, false, false);
    }

    /** Returns a capabilities descriptor with every flag set to {@code true}. */
    public static BackendCapabilities all() {
        return new BackendCapabilities(true, true, true, true, true, true, true);
    }

    /** Convenience builder for incremental construction. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Mutable builder for {@link BackendCapabilities}. Designed for plugin
     * authors who want to set a few flags without remembering the constructor
     * argument order.
     */
    public static final class Builder {
        private boolean focusDrive;
        private boolean contentsListing;
        private boolean deleteAfterDownload;
        private boolean videoCapture;
        private boolean localVisionAnalysis;
        private boolean usb;
        private boolean wifi;

        private Builder() {}

        public Builder focusDrive(boolean v) { this.focusDrive = v; return this; }
        public Builder contentsListing(boolean v) { this.contentsListing = v; return this; }
        public Builder deleteAfterDownload(boolean v) { this.deleteAfterDownload = v; return this; }
        public Builder videoCapture(boolean v) { this.videoCapture = v; return this; }
        public Builder localVisionAnalysis(boolean v) { this.localVisionAnalysis = v; return this; }
        public Builder usb(boolean v) { this.usb = v; return this; }
        public Builder wifi(boolean v) { this.wifi = v; return this; }

        public BackendCapabilities build() {
            return new BackendCapabilities(focusDrive, contentsListing, deleteAfterDownload,
                    videoCapture, localVisionAnalysis, usb, wifi);
        }
    }
}
