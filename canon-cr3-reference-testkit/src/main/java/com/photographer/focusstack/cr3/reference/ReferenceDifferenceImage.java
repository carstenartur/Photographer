package com.photographer.focusstack.cr3.reference;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Objects;

/**
 * Deterministic diagnostic images for visualizing sample differences.
 *
 * <p>The absolute heat-map convention is the one historically used by the active
 * CR3 reference-comparison and pedestal experiments:</p>
 *
 * <ol>
 *   <li>Calculate {@code abs(actual + actualOffset - reference)}.</li>
 *   <li>Divide by a positive display divisor using integer division.</li>
 *   <li>Clamp the resulting intensity to {@code [0, 255]}.</li>
 *   <li>Write intensity to red and half intensity to green; blue remains zero.</li>
 * </ol>
 *
 * <p>Identical samples are black. Larger absolute differences progress from dark
 * red through orange and saturate at RGB {@code (255, 127, 0)}. The image is a
 * visualization only; quantitative decisions must use the unscaled metric APIs.</p>
 */
public final class ReferenceDifferenceImage {

    /** Historical CR3 visualization gain: one display level per eight RAW levels. */
    public static final int DEFAULT_DISPLAY_DIVISOR = 8;

    private ReferenceDifferenceImage() {
    }

    /** Renders the complete image with zero actual offset and the default divisor. */
    public static BufferedImage absoluteHeatmap(ReferenceImage reference,
                                                ReferenceImage actual) {
        return absoluteHeatmap(reference, actual, 0, DEFAULT_DISPLAY_DIVISOR);
    }

    /** Renders the complete image with the supplied actual offset and divisor. */
    public static BufferedImage absoluteHeatmap(ReferenceImage reference,
                                                ReferenceImage actual,
                                                int actualOffset,
                                                int displayDivisor) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(actual, "actual");
        requireSameGeometry(reference, actual);
        return absoluteHeatmap(
                reference.samples(),
                actual.samples(),
                reference.width(),
                reference.height(),
                new SpatialReferenceMetrics.Rect(
                        0, 0, reference.width(), reference.height()),
                actualOffset,
                displayDivisor);
    }

    /**
     * Renders one rectangular image region without allocating cropped sample arrays.
     *
     * @param reference row-major reference samples
     * @param actual row-major decoder samples
     * @param imageWidth full source-image width
     * @param imageHeight full source-image height
     * @param region region to render, using exclusive right/bottom semantics
     * @param actualOffset constant added to every actual sample before comparison
     * @param displayDivisor positive divisor controlling visual gain
     */
    public static BufferedImage absoluteHeatmap(int[] reference,
                                                int[] actual,
                                                int imageWidth,
                                                int imageHeight,
                                                SpatialReferenceMetrics.Rect region,
                                                int actualOffset,
                                                int displayDivisor) {
        validateInputs(reference, actual, imageWidth, imageHeight, region, displayDivisor);

        BufferedImage image = new BufferedImage(
                region.width(), region.height(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < region.height(); y++) {
            int sourceRow = (region.y() + y) * imageWidth + region.x();
            for (int x = 0; x < region.width(); x++) {
                long difference = Math.abs(
                        actual[sourceRow + x] + (long) actualOffset - reference[sourceRow + x]);
                int intensity = (int) Math.min(255L, difference / displayDivisor);
                int rgb = (intensity << 16) | ((intensity / 2) << 8);
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }

    /**
     * Scales a diagnostic image to the requested output size using bicubic
     * interpolation, matching the active CR3 report generators.
     */
    public static BufferedImage scale(BufferedImage source, int width, int height) {
        Objects.requireNonNull(source, "source");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Output dimensions must be positive");
        }
        if (source.getWidth() == width && source.getHeight() == height) {
            return source;
        }

        int type = source.getType() == BufferedImage.TYPE_CUSTOM
                ? BufferedImage.TYPE_INT_RGB : source.getType();
        BufferedImage scaled = new BufferedImage(width, height, type);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(
                    RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    private static void requireSameGeometry(ReferenceImage reference,
                                            ReferenceImage actual) {
        if (reference.width() != actual.width() || reference.height() != actual.height()) {
            throw new IllegalArgumentException(
                    "Reference/actual geometry mismatch: reference="
                            + reference.width() + "x" + reference.height()
                            + ", actual=" + actual.width() + "x" + actual.height());
        }
    }

    private static void validateInputs(int[] reference,
                                       int[] actual,
                                       int width,
                                       int height,
                                       SpatialReferenceMetrics.Rect region,
                                       int displayDivisor) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(actual, "actual");
        Objects.requireNonNull(region, "region");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Image dimensions must be positive");
        }
        long expected = (long) width * height;
        if (expected > Integer.MAX_VALUE
                || reference.length != expected
                || actual.length != expected) {
            throw new IllegalArgumentException(
                    "Image sample count mismatch: expected=" + expected
                            + ", reference=" + reference.length
                            + ", actual=" + actual.length);
        }
        if (region.isEmpty()) {
            throw new IllegalArgumentException("Difference-image region must not be empty");
        }
        long right = (long) region.x() + region.width();
        long bottom = (long) region.y() + region.height();
        if (right > width || bottom > height) {
            throw new IllegalArgumentException(
                    "Difference-image region exceeds source image: " + region
                            + " for " + width + "x" + height);
        }
        if (displayDivisor <= 0) {
            throw new IllegalArgumentException("Display divisor must be positive");
        }
    }
}
