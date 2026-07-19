package com.photographer.focusstack.cr3.reference;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ReferenceDifferenceImageTest {

    @Test
    void preservesHistoricalAbsoluteHeatmapMapping() {
        ReferenceImage reference = image(4, 1, 100, 100, 100, 100);
        ReferenceImage actual = image(4, 1, 100, 108, 116, 2_140);

        BufferedImage result = ReferenceDifferenceImage.absoluteHeatmap(reference, actual);

        assertThat(rgb(result, 0, 0)).isEqualTo(0x000000);
        assertThat(rgb(result, 1, 0)).isEqualTo(0x010000);
        assertThat(rgb(result, 2, 0)).isEqualTo(0x020100);
        assertThat(rgb(result, 3, 0)).isEqualTo(0xFF7F00);
    }

    @Test
    void absoluteMappingTreatsPositiveAndNegativeDifferencesEqually() {
        ReferenceImage reference = image(2, 1, 1_000, 1_000);
        ReferenceImage actual = image(2, 1, 936, 1_064);

        BufferedImage result = ReferenceDifferenceImage.absoluteHeatmap(reference, actual);

        assertThat(rgb(result, 0, 0)).isEqualTo(rgb(result, 1, 0));
        assertThat(rgb(result, 0, 0)).isEqualTo(0x080400);
    }

    @Test
    void appliesConstantActualOffsetBeforeTakingAbsoluteDifference() {
        ReferenceImage reference = image(2, 1, 1_000, 1_000);
        ReferenceImage actual = image(2, 1, 900, 1_100);

        BufferedImage result = ReferenceDifferenceImage.absoluteHeatmap(
                reference, actual, 100, 10);

        assertThat(rgb(result, 0, 0)).isEqualTo(0x000000);
        assertThat(rgb(result, 1, 0)).isEqualTo(0x140A00);
    }

    @Test
    void rendersOnlyRequestedRegionWithoutCopyingSamples() {
        int[] reference = {
                0, 0, 0,
                0, 0, 0
        };
        int[] actual = {
                8, 16, 24,
                32, 40, 48
        };

        BufferedImage result = ReferenceDifferenceImage.absoluteHeatmap(
                reference,
                actual,
                3,
                2,
                new SpatialReferenceMetrics.Rect(1, 0, 2, 2),
                0,
                8);

        assertThat(result.getWidth()).isEqualTo(2);
        assertThat(result.getHeight()).isEqualTo(2);
        assertThat(rgb(result, 0, 0)).isEqualTo(0x020100);
        assertThat(rgb(result, 1, 0)).isEqualTo(0x030100);
        assertThat(rgb(result, 0, 1)).isEqualTo(0x050200);
        assertThat(rgb(result, 1, 1)).isEqualTo(0x060300);
    }

    @Test
    void scalingReturnsSameInstanceForUnchangedGeometry() {
        BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);

        assertThat(ReferenceDifferenceImage.scale(source, 2, 2)).isSameAs(source);
        assertThat(ReferenceDifferenceImage.scale(source, 4, 3))
                .extracting(BufferedImage::getWidth, BufferedImage::getHeight)
                .containsExactly(4, 3);
    }

    @Test
    void rejectsInvalidGeometryRegionAndDivisor() {
        ReferenceImage reference = image(2, 1, 0, 0);
        ReferenceImage wrongGeometry = image(1, 2, 0, 0);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> ReferenceDifferenceImage.absoluteHeatmap(
                        reference, wrongGeometry));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ReferenceDifferenceImage.absoluteHeatmap(
                        reference.samples(), reference.samples(), 2, 1,
                        new SpatialReferenceMetrics.Rect(1, 0, 2, 1), 0, 8));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ReferenceDifferenceImage.absoluteHeatmap(
                        reference.samples(), reference.samples(), 2, 1,
                        new SpatialReferenceMetrics.Rect(0, 0, 2, 1), 0, 0));
    }

    private static ReferenceImage image(int width, int height, int... samples) {
        return new ReferenceImage(width, height, samples);
    }

    private static int rgb(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) & 0x00FF_FFFF;
    }
}
