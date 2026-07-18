package com.photographer.focusstack.cr3.raw.codec;

/** Inverse wavelet transforms used by CRX wavelet reconstruction. */
public final class InverseWaveletTransform {

    public enum FilterType {
        LEGALL_5_3,
        CDF_9_7
    }

    private InverseWaveletTransform() {}

    public static int[] inverseSynthesis1D(int[] lowpass, int[] highpass, FilterType filterType) {
        return switch (filterType) {
            case LEGALL_5_3 -> inverseLegall53_1D(lowpass, highpass);
            case CDF_9_7 -> throw new UnsupportedOperationException(
                    "CDF 9/7 inverse wavelet transform is not yet implemented. "
                            + "The filter coefficients are known (ITU-T T.800 Annex F.3.5), "
                            + "but confirmation of CRX C-RAW's specific filter choice is pending.");
        };
    }

    public static int[] inverseSynthesis2D(int[] ll, int[] lh, int[] hl, int[] hh,
                                            int llWidth, int llHeight,
                                            int outWidth, int outHeight,
                                            FilterType filterType) {
        int halfWidth = llWidth;
        int halfHeight = llHeight;
        int[] lowColumns = new int[outHeight * halfWidth];
        int[] highColumns = new int[outHeight * halfWidth];

        for (int x = 0; x < halfWidth; x++) {
            int[] columnLow = new int[halfHeight];
            int[] columnHigh = new int[halfHeight];
            for (int y = 0; y < halfHeight; y++) {
                columnLow[y] = ll[y * halfWidth + x];
                columnHigh[y] = lh[y * halfWidth + x];
            }
            int[] columnReconstructed = inverseSynthesis1D(columnLow, columnHigh, filterType);
            for (int y = 0; y < Math.min(columnReconstructed.length, outHeight); y++) {
                lowColumns[y * halfWidth + x] = columnReconstructed[y];
            }

            int[] columnLow2 = new int[halfHeight];
            int[] columnHigh2 = new int[halfHeight];
            for (int y = 0; y < halfHeight; y++) {
                columnLow2[y] = hl[y * halfWidth + x];
                columnHigh2[y] = hh[y * halfWidth + x];
            }
            int[] columnReconstructed2 = inverseSynthesis1D(columnLow2, columnHigh2, filterType);
            for (int y = 0; y < Math.min(columnReconstructed2.length, outHeight); y++) {
                highColumns[y * halfWidth + x] = columnReconstructed2[y];
            }
        }

        int[] output = new int[outWidth * outHeight];
        for (int y = 0; y < outHeight; y++) {
            int[] rowLow = new int[halfWidth];
            int[] rowHigh = new int[halfWidth];
            for (int x = 0; x < halfWidth; x++) {
                rowLow[x] = lowColumns[y * halfWidth + x];
                rowHigh[x] = highColumns[y * halfWidth + x];
            }
            int[] rowReconstructed = inverseSynthesis1D(rowLow, rowHigh, filterType);
            System.arraycopy(rowReconstructed, 0, output, y * outWidth,
                    Math.min(rowReconstructed.length, outWidth));
        }
        return output;
    }

    private static int[] inverseLegall53_1D(int[] lowpass, int[] highpass) {
        int lowCount = lowpass.length;
        int highCount = highpass.length;
        int length = lowCount + highCount;
        if (lowCount == 0) return new int[0];
        if (highCount == 0) return java.util.Arrays.copyOf(lowpass, lowCount);

        int[] even = java.util.Arrays.copyOf(lowpass, lowCount);
        int[] odd = java.util.Arrays.copyOf(highpass, highCount);

        for (int i = 0; i < lowCount; i++) {
            int oddLeft = i > 0 ? odd[i - 1] : odd[0];
            int oddRight = i < highCount ? odd[i] : odd[highCount - 1];
            even[i] = even[i] - ((oddLeft + oddRight + 2) >> 2);
        }

        for (int i = 0; i < highCount; i++) {
            int evenRight = i + 1 < lowCount ? even[i + 1] : even[lowCount - 1];
            odd[i] = odd[i] + ((even[i] + evenRight) >> 1);
        }

        int[] result = new int[length];
        for (int i = 0; i < lowCount; i++) result[2 * i] = even[i];
        for (int i = 0; i < highCount; i++) result[2 * i + 1] = odd[i];
        return result;
    }
}
