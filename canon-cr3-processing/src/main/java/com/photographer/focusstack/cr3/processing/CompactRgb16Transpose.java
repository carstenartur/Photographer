package com.photographer.focusstack.cr3.processing;

import java.util.BitSet;

/**
 * Transposes dense, exclusively owned RGB16 banks. A group is a contiguous horizontal
 * run in a source row. The group permutation moves whole runs with array copies, then
 * a local transpose puts the samples within each group of destination rows in order.
 * One buffer and one group bitmap serve all three banks in turn.
 */
final class CompactRgb16Transpose {
    private static final int MAX_GROUP_WIDTH = 64;
    private static final int MIN_GROUP_WIDTH = 8;
    private static final int ROWS_PER_BLOCK = 32;
    // Allow for the second array's header and allocation alignment.
    private static final int ARRAY_OVERHEAD_RESERVE = 1024;

    private CompactRgb16Transpose() { }

    /**
     * Returns false before modifying any sample if a group and its bitmap exceed the
     * pixel-cycle workspace budget, or if there are too few rows to block. The caller
     * has verified that each bank contains exactly width × height samples.
     */
    static boolean apply(short[][] banks, int width, int height, boolean flipX, boolean flipY) {
        int groupWidth = MAX_GROUP_WIDTH;
        // Limit scratch to the bytes already allowed for the single-pixel visited set.
        long budget = bitmapBytes((long) width * height) - ARRAY_OVERHEAD_RESERVE;
        while (groupWidth >= MIN_GROUP_WIDTH && height * ((long) Short.BYTES * groupWidth)
                + bitmapBytes((long) (width / groupWidth) * height) > budget) {
            groupWidth /= 2;
        }
        if (groupWidth < MIN_GROUP_WIDTH || height < 8) {
            return false;
        }
        // groupWidth is at most width. The group buffer and bitmap element counts
        // are therefore each bounded by the length of one validated dense bank.
        int groupsPerRow = width / groupWidth;
        var visited = new BitSet(groupsPerRow * height);
        short[] work = new short[groupWidth * height];
        for (short[] bank : banks) {
            compactTail(bank, work, width, height, groupsPerRow * groupWidth, flipX, flipY);
            reorderGroups(bank, work, groupWidth, groupsPerRow, height, flipX, flipY, visited);
            transposeColumns(bank, work, groupWidth, groupsPerRow, height, flipX);
            visited.clear();
        }
        return true;
    }

    private static long bitmapBytes(long elements) {
        return ((elements + 63) / 64) * Long.BYTES;
    }

    // An incomplete group always becomes the last destination rows. With reverse X
    // these samples come from the left edge of the sensor. Save them in final order,
    // pack the remaining rows forward, and copy the saved values to the free suffix.
    private static void compactTail(short[] bank, short[] work, int width, int height,
                                    int fullWidth, boolean flipX, boolean flipY) {
        int tail = width - fullWidth;
        if (tail == 0) {
            return;
        }
        int tailStart = flipX ? 0 : fullWidth;
        int fullStart = flipX ? tail : 0;
        for (int y = 0; y < height; y++) {
            int source = y * width;
            int outputY = flipY ? height - 1 - y : y;
            for (int x = 0; x < tail; x++) {
                int outputX = flipX ? tail - 1 - x : x;
                work[outputX * height + outputY] = bank[source + tailStart + x];
            }
            System.arraycopy(bank, source + fullStart, bank, y * fullWidth, fullWidth);
        }
        System.arraycopy(work, 0, bank, fullWidth * height, tail * height);
    }

    // The source group index is sourceY * groupsPerRow + sourceX. Its destination
    // uses X as the major coordinate and applies the EXIF flips to both group axes.
    // Follow the inverse permutation: fill each vacated group from its predecessor,
    // keeping the first group's original values in work until its cycle closes.
    private static void reorderGroups(short[] bank, short[] work, int groupWidth, int groupsPerRow, int height,
                                      boolean flipX, boolean flipY, BitSet visited) {
        int count = groupsPerRow * height;
        for (int first = visited.nextClearBit(0); first < count; first = visited.nextClearBit(first + 1)) {
            System.arraycopy(bank, first * groupWidth, work, 0, groupWidth);
            int to = first;
            while (true) {
                visited.set(to);
                int x = to / height;
                int y = to - x * height;
                int sourceX = flipX ? groupsPerRow - 1 - x : x;
                int sourceY = flipY ? height - 1 - y : y;
                int from = sourceY * groupsPerRow + sourceX;
                if (from == first) {
                    break;
                }
                System.arraycopy(bank, from * groupWidth, bank, to * groupWidth, groupWidth);
                to = from;
            }
            System.arraycopy(work, 0, bank, to * groupWidth, groupWidth);
        }
    }

    // Each block of groupWidth destination rows is now contiguous. Its rows are
    // still interleaved, so transpose the block locally into the work buffer.
    private static void transposeColumns(short[] bank, short[] work, int groupWidth, int groupsPerRow,
                                         int height, boolean flipX) {
        int length = groupWidth * height;
        for (int column = 0; column < groupsPerRow; column++) {
            int start = column * length;
            for (int top = 0; top < height;) {
                int bottom = top + Math.min(ROWS_PER_BLOCK, height - top);
                for (int x = 0; x < groupWidth; x++) {
                    int dest = (flipX ? groupWidth - 1 - x : x) * height + top;
                    int source = start + top * groupWidth + x;
                    for (int y = top; y < bottom; y++, source += groupWidth) {
                        work[dest++] = bank[source];
                    }
                }
                top = bottom;
            }
            System.arraycopy(work, 0, bank, start, length);
        }
    }
}
