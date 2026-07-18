package com.photographer.focusstack.cr3.reference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ReferenceMetricsTest {

    @Test
    void identicalArraysHaveZeroErrorAndPerfectCorrelation() {
        int[] samples = {10, 20, 30, 40};

        ReferenceMetrics.Metrics metrics = ReferenceMetrics.compare(samples, samples);

        assertThat(metrics.mae()).isZero();
        assertThat(metrics.rmse()).isZero();
        assertThat(metrics.pearson()).isEqualTo(1.0);
    }

    @Test
    void comparisonSupportsConstantActualOffset() {
        int[] reference = {10, 20, 30};
        int[] actual = {5, 15, 25};

        ReferenceMetrics.Metrics baseline = ReferenceMetrics.compare(reference, actual);
        ReferenceMetrics.Metrics corrected = ReferenceMetrics.compare(reference, actual, 5);

        assertThat(baseline.mae()).isEqualTo(5.0);
        assertThat(corrected.mae()).isZero();
        assertThat(corrected.rmse()).isZero();
        assertThat(corrected.pearson()).isEqualTo(1.0);
    }

    @Test
    void optimalConstantDeltaIsMedianDifference() {
        int[] reference = {100, 100, 100, 100, 100};
        int[] actual = {90, 91, 92, 93, 0};

        assertThat(ReferenceMetrics.optimalConstantDelta(reference, actual)).isEqualTo(8);
    }

    @Test
    void addClampedDoesNotWrap() {
        int[] actual = {0, 10, 65_530};

        assertThat(ReferenceMetrics.addClamped(actual, 10, 0, 65_535))
                .containsExactly(10, 20, 65_535);
    }

    @Test
    void mismatchedArraysAreRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ReferenceMetrics.compare(new int[]{1}, new int[]{1, 2}))
                .withMessageContaining("Sample count mismatch");
    }
}
