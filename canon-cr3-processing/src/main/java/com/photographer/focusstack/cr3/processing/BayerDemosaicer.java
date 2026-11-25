package com.photographer.focusstack.cr3.processing;

import com.photographer.focusstack.cr3.raw.Cr3Workers;
import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.Raster;

/**
 * Demosaics a Bayer-pattern RAW image into a full-color RGB image.
 *
 * <p>Uses bilinear interpolation. Supports all four standard Bayer patterns:
 * RGGB, BGGR, GRBG, GBRG.</p>
 *
 * <p>The Canon PowerShot G5 X Mark II uses an RGGB Bayer pattern.</p>
 */
public final class BayerDemosaicer {

    private static final Logger LOG = LoggerFactory.getLogger(BayerDemosaicer.class);

    private BayerDemosaicer() {
        // utility class
    }

    /**
     * Demosaics a Bayer-pattern RAW image using bilinear interpolation (8-bit output).
     *
     * <p>Uses {@code rawImage.maxValue()} as the white level for normalization.
     * When the pipeline has already subtracted black levels, pass an explicit
     * {@code whiteLevel} using {@link #demosaic(Cr3RawImage, int)} to avoid
     * a small green-channel under-exposure in the output.</p>
     *
     * @param rawImage the RAW Bayer image
     * @return a full-color RGB BufferedImage (TYPE_INT_RGB, 8 bits per channel)
     */
    public static BufferedImage demosaic(Cr3RawImage rawImage) {
        return demosaic(rawImage, rawImage.maxValue());
    }

    /**
     * Demosaics a Bayer-pattern RAW image using bilinear interpolation (8-bit output),
     * normalizing pixel values against an explicit {@code whiteLevel}.
     *
     * <p>After black-level subtraction and white-balance, the effective maximum
     * for the (unscaled) green channel is {@code maxVal - blackLevel}, which is
     * lower than {@code maxValue()}.  Passing {@code whiteLevel = maxVal - minBL}
     * corrects this so that sensor saturation maps to 255 in all channels.</p>
     *
     * @param rawImage   the RAW Bayer image
     * @param whiteLevel the value that should map to 255 in the 8-bit output
     * @return a full-color RGB BufferedImage (TYPE_INT_RGB, 8 bits per channel)
     */
    public static BufferedImage demosaic(Cr3RawImage rawImage, int whiteLevel) {
        LOG.info("Demosaicing {}x{} {} image with bilinear interpolation (whiteLevel={})",
                rawImage.width(), rawImage.height(), rawImage.bayerPattern(), whiteLevel);

        return demosaicToNewImage(rawImage, Math.max(1, whiteLevel));
    }

    /**
     * Develops a crop-local Bayer region directly into three unsigned-short RGB planes.
     * The supplied mosaic must include the interpolation neighbors of the region.
     *
     * @param image Bayer samples, in native sensor units, left unmodified
     * @param blackLevel pedestal in sensor coordinates, or null to skip subtraction
     * @param sensorX sensor column of the first Bayer sample
     * @param sensorY sensor row of the first Bayer sample
     * @param whiteBalance RGB gains, or null for unit gains
     * @param whiteLevel normalization level after pedestal subtraction
     * @param matrix camera-to-linear-RGB matrix, or null to keep camera RGB
     * @param region requested rectangle within the supplied mosaic, before orientation
     * @param orientation EXIF orientation of the result
     * @param srgb whether to apply the sRGB transfer function
     * @param maxWorkers maximum workers, or zero for automatic selection
     * @return oriented RGB image, quantized once to 0–65535 per channel
     */
    public static BufferedImage develop16(Cr3RawImage image, BlackLevel blackLevel, int sensorX, int sensorY,
                                          double[] whiteBalance, int whiteLevel, double[][] matrix,
                                          Rectangle region, int orientation, boolean srgb, int maxWorkers) {
        return new Rgb16Developer(BayerSamples.of(image), blackLevel, sensorX, sensorY,
                whiteBalance, whiteLevel, matrix, srgb)
                .develop(region, orientation, maxWorkers);
    }

