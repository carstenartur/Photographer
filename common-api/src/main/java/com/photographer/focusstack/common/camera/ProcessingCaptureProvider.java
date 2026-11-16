package com.photographer.focusstack.common.camera;

/** Camera backends that can prefer a supported RAW file from a shutter capture. */
public interface ProcessingCaptureProvider {

    CaptureResult captureForProcessing();

    /** Uses the backend's existing capture path when it offers no format preference. */
    static CaptureResult capture(CameraBackend backend) {
        return backend instanceof ProcessingCaptureProvider provider
                ? provider.captureForProcessing()
                : backend.capture();
    }
}
