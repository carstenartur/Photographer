package com.photographer.focusstack.cr3.reference;

import java.util.List;
import java.util.Objects;

/**
 * Spatial sample metrics for reference-backed CR3 tests.
 *
 * <p>The methods operate on row-major, single-band images. Signed error is
 * defined as {@code actual - reference}. Regions are aggregated as supplied;
 * overlapping regions therefore count overlapping pixels more than once.</p>
 */
public final class SpatialReferenceMetrics {

    private SpatialReferenceMetrics() {
    }

    /** Integer rectangle using an exclusive right and bottom edge. */
    public record Rect(int x, int y, int width, int height) {
        public Rect {
            if (x < 0 || y < 0 || width < 0 || height < 0) {
                throw new IllegalArgumentException("Rectangle coordinates and size must be non-negative");
            }
        }

        /** Clips left/top/right/bottom coordinates to the supplied image. */
        public static Rect clip(int left, int top, int right, int bottom,
                                int imageWidth, int imageHeight) {
            validateDimensions(imageWidth, imageHeight);
            int x0 = Math.max(0, Math.min(imageWidth, left));
            int y0 = Math.max(0, Math.min(imageHeight, top));
            int x1 = Math.max(x0, Math.min(imageWidth, right));
            int y1 = Math.max(y0, Math.min(imageHeight, bottom));
            return new Rect(x0, y0, x1 - x0, y1 - y0);
        }

        public boolean isEmpty() {
            return width == 0 || height == 0;
        }
    }

    /** Population statistics for signed sample differences. */
    public record DifferenceStats(long sampleCount, double mae, double rmse,
                                  double meanError, double errorStdDev) {
    }

    /**
     * Compares all pixels selected by the supplied rectangles.
     *
     * @throws IllegalArgumentException if a rectangle exceeds the image or no
     *                                  sample is selected
     */
    public static DifferenceStats compareRegions(int[] reference, int[] actual,
                                                 int width, int height,
                                                 List<Rect> regions) {
        validateImagePair(reference, actual, width, height);
        Objects.requireNonNull(regions, "regions");

        Accumulator accumulator = new Accumulator();
        for (Rect region : regions) {
            Objects.requireNonNull(region, "region");
            validateRect(region, width, height);
            for (int y = region.y(); y < region.y() + region.height(); y++) {
                int row = y * width;
                for (int x = region.x(); x < region.x() + region.width(); x++) {
                    accumulator.add(reference[row + x], actual[row + x]);
                }
            }
        }
        return accumulator.result("Regions select no samples");
    }

    /**
     * Compares one regular grid plane, for example one Bayer position in a
     * two-by-two CFA cell.
     */
    public static DifferenceStats compareGrid(int[] reference, int[] actual,
                                              int width, int height,
                                              int rowOffset, int columnOffset,
                                              int rowStep, int columnStep) {
        validateImagePair(reference, actual, width, height);
        if (rowStep <= 0 || columnStep <= 0) {
            throw new IllegalArgumentException("Grid steps must be positive");
        }
        if (rowOffset < 0 || rowOffset >= rowStep
                || columnOffset < 0 || columnOffset >= columnStep) {
            throw new IllegalArgumentException("Grid offsets must be within their step");
        }

        Accumulator accumulator = new Accumulator();
        for (int y = rowOffset; y < height; y += rowStep) {
            int row = y * width;
            for (int x = columnOffset; x < width; x += columnStep) {
                accumulator.add(reference[row + x], actual[row + x]);
            }
        }
        return accumulator.result("Grid selects no samples");
    }

    /**
     * Returns the mean absolute decoder error on both sides of every internal
     * tile seam. Pixels at horizontal/vertical seam intersections are counted
     * once per seam direction, matching the historical CR3 reports.
     *
     * @return {@link Double#NaN} when the image contains no internal tile seam
     */
    public static double tileBoundaryMae(int[] reference, int[] actual,
                                         int width, int height,
                                         int tileWidth, int tileHeight) {
        validateImagePair(reference, actual, width, height);
        if (tileWidth <= 0 || tileHeight <= 0) {
            throw new IllegalArgumentException("Tile dimensions must be positive");
        }

        double absoluteSum = 0.0;
        long count = 0L;
        for (int seamX = tileWidth; seamX < width; seamX += tileWidth) {
            int left = seamX - 1;
            int right = seamX;
            for (int y = 0; y < height; y++) {
                int row = y * width;
                absoluteSum += Math.abs(actual[row + left] - reference[row + left]);
                absoluteSum += Math.abs(actual[row + right] - reference[row + right]);
                count += 2;
            }
        }
        for (int seamY = tileHeight; seamY < height; seamY += tileHeight) {
            int top = (seamY - 1) * width;
            int bottom = seamY * width;
            for (int x = 0; x < width; x++) {
                absoluteSum += Math.abs(actual[top + x] - reference[top + x]);
                absoluteSum += Math.abs(actual[bottom + x] - reference[bottom + x]);
                count += 2;
            }
        }
        return count == 0L ? Double.NaN : absoluteSum / count;
    }

    private static void validateImagePair(int[] reference, int[] actual, int width, int height) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(actual, "actual");
        validateDimensions(width, height);
        long expected = (long) width * height;
        if (expected > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Image is too large for int[] samples");
        }
        if (reference.length != expected || actual.length != expected) {
            throw new IllegalArgumentException("Image sample count mismatch: expected=" + expected
                    + ", reference=" + reference.length + ", actual=" + actual.length);
        }
    }

    private static void validateDimensions(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Image dimensions must be positive");
        }
    }

    private static void validateRect(Rect rect, int width, int height) {
        long right = (long) rect.x() + rect.width();
        long bottom = (long) rect.y() + rect.height();
        if (right > width || bottom > height) {
            throw new IllegalArgumentException("Rectangle exceeds image: " + rect
                    + " for " + width + "x" + height);
        }
    }

    private static final class Accumulator {
        private long count;
        private double absoluteSum;
        private double squareSum;
        private double signedSum;

        private void add(int reference, int actual) {
            double difference = actual - (double) reference;
            count++;
            absoluteSum += Math.abs(difference);
            squareSum += difference * difference;
            signedSum += difference;
        }

        private DifferenceStats result(String emptyMessage) {
            if (count == 0L) {
                throw new IllegalArgumentException(emptyMessage);
            }
            double mean = signedSum / count;
            double meanSquare = squareSum / count;
            double variance = Math.max(0.0, meanSquare - mean * mean);
            return new DifferenceStats(count, absoluteSum / count,
                    Math.sqrt(meanSquare), mean, Math.sqrt(variance));
        }
    }
}