    /**
     * Develops native Bayer codes from compact unsigned-short storage into RGB16.
     * Every array element is interpreted as an unsigned value from 0 to 65535, in
     * sensor units. The array is borrowed read-only for this synchronous call; the
     * caller must not modify it until the call returns. The result owns its storage.
     * Black-level correction, gains and interpolation retain fractional values and
     * highlight headroom until the final RGB channels are clamped and quantized.
     *
     * @param samples native Bayer codes in row-major order, left unmodified
     * @param width positive mosaic width
     * @param height positive mosaic height
     * @param pattern crop-local Bayer layout
     * @param blackLevel pedestal in sensor coordinates, or null to skip subtraction
     * @param sensorX sensor column of the first Bayer sample
     * @param sensorY sensor row of the first Bayer sample
     * @param whiteBalance RGB gains, or null for unit gains
     * @param whiteLevel normalization level after pedestal subtraction
     * @param matrix camera-to-linear-RGB matrix, or null to keep camera RGB
     * @param region requested rectangle within the mosaic, which includes its interpolation neighbors
     * @param orientation EXIF orientation of the result
     * @param srgb whether to apply the sRGB transfer function
     * @param maxWorkers maximum workers, or zero for automatic selection
     * @return oriented RGB image, quantized once to 0–65535 per channel
     * @throws IllegalArgumentException if the dimensions do not match the sample count
     */
    public static BufferedImage develop16(short[] samples, int width, int height, Cr3RawImage.BayerPattern pattern,
                                          BlackLevel blackLevel, int sensorX, int sensorY, double[] whiteBalance,
                                          int whiteLevel, double[][] matrix, Rectangle region, int orientation,
                                          boolean srgb, int maxWorkers) {
        return new Rgb16Developer(BayerSamples.of(samples, width, height, pattern), blackLevel, sensorX, sensorY,
                whiteBalance, whiteLevel, matrix, srgb).develop(region, orientation, maxWorkers);
    }

    /**
     * Develops exclusively owned native Bayer storage, reusing it as the green RGB16
     * bank when the region is the complete mosaic and the EXIF orientation is 1.
     * Other regions and orientations leave the input unmodified and allocate separate
     * output storage. Native codes are unsigned and use the same fractional processing
     * and final quantization as the read-only {@code develop16} overload.
     *
     * <p>The caller must relinquish exclusive ownership of the array: it may be
     * overwritten during this synchronous call and retained by the returned image.
     * A failed development can leave partially processed samples. Use the read-only
     * overload when the original Bayer values must be retained.</p>
     *
     * @param samples exclusively owned native Bayer codes in row-major order
     * @param width positive mosaic width
     * @param height positive mosaic height
     * @param pattern crop-local Bayer layout
     * @param blackLevel pedestal in sensor coordinates, or null to skip subtraction
     * @param sensorX sensor column of the first Bayer sample
     * @param sensorY sensor row of the first Bayer sample
     * @param whiteBalance RGB gains, or null for unit gains
     * @param whiteLevel normalization level after pedestal subtraction
     * @param matrix camera-to-linear-RGB matrix, or null to keep camera RGB
     * @param region requested rectangle within the mosaic, which includes its interpolation neighbors
     * @param orientation EXIF orientation of the result
     * @param srgb whether to apply the sRGB transfer function
     * @param maxWorkers maximum workers, or zero for automatic selection
     * @return oriented RGB image, possibly backed by the supplied native array
     * @throws IllegalArgumentException if the dimensions do not match the sample count
     */
    public static BufferedImage develop16Owned(short[] samples, int width, int height, Cr3RawImage.BayerPattern pattern,
                                               BlackLevel blackLevel, int sensorX, int sensorY, double[] whiteBalance,
                                               int whiteLevel, double[][] matrix, Rectangle region, int orientation,
                                               boolean srgb, int maxWorkers) {
        return new Rgb16Developer(BayerSamples.of(samples, width, height, pattern), blackLevel, sensorX, sensorY,
                whiteBalance, whiteLevel, matrix, srgb).developOwned(region, orientation, maxWorkers);
    }

