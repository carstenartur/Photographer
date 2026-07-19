package com.photographer.focusstack.cr3.reference;

import java.util.Arrays;
import java.util.Objects;

/** Shared sample-level metrics for reference-backed CR3 tests. */
public final class ReferenceMetrics {

    private static final int MAX_EXACT_MEDIAN_RANGE = 200_000;
    private static final int MAX_APPROXIMATE_MEDIAN_SAMPLES = 1_000_000;

    private ReferenceMetrics() {
    }

    /** Immutable MAE/RMSE/Pearson result. */
    public record Metrics(double mae, double rmse, double pearson) {
    }

    /** Compares two equally sized sample arrays. */
    public static Metrics compare(int[] reference, int[] actual) {
        return compare(reference, actual, 0);
    }

    /**
     * Compares two equally sized sample arrays after adding {@code actualOffset}
     * to every actual sample. Values are not clamped for this calculation.
     */
    public static Metrics compare(int[] reference, int[] actual, int actualOffset) {
        validatePair(reference, actual);
        if (reference.length == 0) {
            throw new IllegalArgumentException("Cannot compare empty sample arrays");
        }

        double absSum = 0.0;
        double squareSum = 0.0;
        double referenceSum = 0.0;
        double actualSum = 0.0;
        double referenceSquareSum = 0.0;
        double actualSquareSum = 0.0;
        double crossSum = 0.0;

        for (int i = 0; i < reference.length; i++) {
            double ref = reference[i];
            double act = actual[i] + (double) actualOffset;
            double diff = act - ref;
            absSum += Math.abs(diff);
            squareSum += diff * diff;
            referenceSum += ref;
            actualSum += act;
            referenceSquareSum += ref * ref;
            actualSquareSum += act * act;
            crossSum += ref * act;
        }

        double count = reference.length;
        double meanReference = referenceSum / count;
        double meanActual = actualSum / count;
        double covariance = crossSum / count - meanReference * meanActual;
        double referenceVariance = Math.max(0.0,
                referenceSquareSum / count - meanReference * meanReference);
        double actualVariance = Math.max(0.0,
                actualSquareSum / count - meanActual * meanActual);
        double denominator = Math.sqrt(referenceVariance * actualVariance);

        double mae = absSum / count;
        double pearson;
        if (denominator == 0.0) {
            pearson = mae == 0.0 ? 1.0 : 0.0;
        } else {
            pearson = covariance / denominator;
        }
        return new Metrics(mae, Math.sqrt(squareSum / count), pearson);
    }

    /** Arithmetic mean of an array of samples. */
    public static double mean(int[] samples) {
        Objects.requireNonNull(samples, "samples");
        if (samples.length == 0) {
            throw new IllegalArgumentException("Cannot calculate mean of empty sample array");
        }
        double sum = 0.0;
        for (int sample : samples) {
            sum += sample;
        }
        return sum / samples.length;
    }

    /**
     * Returns the constant delta that minimizes L1 error. It is the median of
     * {@code reference[i] - actual[i]}.
     */
    public static int optimalConstantDelta(int[] reference, int[] actual) {
        validatePair(reference, actual);
        if (reference.length == 0) {
            throw new IllegalArgumentException("Cannot calculate delta for empty sample arrays");
        }

        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < reference.length; i++) {
            int difference = reference[i] - actual[i];
            min = Math.min(min, difference);
            max = Math.max(max, difference);
        }

        long range = (long) max - min + 1L;
        if (range > MAX_EXACT_MEDIAN_RANGE) {
            return approximateMedianDifference(reference, actual);
        }

        long[] histogram = new long[(int) range];
        for (int i = 0; i < reference.length; i++) {
            histogram[reference[i] - actual[i] - min]++;
        }
        long target = (reference.length + 1L) / 2L;
        long cumulative = 0L;
        for (int i = 0; i < histogram.length; i++) {
            cumulative += histogram[i];
            if (cumulative >= target) {
                return min + i;
            }
        }
        throw new IllegalStateException("Median histogram did not contain expected sample count");
    }

    /** Adds an offset and clamps every result to the inclusive range. */
    public static int[] addClamped(int[] samples, int offset, int minimum, int maximum) {
        Objects.requireNonNull(samples, "samples");
        if (minimum > maximum) {
            throw new IllegalArgumentException("minimum must not exceed maximum");
        }
        int[] result = new int[samples.length];
        for (int i = 0; i < samples.length; i++) {
            long shifted = (long) samples[i] + offset;
            result[i] = (int) Math.max(minimum, Math.min(maximum, shifted));
        }
        return result;
    }

    private static int approximateMedianDifference(int[] reference, int[] actual) {
        int sampleSize = Math.min(reference.length, MAX_APPROXIMATE_MEDIAN_SAMPLES);
        int[] differences = new int[sampleSize];
        int step = Math.max(1, reference.length / sampleSize);
        int stored = 0;
        for (int i = 0; i < reference.length && stored < sampleSize; i += step) {
            differences[stored++] = reference[i] - actual[i];
        }
        Arrays.sort(differences, 0, stored);
        return differences[stored / 2];
    }

    private static void validatePair(int[] reference, int[] actual) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(actual, "actual");
        if (reference.length != actual.length) {
            throw new IllegalArgumentException("Sample count mismatch: reference="
                    + reference.length + ", actual=" + actual.length);
        }
    }
}
