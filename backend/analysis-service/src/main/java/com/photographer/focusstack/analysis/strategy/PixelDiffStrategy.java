package com.photographer.focusstack.analysis.strategy;

import com.photographer.focusstack.analysis.service.MotionDetector;

/**
 * {@link MotionDetectionStrategy} backed by a simple pixel-difference algorithm.
 *
 * <p>Each corresponding pixel pair is compared; the mean absolute difference
 * (normalised to 0–1) is used as the motion score. Fast and dependency-free.
 */
public class PixelDiffStrategy implements MotionDetectionStrategy {

    private final MotionDetector delegate;

    public PixelDiffStrategy(MotionDetector delegate) {
        this.delegate = delegate;
    }

    @Override
    public MotionDetector.MotionResult detect(byte[] current, byte[] previous, double threshold) {
        return delegate.detectMotion(current, previous);
    }

    @Override
    public String name() {
        return "pixel-diff";
    }
}
