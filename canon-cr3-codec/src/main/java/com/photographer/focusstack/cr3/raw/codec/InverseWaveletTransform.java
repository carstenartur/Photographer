package com.photographer.focusstack.cr3.raw.codec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Inverse Discrete Wavelet Transform (IDWT) for reconstructing image data
 * from wavelet-decomposed subbands.
 *
 * <h2>Background (publicly known)</h2>
 * <p>Wavelet-based image compression decomposes an image into frequency subbands
 * using a filter bank. The inverse transform reconstructs the image from these
 * subbands. Two standard filter families are widely used:</p>
 * <ul>
 *   <li><b>Le Gall 5/3</b> — reversible (lossless), used in JPEG 2000 lossless mode.
 *       Defined in ITU-T T.800 / ISO/IEC 15444-1 Annex F.</li>
 *   <li><b>CDF 9/7</b> — irreversible (lossy), used in JPEG 2000 lossy mode.
 *       Defined in ITU-T T.800 / ISO/IEC 15444-1 Annex F.</li>
 * </ul>
 *
 * <h2>Current status</h2>
 * <p>This class implements the Le Gall 5/3 inverse wavelet transform, which is
 * a well-documented public standard (ITU-T T.800). The CDF 9/7 transform is
 * prepared but not yet connected to the CRX lossy decoder because:</p>
 * <ul>
 *   <li>The specific wavelet filter used by Canon CRX C-RAW has not been
 *       independently confirmed from our test files</li>
 *   <li>The dequantization and coefficient ordering steps need further analysis</li>
 * </ul>
 *
 * <p>No proprietary code was used. The filter coefficients are from the
 * public ITU-T T.800 (JPEG 2000 Part 1) standard.</p>
 */
public final class InverseWaveletTransform {

    private static final Logger LOG = LoggerFactory.getLogger(InverseWaveletTransform.class);

    /** Wavelet filter type. */
    public enum FilterType {
        /**
         * Le Gall 5/3 reversible filter (ITU-T T.800, Annex F.3.4).
         * Used for lossless wavelet coding.
         */
        LEGALL_5_3,

        /**
         * CDF 9/7 irreversible filter (ITU-T T.800, Annex F.3.5).
         * Used for lossy wavelet coding.
         */
        CDF_9_7
    }

    private InverseWaveletTransform() {}

    /**
     * Performs a single-level 1D inverse wavelet transform (synthesis).
     *
     * <p>Given interleaved lowpass (even indices) and highpass (odd indices)
     * coefficients, reconstructs the original signal at double the resolution.</p>
     *
     * <h4>Le Gall 5/3 inverse lifting steps (ITU-T T.800)</h4>
     * <ol>
     *   <li>Undo predict: even[i] += (odd[i-1] + odd[i] + 2) &gt;&gt; 2</li>
     *   <li>Undo update: odd[i] -= (even[i] + even[i+1]) &gt;&gt; 1</li>
     * </ol>
     *
     * @param lowpass   the lowpass (approximation) coefficients
     * @param highpass  the highpass (detail) coefficients
     * @param filterType the wavelet filter to use
     * @return the reconstructed signal (length = lowpass.length + highpass.length)
     * @throws UnsupportedOperationException if the filter type is not yet implemented
     */
    public static int[] inverseSynthesis1D(int[] lowpass, int[] highpass, FilterType filterType) {
        return switch (filterType) {
            case LEGALL_5_3 -> inverseLegall53_1D(lowpass, highpass);
            case CDF_9_7 -> throw new UnsupportedOperationException(
                    "CDF 9/7 inverse wavelet transform is not yet implemented. "
                    + "The filter coefficients are known (ITU-T T.800 Annex F.3.5), "
                    + "but confirmation of CRX C-RAW's specific filter choice is pending.");
        };
    }

