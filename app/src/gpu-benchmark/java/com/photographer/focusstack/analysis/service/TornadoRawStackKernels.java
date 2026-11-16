package com.photographer.focusstack.analysis.service;

import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;
import uk.ac.manchester.tornado.api.types.arrays.ShortArray;

/** Pixel operations shared by the TornadoVM task graph and the Java parity check. */
final class TornadoRawStackKernels {
    static final int RAW_WIDTH = 0;
    static final int RAW_HEIGHT = 1;
    static final int OUTPUT_WIDTH = 2;
    static final int OUTPUT_HEIGHT = 3;
    static final int ORIENTATION = 4;
    static final int RED_PHASE = 5;
    static final int MAX_SAMPLE = 6;
    static final int WHITE_LEVEL = 7;
    static final int FIRST_FRAME = 8;
    static final int SETTINGS_SIZE = 9;
    static final int MATRIX = 3;
    static final int COLOR_SIZE = MATRIX + 9;

    private TornadoRawStackKernels() {}

    static void correct(ShortArray raw, IntArray settings, IntArray black, DoubleArray color) {
        int width = settings.get(RAW_WIDTH);
        int redPhase = settings.get(RED_PHASE);
        int bluePhase = redPhase ^ 3;
        int maximum = settings.get(MAX_SAMPLE);
        for (@Parallel int i = 0; i < raw.getSize(); i++) {
            int y = i / width;
            int x = i - y * width;
            int columnPhase = x & 1;
            int phase = ((y & 1) << 1) | columnPhase;
            int channel = phase == redPhase ? 0 : phase == bluePhase ? 2 : 1;
            int value = Math.max(0, sample(raw, i) - black.get(2 * y + columnPhase));
            int balanced = Math.max(0, Math.min(maximum, (int) (value * color.get(channel))));
            raw.set(i, (short) balanced);
        }
    }

    static void develop(ShortArray raw, IntArray settings, DoubleArray color, IntArray gamma,
                        IntArray rgb, FloatArray luminance) {
        int width = settings.get(RAW_WIDTH);
        int height = settings.get(RAW_HEIGHT);
        int redPhase = settings.get(RED_PHASE);
        int white = settings.get(WHITE_LEVEL);
        int orientation = settings.get(ORIENTATION);
        for (@Parallel int i = 0; i < raw.getSize(); i++) {
            int y = i / width;
            int x = i - y * width;
            int phase = ((y & 1) << 1) | (x & 1);
            int interpolated = interpolate(raw, i, x, y, width, height, phase, redPhase, white);
            int red = (interpolated >> 16) & 255;
            int green = (interpolated >> 8) & 255;
            int blue = interpolated & 255;
            int correctedRed = gamma.get(matrixChannel(color, MATRIX, red, green, blue));
            int correctedGreen = gamma.get(matrixChannel(color, MATRIX + 3, red, green, blue));
            int correctedBlue = gamma.get(matrixChannel(color, MATRIX + 6, red, green, blue));
            int target = orientedIndex(x, y, width, height, orientation);
            rgb.set(target, (correctedRed << 16) | (correctedGreen << 8) | correctedBlue);
            luminance.set(target, 0.2126f * correctedRed + 0.7152f * correctedGreen + 0.0722f * correctedBlue);
        }
    }

    static void select(IntArray rgb, FloatArray luminance, IntArray settings, IntArray winner, FloatArray bestScore) {
        int width = settings.get(OUTPUT_WIDTH);
        int height = settings.get(OUTPUT_HEIGHT);
        boolean first = settings.get(FIRST_FRAME) != 0;
        for (@Parallel int i = 0; i < rgb.getSize(); i++) {
            int y = i / width;
            int x = i - y * width;
            int north = y > 0 ? i - width : i;
            int south = y < height - 1 ? i + width : i;
            int west = x > 0 ? i - 1 : i;
            int east = x < width - 1 ? i + 1 : i;
            // The CPU extends edges and accumulates FP32 luminance samples in FP64.
            double sum = luminance.get(north);
            sum += luminance.get(west);
            sum -= 4 * luminance.get(i);
            sum += luminance.get(east);
            sum += luminance.get(south);
            float response = Math.abs((float) sum);
            if (first || response > bestScore.get(i)) {
                bestScore.set(i, response);
                winner.set(i, rgb.get(i));
            }
        }
    }

