package com.photographer.focusstack.cr3.reference;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Shared numerical metrics for reference-backed decoder validation. */
public final class ReferenceMetrics {

    private ReferenceMetrics() {
    }

    /** Global error/correlation metrics. */
    public record MetricTriple(double mae, double rmse, double pearson) {
    }

    /** Rectangular image region in row-major coordinates. */
    public record Region(int x, int y, int width, int height) {
        public Region {
            if (x < 0 || y < 0 || width < 0 || height < 0) {
                throw new IllegalArgumentException("Region values must be non-negative");
            }
        }

        public boolean isEmpty() {
            return width == 0 || height == 0;
        }
    }

    /** Error distribution over one or more selected regions. */
    public record RegionMetrics(double mae, double meanError, double stddev, long sampleCount) {
    }

    /** Computes MAE, RMSE and Pearson correlation over two equally sized arrays. */
    public static MetricTriple compare(int[] reference, int[] actual) {
        return compareWithOffset(reference, actual, 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /**
     * Computes metrics after applying a bounded constant offset to the actual values.
     * Source arrays are not modified.
     */
    public static MetricTriple compareWithOffset(
            int[] reference, int[] actual, int offset, int minimum, int maximum) {
        validatePair(reference, actual);
        if (minimum > maximum) {
            throw new IllegalArgumentException("minimum must not exceed maximum");
        }

        double absoluteErrorSum = 0.0;
        double squaredErrorSum = 0.0;
        double referenceSum = 0.0;
        double actualSum = 0.0;
        double referenceSquaredSum = 0.0;
        double actualSquaredSum = 0.0;
        double crossSum = 0.0;
        boolean equal = true;

        for (int index = 0; index < reference.length; index++) {
            double expected = reference[index];
            double observed = clamp((long) actual[index] + offset, minimum, maximum);
            double difference = observed - expected;

            absoluteErrorSum += Math.abs(difference);
            squaredErrorSum += difference * difference;
            referenceSum += expected;
            actualSum += observed;
            referenceSquaredSum += expected * expected;
            actualSquaredSum += observed * observed;
            crossSum += expected * observed;
            equal &= difference == 0.0;
        }

        double count = reference.length;
        double referenceMean = referenceSum / count;
        double actualMean = actualSum / count;
        double covariance = crossSum / count - referenceMean * actualMean;
        double referenceVariance = Math.max(0.0, referenceSquaredSum / count - referenceMean * referenceMean);
        double actualVariance = Math.max(0.0, actualSquaredSum / count - actualMean * actualMean);
        double denominator = Math.sqrt(referenceVariance * actualVariance);
        double pearson = denominator == 0.0 ? (equal ? 1.0 : 0.0) : covariance / denominator;

        return new MetricTriple(
                absoluteErrorSum / count,
                Math.sqrt(squaredErrorSum / count),
                pearson);
    }

    /** Returns the arithmetic mean of an integer sample array. */
    public static double mean(int[] values) {
        Objects.requireNonNull(values, "values");
        if (values.length == 0) {
            throw new IllegalArgumentException("values must not be empty");
        }
        double sum = 0.0;
        for (int value : values) {
            sum += value;
        }
        return sum / values.length;
    }

    /**
     * Returns the integer median of {@code reference[i] - actual[i]}.
     * This minimizes the L1 error for an unconstrained constant offset.
     */
    public static int medianOffset(int[] reference, int[] actual) {
        validatePair(reference, actual);
        int[] differences = new int[reference.length];
        for (int index = 0; index < reference.length; index++) {
            long difference = (long) reference[index] - actual[index];
            differences[index] = (int) clamp(difference, Integer.MIN_VALUE, Integer.MAX_VALUE);
        }
        Arrays.sort(differences);
        int middle = differences.length / 2;
        if ((differences.length & 1) == 1) {
            return differences[middle];
        }
        return (int) (((long) differences[middle - 1] + differences[middle]) / 2L);
    }

    /** Returns a copy of the samples with a bounded constant offset applied. */
    public static int[] addClampedOffset(int[] samples, int offset, int minimum, int maximum) {
        Objects.requireNonNull(samples, "samples");
        if (minimum > maximum) {
            throw new IllegalArgumentException("minimum must not exceed maximum");
        }
        int[] result = new int[samples.length];
        for (int index = 0; index < samples.length; index++) {
            result[index] = (int) clamp((long) samples[index] + offset, minimum, maximum);
        }
        return result;
    }

    /**
     * Computes MAE, signed mean error and error standard deviation over selected regions.
     */
    public static RegionMetrics compareRegions(
            int[] reference,
            int[] actual,
            int imageWidth,
            int imageHeight,
            List<Region> regions,
            int actualOffset,
            int minimum,
            int maximum) {
        validateImage(reference, actual, imageWidth, imageHeight);
        Objects.requireNonNull(regions, "regions");

        double absoluteErrorSum = 0.0;
        double errorSum = 0.0;
        double squaredErrorSum = 0.0;
        long count = 0;

        for (Region region : regions) {
            Objects.requireNonNull(region, "region");
            validateRegion(region, imageWidth, imageHeight);
            for (int y = region.y(); y < region.y() + region.height(); y++) {
                int rowOffset = y * imageWidth;
                for (int x = region.x(); x < region.x() + region.width(); x++) {
                    int index = rowOffset + x;
                    double observed = clamp((long) actual[index] + actualOffset, minimum, maximum);
                    double error = observed - reference[index];
                    absoluteErrorSum += Math.abs(error);
                    errorSum += error;
                    squaredErrorSum += error * error;
                    count++;
                }
            }
        }

        if (count == 0) {
            return new RegionMetrics(Double.NaN, Double.NaN, Double.NaN, 0);
        }
        double meanError = errorSum / count;
        double variance = Math.max(0.0, squaredErrorSum / count - meanError * meanError);
        return new RegionMetrics(absoluteErrorSum / count, meanError, Math.sqrt(variance), count);
    }

    /**
     * Computes mean absolute reference error at both sides of every tile seam.
     */
    public static double tileBoundaryMae(
            int[] reference,
            int[] actual,
            int imageWidth,
            int imageHeight,
            int tileWidth,
            int tileHeight) {
        validateImage(reference, actual, imageWidth, imageHeight);
        if (tileWidth <= 0 || tileHeight <= 0) {
            return Double.NaN;
        }

        double absoluteErrorSum = 0.0;
        long count = 0;
        for (int seamX = tileWidth; seamX < imageWidth; seamX += tileWidth) {
            int left = seamX - 1;
            int right = seamX;
            for (int y = 0; y < imageHeight; y++) {
                int rowOffset = y * imageWidth;
                absoluteErrorSum += Math.abs(actual[rowOffset + left] - reference[rowOffset + left]);
                absoluteErrorSum += Math.abs(actual[rowOffset + right] - reference[rowOffset + right]);
                count += 2;
            }
        }
        for (int seamY = tileHeight; seamY < imageHeight; seamY += tileHeight) {
            int topOffset = (seamY - 1) * imageWidth;
            int bottomOffset = seamY * imageWidth;
            for (int x = 0; x < imageWidth; x++) {
                absoluteErrorSum += Math.abs(actual[topOffset + x] - reference[topOffset + x]);
                absoluteErrorSum += Math.abs(actual[bottomOffset + x] - reference[bottomOffset + x]);
                count += 2;
            }
        }
        return count == 0 ? Double.NaN : absoluteErrorSum / count;
    }

    private static void validatePair(int[] reference, int[] actual) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(actual, "actual");
        if (reference.length == 0) {
            throw new IllegalArgumentException("sample arrays must not be empty");
        }
        if (reference.length != actual.length) {
            throw new IllegalArgumentException(
                    "sample length mismatch: reference=" + reference.length + ", actual=" + actual.length);
        }
    }

    private static void validateImage(int[] reference, int[] actual, int width, int height) {
        validatePair(reference, actual);
        if (width <= 0 || height <= 0 || Math.multiplyExact(width, height) != reference.length) {
            throw new IllegalArgumentException(
                    "sample arrays do not match image dimensions " + width + "x" + height);
        }
    }

    private static void validateRegion(Region region, int width, int height) {
        if ((long) region.x() + region.width() > width || (long) region.y() + region.height() > height) {
            throw new IllegalArgumentException("region exceeds image dimensions: " + region);
        }
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