    /**
     * Returns the writable arrays of a dense, three-bank RGB16 destination, in logical R, G, B order.
     * Each bank spans the full image in row-major order and starts at offset zero. Changes to
     * the returned arrays change the image. A null result lets the caller select a copying path.
     *
     * @param destination image to check, or null
     * @param width required output width after orientation
     * @param height required output height after orientation
     * @param srgb true for sRGB encoding, false for linear RGB
     * @return the three distinct pixel arrays, or null if the dimensions, encoding or layout differ
     */
    public static short[][] rgb16DestinationBanks(BufferedImage destination, int width, int height, boolean srgb) {
        if (destination == null || destination.getWidth() != width || destination.getHeight() != height
                || destination.getRaster().getParent() != null) {
            return null;
        }
        var color = destination.getColorModel();
        var space = ColorSpace.getInstance(srgb ? ColorSpace.CS_sRGB : ColorSpace.CS_LINEAR_RGB);
        if (color.getClass() != ComponentColorModel.class || color.hasAlpha() || color.getColorSpace() != space) {
            return null;
        }
        return Pixels.denseRgb16Bands(destination);
    }

    /**
     * Develops straight into a caller-owned planar RGB16 image. The calibration, crop and
     * orientation have the same meaning as in {@link #develop16Owned}. The caller must have
     * exclusive access to the destination while this method runs. The input is read-only,
     * except that a complete, unrotated mosaic may occupy the destination's green bank.
     * In that case RGB values replace the original sensor codes. A failure during processing
     * can leave the destination partially written.
     *
     * @param samples native unsigned Bayer codes, in row-major order
     * @param width mosaic width
     * @param height mosaic height
     * @param pattern crop-local Bayer pattern
     * @param blackLevel sensor-coordinate pedestal, or null to skip correction
     * @param sensorX sensor column of the first mosaic sample
     * @param sensorY sensor row of the first mosaic sample
     * @param whiteBalance RGB gains, or null for unit gains
     * @param whiteLevel normalization level after pedestal subtraction
     * @param matrix camera-to-linear-RGB matrix, or null to keep camera RGB
     * @param region rectangle of the mosaic to develop, before orientation
     * @param orientation EXIF orientation of the result
     * @param srgb true for sRGB encoding, false for linear RGB
     * @param maxWorkers worker limit, or zero for automatic selection
     * @param destination image with the oriented dimensions and matching {@link #rgb16DestinationBanks} layout
     * @return the supplied destination
     * @throws IllegalArgumentException if the geometry, calibration, destination layout or sample alias is invalid
     */
    public static BufferedImage develop16Into(short[] samples, int width, int height, Cr3RawImage.BayerPattern pattern,
                                             BlackLevel blackLevel, int sensorX, int sensorY, double[] whiteBalance,
                                             int whiteLevel, double[][] matrix, Rectangle region, int orientation,
                                             boolean srgb, int maxWorkers, BufferedImage destination) {
        return new Rgb16Developer(BayerSamples.of(samples, width, height, pattern), blackLevel, sensorX, sensorY,
                whiteBalance, whiteLevel, matrix, srgb).developInto(region, orientation, maxWorkers, destination);
    }

    /**
     * Develops a sampled RGB16 region from a full-resolution Bayer mosaic. The
     * supplied mosaic must include each selected site's interpolation neighbours.
     */
    public static BufferedImage develop16Sampled(short[] samples, int width, int height,
                                                 Cr3RawImage.BayerPattern pattern, BlackLevel blackLevel,
                                                 int sensorX, int sensorY, double[] whiteBalance, int whiteLevel,
                                                 double[][] matrix, Rectangle region, int xStep, int yStep,
                                                 boolean srgb, int maxWorkers) {
        return new Rgb16Developer(BayerSamples.of(samples, width, height, pattern), blackLevel, sensorX, sensorY,
                whiteBalance, whiteLevel, matrix, srgb).developSampled(region, xStep, yStep, maxWorkers);
    }

