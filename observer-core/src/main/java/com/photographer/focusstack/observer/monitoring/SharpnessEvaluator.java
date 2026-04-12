package com.photographer.focusstack.observer.monitoring;

import com.photographer.focusstack.observer.events.RegionOfInterest;

/**
 * Evaluates image sharpness using a Laplacian-variance approximation.
 *
 * <p>A higher score indicates a sharper image. Used for best-shot selection
 * within a burst capture series.</p>
 *
 * <p>This implementation works on raw byte arrays without requiring external
 * image-processing libraries.</p>
 */
public class SharpnessEvaluator {

    /**
     * Evaluates sharpness of the given image bytes within the specified ROI.
     *
     * @param imageBytes raw JPEG or raw image bytes
     * @param roi        region of interest
     * @return sharpness score in [0.0, 1.0]; 0.0 for empty/null input
     */
    public double evaluate(byte[] imageBytes, RegionOfInterest roi) {
        if (imageBytes == null || imageBytes.length < 3) {
            return 0.0;
        }
        // Note: ROI position is not meaningfully supported on raw compressed bytes;
        // only the ROI area (width*height) determines the evaluation window.
        int roiLen = (int) (imageBytes.length * roi.width() * roi.height());
        int start = 0;
        int end = Math.min(imageBytes.length, Math.max(3, roiLen));
        if (end - start < 3) {
            start = 0;
            end = imageBytes.length;
        }

        // Approximate Laplacian: second derivative along the byte stream
        double laplacianSum = 0;
        int count = 0;
        for (int i = start + 1; i < end - 1; i++) {
            int prev = imageBytes[i - 1] & 0xFF;
            int curr = imageBytes[i] & 0xFF;
            int next = imageBytes[i + 1] & 0xFF;
            double lap = Math.abs(prev - 2.0 * curr + next);
            laplacianSum += lap;
            count++;
        }
        if (count == 0) {
            return 0.0;
        }
        double mean = laplacianSum / count;
        // Normalize: typical sharp images have mean Laplacian ~5–20 on compressed bytes
        return Math.min(1.0, mean / 20.0);
    }
}
