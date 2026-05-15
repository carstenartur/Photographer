package com.photographer.focusstack.cr3.research;

import java.util.Arrays;
import java.util.function.Function;

/**
 * Test-side harness for evaluating candidate CRX subband decoders against
 * ground-truth pixel grids without having to run a full pipeline.
 *
 * <p>A "hypothesis" is anything you can express as a {@code Function&lt;byte[], int[]&gt;} —
 * given the raw entropy-coded subband bytes, return the decoded pixel values
 * (in raster order). The harness compares those samples against a reference
 * pixel grid and reports per-fixture accuracy.</p>
 *
 * <p>Reference grids in real CRX investigation come from downsampling the
 * embedded PRVW JPEG of the same {@code .cr3} file to the CRX SD-track plane
 * grid, then deinterleaving the Bayer planes — see
 * {@code SubbandHypothesisHarnessIT} in {@code canon-cr3-imageio} for a real
 * usage. The harness itself has no dependency on either side and is fully
 * exercised by the synthetic tests in this module.</p>
 */
public final class SubbandHypothesisHarness {

    private SubbandHypothesisHarness() {
        // utility class
    }

    /**
     * Result of evaluating one hypothesis against one fixture.
     *
     * @param fixtureName    a human-readable name for the fixture (used in reports)
     * @param expectedLength the number of reference pixels available
     * @param decodedLength  the number of pixels the candidate returned
     * @param exactMatches   number of reference pixels the candidate reproduced exactly
     * @param meanAbsError   mean absolute error over the overlap (or NaN if no overlap)
     * @param maxAbsError    maximum absolute error over the overlap (or 0 if no overlap)
     * @param threwException whether the candidate threw an exception
     * @param exceptionType  exception class name if {@code threwException}, else {@code null}
     */
    public record Result(
            String fixtureName,
            int expectedLength,
            int decodedLength,
            int exactMatches,
            double meanAbsError,
            int maxAbsError,
            boolean threwException,
            String exceptionType) {

        /** Fraction of reference samples reproduced exactly (over the overlap). */
        public double exactMatchFraction() {
            int overlap = Math.min(expectedLength, decodedLength);
            return overlap == 0 ? 0.0 : (double) exactMatches / (double) overlap;
        }
    }

    /**
     * Runs a hypothesis against a single fixture and returns a {@link Result}.
     *
     * <p>Exceptions thrown by the candidate are caught and reported in the result
     * — they are not propagated. This allows research code to feed a candidate
     * against many fixtures without a single bad input aborting the whole sweep.</p>
     *
     * @param fixtureName  human-readable name of the fixture
     * @param payload      the raw entropy-coded bytes to decode
     * @param reference    the expected pixel values
     * @param candidate    the candidate decoder
     * @return the evaluation result
     */
    public static Result evaluate(String fixtureName, byte[] payload, int[] reference,
                                  Function<byte[], int[]> candidate) {
        if (fixtureName == null) {
            throw new IllegalArgumentException("fixtureName must not be null");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        if (reference == null) {
            throw new IllegalArgumentException("reference must not be null");
        }
        if (candidate == null) {
            throw new IllegalArgumentException("candidate must not be null");
        }

        int[] decoded;
        try {
            decoded = candidate.apply(payload);
            if (decoded == null) {
                decoded = new int[0];
            }
        } catch (RuntimeException ex) {
            return new Result(fixtureName, reference.length, 0, 0,
                    Double.NaN, 0, true, ex.getClass().getName());
        }

        int overlap = Math.min(reference.length, decoded.length);
        int exact = 0;
        long sumAbs = 0L;
        int maxAbs = 0;
        for (int i = 0; i < overlap; i++) {
            int diff = decoded[i] - reference[i];
            int abs = diff < 0 ? -diff : diff;
            if (abs == 0) {
                exact++;
            }
            sumAbs += abs;
            if (abs > maxAbs) {
                maxAbs = abs;
            }
        }
        double meanAbs = overlap == 0 ? Double.NaN : (double) sumAbs / (double) overlap;

        return new Result(fixtureName, reference.length, decoded.length,
                exact, meanAbs, maxAbs, false, null);
    }

    /**
     * Renders a tabular report over a list of results, suitable for printing
     * directly to stdout in research tests.
     *
     * @param results results from {@link #evaluate} calls
     * @return a multi-line text table
     */
    public static String report(Iterable<Result> results) {
        StringBuilder sb = new StringBuilder(512);
        sb.append(String.format("%-32s %-10s %-10s %-10s %-10s %-10s %-12s%n",
                "fixture", "expected", "decoded", "exact", "exact%", "meanAbs", "maxAbs/exc"));
        for (Result r : results) {
            String last = r.threwException()
                    ? r.exceptionType()
                    : Integer.toString(r.maxAbsError());
            String mean = Double.isNaN(r.meanAbsError())
                    ? "n/a"
                    : String.format("%.2f", r.meanAbsError());
            sb.append(String.format("%-32s %-10d %-10d %-10d %-10s %-10s %-12s%n",
                    truncate(r.fixtureName(), 32),
                    r.expectedLength(), r.decodedLength(), r.exactMatches(),
                    String.format("%.2f", r.exactMatchFraction() * 100.0),
                    mean, last));
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /**
     * An identity decoder useful for self-testing the harness: it interprets each
     * input byte as an unsigned int (0..255). On a fixture whose reference values
     * equal the input bytes the harness must report 100% exact matches.
     *
     * @return an identity {@code Function<byte[], int[]>}
     */
    public static Function<byte[], int[]> identityByteDecoder() {
        return bytes -> {
            int[] out = new int[bytes.length];
            for (int i = 0; i < bytes.length; i++) {
                out[i] = bytes[i] & 0xFF;
            }
            return out;
        };
    }

    /**
     * Helper that returns a copy of an int array, occasionally useful when a
     * candidate decoder wants to express a "decoded nothing" result without
     * relying on null handling.
     *
     * @param a input array
     * @return a defensive copy
     */
    public static int[] copy(int[] a) {
        return Arrays.copyOf(a, a.length);
    }
}
