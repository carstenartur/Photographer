package com.photographer.focusstack.cr3.processing;

import com.photographer.focusstack.cr3.processing.BayerDemosaicer.PixelColor;
import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import com.photographer.focusstack.cr3.raw.Cr3Workers;

import java.awt.Rectangle;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferUShort;
import java.awt.image.Raster;
import java.util.Arrays;

/** Bilinear development using three normalized Bayer rows per worker. */
record Rgb16Developer(Cr3RawImage image, BlackLevel black, int sensorX, int sensorY,
                      double[] gains, int whiteLevel, double[][] matrix, boolean srgb) {

    BufferedImage develop(Rectangle region, int orientation, int maxWorkers) {
        if (region.isEmpty() || !new Rectangle(image.width(), image.height()).contains(region)) {
            throw new IllegalArgumentException("RGB region must be within the Bayer image");
        }
        if (whiteLevel < 1 || (gains != null && gains.length != 3)) {
            throw new IllegalArgumentException("Expected a positive white level and three RGB gains");
        }
        if (matrix != null && (matrix.length != 3 || Arrays.stream(matrix).anyMatch(row -> row == null || row.length != 3))) {
            throw new IllegalArgumentException("Color matrix must be 3x3");
        }
        int[] size = ExifOrientationTransformer.outputDimensions(region.width, region.height, orientation);
        var space = ColorSpace.getInstance(srgb ? ColorSpace.CS_sRGB : ColorSpace.CS_LINEAR_RGB);
        var colorModel = new ComponentColorModel(space, false, false, Transparency.OPAQUE, DataBuffer.TYPE_USHORT);
        var raster = Raster.createBandedRaster(DataBuffer.TYPE_USHORT, size[0], size[1], 3, null);
        short[][] channels = ((DataBufferUShort) raster.getDataBuffer()).getBankData();
        int stripes = Cr3Workers.count(maxWorkers, Pixels.maxStripes(region.width, region.height));
        Cr3Workers.run(stripes, stripe -> {
            int first = region.y + Pixels.stripeStart(stripe, region.height, stripes);
            int end = region.y + Pixels.stripeStart(stripe + 1, region.height, stripes);
            developRows(channels, region, orientation, first, end);
        });
        return new BufferedImage(colorModel, raster, false, null);
    }

    private void developRows(short[][] channels, Rectangle region, int orientation, int first, int end) {
        // A zero on either end lets the same sums handle borders. Divisors count only real neighbors.
        int rowLength = image.width() + 2;
        double[] above = new double[rowLength];
        double[] current = new double[rowLength];
        double[] below = new double[rowLength];
        correctRow(first - 1, above);
        correctRow(first, current);

        boolean transpose = orientation >= 5 && orientation <= 8;
        boolean flipX = ExifOrientationTransformer.reversesX(orientation);
        boolean flipY = ExifOrientationTransformer.reversesY(orientation);
        int step = transpose ? region.height : 1;
        int firstColumn = flipX ? (region.width - 1) * step : 0;
        if (flipX) {
            step = -step;
        }
        for (int y = first; y < end; y++) {
            correctRow(y + 1, below);
            int outputRow = flipY ? region.y + region.height - 1 - y : y - region.y;
            int destination = firstColumn + (transpose ? outputRow : outputRow * region.width);
            PixelColor even = color(0, y);
            PixelColor odd = color(1, y);
            int verticalCount = neighbors(y, image.height());
            for (int x = region.x; x < region.x + region.width; x++, destination += step) {
                int i = x + 1;
                int horizontalCount = neighbors(x, image.width());
                double horizontal = current[i - 1] + current[i + 1];
                double vertical = above[i] + below[i];
                PixelColor site = (x & 1) == 0 ? even : odd;
                if (site == PixelColor.GREEN_IN_RED_ROW || site == PixelColor.GREEN_IN_BLUE_ROW) {
                    double across = horizontal / Math.max(1, horizontalCount);
                    double along = vertical / Math.max(1, verticalCount);
                    boolean redRow = site == PixelColor.GREEN_IN_RED_ROW;
                    write(channels, destination, redRow ? across : along, current[i], redRow ? along : across);
                } else {
                    double green = (horizontal + vertical) / Math.max(1, horizontalCount + verticalCount);
                    double diagonal = (above[i - 1] + above[i + 1] + below[i - 1] + below[i + 1])
                            / Math.max(1, horizontalCount * verticalCount);
                    boolean red = site == PixelColor.RED;
                    write(channels, destination, red ? current[i] : diagonal, green, red ? diagonal : current[i]);
                }
            }
            double[] old = above;
            above = current;
            current = below;
            below = old;
        }
    }

    private void correctRow(int y, double[] row) {
        if (y < 0 || y >= image.height()) {
            Arrays.fill(row, 0.0);
            return;
        }
        // The pedestal uses sensor coordinates. Gains and interpolation follow the crop-local CFA.
        int offset = y * image.width();
        for (int phase = 0; phase < 2; phase++) {
            int pedestal = black == null ? 0 : black.levelAt(sensorX + phase, sensorY + y, image.bayerPattern());
            double scale = gains == null ? 1.0 : switch (color(phase, y)) {
                case RED -> gains[0];
                case GREEN_IN_RED_ROW, GREEN_IN_BLUE_ROW -> gains[1];
                case BLUE -> gains[2];
            };
            for (int x = phase; x < image.width(); x += 2) {
                double corrected = Math.max(0.0, (double) image.data()[offset + x] - pedestal);
                row[x + 1] = corrected * scale / whiteLevel;
            }
        }
    }

    private PixelColor color(int x, int y) {
        return BayerDemosaicer.getPixelColor(image.bayerPattern(), (y & 1) == 0, (x & 1) == 0);
    }

    private static int neighbors(int position, int extent) {
        return (position > 0 ? 1 : 0) + (position + 1 < extent ? 1 : 0);
    }

    private void write(short[][] channels, int i, double red, double green, double blue) {
        if (matrix == null) {
            channels[0][i] = quantize(red);
            channels[1][i] = quantize(green);
            channels[2][i] = quantize(blue);
        } else {
            for (int band = 0; band < 3; band++) {
                double[] row = matrix[band];
                channels[band][i] = quantize(row[0] * red + row[1] * green + row[2] * blue);
            }
        }
    }

    private short quantize(double linear) {
        double value = Math.clamp(linear, 0.0, 1.0);
        return (short) Math.round(65535.0 * (srgb ? SrgbConverter.linearToSrgb(value) : value));
    }
}
