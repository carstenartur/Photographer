package com.photographer.focusstack.cr3.reference;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReferenceMetricsTest {

    @Test
    void equalSamplesHaveZeroErrorAndPerfectCorrelation() {
        int[] samples = {10, 20, 30, 40};

        ReferenceMetrics.MetricTriple metrics = ReferenceMetrics.compare(samples, samples.clone());

        assertThat(metrics.mae()).isZero();
        assertThat(metrics.rmse()).isZero();
        assertThat(metrics.pearson()).isEqualTo(1.0);
    }

    @Test
    void computesKnownErrorAndCorrelation() {
        int[] reference = {0, 10, 20, 30};
        int[] actual = {0, 12, 18, 34};

        ReferenceMetrics.MetricTriple metrics = ReferenceMetrics.compare(reference, actual);

        assertThat(metrics.mae()).isEqualTo(2.0);
        assertThat(metrics.rmse()).isCloseTo(Math.sqrt(6.0), within(1e-12));
        assertThat(metrics.pearson()).isBetween(0.95, 1.0);
    }

    @Test
    void constantArrayCorrelationHasExplicitSemantics() {
        assertThat(ReferenceMetrics.compare(new int[]{7, 7}, new int[]{7, 7}).pearson()).isEqualTo(1.0);
        assertThat(ReferenceMetrics.compare(new int[]{7, 7}, new int[]{8, 8}).pearson()).isZero();
        assertThat(ReferenceMetrics.compare(new int[]{7, 7}, new int[]{8, 9}).pearson()).isZero();
    }

    @Test
    void offsetComparisonDoesNotMutateInputAndClamps() {
        int[] reference = {5, 10, 15};
        int[] actual = {0, 5, 20};

        ReferenceMetrics.MetricTriple metrics =
                ReferenceMetrics.compareWithOffset(reference, actual, 5, 0, 20);

        assertThat(metrics.mae()).isCloseTo(10.0 / 3.0, within(1e-12));
        assertThat(actual).containsExactly(0, 5, 20);
        assertThat(ReferenceMetrics.addClampedOffset(actual, 5, 0, 20)).containsExactly(5, 10, 20);
    }

    @Test
    void medianOffsetMinimizesL1ConstantShift() {
        int[] reference = {100, 110, 120, 130, 140};
        int[] actual = {90, 100, 111, 119, 128};

        assertThat(ReferenceMetrics.medianOffset(reference, actual)).isEqualTo(10);
    }

    @Test
    void computesRegionErrorDistribution() {
        int[] reference = {
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0
        };
        int[] actual = {
                1, 2, 9, 9,
                3, 4, 9, 9,
                5, 6, 9, 9
        };

        ReferenceMetrics.RegionMetrics metrics = ReferenceMetrics.compareRegions(
                reference,
                actual,
                4,
                3,
                List.of(new ReferenceMetrics.Region(0, 0, 2, 3)),
                0,
                0,
                65535);

        assertThat(metrics.sampleCount()).isEqualTo(6);
        assertThat(metrics.mae()).isEqualTo(3.5);
        assertThat(metrics.meanError()).isEqualTo(3.5);
        assertThat(metrics.stddev()).isCloseTo(Math.sqrt(35.0 / 12.0), within(1e-12));
    }

    @Test
    void computesErrorAtBothSidesOfTileSeams() {
        int[] reference = new int[16];
        int[] actual = {
                0, 1, 2, 0,
                0, 1, 2, 0,
                3, 4, 5, 3,
                0, 1, 2, 0
        };

        double boundaryMae = ReferenceMetrics.tileBoundaryMae(reference, actual, 4, 4, 2, 2);

        assertThat(boundaryMae).isEqualTo(2.25);
    }

    @Test
    void rejectsMismatchedArrays() {
        assertThatThrownBy(() -> ReferenceMetrics.compare(new int[]{1}, new int[]{1, 2}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sample length mismatch");
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
