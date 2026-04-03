package com.canon.focusstack.analysis.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MotionDetectorTest {

    private final MotionDetector detector = new MotionDetector();

    @Test
    void identicalFramesShouldNotDetectMotion() {
        byte[] frame = new byte[100];
        java.util.Arrays.fill(frame, (byte) 100);

        MotionDetector.MotionResult result = detector.detectMotion(frame, frame);
        assertEquals(0.0, result.motionScore(), 0.001);
        assertFalse(result.motionDetected());
    }

    @Test
    void differentFramesShouldDetectMotion() {
        byte[] frame1 = new byte[100];
        byte[] frame2 = new byte[100];
        java.util.Arrays.fill(frame1, (byte) 0);
        java.util.Arrays.fill(frame2, (byte) 255);

        MotionDetector.MotionResult result = detector.detectMotion(frame2, frame1);
        assertTrue(result.motionScore() > 0.5, "Different frames should have high motion score");
        assertTrue(result.motionDetected());
    }

    @Test
    void nullFramesReturnNoMotion() {
        MotionDetector.MotionResult result = detector.detectMotion(null, null);
        assertFalse(result.motionDetected());
        assertEquals(0.0, result.motionScore());
    }

    @Test
    void slightDifferenceBelowThreshold() {
        byte[] frame1 = new byte[100];
        byte[] frame2 = new byte[100];
        java.util.Arrays.fill(frame1, (byte) 100);
        java.util.Arrays.fill(frame2, (byte) 102);

        MotionDetector.MotionResult result = detector.detectMotion(frame2, frame1);
        assertFalse(result.motionDetected(), "Slight difference should be below threshold");
    }
}
