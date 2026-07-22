package com.photographer.focusstack.cr3.research;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GrayCodeSpatialCorrelatorTest {

    @Test
    void directPolarityIdentifiesExactUniqueCoordinate() {
        boolean[] observed = GrayCodeSpatialCorrelator.codeWord(37, 64);

        GrayCodeSpatialCorrelator.Result result = GrayCodeSpatialCorrelator.correlate(
                observed, 64, GrayCodeSpatialCorrelator.Polarity.DIRECT);

        assertThat(result.exactUnique()).isTrue();
        assertThat(result.uniqueCandidate())
                .isEqualTo(new GrayCodeSpatialCorrelator.Candidate(37, false, 0));
    }

    @Test
    void invertedPolarityIdentifiesExactUniqueCoordinate() {
        boolean[] observed = GrayCodeSpatialCorrelator.codeWord(11, 64);
        for (int bit = 0; bit < observed.length; bit++) {
            observed[bit] = !observed[bit];
        }

        GrayCodeSpatialCorrelator.Result result = GrayCodeSpatialCorrelator.correlate(
                observed, 64, GrayCodeSpatialCorrelator.Polarity.INVERTED);

        assertThat(result.exactUnique()).isTrue();
        assertThat(result.uniqueCandidate())
                .isEqualTo(new GrayCodeSpatialCorrelator.Candidate(11, true, 0));
    }

    @Test
    void unknownPolarityRemainsAmbiguousAcrossTheCompleteCodeSpace() {
        boolean[] observed = GrayCodeSpatialCorrelator.codeWord(37, 64);

        GrayCodeSpatialCorrelator.Result result =
                GrayCodeSpatialCorrelator.correlate(observed, 64);

        assertThat(result.minimumDistance()).isZero();
        assertThat(result.exactUnique()).isFalse();
        assertThat(result.bestCandidates()).hasSize(2);
        assertThat(result.bestCandidates())
                .contains(new GrayCodeSpatialCorrelator.Candidate(37, false, 0));
    }

    @Test
    void truncatedSignatureRemainsExplicitlyAmbiguous() {
        boolean[] full = GrayCodeSpatialCorrelator.codeWord(23, 64);
        boolean[] observed = Arrays.copyOf(full, 3);

        GrayCodeSpatialCorrelator.Result result = GrayCodeSpatialCorrelator.correlate(
                observed, 64, GrayCodeSpatialCorrelator.Polarity.DIRECT);

        assertThat(result.minimumDistance()).isZero();
        assertThat(result.exactUnique()).isFalse();
        assertThat(result.bestCandidates()).hasSizeGreaterThan(1);
        assertThat(result.bestCandidates())
                .allSatisfy(candidate -> assertThat(candidate.hammingDistance()).isZero());
    }

    @Test
    void oneNoisyPlaneCanAliasAnotherExactCoordinateEvenWithKnownPolarity() {
        boolean[] observed = GrayCodeSpatialCorrelator.codeWord(42, 64);
        observed[4] = !observed[4];

        GrayCodeSpatialCorrelator.Result result = GrayCodeSpatialCorrelator.correlate(
                observed, 64, GrayCodeSpatialCorrelator.Polarity.DIRECT);

        // A complete six-bit Gray code covers all 64 code words. A single unstable
        // plane may therefore look like another exact coordinate. Duplicate
        // filtering and an independent stimulus family remain mandatory.
        assertThat(result.exactUnique()).isTrue();
        assertThat(result.uniqueCandidate().coordinate()).isNotEqualTo(42);
        assertThat(result.uniqueCandidate().hammingDistance()).isZero();
        assertThat(GrayCodeSpatialCorrelator.correlate(
                GrayCodeSpatialCorrelator.codeWord(42, 64),
                64,
                GrayCodeSpatialCorrelator.Polarity.DIRECT)
                .uniqueCandidate().coordinate()).isEqualTo(42);
    }

    @Test
    void validatesDeclaredCellAndPlaneCounts() {
        assertThatThrownBy(() -> GrayCodeSpatialCorrelator.correlate(new boolean[0], 64))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GrayCodeSpatialCorrelator.correlate(new boolean[7], 64))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GrayCodeSpatialCorrelator.codeWord(64, 64))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GrayCodeSpatialCorrelator.codeWord(0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cells must be at least 2");
        assertThatThrownBy(() -> GrayCodeSpatialCorrelator.codeWord(0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cells must be at least 2");
    }
}