    /**
     * Demosaics into the supplied sensor buffer and uses it as the returned image's storage.
     * The caller must own the buffer exclusively. Its Bayer samples are replaced by packed RGB.
     *
     * @param rawImage the Bayer image whose storage may be overwritten
     * @param whiteLevel the value that should map to 255 in the 8-bit output
     * @return a TYPE_INT_RGB image backed by {@code rawImage.data()}
     */
    public static BufferedImage demosaicInPlace(Cr3RawImage rawImage, int whiteLevel) {
        return demosaicInPlace(rawImage, whiteLevel, 1);
    }

    /** Estimates the sample storage of the temporary rows for a positive worker budget. */
    public static long estimateInPlaceRowBytes(int width, int height, int workers) {
        if (width < 1 || height < 1 || workers < 1) {
            throw new IllegalArgumentException("Image dimensions and worker budget must be positive");
        }
        int stripes = Math.min(workers, Pixels.maxStripes(width, height));
        // Each stripe has two working rows and each boundary adds a seam row.
        return (3L * stripes - 1) * width * Integer.BYTES;
    }

    /**
     * Estimates the array payload first allocated by the shared 16-bit sRGB encoder.
     * The tables are initialized lazily and shared across imports and workers; asking
     * for this estimate does not initialize them. Linear development needs none of
     * this storage. Object headers and garbage-collector reserves are not included.
     *
     * @return shared first-use table payload in bytes
     */
    public static long estimateSrgb16TableBytes() {
        return Srgb16Encoder.TABLE_BYTES;
    }

    /**
     * Demosaics in place, dividing large images into independently processed row stripes.
     *
     * @param rawImage the exclusively owned Bayer image whose storage may be overwritten
     * @param whiteLevel the value that should map to 255 in the 8-bit output
     * @param maxWorkers maximum number of workers, or zero for automatic selection
     * @return a TYPE_INT_RGB image backed by {@code rawImage.data()}
     */
    public static BufferedImage demosaicInPlace(Cr3RawImage rawImage, int whiteLevel, int maxWorkers) {
        int width = rawImage.width();
        int height = rawImage.height();
        int stripes = Cr3Workers.count(maxWorkers, Pixels.maxStripes(width, height));
        int[] data = rawImage.data();
        var output = packedImage(data, width, height);

        RgbScale scale = new RgbScale(rawImage, Math.max(1, whiteLevel));
        if (stripes == 1) {
            writeRows(rawImage, scale, 0, height);
            return output;
        }

        // Interpolation reads one row above and below. Keep each seam's Bayer samples
        // intact for both adjacent stripes, saving its RGB result until after the join.
        int[][] seams = new int[stripes - 1][width];
        for (int stripe = 1; stripe < stripes; stripe++) {
            int seam = Pixels.stripeStart(stripe, height, stripes);
            interpolateRow(rawImage, seam, scale, seams[stripe - 1], 0);
        }
        Cr3Workers.run(stripes, stripe -> {
            int first = stripe == 0 ? 0 : Pixels.stripeStart(stripe, height, stripes) + 1;
            int end = Pixels.stripeStart(stripe + 1, height, stripes);
            writeRows(rawImage, scale, first, end);
        });
        for (int stripe = 1; stripe < stripes; stripe++) {
            int seam = Pixels.stripeStart(stripe, height, stripes);
            System.arraycopy(seams[stripe - 1], 0, data, seam * width, width);
        }
        return output;
    }

