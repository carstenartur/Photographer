package com.photographer.focusstack.cr3.raw.codec;

import java.io.IOException;

/** Three-level integer 5/3 synthesis of a horizontally tiled CRX plane. */
final class CrxWaveletTile {
    static final int MAX_BOUNDED_DETAIL_FACTOR = 64;
    private static final int LEVELS = 3;

    record Shape(int width, int height) {}

    @FunctionalInterface
    interface RowReader {
        void read(int[] output) throws IOException;
    }

    /** Physical band dimensions include any coded halo columns. */
    record Band(int width, int height, RowReader rows) {}

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
    static Band synthesize(Band[] bands, int width, int height, boolean hasLeftTile, boolean hasRightTile) {
        return synthesize(bands, width, height, hasLeftTile, hasRightTile, true);
    }

    static Band synthesizeNative(Band[] bands, int width, int height, boolean hasLeftTile, boolean hasRightTile) {
        // The decoder bounds the signed LL3 and detail symbols by 2^20 in magnitude
        // before dequantization. With detail factors at most 64, D = 2^26 bounds the
        // detail inputs. For an approximation bounded by L, horizontal lifting yields
        // low rows bounded by L + 3D/2 and high rows by 5D/2. Vertical lifting then
        // yields samples bounded by L + 21D/4. After three levels the bound is
        // 2^20 + 3 * 21D/4 < 2^30, so predictor sums and rounding fit in int.
        return synthesize(bands, width, height, hasLeftTile, hasRightTile, false);
    }

    private static Band synthesize(Band[] bands, int width, int height, boolean hasLeftTile, boolean hasRightTile,
                                   boolean checked) {
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
                    widths[level], heights[level], hasLeftTile, hasRightTile, checked);
        }
        return low;
    }

    private static int lowWidth(int output, boolean hasRightTile) {
        return hasRightTile ? output / 2 + 1 : Math.ceilDiv(output, 2);
    }

    private static Band level(Band ll, Band x, Band y, Band xy, int width, int height,
                              boolean hasLeftTile, boolean hasRightTile, boolean checked) {
        int lw = lowWidth(width, hasRightTile);
        int hw = (hasRightTile ? width / 2 + 1 : width / 2) + (hasLeftTile ? 1 : 0);
        int lh = Math.ceilDiv(height, 2), hh = height / 2;
        requireShape(ll, lw, lh);
        requireShape(x, hw, lh);
        requireShape(y, lw, hh);
        requireShape(xy, hw, hh);
        var lowRows = new HorizontalRows(ll, x, lw, hw, hasLeftTile, width, checked);
        var highRows = new HorizontalRows(y, xy, lw, hw, hasLeftTile, width, checked);
        return new Band(width, height, new VerticalRows(lowRows, highRows, width, height, checked));
    }

    private static void requireShape(Band band, int columns, int rows) {
        // Some right halos store an additional column. Only the lifting support is used;
        // row strides must always follow the physical entropy dimensions.
        if (band.width < columns || band.height != rows) {
            throw new IllegalArgumentException("Wavelet band does not cover its synthesis region");
        }
    }

    /** Reconstructs one contiguous row across both tile halos. */
    private record HorizontalRows(Band low, Band high, int lowWidth, int highWidth, boolean leftGhost,
                                  int width, boolean checked, int[] lowRow, int[] highRow) implements RowReader {
        HorizontalRows(Band low, Band high, int lowWidth, int highWidth, boolean leftGhost, int width, boolean checked) {
            this(low, high, lowWidth, highWidth, leftGhost, width, checked, new int[low.width], new int[high.width]);
        }

        @Override
        public void read(int[] output) throws IOException {
            low.rows.read(lowRow);
            high.rows.read(highRow);
            if (highWidth == 0) {
                output[0] = lowRow[0];
                return;
            }
            int detail = leftGhost ? 1 : 0;
            int right = highRow[Math.min(detail, highWidth - 1)];
            int even = undoUpdate(lowRow[0], highRow[0], right, checked);
            output[0] = even;
            for (int odd = 1, i = 1; odd < width; odd += 2, i++) {
                int nextRight = highRow[Math.min(++detail, highWidth - 1)];
                int nextEven = i < lowWidth ? undoUpdate(lowRow[i], right, nextRight, checked) : even;
                output[odd] = undoPredict(right, even, nextEven, checked);
                if (odd + 1 < width) {
                    output[odd + 1] = nextEven;
                }
                even = nextEven;
                right = nextRight;
            }
        }
    }

    /** One even row above and below the odd row suffice for the vertical inverse 5/3 lift. */
    private static final class VerticalRows implements RowReader {
        private final RowReader low;
        private final RowReader high;
        private final int width;
        private final int height;
        private final boolean checked;
        private int[] even;
        private int[] nextEven;
        private int[] detail;
        private int[] nextDetail;
        private int row;

        VerticalRows(RowReader low, RowReader high, int width, int height, boolean checked) {
            this.low = low;
            this.high = high;
            this.width = width;
            this.height = height;
            this.checked = checked;
            even = new int[width];
            nextEven = new int[width];
            detail = new int[width];
            nextDetail = new int[width];
        }

        @Override
        public void read(int[] output) throws IOException {
            if (row == 0) {
                low.read(even);
                if (height > 1) {
                    high.read(detail);
                    updateRow(even, detail, detail);
                }
            }
            if ((row & 1) == 0) {
                System.arraycopy(even, 0, output, 0, width);
            } else if (row == height - 1) {
                predictRow(output, even, even);
            } else {
                low.read(nextEven);
                if (row + 2 < height) {
                    high.read(nextDetail);
                    updateRow(nextEven, detail, nextDetail);
                } else {
                    updateRow(nextEven, detail, detail);
                }
                predictRow(output, even, nextEven);
                int[] oldEven = even;
                even = nextEven;
                nextEven = oldEven;
                int[] oldDetail = detail;
                detail = nextDetail;
                nextDetail = oldDetail;
            }
            row++;
        }

        private void updateRow(int[] lowRow, int[] above, int[] below) {
            for (int x = 0; x < width; x++) {
                lowRow[x] = undoUpdate(lowRow[x], above[x], below[x], checked);
            }
        }

        private void predictRow(int[] output, int[] above, int[] below) {
            for (int x = 0; x < width; x++) {
                output[x] = undoPredict(detail[x], above[x], below[x], checked);
            }
        }
    }

    private static int undoUpdate(int low, int left, int right, boolean checked) {
        if (checked) {
            return Math.toIntExact(low - (((long) left + right + 2) >> 2));
        }
        return low - ((left + right + 2) >> 2);
    }

    private static int undoPredict(int high, int left, int right, boolean checked) {
        if (checked) {
            return Math.toIntExact(high + (((long) left + right) >> 1));
        }
        return high + ((left + right) >> 1);
    }
}
