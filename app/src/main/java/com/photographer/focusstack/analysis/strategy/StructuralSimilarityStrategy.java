package com.photographer.focusstack.analysis.strategy;

import com.photographer.focusstack.analysis.service.ImageJProcessor;
import com.photographer.focusstack.analysis.service.MotionDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Motion detection strategy based on structural similarity (SSIM-like).
 *
 * <p>Focus stacking intentionally changes the blur level between frames, which
 * causes large pixel-intensity differences even when the subject has not moved.
 * A naïve pixel-diff algorithm misinterprets these expected blur changes as
 * subject motion and aborts the stack prematurely.
 *
 * <p>This strategy compares the <em>structural content</em> of two frames by
 * first extracting edges (Sobel gradient magnitude) and then computing the
 * normalised cross-correlation of the edge maps. Edge structure is largely
 * invariant to defocus blur — the same edges appear in both a sharp and a
 * blurry image, only their contrast changes. Real subject motion, however,
 * shifts the edge positions, producing a low correlation score.
 *
 * <p>The motion score reported is {@code 1.0 - structuralSimilarity}, so a
 * higher score means more detected motion (consistent with the other
 * strategies).
 *
 * <p>Falls back to {@link PixelDiffStrategy} when the input is not a
 * decodable image file (e.g. synthetic test byte arrays).
 */
public class StructuralSimilarityStrategy implements MotionDetectionStrategy {

    private static final Logger log = LoggerFactory.getLogger(StructuralSimilarityStrategy.class);

    private final ImageJProcessor imageJProcessor;
    private final PixelDiffStrategy fallback;

    public StructuralSimilarityStrategy(ImageJProcessor imageJProcessor,
                                        MotionDetector motionDetector) {
        this.imageJProcessor = imageJProcessor;
        this.fallback = new PixelDiffStrategy(motionDetector);
    }

    @Override
    public MotionDetector.MotionResult detect(byte[] current, byte[] previous, double threshold) {
        if (current == null || previous == null) {
            return new MotionDetector.MotionResult(0.0, false, threshold);
        }

        // For real image files, use edge-based structural similarity
        if (imageJProcessor.isImageFile(current) && imageJProcessor.isImageFile(previous)) {
            return imageJProcessor.detectStructuralMotion(current, previous, threshold);
        }

        // For synthetic / raw byte arrays, use edge-based comparison on raw data
        return detectOnRawBytes(current, previous, threshold);
    }

    /**
     * Edge-based motion detection for raw (non-image-file) byte arrays.
     *
     * <p>Treats the input as a flat grayscale image, estimates its width,
     * computes a Sobel gradient magnitude map, and correlates the two maps.
     */
    MotionDetector.MotionResult detectOnRawBytes(byte[] current, byte[] previous, double threshold) {
        int length = Math.min(current.length, previous.length);
        if (length < 9) {
            return new MotionDetector.MotionResult(0.0, false, threshold);
        }

        int width = estimateWidth(length);
        int height = length / width;
        if (width < 3 || height < 3) {
            return new MotionDetector.MotionResult(0.0, false, threshold);
        }

        double[] edgesCurrent = sobelMagnitude(current, width, height);
        double[] edgesPrevious = sobelMagnitude(previous, width, height);

        double similarity = normalizedCrossCorrelation(edgesCurrent, edgesPrevious);
        double motionScore = 1.0 - similarity;

        log.debug("StructuralSimilarity (raw): similarity={}, motionScore={}, threshold={}",
                String.format("%.4f", similarity),
                String.format("%.4f", motionScore),
                threshold);

        return new MotionDetector.MotionResult(
                Math.max(0.0, motionScore),
                motionScore > threshold,
                threshold);
    }

    /**
     * Computes Sobel gradient magnitude for a grayscale byte array.
     * Returns an array of size (width-2)*(height-2) covering the interior pixels.
     */
    static double[] sobelMagnitude(byte[] data, int width, int height) {
        int innerW = width - 2;
        int innerH = height - 2;
        double[] result = new double[innerW * innerH];

        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                // Sobel X kernel: [-1 0 1; -2 0 2; -1 0 1]
                double gx =
                        -pixel(data, width, x - 1, y - 1) + pixel(data, width, x + 1, y - 1)
                        - 2.0 * pixel(data, width, x - 1, y) + 2.0 * pixel(data, width, x + 1, y)
                        - pixel(data, width, x - 1, y + 1) + pixel(data, width, x + 1, y + 1);

                // Sobel Y kernel: [-1 -2 -1; 0 0 0; 1 2 1]
                double gy =
                        -pixel(data, width, x - 1, y - 1) - 2.0 * pixel(data, width, x, y - 1) - pixel(data, width, x + 1, y - 1)
                        + pixel(data, width, x - 1, y + 1) + 2.0 * pixel(data, width, x, y + 1) + pixel(data, width, x + 1, y + 1);

                result[(y - 1) * innerW + (x - 1)] = Math.sqrt(gx * gx + gy * gy);
            }
        }
        return result;
    }

    /**
     * Normalised cross-correlation of two same-length arrays.
     * Returns a value in [0, 1] where 1 = identical structure.
     */
    static double normalizedCrossCorrelation(double[] a, double[] b) {
        if (a.length != b.length || a.length == 0) return 0.0;

        double meanA = 0, meanB = 0;
        for (int i = 0; i < a.length; i++) {
            meanA += a[i];
            meanB += b[i];
        }
        meanA /= a.length;
        meanB /= b.length;

        double sumAB = 0, sumAA = 0, sumBB = 0;
        for (int i = 0; i < a.length; i++) {
            double da = a[i] - meanA;
            double db = b[i] - meanB;
            sumAB += da * db;
            sumAA += da * da;
            sumBB += db * db;
        }

        double denom = Math.sqrt(sumAA * sumBB);
        if (denom < 1e-10) return 1.0; // both constant → identical

        double ncc = sumAB / denom;
        // Clamp to [0, 1] — negative correlation treated as complete dissimilarity
        return Math.max(0.0, Math.min(1.0, ncc));
    }

    private static int pixel(byte[] data, int width, int x, int y) {
        return Byte.toUnsignedInt(data[y * width + x]);
    }

    /** Estimates the image width from total byte count (assumes square-ish). */
    private static int estimateWidth(int length) {
        int sqrt = (int) Math.sqrt(length);
        while (sqrt > 1 && length % sqrt != 0) {
            sqrt--;
        }
        return Math.max(sqrt, 1);
    }

    @Override
    public String name() {
        return "structural-similarity";
    }
}
