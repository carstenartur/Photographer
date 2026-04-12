package com.photographer.focusstack.observer.monitoring;

import com.photographer.focusstack.observer.events.RegionOfInterest;

/**
 * Detects whether detected motion is caused by wind (global image shift)
 * rather than an animal (local motion).
 *
 * <p>Wind causes the entire frame (or a large portion) to shift uniformly,
 * while an animal creates localized motion within the ROI.</p>
 */
public class WindFilter {

    /**
     * Fraction of the frame that must show motion before it is classified as wind.
     * Default: 0.6 (60% of the frame).
     */
    private final double globalMotionFraction;
    private final PixelDiffMotionDetector detector;

    public WindFilter() {
        this(0.6);
    }

    public WindFilter(double globalMotionFraction) {
        this.globalMotionFraction = globalMotionFraction;
        this.detector = new PixelDiffMotionDetector();
    }

    /**
     * Returns {@code true} if the motion between {@code reference} and {@code current}
     * appears to be caused by wind rather than a subject within the ROI.
     */
    public boolean isWindMotion(byte[] reference, byte[] current, RegionOfInterest roi) {
        if (reference == null || current == null || reference.length == 0) {
            return false;
        }
        // Compare motion inside ROI vs. full frame
        double roiMotion = detector.detect(reference, current, roi);
        double fullMotion = detector.detect(reference, current, RegionOfInterest.fullFrame());
        // If full-frame motion is much greater than ROI motion, it is likely wind
        return fullMotion >= globalMotionFraction && (fullMotion / Math.max(roiMotion, 0.001)) > 1.5;
    }
}
