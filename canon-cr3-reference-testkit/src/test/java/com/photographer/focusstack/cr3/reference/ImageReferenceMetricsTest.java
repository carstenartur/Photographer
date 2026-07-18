package com.photographer.focusstack.cr3.reference;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

class ImageReferenceMetricsTest {

    @Test
    void identicalImagesHaveZeroErrorAndHistogramDelta() {
        BufferedImage image = grayImage(10, 20, 30, 40);

        ImageReferenceMetrics.Metrics metrics = ImageReferenceMetrics.compare(image, image);

        assertThat(metrics.mae()).isZero();
        assertThat(metrics.rmse()).isZero();
        assertThat(metrics.histogramDelta()).isZero();
    }

    @Test
    void reportsExpectedMaeAndRmse() {
        BufferedImage reference = grayImage(0, 10, 20, 30);
        BufferedImage actual = grayImage(0, 20, 20, 40);

        ImageReferenceMetrics.Metrics metrics = ImageReferenceMetrics.compare(reference, actual);

        assertThat(metrics.mae()).isEqualTo(5.0);
        assertThat(metrics.rmse()).isEqualTo(Math.sqrt(50.0));
        assertThat(metrics.histogramDelta()).isGreaterThan(0.0);
    }

    private static BufferedImage grayImage(int... values) {
        BufferedImage image = new BufferedImage(values.length, 1, BufferedImage.TYPE_BYTE_GRAY);
        image.getRaster().setSamples(0, 0, values.length, 1, 0, values);
        return image;
    }
}
