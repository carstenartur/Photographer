package com.photographer.focusstack.capture;

import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.model.CaptureFrame;

import java.util.HashMap;
import java.util.Map;

/**
 * Service for capturing a sequence of single frames with metadata.
 * Used by Face Orbit and other sequential capture modes.
 */
public class SequenceCaptureService {

    private final CameraBackend cameraBackend;

    public SequenceCaptureService(CameraBackend cameraBackend) {
        this.cameraBackend = cameraBackend;
    }

    /**
     * Captures a single frame in the sequence.
     *
     * @param sessionId     the parent session ID
     * @param sequenceIndex the position in the sequence
     * @param angle         the capture angle (for face orbit)
     * @return the captured frame with metadata
     */
    public CaptureFrame captureFrame(String sessionId, int sequenceIndex, double angle) {
        CaptureResult result = cameraBackend.capture();

        Map<String, String> metadata = new HashMap<>();
        metadata.put("angle", String.valueOf(angle));
        metadata.put("sequenceIndex", String.valueOf(sequenceIndex));

        return new CaptureFrame(
                result.imageId(),
                sessionId,
                result.fileName(),
                result.filePath(),
                result.capturedAt(),
                0.0,
                sequenceIndex,
                metadata
        );
    }
}
