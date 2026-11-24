package com.photographer.focusstack.cr3.processing;

import com.photographer.focusstack.cr3.processing.BayerDemosaicer.PixelColor;
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
record Rgb16Developer(BayerSamples image, BlackLevel black, int sensorX, int sensorY,
                      double[] gains, int whiteLevel, double[][] matrix, boolean srgb) {

    BufferedImage develop(Rectangle region, int orientation, int maxWorkers) {
        return develop(region, orientation, maxWorkers, false);
    }

    BufferedImage developOwned(Rectangle region, int orientation, int maxWorkers) {
        return develop(region, orientation, maxWorkers, true);
    }

    BufferedImage developInto(Rectangle region, int orientation, int maxWorkers, BufferedImage destination) {
        validate(region);
        int[] size = ExifOrientationTransformer.outputDimensions(region.width, region.height, orientation);
        short[][] channels = BayerDemosaicer.rgb16DestinationBanks(destination, size[0], size[1], srgb);
        if (channels == null) {
            throw new IllegalArgumentException("Expected a dense, correctly sized and encoded planar RGB16 destination");
        }
        short[] samples = image.unsignedShorts();
        boolean reuse = channels[1] == samples;
        if (channels[0] == samples || channels[2] == samples || (reuse && !canReuseBayerAsGreen(region, orientation))) {
            throw new IllegalArgumentException("Only a full, unrotated RGB16 image can reuse Bayer samples as green");
        }
        developStripes(channels, region, orientation, maxWorkers, reuse);
        return destination;
    }

    private boolean canReuseBayerAsGreen(Rectangle region, int orientation) {
        return orientation == 1 && region.equals(new Rectangle(image.width(), image.height()));
    }

    private BufferedImage develop(Rectangle region, int orientation, int maxWorkers, boolean owned) {
        validate(region);
        int[] size = ExifOrientationTransformer.outputDimensions(region.width, region.height, orientation);
        boolean reuse = owned && image.unsignedShorts() != null && canReuseBayerAsGreen(region, orientation);
        int sampleCount = Math.multiplyExact(size[0], size[1]);
        short[][] channels = {new short[sampleCount],
                reuse ? image.unsignedShorts() : new short[sampleCount], new short[sampleCount]};
        developStripes(channels, region, orientation, maxWorkers, reuse);
        return rgbImage(channels, size[0], size[1]);
    }

    private void developStripes(short[][] channels, Rectangle region, int orientation, int maxWorkers, boolean reuse) {
        int stripes = Cr3Workers.count(maxWorkers, Pixels.maxStripes(region.width, region.height));

        // In-place stores may replace a neighbor before an adjacent stripe reads it.
        // Capture both sides of every boundary before any worker writes the green bank.
        double[][][] seams = reuse && stripes > 1 ? new double[stripes - 1][2][image.width() + 2] : null;
        if (seams != null) {
            for (int stripe = 1; stripe < stripes; stripe++) {
                int boundary = Pixels.stripeStart(stripe, image.height(), stripes);
                correctRow(boundary - 1, seams[stripe - 1][0]);
                correctRow(boundary, seams[stripe - 1][1]);
            }
        }
        Cr3Workers.run(stripes, stripe -> {
            int first = region.y + Pixels.stripeStart(stripe, region.height, stripes);
            int end = region.y + Pixels.stripeStart(stripe + 1, region.height, stripes);
            double[] originalAbove = seams == null || stripe == 0 ? null : seams[stripe - 1][0];
            double[] originalBelow = seams == null || stripe + 1 == stripes ? null : seams[stripe][1];
            developRows(channels, region, orientation, first, end, originalAbove, originalBelow);
        });
    }

    BufferedImage developSampled(Rectangle region, int xStep, int yStep, int maxWorkers) {
        validate(region);
        if (xStep < 1 || yStep < 1) {
            throw new IllegalArgumentException("RGB sample spacing must be positive");
        }
        int columns = Math.ceilDiv(region.width, xStep);
        int rows = Math.ceilDiv(region.height, yStep);
        short[][] channels = new short[3][Math.multiplyExact(columns, rows)];
        int stripes = Cr3Workers.count(maxWorkers, Pixels.maxStripes(columns, rows));
        Cr3Workers.run(stripes, stripe -> {
            int first = Pixels.stripeStart(stripe, rows, stripes);
            int end = Pixels.stripeStart(stripe + 1, rows, stripes);
            double[][] corrected = new double[3][image.width() + 2];
            int nextRow = region.y + first * yStep - 1;
            for (int row = first; row < end; row++) {
                int y = region.y + row * yStep;
                // Keep the last three rows and normalize only the requested row and its neighbours.
                for (int source = Math.max(nextRow, y - 1); source <= y + 1; source++) {
                    correctRow(source, corrected[Math.floorMod(source, corrected.length)]);
                }
                nextRow = y + 2;
                double[] above = corrected[Math.floorMod(y - 1, corrected.length)];
                double[] current = corrected[y % corrected.length];
                double[] below = corrected[(y + 1) % corrected.length];
                PixelColor even = color(0, y);
                PixelColor odd = color(1, y);
                int verticalCount = neighbors(y, image.height());
                int offset = row * columns;
                for (int column = 0; column < columns; column++) {
                    int x = region.x + column * xStep;
                    writeBorder(channels, offset + column, above, current, below, x, verticalCount,
                            (x & 1) == 0 ? even : odd);
                }
            }
        });
        return rgbImage(channels, columns, rows);
    }

    private void validate(Rectangle region) {
        if (region.isEmpty() || !new Rectangle(image.width(), image.height()).contains(region)) {
            throw new IllegalArgumentException("RGB region must be within the Bayer image");
        }
        if (whiteLevel < 1 || (gains != null && gains.length != 3)) {
            throw new IllegalArgumentException("Expected a positive white level and three RGB gains");
        }
        if (matrix != null && (matrix.length != 3 || Arrays.stream(matrix).anyMatch(row -> row == null || row.length != 3))) {
            throw new IllegalArgumentException("Color matrix must be 3x3");
        }
    }

    private BufferedImage rgbImage(short[][] channels, int width, int height) {
        var space = ColorSpace.getInstance(srgb ? ColorSpace.CS_sRGB : ColorSpace.CS_LINEAR_RGB);
        var colorModel = new ComponentColorModel(space, false, false, Transparency.OPAQUE, DataBuffer.TYPE_USHORT);
        var raster = Raster.createBandedRaster(new DataBufferUShort(channels, channels[0].length),
                width, height, width, new int[]{0, 1, 2}, new int[3], null);
        return new BufferedImage(colorModel, raster, false, null);
    }

    private void developRows(short[][] channels, Rectangle region, int orientation, int first, int end,
                             double[] originalAbove, double[] originalBelow) {
        // A zero on either end lets the same sums handle borders. Divisors count only real neighbors.
        int rowLength = image.width() + 2;
        double[] above = new double[rowLength];
        double[] current = new double[rowLength];
        double[] below = new double[rowLength];
        if (originalAbove == null) {
            correctRow(first - 1, above);
        } else {
            System.arraycopy(originalAbove, 0, above, 0, rowLength);
        }
        correctRow(first, current);

        boolean transpose = orientation >= 5 && orientation <= 8;
        boolean flipX = ExifOrientationTransformer.reversesX(orientation);
        boolean flipY = ExifOrientationTransformer.reversesY(orientation);
        int step = transpose ? region.height : 1;
        int firstColumn = flipX ? (region.width - 1) * step : 0;
        if (flipX) {
            step = -step;
        }
        int rowEnd = region.x + region.width;
        int interiorEnd = Math.min(rowEnd, image.width() - 1);
        for (int y = first; y < end; y++) {
            if (y + 1 == end && originalBelow != null) {
                System.arraycopy(originalBelow, 0, below, 0, rowLength);
            } else {
                correctRow(y + 1, below);
            }
            int outputRow = flipY ? region.y + region.height - 1 - y : y - region.y;
            int destination = firstColumn + (transpose ? outputRow : outputRow * region.width);
            PixelColor even = color(0, y);
            PixelColor odd = color(1, y);
            int verticalCount = neighbors(y, image.height());
            int x = region.x;
            if (verticalCount == 2) {
                // Each interior pair begins at a red or blue site, then visits the green site.
                boolean evenChroma = even == PixelColor.RED || even == PixelColor.BLUE;
                int firstChroma = Math.max(1, x);
                if (((firstChroma & 1) == 0) != evenChroma) {
                    firstChroma++;
                }
                if (firstChroma + 1 < interiorEnd) {
                    for (; x < firstChroma; x++, destination += step) {
                        writeBorder(channels, destination, above, current, below, x, verticalCount,
                                (x & 1) == 0 ? even : odd);
                    }
                    boolean redRow = (evenChroma ? even : odd) == PixelColor.RED;
                    for (; x + 1 < interiorEnd; x += 2, destination += 2 * step) {
                        int i = x + 1;
                        double horizontal = current[i - 1] + current[i + 1];
                        double vertical = above[i] + below[i];
                        double green = (horizontal + vertical) * 0.25;
                        double diagonal = (above[i - 1] + above[i + 1] + below[i - 1] + below[i + 1]) * 0.25;
                        write(channels, destination, redRow ? current[i] : diagonal, green,
                                redRow ? diagonal : current[i]);

                        double across = (current[i] + current[i + 2]) * 0.5;
                        double along = (above[i + 1] + below[i + 1]) * 0.5;
                        write(channels, destination + step, redRow ? across : along, current[i + 1],
                                redRow ? along : across);
                    }
                }
            }
            for (; x < rowEnd; x++, destination += step) {
                writeBorder(channels, destination, above, current, below, x, verticalCount,
                        (x & 1) == 0 ? even : odd);
            }
            double[] old = above;
            above = current;
            current = below;
            below = old;
        }
    }

    private void writeBorder(short[][] channels, int destination, double[] above, double[] current,
                             double[] below, int x, int verticalCount, PixelColor site) {
        int i = x + 1;
        int horizontalCount = neighbors(x, image.width());
        double horizontal = current[i - 1] + current[i + 1];
        double vertical = above[i] + below[i];
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
                double corrected = Math.max(0.0, (double) image.sample(offset + x) - pedestal);
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
        if (srgb) {
            return Srgb16Encoder.encode(linear);
        }
        double value = Math.clamp(linear, 0.0, 1.0);
        return (short) Math.round(65535.0 * value);
    }
}
