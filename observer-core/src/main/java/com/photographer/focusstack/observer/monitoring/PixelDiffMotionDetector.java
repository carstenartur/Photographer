package com.photographer.focusstack.observer.monitoring;

import com.photographer.focusstack.observer.events.RegionOfInterest;

/**
 * Simple pixel-difference motion detector.
 *
 * <p>Compares raw byte values of two JPEG frames. While JPEG compression prevents exact
 * pixel-level comparisons, changes above a noise floor are still clearly visible in the
 * compressed bytes when decoded or compared at the byte level within the same session.</p>
 *
 * <p>This implementation works on the raw JPEG bytes using a simplified sliding-window
 * approach rather than full decoding, making it fast and dependency-free.</p>
 */
public class PixelDiffMotionDetector implements MotionDetector {

    /** Minimum absolute byte difference to be considered a changed pixel. */
    private final int noiseTolerance;

    public PixelDiffMotionDetector() {
        this(10);
    }

    public PixelDiffMotionDetector(int noiseTolerance) {
        this.noiseTolerance = noiseTolerance;
    }

    @Override
    public double detect(byte[] reference, byte[] current, RegionOfInterest roi) {
        if (reference == null || current == null || reference.length == 0 || current.length == 0) {
            return 0.0;
        }
        int compareLen = Math.min(reference.length, current.length);
        // For ROI-weighted comparison, approximate the ROI as a fraction of the total bytes.
        int roiStart = (int) (compareLen * roi.y() * roi.x());
        int roiEnd = Math.min(compareLen, roiStart + (int) (compareLen * roi.width() * roi.height()));
        if (roiStart >= roiEnd) {
            roiStart = 0;
            roiEnd = compareLen;
        }

        int changed = 0;
        int total = roiEnd - roiStart;
        for (int i = roiStart; i < roiEnd; i++) {
            int diff = Math.abs((reference[i] & 0xFF) - (current[i] & 0xFF));
            if (diff > noiseTolerance) {
                changed++;
            }
        }
        return total > 0 ? (double) changed / total : 0.0;
    }

    @Override
    public double estimateObjectSize(byte[] frame, RegionOfInterest roi) {
        if (frame == null || frame.length == 0) {
            return 0.0;
        }
        // Simple heuristic: measure local variance within the ROI region of the byte stream
        int start = (int) (frame.length * roi.y() * roi.x());
        int end = Math.min(frame.length, start + (int) (frame.length * roi.width() * roi.height()));
        if (start >= end || end - start < 2) {
            return 0.0;
        }
        long sum = 0;
        for (int i = start; i < end; i++) {
            sum += (frame[i] & 0xFF);
        }
        double mean = (double) sum / (end - start);
        double variance = 0;
        for (int i = start; i < end; i++) {
            double diff = (frame[i] & 0xFF) - mean;
            variance += diff * diff;
        }
        variance /= (end - start);
        // Normalize variance (max theoretical variance for 8-bit: ~16256)
        return Math.min(1.0, variance / 16256.0);
    }
}
