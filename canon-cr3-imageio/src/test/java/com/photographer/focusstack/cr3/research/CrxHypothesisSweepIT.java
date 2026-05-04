package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.raw.codec.SubbandExtractor;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cleanroom CRX hypothesis sweep against the bundled CR3 samples.
 *
 * <p>For each candidate decoder in {@link CrxCandidateDecoders#standardLibrary},
 * this test:
 * <ol>
 *   <li>Decodes the first subband of plane 0 of sample.cr3 to a synthetic int grid.</li>
 *   <li>Builds a reference 8-bit grid from the embedded JPEG preview's R channel,
 *       downsampled to the same plane geometry, via
 *       {@link PreviewReferenceExtractor}.</li>
 *   <li>Computes the Pearson correlation between the candidate output and the
 *       reference. A candidate emitting noise scores near 0; a candidate
 *       reconstructing real image structure scores well above 0.1.</li>
 * </ol>
 *
 * <p>The test only asserts the sweep <em>runs to completion</em> — its real
 * value is the per-candidate row printed to stdout, which is captured in
 * {@code SubbandFixtures.md} and used by future sessions to decide which
 * hypotheses are dead ends and which are worth pursuing.</p>
 *
 * <p><b>NB:</b> The test does not assert any particular candidate succeeds.
 * The current state of the art on the bundled fixture is that all candidates
 * here score within statistical noise; that finding is itself the result
 * documented in {@code SubbandFixtures.md}.</p>
 */
class CrxHypothesisSweepIT {

    private static final String SAMPLE = "src/test/resources/samples/sample.cr3";
    private static final String EOSR_FIXTURE = "src/test/resources/samples/eosr-iso-low.cr3";

    @Test
    void sweepCandidatesAgainstFirstSubbandOfSampleCr3() throws Exception {
        runSweepForSubband(SAMPLE, 0);
    }

    @Test
    void sweepAllPlanesOfTile0() throws Exception {
        for (int seq = 0; seq < 4; seq++) {
            runSweepForSubband(SAMPLE, seq);
        }
    }

    @Test
    void sweepFirstPlaneOfTile1() throws Exception {
        // Cross-tile sanity: same sign-convention conclusion should hold.
        runSweepForSubband(SAMPLE, 4);
    }

    @Test
    void sweepRealEosrFixturePlane0() throws Exception {
        // Real Canon CR3 fixture (eosr-iso-low.cr3) — bits/pixel ≈ 9.0, fixed-k=7
        // consumes ≈100% of the bytes per CrxBitsPerPixelIT. This sweep is the
        // critical experiment: if the empirical-k candidates score well above
        // the noise floor here, we have the actual k for production.
        runSweepForSubband(EOSR_FIXTURE, 0);
    }

    @Test
    void sweepRealEosrFixtureAllPlanesTile0() throws Exception {
        for (int seq = 0; seq < 4; seq++) {
            runSweepForSubband(EOSR_FIXTURE, seq);
        }
    }

    private void runSweepForSubband(String fixture, int sequence) throws Exception {
        File f = new File(fixture);
        assertThat(f).as(fixture + " fixture").exists();

        List<SubbandExtractor.SubbandRef> subs = SubbandExtractor.list(f);
        assertThat(subs).isNotEmpty();
        SubbandExtractor.SubbandRef ref = subs.get(sequence);

        byte[] payload = SubbandExtractor.read(f, ref);
        int w = ref.planeWidth();
        int h = ref.planeHeight();
        int bitDepth = ref.bitDepth();

        // Reference grid for plane 0 = R channel of the preview, downsampled.
        PreviewReferenceExtractor.Reference reference =
                PreviewReferenceExtractor.extract(f, ref.planeIndex(), w, h);
        assertThat(reference.pixels()).hasSize(w * h);

        Map<String, Function<byte[], int[]>> library =
                CrxCandidateDecoders.standardLibrary(w, h, bitDepth, ref.quantValue());

        StringBuilder report = new StringBuilder();
        report.append(String.format(
                "%n=== CRX Hypothesis Sweep — %s seq=%d tile=%d plane %d (%s), "
                        + "%dx%d, %d-bit, q=%d, payload=%d bytes ===%n",
                f.getName(), sequence, ref.tileSequence(), ref.planeIndex(),
                reference.channel(), w, h, bitDepth, ref.quantValue(), payload.length));
        report.append(String.format("%-36s %-10s %-10s %-12s %-12s%n",
                "candidate", "outLen", "validPx", "pearsonR", "meanAbsErr"));

        for (var entry : library.entrySet()) {
            String name = entry.getKey();
            int[] out;
            String error = null;
            try {
                out = entry.getValue().apply(payload);
            } catch (RuntimeException e) {
                out = new int[0];
                error = e.getClass().getSimpleName();
            }

            ScaledStats stats = scoreAgainstReference(out, reference.pixels());
            report.append(String.format("%-36s %-10d %-10d %-12s %-12s%n",
                    name,
                    out.length,
                    stats.validPx,
                    error != null ? error : String.format("%.4f", stats.pearsonR),
                    error != null ? "n/a" : String.format("%.2f", stats.meanAbsErr)));
        }

        // Print the report to stdout — the captured CI log is the real artifact here.
        System.out.println(report);

        // Update the assertion to match the new library size.
        assertThat(library).hasSizeGreaterThanOrEqualTo(8);
    }

    /** Result of comparing a candidate output (rescaled to 0..255) against a reference grid. */
    private record ScaledStats(int validPx, double pearsonR, double meanAbsErr) {}

    /**
     * Rescales the candidate output to the [0, 255] range using its observed
     * (min, max) and computes Pearson correlation and mean absolute error
     * against the 8-bit reference grid.
     */
    private static ScaledStats scoreAgainstReference(int[] candidate, int[] reference) {
        int n = Math.min(candidate.length, reference.length);
        if (n < 2) {
            return new ScaledStats(n, Double.NaN, Double.NaN);
        }

        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            int v = candidate[i];
            if (v < min) {
                min = v;
            }
            if (v > max) {
                max = v;
            }
        }
        int range = Math.max(1, max - min);

        double sumA = 0;
        double sumB = 0;
        double sumAA = 0;
        double sumBB = 0;
        double sumAB = 0;
        long sumAbs = 0;
        for (int i = 0; i < n; i++) {
            double a = (candidate[i] - min) * 255.0 / range;
            double b = reference[i];
            sumA += a;
            sumB += b;
            sumAA += a * a;
            sumBB += b * b;
            sumAB += a * b;
            sumAbs += Math.abs((int) Math.round(a) - reference[i]);
        }
        double meanA = sumA / n;
        double meanB = sumB / n;
        double cov = sumAB / n - meanA * meanB;
        double varA = sumAA / n - meanA * meanA;
        double varB = sumBB / n - meanB * meanB;
        double denom = Math.sqrt(Math.max(0, varA) * Math.max(0, varB));
        double r = denom < 1e-9 ? 0.0 : cov / denom;
        double meanAbs = (double) sumAbs / n;
        return new ScaledStats(n, r, meanAbs);
    }
}
