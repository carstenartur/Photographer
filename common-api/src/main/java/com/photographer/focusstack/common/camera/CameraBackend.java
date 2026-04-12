package com.photographer.focusstack.common.camera;

public interface CameraBackend {

    CameraInfo getInfo();

    LiveViewFrame getLiveView();

    /**
     * Stops the remote live-view stream on the camera.
     *
     * <p>On Canon CCAPI cameras the live-view stream locks the camera into a
     * "shooting or recording" state that blocks shutter release and SD-card
     * access.  This method must be called before {@link #capture()} to avoid
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
}
