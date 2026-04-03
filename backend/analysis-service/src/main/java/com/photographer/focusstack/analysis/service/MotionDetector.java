package com.photographer.focusstack.analysis.service;

import org.springframework.stereotype.Service;

@Service
public class MotionDetector {

    private static final double DEFAULT_THRESHOLD = 0.15;

    public record MotionResult(double motionScore, boolean motionDetected, double threshold) {}

    /**
     * Detects motion between two consecutive frames using pixel difference.
     * @param currentFrame  raw grayscale pixel data of the current frame
     * @param previousFrame raw grayscale pixel data of the previous frame
     * @return MotionResult with motion score, detected flag, and threshold
     */
    public MotionResult detectMotion(byte[] currentFrame, byte[] previousFrame) {
        return detectMotion(currentFrame, previousFrame, DEFAULT_THRESHOLD);
    }

    public MotionResult detectMotion(byte[] currentFrame, byte[] previousFrame, double threshold) {
        if (currentFrame == null || previousFrame == null) {
            return new MotionResult(0.0, false, threshold);
        }

        int length = Math.min(currentFrame.length, previousFrame.length);
        if (length == 0) {
            return new MotionResult(0.0, false, threshold);
        }

        double totalDiff = 0.0;
        for (int i = 0; i < length; i++) {
            int curr = Byte.toUnsignedInt(currentFrame[i]);
            int prev = Byte.toUnsignedInt(previousFrame[i]);
            totalDiff += Math.abs(curr - prev);
        }

        double motionScore = totalDiff / (length * 255.0);
        return new MotionResult(motionScore, motionScore > threshold, threshold);
    }
}
