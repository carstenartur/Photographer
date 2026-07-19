package com.photographer.focusstack.cr3.research;

import java.util.Arrays;

/**
 * Bit- and byte-level statistics over a single CRX entropy-coded subband payload.
 *
 * <p>These statistics are the discriminative fingerprints needed to distinguish
 * candidate decoder hypotheses for the CRX bitstream:</p>
 * <ul>
 *   <li>The byte histogram identifies stuff bytes, escape patterns, and
 *       byte-stuffing schemes (e.g. JPEG-style 0xFF -&gt; 0xFF 0x00).</li>
 *   <li>The bit-balance ratio tells us whether the stream is roughly entropy-balanced
 *       (close to 0.5 for compressed data) or biased.</li>
 *   <li>The run-length-of-zeros distribution is the empirical distribution of unary
 *       code lengths under any {@code crxBitstreamGetZeros}-style scheme — i.e. it
 *       directly mirrors the residual magnitude distribution after Rice/Golomb
 *       quotient encoding. Its shape is the strongest available signal for guessing
 *       the initial {@code k} parameter and the adaptation rule.</li>
 *   <li>The longest zero-run bounds the maximum unary count we should expect to
 *       decode before treating the stream as corrupt or hitting an escape symbol.</li>
 * </ul>
 *
 * <p>This class performs no decoding — it computes statistics over raw bytes
 * read MSB-first as a bit stream, the convention used by CRX subband payloads.</p>
 */
public final class SubbandStatistics {

    private final long[] byteHistogram;
    private final long totalBits;
    private final long oneBits;
    private final long[] zeroRunHistogram;
    private final int longestZeroRun;

    private SubbandStatistics(long[] byteHistogram, long totalBits, long oneBits,
                              long[] zeroRunHistogram, int longestZeroRun) {
        this.byteHistogram = byteHistogram;
        this.totalBits = totalBits;
        this.oneBits = oneBits;
        this.zeroRunHistogram = zeroRunHistogram;
        this.longestZeroRun = longestZeroRun;
    }

    /**
     * Computes statistics over the given byte payload.
     *
     * <p>Bits are read MSB-first within each byte, matching the convention used by
     * {@link com.photographer.focusstack.cr3.raw.codec.BitReader}. The
     * run-length-of-zeros histogram counts every maximal run of {@code 0}-bits
     * terminated by a {@code 1}-bit (or by end of stream).</p>
     *
     * @param data the subband payload (must not be {@code null})
     * @return computed statistics
     */
    public static SubbandStatistics compute(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }

        long[] hist = new long[256];
        for (byte b : data) {
            hist[b & 0xFF]++;
        }

        long totalBits = (long) data.length * 8L;
        long oneBits = 0L;
        // Run-length histogram: bucket size 1024 is enough for sub-megabyte subbands
        // and keeps the data compact even when reading multi-MB tracks.
        long[] zeroRuns = new long[1024];
        int longest = 0;
        int currentRun = 0;
        for (byte by : data) {
            int v = by & 0xFF;
            for (int bit = 7; bit >= 0; bit--) {
                int b = (v >> bit) & 1;
                if (b == 0) {
                    currentRun++;
                } else {
                    oneBits++;
                    int idx = Math.min(currentRun, zeroRuns.length - 1);
                    zeroRuns[idx]++;
                    if (currentRun > longest) {
                        longest = currentRun;
                    }
                    currentRun = 0;
                }
            }
        }
        // Trailing run, if any
        if (currentRun > 0) {
            int idx = Math.min(currentRun, zeroRuns.length - 1);
            zeroRuns[idx]++;
            if (currentRun > longest) {
                longest = currentRun;
            }
        }

        return new SubbandStatistics(hist, totalBits, oneBits, zeroRuns, longest);
    }

    /**
     * Returns a defensive copy of the byte histogram.
     *
     * @return a 256-entry histogram indexed by unsigned byte value
     */
    public long[] byteHistogram() {
        return byteHistogram.clone();
    }

    /**
     * Returns the total number of bits scanned.
     *
     * @return the total number of bits scanned
     */
    public long totalBits() {
        return totalBits;
    }

    /**
     * Returns the number of one-bits observed.
     *
     * @return the number of {@code 1}-bits observed
     */
    public long oneBits() {
        return oneBits;
    }

    /**
     * Returns the fraction of scanned bits that were one.
     *
     * @return the fraction of one-bits, from 0.0 to 1.0
     */
    public double oneBitFraction() {
        return totalBits == 0 ? 0.0 : (double) oneBits / (double) totalBits;
    }

    /**
     * Returns a defensive copy of the zero-run-length histogram. Index {@code i}
     * counts maximal runs of exactly {@code i} zero bits terminated by a one
     * (or end of stream). The last bucket is a saturation bucket that absorbs
     * runs longer than the histogram size.
     *
     * @return the zero-run histogram
     */
    public long[] zeroRunHistogram() {
        return zeroRunHistogram.clone();
    }

    /**
     * Returns the length of the longest maximal run of zero bits observed.
     *
     * @return the longest observed zero-run length
     */
    public int longestZeroRun() {
        return longestZeroRun;
    }

    /**
     * Renders a compact human-readable summary suitable for printing in CLI output
     * or pasting into a research log.
     *
     * @return a multi-line text summary
     */
    public String summary() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("totalBytes=").append(totalBits / 8L)
                .append(" totalBits=").append(totalBits)
                .append(" oneBits=").append(oneBits)
                .append(String.format(" oneFrac=%.4f%n", oneBitFraction()));
        sb.append("longestZeroRun=").append(longestZeroRun).append('\n');

        // Top 10 most-frequent bytes
        Integer[] order = new Integer[256];
        for (int i = 0; i < 256; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Long.compare(byteHistogram[b], byteHistogram[a]));
        sb.append("topBytes=");
        for (int i = 0; i < Math.min(10, 256); i++) {
            int v = order[i];
            sb.append(String.format("0x%02X:%d ", v, byteHistogram[v]));
        }
        sb.append('\n');

        // Zero-run distribution head (lengths 0..15) and selected longer buckets
        sb.append("zeroRunHist[0..15]=");
        for (int i = 0; i <= 15; i++) {
            sb.append(zeroRunHistogram[i]).append(' ');
        }
        sb.append('\n');

        long longTail = 0L;
        for (int i = 16; i < zeroRunHistogram.length; i++) {
            longTail += zeroRunHistogram[i];
        }
        sb.append("zeroRunHist[16+]=").append(longTail).append('\n');
        return sb.toString();
    }
}
