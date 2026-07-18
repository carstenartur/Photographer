package com.photographer.focusstack.cr3.reference;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.util.Objects;

/** Shared image-level metrics used by reference rebuild validation. */
public final class ImageReferenceMetrics {

    private static final int HISTOGRAM_BINS = 256;

    private ImageReferenceMetrics() {
    }

    /** Image-level MAE, RMSE and mean absolute normalized-CDF delta. */
    public record Metrics(double mae, double rmse, double histogramDelta) {
    }

    /**
     * Compares all bands common to two equally sized images.
     * Histogram values are normalized independently from each source bit depth.
     */
    public static Metrics compare(BufferedImage reference, BufferedImage actual) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(actual, "actual");
        if (reference.getWidth() != actual.getWidth()
                || reference.getHeight() != actual.getHeight()) {
            throw new IllegalArgumentException("Image geometry mismatch: reference="
                    + reference.getWidth() + "x" + reference.getHeight()
                    + ", actual=" + actual.getWidth() + "x" + actual.getHeight());
        }

        Raster referenceRaster = reference.getRaster();
        Raster actualRaster = actual.getRaster();
        int commonBands = Math.min(referenceRaster.getNumBands(), actualRaster.getNumBands());
        if (commonBands == 0) {
            throw new IllegalArgumentException("Images have no common raster bands");
        }

        int referenceBitDepth = referenceRaster.getSampleModel().getSampleSize(0);
        int actualBitDepth = actualRaster.getSampleModel().getSampleSize(0);
        long sampleCount = (long) reference.getWidth() * reference.getHeight() * commonBands;
        double absoluteSum = 0.0;
        double squareSum = 0.0;
        long[] referenceHistogram = new long[HISTOGRAM_BINS];
        long[] actualHistogram = new long[HISTOGRAM_BINS];

        for (int y = 0; y < reference.getHeight(); y++) {
            for (int x = 0; x < reference.getWidth(); x++) {
                for (int band = 0; band < commonBands; band++) {
                    int ref = referenceRaster.getSample(x, y, band);
                    int act = actualRaster.getSample(x, y, band);
                    double difference = act - ref;
                    absoluteSum += Math.abs(difference);
                    squareSum += difference * difference;
                    referenceHistogram[normalize(ref, referenceBitDepth)]++;
                    actualHistogram[normalize(act, actualBitDepth)]++;
                }
            }
        }

        double histogramDelta = normalizedCdfDelta(referenceHistogram, actualHistogram, sampleCount);
        return new Metrics(absoluteSum / sampleCount,
                Math.sqrt(squareSum / sampleCount), histogramDelta);
    }

    private static double normalizedCdfDelta(long[] reference, long[] actual, long count) {
        double referenceCdf = 0.0;
        double actualCdf = 0.0;
        double delta = 0.0;
        for (int i = 0; i < HISTOGRAM_BINS; i++) {
            referenceCdf += reference[i] / (double) count;
            actualCdf += actual[i] / (double) count;
            delta += Math.abs(referenceCdf - actualCdf);
        }
        return delta / HISTOGRAM_BINS;
    }

    private static int normalize(int value, int bitDepth) {
        if (bitDepth <= 0) {
            throw new IllegalArgumentException("Invalid sample bit depth: " + bitDepth);
        }
        if (bitDepth <= 8) {
            int max = (1 << bitDepth) - 1;
            return (int) Math.round(Math.max(0, Math.min(max, value)) * 255.0 / max);
        }
        if (bitDepth >= 31) {
            throw new IllegalArgumentException("Unsupported sample bit depth: " + bitDepth);
        }
        int maximum = (1 << bitDepth) - 1;
        return (int) Math.round(Math.max(0, Math.min(maximum, value)) * 255.0 / maximum);
    }
}
