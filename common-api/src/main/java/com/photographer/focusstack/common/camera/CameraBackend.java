package com.photographer.focusstack.common.camera;

public interface CameraBackend {

    CameraInfo getInfo();

    LiveViewFrame getLiveView();

    CaptureResult capture();

    void setFocus(FocusCommand command);

    CameraState getState();

    boolean supports(Operation operation);
}
