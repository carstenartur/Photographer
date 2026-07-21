package com.photographer.focusstack.cr3.research;

import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class D14DifferentialAnalyzerTest {

    @Test
    void reportsHalfOpenMostSignificantBitFirstIntervals() {
        byte[] left = {(byte) 0b0000_0000, (byte) 0b1111_0000};
        byte[] right = {(byte) 0b0111_0001, (byte) 0b1100_0000};

        assertThat(D14DifferentialAnalyzer.changedBitIntervals(left, right))
                .containsExactly(
                        new D14DifferentialAnalyzer.BitInterval(1, 4),
                        new D14DifferentialAnalyzer.BitInterval(7, 7 + 1),
                        new D14DifferentialAnalyzer.BitInterval(10, 12));
    }

    @Test
    void mergesRunsAcrossByteBoundaries() {
        byte[] left = {(byte) 0b0000_0000, (byte) 0b0000_0000};
        byte[] right = {(byte) 0b0000_0011, (byte) 0b1100_0000};

        assertThat(D14DifferentialAnalyzer.changedBitIntervals(left, right))
                .containsExactly(new D14DifferentialAnalyzer.BitInterval(6, 10));
    }

    @Test
    void duplicateBaselineMasksOnlyFrequentlyChangingBytes() {
        List<D14DifferentialAnalyzer.PayloadPair> duplicates = List.of(
                pair(0, 0, 0, 0, 0, 1),
                pair(0, 0, 0, 0, 0, 1),
                pair(0, 0, 0, 1, 0, 0),
                pair(0, 0, 0, 0, 0, 1));

        D14DifferentialAnalyzer.DuplicateNoiseBaseline baseline =
                D14DifferentialAnalyzer.duplicateNoiseBaseline(duplicates, 0.50);

        assertThat(baseline.changedPairCounts()).containsExactly(0, 1, 3);
        assertThat(baseline.isNoisyByte(0)).isFalse();
        assertThat(baseline.isNoisyByte(1)).isFalse();
        assertThat(baseline.isNoisyByte(2)).isTrue();

        byte[] left = {0, 0, 0};
        byte[] right = {1, 2, 4};
        assertThat(D14DifferentialAnalyzer.stableChangedBitIntervals(
                left, right, baseline))
                .containsExactly(
                        new D14DifferentialAnalyzer.BitInterval(7, 8),
                        new D14DifferentialAnalyzer.BitInterval(14, 15));
    }

    @Test
    void thresholdIsStrictSoExactlyAllowedFractionRemainsSignal() {
        D14DifferentialAnalyzer.DuplicateNoiseBaseline baseline =
                D14DifferentialAnalyzer.duplicateNoiseBaseline(
                        List.of(pair(0, 0), pair(0, 1)), 0.5);

        assertThat(baseline.isNoisyByte(0)).isFalse();
    }

    @Test
    void returnsExpectedBinaryReflectedGrayCodePlanes() {
        assertThat(bits(D14DifferentialAnalyzer.grayCodeVector(0, 3), 3)).isEqualTo("000");
        assertThat(bits(D14DifferentialAnalyzer.grayCodeVector(1, 3), 3)).isEqualTo("100");
        assertThat(bits(D14DifferentialAnalyzer.grayCodeVector(2, 3), 3)).isEqualTo("110");
        assertThat(bits(D14DifferentialAnalyzer.grayCodeVector(3, 3), 3)).isEqualTo("010");
        assertThat(bits(D14DifferentialAnalyzer.grayCodeVector(4, 3), 3)).isEqualTo("011");
        assertThat(bits(D14DifferentialAnalyzer.grayCodeVector(7, 3), 3)).isEqualTo("001");
    }

    @Test
    void exactGrayVectorMapsToUniqueCell() {
        BitSet observed = D14DifferentialAnalyzer.grayCodeVector(37, 6);

        D14DifferentialAnalyzer.GrayCodeMatch match =
                D14DifferentialAnalyzer.matchGrayCode(observed, 6, 64);

        assertThat(match.cellIndex()).isEqualTo(37);
        assertThat(match.hammingDistance()).isZero();
        assertThat(match.uniqueBest()).isTrue();
    }

    @Test
    void oneBitErrorStillMapsWithinPreRegisteredBound() {
        BitSet observed = D14DifferentialAnalyzer.grayCodeVector(19, 6);
        observed.flip(4);

        D14DifferentialAnalyzer.GrayCodeMatch match =
                D14DifferentialAnalyzer.matchGrayCode(observed, 6, 64);

        assertThat(match.hammingDistance()).isZero();
        assertThat(match.cellIndex()).isNotEqualTo(19);
        assertThat(match.uniqueBest()).isTrue();
    }

    @Test
    void boundedDomainCanExposeAmbiguousBestMatch() {
        BitSet observed = new BitSet(3);
        observed.set(0);
        observed.set(2);

        D14DifferentialAnalyzer.GrayCodeMatch match =
                D14DifferentialAnalyzer.matchGrayCode(observed, 3, 3);

        assertThat(match.hammingDistance()).isEqualTo(1);
        assertThat(match.uniqueBest()).isFalse();
    }

    @Test
    void correlationRejectsAmbiguousAndOutOfBoundVectorsAndSortsPositions() {
        Map<Long, BitSet> observations = new LinkedHashMap<>();
        observations.put(80L, D14DifferentialAnalyzer.grayCodeVector(2, 3));
        observations.put(16L, D14DifferentialAnalyzer.grayCodeVector(1, 3));
        BitSet ambiguous = new BitSet(3);
        ambiguous.set(0);
        ambiguous.set(2);
        observations.put(32L, ambiguous);

        Map<Long, D14DifferentialAnalyzer.GrayCodeMatch> matches =
                D14DifferentialAnalyzer.correlateGrayCodeVectors(
                        observations, 3, 3, 0);

        assertThat(matches.keySet()).containsExactly(16L, 80L);
        assertThat(matches.get(16L).cellIndex()).isEqualTo(1);
        assertThat(matches.get(80L).cellIndex()).isEqualTo(2);
    }

    @Test
    void payloadInputsAreDefensivelyCopied() {
        byte[] left = {0};
        byte[] right = {1};
        D14DifferentialAnalyzer.PayloadPair pair =
                new D14DifferentialAnalyzer.PayloadPair(left, right);
        left[0] = 7;
        right[0] = 7;

        assertThat(pair.left()).containsExactly((byte) 0);
        assertThat(pair.right()).containsExactly((byte) 1);
        byte[] returned = pair.right();
        returned[0] = 9;
        assertThat(pair.right()).containsExactly((byte) 1);
    }

    @Test
    void rejectsMismatchedPayloadAndGrayCodeDimensions() {
        assertThatThrownBy(() -> D14DifferentialAnalyzer.changedBitIntervals(
                new byte[1], new byte[2]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> D14DifferentialAnalyzer.grayCodeVector(64, 6))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> D14DifferentialAnalyzer.matchGrayCode(
                new BitSet(), 2, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static D14DifferentialAnalyzer.PayloadPair pair(int... values) {
        int half = values.length / 2;
        byte[] left = new byte[half];
        byte[] right = new byte[half];
        for (int index = 0; index < half; index++) {
            left[index] = (byte) values[index];
            right[index] = (byte) values[index + half];
        }
        return new D14DifferentialAnalyzer.PayloadPair(left, right);
    }

    private static String bits(BitSet bits, int count) {
        StringBuilder value = new StringBuilder(count);
        for (int bit = 0; bit < count; bit++) {
            value.append(bits.get(bit) ? '1' : '0');
        }
        return value.toString();
    }
}
