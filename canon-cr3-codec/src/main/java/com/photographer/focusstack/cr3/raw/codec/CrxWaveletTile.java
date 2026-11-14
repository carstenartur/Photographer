package com.photographer.focusstack.cr3.raw.codec;

/** Three-level integer 5/3 synthesis of a horizontally tiled CRX plane. */
final class CrxWaveletTile {
    private static final int LEVELS = 3;

    record Shape(int width, int height) {}

    record Band(int[] samples, int width, int height) {
        Band {
            if (width < 0 || height < 0 || (long) width * height != samples.length) {
                throw new IllegalArgumentException("Invalid wavelet band geometry");
            }
        }
    }

    private CrxWaveletTile() {}

    /**
     * Physical entropy grids. The high-Y, low-X band has the same stored width as its
     * LL sibling at the same scale. That LL row can include a trailing column needed
     * to interleave both independently coded halves at the next, coarser scale.
     */
    static Shape[] shapes(int width, int height, boolean hasLeftTile, boolean hasRightTile) {
        if (width <= 0 || height <= 0 || (long) width * height > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid wavelet tile dimensions");
        }
        Shape[] shapes = new Shape[1 + 3 * LEVELS];
        int[] requiredWidths = new int[LEVELS];
        for (int level = LEVELS - 1; level >= 0; level--) {
            int index = 1 + level * 3;
            int highWidth = (hasRightTile ? width / 2 + 1 : width / 2) + (hasLeftTile ? 1 : 0);
            int highHeight = height / 2;
            height = Math.ceilDiv(height, 2);
            shapes[index] = new Shape(highWidth, height);
            shapes[index + 2] = new Shape(highWidth, highHeight);
            width = lowWidth(width, hasRightTile);
            requiredWidths[level] = width;
        }
        shapes[0] = new Shape(width, height);
        for (int level = 0; level < LEVELS; level++) {
            int index = 1 + level * 3;
            shapes[index + 1] = new Shape(width, shapes[index + 2].height);
            if (level + 1 < LEVELS) {
                width = hasRightTile ? 2 * requiredWidths[level] - 1 : requiredWidths[level + 1];
            }
        }
        return shapes;
    }

    /** Band order is LL3 followed by X, Y and XY details at each level, coarsest first. */
    static int[] synthesize(Band[] bands, int width, int height, boolean hasLeftTile, boolean hasRightTile) {
        if (bands.length != 1 + 3 * LEVELS || width <= 0 || height <= 0
                || (long) width * height > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid three-level wavelet tile");
        }
        int[] widths = new int[LEVELS], heights = new int[LEVELS];
        for (int level = LEVELS - 1; level >= 0; level--) {
            widths[level] = width;
            heights[level] = height;
            // The final odd column of a nonterminal tile predicts from an even sample in the halo.
            width = lowWidth(width, hasRightTile);
            height = Math.ceilDiv(height, 2);
        }
        Band low = bands[0];
        for (int level = 0; level < LEVELS; level++) {
            int index = 1 + 3 * level;
            low = level(low, bands[index], bands[index + 1], bands[index + 2],
                    widths[level], heights[level], hasLeftTile, hasRightTile);
        }
        return low.samples;
    }

    private static int lowWidth(int output, boolean hasRightTile) {
        return hasRightTile ? output / 2 + 1 : Math.ceilDiv(output, 2);
    }

    private static Band level(Band ll, Band x, Band y, Band xy, int width, int height,
                              boolean hasLeftTile, boolean hasRightTile) {
        int lw = lowWidth(width, hasRightTile);
        int hw = (hasRightTile ? width / 2 + 1 : width / 2) + (hasLeftTile ? 1 : 0);
        int lh = Math.ceilDiv(height, 2), hh = height / 2;
        requireShape(ll, lw, lh);
        requireShape(x, hw, lh);
        requireShape(y, lw, hh);
        requireShape(xy, hw, hh);
        int[] temporary = new int[Math.multiplyExact(width, height)];
        for (int row = 0; row < lh; row++) {
            lift(ll.samples, row * ll.width, 1, lw, x.samples, row * x.width, 1, hw, hasLeftTile,
                    temporary, row * width, 1, width);
        }
        for (int row = 0; row < hh; row++) {
            lift(y.samples, row * y.width, 1, lw, xy.samples, row * xy.width, 1, hw, hasLeftTile,
                    temporary, (lh + row) * width, 1, width);
        }
        int[] output = new int[temporary.length];
        for (int col = 0; col < width; col++) {
            lift(temporary, col, width, lh, temporary, lh * width + col, width, hh, false,
                    output, col, width, height);
        }
        return new Band(output, width, height);
    }

    private static void requireShape(Band band, int columns, int rows) {
        // Some right halos store an additional column. Only the lifting support is used;
        // row strides must always follow the physical entropy dimensions.
        if (band.width < columns || band.height != rows) {
            throw new IllegalArgumentException("Wavelet band does not cover its synthesis region");
        }
    }

    /**
     * Undoes update, then predict. The arithmetic matches InverseWaveletTransform's
     * Le Gall 5/3 filter; the strides and the two tile halos differ.
     */
    private static void lift(int[] low, int l, int lowStride, int nl,
                             int[] high, int h, int highStride, int nh, boolean leftGhost,
                             int[] out, int dst, int outStride, int n) {
        if (nh == 0) {
            out[dst] = low[l];
            return;
        }
        long leftDetail = high[h];
        int detailIndex = leftGhost ? 1 : 0;
        long rightDetail = high[h + Math.min(detailIndex, nh - 1) * highStride];
        int even = Math.toIntExact(low[l] - ((leftDetail + rightDetail + 2) >> 2));
        out[dst] = even;
        for (int odd = 1, i = 0; odd < n; odd += 2, i++) {
            int nextEven = even;
            if (i + 1 < nl) {
                long nextDetail = high[h + Math.min(detailIndex + 1, nh - 1) * highStride];
                nextEven = Math.toIntExact(low[l + (i + 1) * lowStride] - ((rightDetail + nextDetail + 2) >> 2));
            }
            out[dst + odd * outStride] = Math.toIntExact(rightDetail + (((long) even + nextEven) >> 1));
            if (odd + 1 < n) {
                out[dst + (odd + 1) * outStride] = nextEven;
            }
            detailIndex++;
            rightDetail = high[h + Math.min(detailIndex, nh - 1) * highStride];
            even = nextEven;
        }
    }
}