    /** Demosaics selected sites with the same neighbours and rounding as a full RGB8 image. */
    public static BufferedImage demosaicSampled(Cr3RawImage rawImage, int whiteLevel, Rectangle region,
                                                 int xStep, int yStep, int maxWorkers) {
        int width = rawImage.width();
        int height = rawImage.height();
        if (xStep < 1 || yStep < 1 || region.isEmpty() || !new Rectangle(width, height).contains(region)) {
            throw new IllegalArgumentException("Invalid RGB sample region");
        }
        int columns = Math.ceilDiv(region.width, xStep);
        int rows = Math.ceilDiv(region.height, yStep);
        int[] pixels = new int[Math.multiplyExact(columns, rows)];
        int[] samples = rawImage.data();
        var scale = new RgbScale(rawImage, Math.max(1, whiteLevel));
        int stripes = Cr3Workers.count(maxWorkers, Pixels.maxStripes(columns, rows));
        Cr3Workers.run(stripes, stripe -> {
            int first = Pixels.stripeStart(stripe, rows, stripes);
            int end = Pixels.stripeStart(stripe + 1, rows, stripes);
            for (int row = first; row < end; row++) {
                int y = region.y + row * yStep;
                boolean even = (y & 1) == 0;
                PixelColor evenColumn = getPixelColor(rawImage.bayerPattern(), even, true);
                PixelColor oddColumn = getPixelColor(rawImage.bayerPattern(), even, false);
                boolean interiorRow = y > 0 && y < height - 1;
                int offset = row * columns;
                for (int column = 0; column < columns; column++) {
                    int x = region.x + column * xStep;
                    PixelColor color = (x & 1) == 0 ? evenColumn : oddColumn;
                    pixels[offset + column] = interiorRow && x > 0 && x < width - 1
                            ? interiorPixel(samples, y * width + x, width, color, scale)
                            : borderPixel(rawImage, x, y, color, scale);
                }
            }
        });
        return packedImage(pixels, columns, rows);
    }

    private static BufferedImage packedImage(int[] data, int width, int height) {
        var buffer = new DataBufferInt(data, data.length);
        var model = new DirectColorModel(24, 0xff0000, 0xff00, 0xff);
        var raster = Raster.createPackedRaster(buffer, width, height, width,
                new int[]{model.getRedMask(), model.getGreenMask(), model.getBlueMask()}, null);
        return new BufferedImage(model, raster, false, null);
    }

