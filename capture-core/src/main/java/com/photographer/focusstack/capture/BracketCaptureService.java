package com.photographer.focusstack.capture;

import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.model.BracketSet;
import com.photographer.focusstack.common.model.CaptureFrame;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service for capturing bracket sets (exposure series) using a CameraBackend.
 * Each bracket set contains multiple frames at different exposure values.
 */
public class BracketCaptureService {

    private final CameraBackend cameraBackend;

    public BracketCaptureService(CameraBackend cameraBackend) {
        this.cameraBackend = cameraBackend;
    }

    /**
     * Captures a bracket set with the specified number of frames and EV step.
     *
     * @param sessionId    the parent session ID
     * @param bracketCount number of exposures in the bracket (e.g. 3 for -1, 0, +1)
     * @param evStep       exposure value step between brackets
     * @param startIndex   sequence start index for frame numbering
     * @return the captured bracket set
     */
    public BracketSet captureBracket(String sessionId, int bracketCount, double evStep, int startIndex) {
        String bracketSetId = UUID.randomUUID().toString();
        List<CaptureFrame> frames = new ArrayList<>();
        Instant capturedAt = Instant.now();

        for (int i = 0; i < bracketCount; i++) {
            double ev = calculateEvOffset(i, bracketCount, evStep);
            CaptureResult result = cameraBackend.capture();

            Map<String, String> metadata = new HashMap<>();
            metadata.put("bracketSetId", bracketSetId);
            metadata.put("bracketIndex", String.valueOf(i));
            metadata.put("evOffset", String.valueOf(ev));

            CaptureFrame frame = new CaptureFrame(
                    result.imageId(),
                    sessionId,
                    result.fileName(),
                    result.filePath(),
                    result.capturedAt(),
                    ev,
                    startIndex + i,
                    metadata
            );
            frames.add(frame);
        }

        return new BracketSet(bracketSetId, sessionId, capturedAt, frames, null);
    }

    /**
     * Calculates the EV offset for a given bracket index.
     * For 3 brackets with step 1.0: -1.0, 0.0, +1.0
     * For 5 brackets with step 0.5: -1.0, -0.5, 0.0, +0.5, +1.0
     */
    static double calculateEvOffset(int index, int bracketCount, double evStep) {
        int center = bracketCount / 2;
        return (index - center) * evStep;
    }
}
