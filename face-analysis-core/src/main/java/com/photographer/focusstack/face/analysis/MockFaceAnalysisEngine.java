package com.photographer.focusstack.face.analysis;

import com.photographer.focusstack.common.model.QualityCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Mock face analysis engine for testing.
 * Returns plausible but fixed analysis results.
 */
public class MockFaceAnalysisEngine implements FaceAnalysisEngine {

    private static final Logger log = LoggerFactory.getLogger(MockFaceAnalysisEngine.class);

    @Override
    public QualityCheck analyzeQuality(byte[] imageData, String frameId) {
        if (imageData == null || imageData.length == 0) {
            return new QualityCheck(frameId, 0.0, 0.0, false, 0.0, false, "No image data");
        }
        log.debug("MockFaceAnalysisEngine: analyzing quality for frame {}", frameId);
        return new QualityCheck(
                frameId,
                0.85,   // sharpnessScore
                0.90,   // exposureScore
                true,   // faceDetected
                0.95,   // faceConfidence
                true,   // passed
                "Mock quality check passed"
        );
    }

    @Override
    public List<double[]> detectLandmarks(byte[] imageData) {
        if (imageData == null || imageData.length == 0) {
            return List.of();
        }
        log.debug("MockFaceAnalysisEngine: detecting landmarks");
        // Return 5 mock facial landmarks (eyes, nose, mouth corners)
        List<double[]> landmarks = new ArrayList<>();
        landmarks.add(new double[]{100.0, 120.0}); // left eye
        landmarks.add(new double[]{200.0, 120.0}); // right eye
        landmarks.add(new double[]{150.0, 170.0}); // nose
        landmarks.add(new double[]{110.0, 210.0}); // left mouth corner
        landmarks.add(new double[]{190.0, 210.0}); // right mouth corner
        return landmarks;
    }

    @Override
    public double estimateFaceAngle(byte[] imageData) {
        if (imageData == null || imageData.length == 0) {
            return 0.0;
        }
        log.debug("MockFaceAnalysisEngine: estimating face angle");
        return 0.0; // Frontal face
    }

    @Override
    public String name() {
        return "mock-face-analysis";
    }
}
