package com.photographer.focusstack.cr3.raw;

/** Represents decoded RAW sensor data from a CR3 file. */
public final class Cr3RawImage {

    public enum BayerPattern {
        RGGB, BGGR, GRBG, GBRG
    }

    private final int width;
    private final int height;
    private final int[] data;
    private final int bitDepth;
    private final BayerPattern bayerPattern;

    public Cr3RawImage(int width, int height, int[] data, int bitDepth, BayerPattern bayerPattern) {
        if (data.length != width * height) {
            throw new IllegalArgumentException(
                    "Data length %d does not match %dx%d=%d"
                            .formatted(data.length, width, height, width * height));
        }
        this.width = width;
        this.height = height;
        this.data = data;
        this.bitDepth = bitDepth;
        this.bayerPattern = bayerPattern;
    }

    public int width() { return width; }
    public int height() { return height; }
    public int[] data() { return data; }
    public int bitDepth() { return bitDepth; }
    public BayerPattern bayerPattern() { return bayerPattern; }
    public int maxValue() { return (1 << bitDepth) - 1; }

    public int getPixel(int x, int y) {
        checkBounds(x, y);
        return data[y * width + x];
    }

    public void setPixel(int x, int y, int value) {
        checkBounds(x, y);
        data[y * width + x] = value;
    }

    private void checkBounds(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw new IndexOutOfBoundsException(
                    "Pixel (%d,%d) is outside image bounds (%dx%d)"
                            .formatted(x, y, width, height));
        }
    }

    @Override
    public String toString() {
        return "Cr3RawImage[%dx%d, %d-bit, %s]".formatted(width, height, bitDepth, bayerPattern);
    }
}