    private static void writeRows(Cr3RawImage rawImage, RgbScale scale, int first, int end) {
        int width = rawImage.width();
        int[] data = rawImage.data();
        int[] previous = new int[width];
        int[] current = new int[width];
        // Row y-1 is still needed as input until interpolation of row y finishes.
        for (int y = first; y < end; y++) {
            interpolateRow(rawImage, y, scale, current, 0);
            if (y > first) {
                System.arraycopy(previous, 0, data, (y - 1) * width, width);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        System.arraycopy(previous, 0, data, (end - 1) * width, width);
    }

    /**
     * Demosaics a Bayer-pattern RAW image to a normalized 8-bit-per-channel BufferedImage.
     *
     * <p>This method scales the raw sensor values (which may be 14-bit or higher) down to
     * the 8-bit-per-channel range (0–255) required by {@link BufferedImage#TYPE_INT_RGB}.
     * Use {@link #develop16(Cr3RawImage, BlackLevel, int, int, double[], int, double[][],
     * Rectangle, int, boolean, int)} to retain fractional values until 16-bit RGB output.</p>
     *
     * @param rawImage the RAW Bayer image
     * @return a full-color RGB BufferedImage (TYPE_INT_RGB, 8-bit per channel)
     */
    public static BufferedImage demosaicNormalized(Cr3RawImage rawImage) {
        LOG.info("Demosaicing {}x{} {} image to normalized 8-bit with bilinear interpolation",
                rawImage.width(), rawImage.height(), rawImage.bayerPattern());

        return demosaicToNewImage(rawImage, rawImage.maxValue());
    }

    private static BufferedImage demosaicToNewImage(Cr3RawImage rawImage, int whiteLevel) {
        int width = rawImage.width();
        var output = new BufferedImage(width, rawImage.height(), BufferedImage.TYPE_INT_RGB);
        int[] pixels = ((DataBufferInt) output.getRaster().getDataBuffer()).getData();
        RgbScale scale = new RgbScale(rawImage, whiteLevel);
        for (int y = 0; y < rawImage.height(); y++) {
            interpolateRow(rawImage, y, scale, pixels, y * width);
        }
        return output;
    }

    /**
     * @deprecated Use {@link #demosaicNormalized(Cr3RawImage)} instead.
     *             This method was misleadingly named "16bit" but actually produces 8-bit output.
     */
    @Deprecated(since = "0.1.4", forRemoval = true)
    public static BufferedImage demosaic16bit(Cr3RawImage rawImage) {
        return demosaicNormalized(rawImage);
    }

    private static void interpolateRow(Cr3RawImage image, int y, RgbScale scale, int[] output, int offset) {
        int width = image.width();
        boolean evenRow = (y & 1) == 0;
        PixelColor first = getPixelColor(image.bayerPattern(), evenRow, true);
        PixelColor second = getPixelColor(image.bayerPattern(), evenRow, false);
        if (y == 0 || y == image.height() - 1 || width < 3) {
            for (int x = 0; x < width; x++) {
                output[offset + x] = borderPixel(image, x, y, (x & 1) == 0 ? first : second, scale);
            }
            return;
        }

        int rowStart = y * width;
        int[] samples = image.data();
        output[offset] = borderPixel(image, 0, y, first, scale);
        int last = width - 1;
        int x = 1;
        // Each Bayer row alternates the same two colors across its entire interior.
        for (; x + 1 < last; x += 2) {
            output[offset + x] = interiorPixel(samples, rowStart + x, width, second, scale);
            output[offset + x + 1] = interiorPixel(samples, rowStart + x + 1, width, first, scale);
        }
        if (x < last) {
            output[offset + x] = interiorPixel(samples, rowStart + x, width, second, scale);
        }
        output[offset + last] = borderPixel(image, last, y, (last & 1) == 0 ? first : second, scale);
    }

    private static int interiorPixel(int[] data, int i, int width, PixelColor color, RgbScale scale) {
        int horizontal = data[i - 1] + data[i + 1];
        int vertical = data[i - width] + data[i + width];
        int center = data[i];
        if (color == PixelColor.GREEN_IN_RED_ROW) {
            return scale.pack(horizontal / 2, center, vertical / 2);
        }
        if (color == PixelColor.GREEN_IN_BLUE_ROW) {
            return scale.pack(vertical / 2, center, horizontal / 2);
        }
        int cross = (horizontal + vertical) / 4;
        int diagonal = (data[i - width - 1] + data[i - width + 1]
                + data[i + width - 1] + data[i + width + 1]) / 4;
        return color == PixelColor.RED
                ? scale.pack(center, cross, diagonal)
                : scale.pack(diagonal, cross, center);
    }

    private static int borderPixel(Cr3RawImage img, int x, int y, PixelColor color, RgbScale scale) {
        int r, g, b;
        switch (color) {
            case RED -> {
                r = img.getPixel(x, y);
                g = averageCross(img, x, y);
                b = averageDiagonal(img, x, y);
            }
            case GREEN_IN_RED_ROW -> {
                r = averageHorizontal(img, x, y);
                g = img.getPixel(x, y);
                b = averageVertical(img, x, y);
            }
            case GREEN_IN_BLUE_ROW -> {
                r = averageVertical(img, x, y);
                g = img.getPixel(x, y);
                b = averageHorizontal(img, x, y);
            }
            case BLUE -> {
                r = averageDiagonal(img, x, y);
                g = averageCross(img, x, y);
                b = img.getPixel(x, y);
            }
            default -> throw new IllegalStateException("Unexpected pixel color: " + color);
        }
        return scale.pack(r, g, b);
    }

    /** Identifies the color type at a given Bayer position. */
    enum PixelColor {
        RED, GREEN_IN_RED_ROW, GREEN_IN_BLUE_ROW, BLUE
    }

    static PixelColor getPixelColor(Cr3RawImage.BayerPattern pattern,
                                             boolean evenRow, boolean evenCol) {
        return switch (pattern) {
            case RGGB -> {
                if (evenRow && evenCol) yield PixelColor.RED;
                if (evenRow) yield PixelColor.GREEN_IN_RED_ROW;
                if (evenCol) yield PixelColor.GREEN_IN_BLUE_ROW;
                yield PixelColor.BLUE;
            }
            case BGGR -> {
                if (evenRow && evenCol) yield PixelColor.BLUE;
                if (evenRow) yield PixelColor.GREEN_IN_BLUE_ROW;
                if (evenCol) yield PixelColor.GREEN_IN_RED_ROW;
                yield PixelColor.RED;
            }
            case GRBG -> {
                if (evenRow && evenCol) yield PixelColor.GREEN_IN_RED_ROW;
                if (evenRow) yield PixelColor.RED;
                if (evenCol) yield PixelColor.BLUE;
                yield PixelColor.GREEN_IN_BLUE_ROW;
            }
            case GBRG -> {
                if (evenRow && evenCol) yield PixelColor.GREEN_IN_BLUE_ROW;
                if (evenRow) yield PixelColor.BLUE;
                if (evenCol) yield PixelColor.RED;
                yield PixelColor.GREEN_IN_RED_ROW;
            }
        };
    }

    /** Averages the 4 cross (horizontal + vertical) neighbors. */
    private static int averageCross(Cr3RawImage img, int x, int y) {
        int sum = 0, count = 0;
        if (x > 0) { sum += img.getPixel(x - 1, y); count++; }
        if (x < img.width() - 1) { sum += img.getPixel(x + 1, y); count++; }
        if (y > 0) { sum += img.getPixel(x, y - 1); count++; }
        if (y < img.height() - 1) { sum += img.getPixel(x, y + 1); count++; }
        return count > 0 ? sum / count : 0;
    }

    private static int averageHorizontal(Cr3RawImage img, int x, int y) {
        int sum = 0, count = 0;
        if (x > 0) { sum += img.getPixel(x - 1, y); count++; }
        if (x < img.width() - 1) { sum += img.getPixel(x + 1, y); count++; }
        return count > 0 ? sum / count : 0;
    }

    private static int averageVertical(Cr3RawImage img, int x, int y) {
        int sum = 0, count = 0;
        if (y > 0) { sum += img.getPixel(x, y - 1); count++; }
        if (y < img.height() - 1) { sum += img.getPixel(x, y + 1); count++; }
        return count > 0 ? sum / count : 0;
    }

    private static int averageDiagonal(Cr3RawImage img, int x, int y) {
        int sum = 0, count = 0;
        if (x > 0 && y > 0) { sum += img.getPixel(x - 1, y - 1); count++; }
        if (x < img.width() - 1 && y > 0) { sum += img.getPixel(x + 1, y - 1); count++; }
        if (x > 0 && y < img.height() - 1) { sum += img.getPixel(x - 1, y + 1); count++; }
        if (x < img.width() - 1 && y < img.height() - 1) { sum += img.getPixel(x + 1, y + 1); count++; }
        return count > 0 ? sum / count : 0;
    }

    /** One read-only table shared by all stripes; typical 14-bit images need 16 KiB. */
    private static final class RgbScale {
        private final int whiteLevel;
        private final byte[] levels;

        RgbScale(Cr3RawImage image, int whiteLevel) {
            this.whiteLevel = whiteLevel;
            int depth = image.bitDepth();
            int entries = depth >= 1 && depth <= 16 ? 1 << depth : 0;
            // Small images cannot amortize populating the complete sensor range.
            levels = new byte[entries <= image.data().length ? entries : 0];
            for (int value = 0; value < levels.length; value++) {
                levels[value] = (byte) scale(value);
            }
        }

        int pack(int red, int green, int blue) {
            // The table length is a power of two. One combined check detects any
            // negative or out-of-range sample, including caller-supplied sensor data.
            int channelBits = red | green | blue;
            if (channelBits >= 0 && channelBits < levels.length) {
                return (Byte.toUnsignedInt(levels[red]) << 16) | (Byte.toUnsignedInt(levels[green]) << 8)
                        | Byte.toUnsignedInt(levels[blue]);
            }
            return (scale(red) << 16) | (scale(green) << 8) | scale(blue);
        }

        private int scale(int value) {
            return Math.min(255, Math.max(0, (int) ((long) value * 255 / whiteLevel)));
        }
    }
}
