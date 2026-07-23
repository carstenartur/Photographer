package com.photographer.focusstack.camera.exception;

/** Fixed, identity-free signal for requests racing with application shutdown. */
public final class CameraServiceUnavailableException extends CameraException {
    public CameraServiceUnavailableException() {
        super("Camera services are shutting down");
    }
}