    private static int interpolate(ShortArray raw, int i, int x, int y, int width, int height,
                                   int phase, int redPhase, int white) {
        boolean primary = phase == redPhase || phase == (redPhase ^ 3);
        boolean left = x > 0;
        boolean right = x < width - 1;
        boolean top = y > 0;
        boolean bottom = y < height - 1;
        int horizontal;
        int vertical;
        int cross = 0;
        int diagonal = 0;
        if (left && right && top && bottom) {
            horizontal = sample(raw, i - 1) + sample(raw, i + 1);
            vertical = sample(raw, i - width) + sample(raw, i + width);
            if (primary) {
                cross = (horizontal + vertical) / 4;
                diagonal = (sample(raw, i - width - 1) + sample(raw, i - width + 1)
                        + sample(raw, i + width - 1) + sample(raw, i + width + 1)) / 4;
            }
            horizontal /= 2;
            vertical /= 2;
        } else {
            horizontal = (left ? sample(raw, i - 1) : 0) + (right ? sample(raw, i + 1) : 0);
            vertical = (top ? sample(raw, i - width) : 0) + (bottom ? sample(raw, i + width) : 0);
            int horizontalCount = (left ? 1 : 0) + (right ? 1 : 0);
            int verticalCount = (top ? 1 : 0) + (bottom ? 1 : 0);
            if (primary) {
                cross = average(horizontal + vertical, horizontalCount + verticalCount);
                int diagonalSum = (left && top ? sample(raw, i - width - 1) : 0)
                        + (right && top ? sample(raw, i - width + 1) : 0)
                        + (left && bottom ? sample(raw, i + width - 1) : 0)
                        + (right && bottom ? sample(raw, i + width + 1) : 0);
                diagonal = average(diagonalSum, horizontalCount * verticalCount);
            }
            horizontal = average(horizontal, horizontalCount);
            vertical = average(vertical, verticalCount);
        }
        int center = sample(raw, i);
        int red;
        int green;
        int blue;
        if (primary) {
            boolean isRed = phase == redPhase;
            red = isRed ? center : diagonal;
            green = cross;
            blue = isRed ? diagonal : center;
        } else {
            boolean redRow = (phase & 2) == (redPhase & 2);
            red = redRow ? horizontal : vertical;
            green = center;
            blue = redRow ? vertical : horizontal;
        }
        return (scale(red, white) << 16) | (scale(green, white) << 8) | scale(blue, white);
    }

    private static int sample(ShortArray raw, int index) {
        return raw.get(index) & 0xffff;
    }

    private static int average(int sum, int count) {
        return count == 0 ? 0 : sum / count;
    }

    private static int scale(int sample, int white) {
        // A corrected u16 sample times 255 fits in a signed int.
        return Math.min(255, sample * 255 / white);
    }

    private static int matrixChannel(DoubleArray color, int offset, int red, int green, int blue) {
        return Math.min(255, Math.max(0, (int) (color.get(offset) * red + color.get(offset + 1) * green
                + color.get(offset + 2) * blue)));
    }

    private static int orientedIndex(int x, int y, int width, int height, int orientation) {
        boolean reverseX = orientation == 2 || orientation == 3 || orientation == 7 || orientation == 8;
        boolean reverseY = orientation == 3 || orientation == 4 || orientation == 6 || orientation == 7;
        int orientedX = reverseX ? width - 1 - x : x;
        int orientedY = reverseY ? height - 1 - y : y;
        return orientation >= 5 && orientation <= 8 ? orientedX * height + orientedY : orientedY * width + orientedX;
    }
}
