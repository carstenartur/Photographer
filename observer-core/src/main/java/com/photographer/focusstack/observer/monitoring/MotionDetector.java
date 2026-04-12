package com.photographer.focusstack.observer.monitoring;

import com.photographer.focusstack.observer.events.RegionOfInterest;

/**
 * Strategy interface for detecting motion between two consecutive frames.
 */
public interface MotionDetector {

    /**
     * Computes a motion score between 0.0 (no motion) and 1.0 (completely different).
     *
     * @param reference  reference (background) frame bytes
     * @param current    current frame bytes
     * @param roi        region of interest to restrict analysis
     * @return motion score in [0.0, 1.0]
     */
    double detect(byte[] reference, byte[] current, RegionOfInterest roi);

    /**
     * Estimates the relative size of the largest moving object within the ROI.
     *
     * @param frame current frame bytes
     * @param roi   region of interest
     * @return estimated size as a fraction of the ROI area (0.0–1.0)
     */
    double estimateObjectSize(byte[] frame, RegionOfInterest roi);
}
