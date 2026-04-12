package com.photographer.focusstack.face.analysis;

import com.photographer.focusstack.common.model.QualityCheck;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MockFaceAnalysisEngineTest {

    private final MockFaceAnalysisEngine engine = new MockFaceAnalysisEngine();

    @Test
    void analyzeQuality_withData_returnsPassed() {
        QualityCheck result = engine.analyzeQuality(new byte[]{1, 2, 3}, "frame-1");

        assertTrue(result.passed());
        assertTrue(result.faceDetected());
        assertEquals("frame-1", result.frameId());
        assertTrue(result.sharpnessScore() > 0);
        assertTrue(result.faceConfidence() > 0);
    }

    @Test
    void analyzeQuality_nullData_returnsFailed() {
        QualityCheck result = engine.analyzeQuality(null, "frame-1");

        assertFalse(result.passed());
        assertFalse(result.faceDetected());
    }

    @Test
    void analyzeQuality_emptyData_returnsFailed() {
        QualityCheck result = engine.analyzeQuality(new byte[0], "frame-1");

        assertFalse(result.passed());
    }

    @Test
    void detectLandmarks_withData_returnsLandmarks() {
        List<double[]> landmarks = engine.detectLandmarks(new byte[]{1, 2, 3});

        assertFalse(landmarks.isEmpty());
        assertEquals(5, landmarks.size());
        assertEquals(2, landmarks.get(0).length);
    }

    @Test
    void detectLandmarks_nullData_returnsEmpty() {
        List<double[]> landmarks = engine.detectLandmarks(null);

        assertTrue(landmarks.isEmpty());
    }

    @Test
    void estimateFaceAngle_withData_returnsAngle() {
        double angle = engine.estimateFaceAngle(new byte[]{1, 2, 3});

        assertTrue(angle >= -90 && angle <= 90);
    }

    @Test
    void estimateFaceAngle_nullData_returnsZero() {
        double angle = engine.estimateFaceAngle(null);

        assertEquals(0.0, angle);
    }

    @Test
    void name_returnsMockFaceAnalysis() {
        assertEquals("mock-face-analysis", engine.name());
    }
}
