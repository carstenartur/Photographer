package com.photographer.focusstack.analysis.strategy;

import com.photographer.focusstack.analysis.service.MotionDetector;

/**
 * Strategy interface for detecting motion between two consecutive frames.
 *
 * <p>Implementations can use different algorithms ranging from a simple pixel
 * difference to full optical-flow estimation.
 */
public interface MotionDetectionStrategy {

    /**
     * Computes the motion score between two raw image byte arrays.
     *
     * @param current   raw bytes of the current frame
     * @param previous  raw bytes of the previous frame
     * @param threshold normalised threshold above which motion is flagged (0.0–1.0)
     * @return a {@link MotionDetector.MotionResult} with score, flag, and threshold
     */
    MotionDetector.MotionResult detect(byte[] current, byte[] previous, double threshold);

    /** Human-readable name of this strategy (for logging and configuration). */
    String name();
}
