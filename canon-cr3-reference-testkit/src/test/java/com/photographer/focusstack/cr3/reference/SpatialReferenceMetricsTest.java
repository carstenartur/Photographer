package com.photographer.focusstack.cr3.reference;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpatialReferenceMetricsTest {

    @Test
    void clipsRectanglesToImageBounds() {
        assertThat(SpatialReferenceMetrics.Rect.clip(-3, 1, 8, 9, 6, 5))
                .isEqualTo(new SpatialReferenceMetrics.Rect(0, 1, 6, 4));
        assertThat(SpatialReferenceMetrics.Rect.clip(9, 9, 12, 12, 6, 5).isEmpty())
                .isTrue();
    }

    @Test
    void comparesMultipleRegionsWithSignedPopulationStatistics() {
        int[] reference = {
                10, 10, 10, 10,
                10, 10, 10, 10,
                10, 10, 10, 10
        };
        int[] actual = {
                8, 12, 10, 10,
                9, 13, 10, 10,
                10, 10, 14, 6
        };

        SpatialReferenceMetrics.DifferenceStats result = SpatialReferenceMetrics.compareRegions(
                reference, actual, 4, 3,
                List.of(new SpatialReferenceMetrics.Rect(0, 0, 2, 2),
                        new SpatialReferenceMetrics.Rect(2, 2, 2, 1)));

        assertThat(result.sampleCount()).isEqualTo(6);
        assertThat(result.mae()).isEqualTo(16.0 / 6.0);
        assertThat(result.rmse()).isEqualTo(Math.sqrt(50.0 / 6.0));
        assertThat(result.meanError()).isEqualTo(2.0 / 6.0);
        assertThat(result.errorStdDev()).isEqualTo(Math.sqrt(50.0 / 6.0 - Math.pow(2.0 / 6.0, 2)));
    }

    @Test
    void comparesOneBayerGridPosition() {
        int[] reference = {
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0
        };
        int[] actual = {
                1, 9, 2, 9,
                9, 9, 9, 9,
                3, 9, 4, 9,
                9, 9, 9, 9
        };

        SpatialReferenceMetrics.DifferenceStats result = SpatialReferenceMetrics.compareGrid(
                reference, actual, 4, 4, 0, 0, 2, 2);

        assertThat(result.sampleCount()).isEqualTo(4);
        assertThat(result.mae()).isEqualTo(2.5);
        assertThat(result.rmse()).isEqualTo(Math.sqrt(7.5));
        assertThat(result.meanError()).isEqualTo(2.5);
    }

    @Test
    void computesErrorOnBothSidesOfInternalTileSeams() {
        int[] reference = new int[16];
        int[] actual = {
                0, 1, 3, 0,
                0, 1, 3, 0,
                0, 1, 3, 0,
                0, 1, 3, 0
        };

        assertThat(SpatialReferenceMetrics.tileBoundaryMae(reference, actual, 4, 4, 2, 4))
                .isEqualTo(2.0);
        assertThat(SpatialReferenceMetrics.tileBoundaryMae(reference, actual, 4, 4, 4, 4))
                .isNaN();
    }

    @Test
    void rejectsInvalidGeometryAndEmptySelections() {
        assertThatThrownBy(() -> SpatialReferenceMetrics.compareRegions(
                new int[4], new int[3], 2, 2,
                List.of(new SpatialReferenceMetrics.Rect(0, 0, 1, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sample count mismatch");

        assertThatThrownBy(() -> SpatialReferenceMetrics.compareRegions(
                new int[4], new int[4], 2, 2,
                List.of(new SpatialReferenceMetrics.Rect(0, 0, 0, 0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no samples");

        assertThatThrownBy(() -> SpatialReferenceMetrics.compareGrid(
                new int[4], new int[4], 2, 2, 2, 0, 2, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offsets");
    }
}