    /**
     * Performs a single-level 2D inverse wavelet transform (synthesis).
     *
     * <p>Reconstructs a 2D image from its four subbands (LL, LH, HL, HH)
     * by first synthesising columns (vertical), then rows (horizontal).</p>
     *
     * @param ll         lowpass-lowpass subband (approximation)
     * @param lh         lowpass-highpass subband (horizontal detail)
     * @param hl         highpass-lowpass subband (vertical detail)
     * @param hh         highpass-highpass subband (diagonal detail)
     * @param llWidth    width of each input subband
     * @param llHeight   height of each input subband
     * @param outWidth   expected output width
     * @param outHeight  expected output height
     * @param filterType the wavelet filter to use
     * @return the reconstructed 2D image as a row-major int array [outHeight][outWidth]
     */
    public static int[] inverseSynthesis2D(int[] ll, int[] lh, int[] hl, int[] hh,
                                            int llWidth, int llHeight,
                                            int outWidth, int outHeight,
                                            FilterType filterType) {
        // The 2D inverse wavelet transform applies 1D synthesis in two passes:
        // 1. Vertical synthesis: combine (LL,LH) → left columns, (HL,HH) → right columns
        // 2. Horizontal synthesis: combine left and right to get output rows

        // Vertical pass: for each column, synthesise lowpass and highpass pairs
        int halfW = llWidth;
        int halfH = llHeight;

        // Intermediate buffer: full height, half width (two halves)
        int[] lowCols = new int[outHeight * halfW];
        int[] highCols = new int[outHeight * halfW];

        for (int x = 0; x < halfW; x++) {
            // Extract columns from LL (low-low) and LH (low-high)
            int[] colLow = new int[halfH];
            int[] colHigh = new int[halfH];
            for (int y = 0; y < halfH; y++) {
                colLow[y] = ll[y * halfW + x];
                colHigh[y] = lh[y * halfW + x];
            }
            int[] colRecon = inverseSynthesis1D(colLow, colHigh, filterType);
            for (int y = 0; y < Math.min(colRecon.length, outHeight); y++) {
                lowCols[y * halfW + x] = colRecon[y];
            }

            // Extract columns from HL (high-low) and HH (high-high)
            int[] colLow2 = new int[halfH];
            int[] colHigh2 = new int[halfH];
            for (int y = 0; y < halfH; y++) {
                colLow2[y] = hl[y * halfW + x];
                colHigh2[y] = hh[y * halfW + x];
            }
            int[] colRecon2 = inverseSynthesis1D(colLow2, colHigh2, filterType);
            for (int y = 0; y < Math.min(colRecon2.length, outHeight); y++) {
                highCols[y * halfW + x] = colRecon2[y];
            }
        }

        // Horizontal pass: for each row, synthesise the two half-rows
        int[] output = new int[outWidth * outHeight];
        for (int y = 0; y < outHeight; y++) {
            int[] rowLow = new int[halfW];
            int[] rowHigh = new int[halfW];
            for (int x = 0; x < halfW; x++) {
                rowLow[x] = lowCols[y * halfW + x];
                rowHigh[x] = highCols[y * halfW + x];
            }
            int[] rowRecon = inverseSynthesis1D(rowLow, rowHigh, filterType);
            System.arraycopy(rowRecon, 0, output, y * outWidth,
                    Math.min(rowRecon.length, outWidth));
        }

        return output;
    }

    /**
     * Le Gall 5/3 inverse lifting (ITU-T T.800 / JPEG 2000).
     *
     * <p>The forward transform applies predict then update.
     * The inverse must undo them in reverse order:</p>
     * <ol>
     *   <li>Undo update: even[n] = s[n] - floor((d[n-1] + d[n] + 2) / 4)</li>
     *   <li>Undo predict: odd[n] = d[n] + floor((even[n] + even[n+1]) / 2)</li>
     * </ol>
     *
     * <p>After lifting, interleave even/odd samples to get the reconstructed signal.</p>
     */
    private static int[] inverseLegall53_1D(int[] lowpass, int[] highpass) {
        int nLow = lowpass.length;
        int nHigh = highpass.length;
        int n = nLow + nHigh;

        // Handle degenerate cases
        if (nLow == 0) return new int[0];
        if (nHigh == 0) {
            int[] result = new int[nLow];
            System.arraycopy(lowpass, 0, result, 0, nLow);
            return result;
        }

        // Work on copies to avoid mutating input
        int[] even = new int[nLow]; // lowpass (s) = even samples
        int[] odd = new int[nHigh]; // highpass (d) = odd samples
        System.arraycopy(lowpass, 0, even, 0, nLow);
        System.arraycopy(highpass, 0, odd, 0, nHigh);

        // Step 1: Undo update — even[i] -= (odd[i-1] + odd[i] + 2) >> 2
        // (reverses: s[n] = x[2n] + floor((d[n-1] + d[n] + 2) / 4))
        for (int i = 0; i < nLow; i++) {
            int oddLeft = (i > 0) ? odd[i - 1] : odd[0];
            int oddRight = (i < nHigh) ? odd[i] : odd[nHigh - 1];
            even[i] = even[i] - ((oddLeft + oddRight + 2) >> 2);
        }

        // Step 2: Undo predict — odd[i] += (even[i] + even[i+1]) >> 1
        // (reverses: d[n] = x[2n+1] - floor((x[2n] + x[2n+2]) / 2))
        for (int i = 0; i < nHigh; i++) {
            int evenRight = (i + 1 < nLow) ? even[i + 1] : even[nLow - 1];
            odd[i] = odd[i] + ((even[i] + evenRight) >> 1);
        }

        // Interleave: even at indices 0,2,4,... and odd at 1,3,5,...
        int[] result = new int[n];
        for (int i = 0; i < nLow; i++) {
            result[2 * i] = even[i];
        }
        for (int i = 0; i < nHigh; i++) {
            result[2 * i + 1] = odd[i];
        }

        return result;
    }
}
