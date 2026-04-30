package com.photographer.focusstack.cr3.research;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SubbandStatistics}. These exercise the pure, synthetic
 * behaviour of the statistics class — they do not need any CR3 sample on disk.
 */
class SubbandStatisticsTest {

    @Test
    void rejectsNullInput() {
        assertThatThrownBy(() -> SubbandStatistics.compute(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyInputProducesZeroedStatistics() {
        SubbandStatistics s = SubbandStatistics.compute(new byte[0]);
        assertThat(s.totalBits()).isZero();
        assertThat(s.oneBits()).isZero();
        assertThat(s.oneBitFraction()).isZero();
        assertThat(s.longestZeroRun()).isZero();
        assertThat(s.byteHistogram()).hasSize(256).containsOnly(0L);
    }

    @Test
    void allZeroesProducesOneLongRun() {
        // 4 bytes of 0x00 = 32 zero bits, no terminating one.
        SubbandStatistics s = SubbandStatistics.compute(new byte[]{0, 0, 0, 0});
        assertThat(s.totalBits()).isEqualTo(32);
        assertThat(s.oneBits()).isZero();
        assertThat(s.longestZeroRun()).isEqualTo(32);
        // The trailing run is recorded
        long[] hist = s.zeroRunHistogram();
        assertThat(hist[32]).isEqualTo(1L);
    }

    @Test
    void allOnesProducesOnlyZeroLengthRuns() {
        // 0xFF byte = 8 one-bits, each preceded by a zero-length run.
        SubbandStatistics s = SubbandStatistics.compute(new byte[]{(byte) 0xFF});
        assertThat(s.oneBits()).isEqualTo(8);
        assertThat(s.oneBitFraction()).isEqualTo(1.0);
        long[] hist = s.zeroRunHistogram();
        assertThat(hist[0]).isEqualTo(8L);
        assertThat(s.longestZeroRun()).isZero();
    }

    @Test
    void byteHistogramCountsUnsignedValues() {
        SubbandStatistics s = SubbandStatistics.compute(new byte[]{0, 0, (byte) 0xFF, 0x42});
        long[] hist = s.byteHistogram();
        assertThat(hist[0]).isEqualTo(2L);
        assertThat(hist[0xFF]).isEqualTo(1L);
        assertThat(hist[0x42]).isEqualTo(1L);
    }

    @Test
    void zeroRunHistogramRecognisesSingleBitRuns() {
        // 0x80 = bits 1000 0000. MSB-first: a 1, then 7 zeroes (trailing run).
        SubbandStatistics s = SubbandStatistics.compute(new byte[]{(byte) 0x80});
        long[] hist = s.zeroRunHistogram();
        assertThat(hist[0]).isEqualTo(1L); // the leading run of 0 zeros before the 1-bit
        assertThat(hist[7]).isEqualTo(1L); // the trailing 7 zero bits
        assertThat(s.longestZeroRun()).isEqualTo(7);
    }

    @Test
    void zeroRunHistogramRecognisesMixedPattern() {
        // 0x21 = 0010 0001 → MSB-first bits: 0 0 1 0 0 0 0 1
        // Runs of zeros terminated by a 1: length 2, then length 4.
        SubbandStatistics s = SubbandStatistics.compute(new byte[]{0x21});
        long[] hist = s.zeroRunHistogram();
        assertThat(hist[2]).isEqualTo(1L);
        assertThat(hist[4]).isEqualTo(1L);
        assertThat(s.oneBits()).isEqualTo(2);
        assertThat(s.longestZeroRun()).isEqualTo(4);
    }

    @Test
    void summaryContainsKeyFields() {
        SubbandStatistics s = SubbandStatistics.compute(new byte[]{0x01, 0x02, 0x03});
        String summary = s.summary();
        assertThat(summary)
                .contains("totalBytes=3")
                .contains("totalBits=24")
                .contains("oneFrac=")
                .contains("longestZeroRun=")
                .contains("topBytes=")
                .contains("zeroRunHist[0..15]=")
                .contains("zeroRunHist[16+]=");
    }

    @Test
    void longRunsAreSaturatedIntoLastBucket() {
        // Construct a buffer of >1024 zero bits to force saturation.
        byte[] data = new byte[200]; // 1600 zero bits
        SubbandStatistics s = SubbandStatistics.compute(data);
        long[] hist = s.zeroRunHistogram();
        // The trailing run of 1600 zeros lands in the last bucket (1023).
        assertThat(hist[hist.length - 1]).isEqualTo(1L);
        assertThat(s.longestZeroRun()).isEqualTo(1600);
    }
}
