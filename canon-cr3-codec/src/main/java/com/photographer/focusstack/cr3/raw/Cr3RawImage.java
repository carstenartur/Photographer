package com.photographer.focusstack.cr3.raw;

/**
 * Represents decoded RAW sensor data from a CR3 file.
 *
 * <p>The raw data is stored as a 2D array of unsigned 14-bit values in Bayer
 * pattern layout. The pattern for Canon sensors is typically RGGB.</p>
 *
 * <p>The data includes the full sensor area including optical black regions.
 * Use {@link com.photographer.focusstack.cr3.metadata.Cr3Metadata} active area
 * coordinates to crop to the visible image.</p>
 */
public final class Cr3RawImage {

    /** Standard Bayer color-filter patterns. */
    public enum BayerPattern {
        /** Red, green, green, blue. */
        RGGB,
        /** Blue, green, green, red. */
        BGGR,
        /** Green, red, blue, green. */
        GRBG,
        /** Green, blue, red, green. */
        GBRG
    }

    private final int width;
    private final int height;
    private final int[] data;
    private final int bitDepth;
    private final BayerPattern bayerPattern;

    /**
     * Creates a new RAW image.
     *
     * @param width        image width in pixels
     * @param height       image height in pixels
     * @param data         raw pixel data (row-major, one value per pixel)
     * @param bitDepth     bit depth per pixel (for example, 14)
     * @param bayerPattern the Bayer color filter pattern
     */
    public Cr3RawImage(int width, int height, int[] data, int bitDepth, BayerPattern bayerPattern) {
        if (data.length != width * height) {
            throw new IllegalArgumentException(
                    "Data length %d does not match %dx%d=%d".formatted(data.length, width, height, width * height));
        }
        this.width = width;
        this.height = height;
        this.data = data;
        this.bitDepth = bitDepth;
        this.bayerPattern = bayerPattern;
    }

    /**
     * Returns the image width in pixels.
     *
     * @return the image width in pixels
     */
    public int width() {
        return width;
    }

    /**
     * Returns the image height in pixels.
     *
     * @return the image height in pixels
     */
    public int height() {
        return height;
    }

    /**
     * Returns the mutable raw pixel data array in row-major order.
     *
     * @return the backing raw pixel data array
     */
    public int[] data() {
        return data;
    }

    /**
     * Returns the bit depth per pixel.
     *
     * @return the bit depth per pixel
     */
    public int bitDepth() {
        return bitDepth;
    }

    /**
     * Returns the Bayer color filter pattern.
     *
     * @return the Bayer color filter pattern
     */
    public BayerPattern bayerPattern() {
        return bayerPattern;
    }

    /**
     * Returns the maximum representable pixel value based on the bit depth.
     *
     * @return the maximum representable pixel value
     */
    public int maxValue() {
        return (1 << bitDepth) - 1;
    }

    /**
     * Gets the pixel value at the given coordinates.
     *
     * @param x column (0-based)
     * @param y row (0-based)
     * @return the pixel value
     */
    public int getPixel(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw new IndexOutOfBoundsException(
                    "Pixel (%d,%d) is outside image bounds (%dx%d)".formatted(x, y, width, height));
        }
        return data[y * width + x];
    }

    /**
     * Sets the pixel value at the given coordinates.
     *
     * @param x     column (0-based)
     * @param y     row (0-based)
     * @param value the pixel value
     * @throws IndexOutOfBoundsException if coordinates are outside the image
     */
    public void setPixel(int x, int y, int value) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw new IndexOutOfBoundsException(
                    "Pixel (%d,%d) is outside image bounds (%dx%d)".formatted(x, y, width, height));
        }
        data[y * width + x] = value;
    }

    @Override
    public String toString() {
        return "Cr3RawImage[%dx%d, %d-bit, %s]".formatted(width, height, bitDepth, bayerPattern);
    }
}
