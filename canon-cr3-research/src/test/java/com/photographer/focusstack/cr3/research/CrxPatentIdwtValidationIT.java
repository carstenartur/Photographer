package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.raw.codec.BitReader;
import com.photographer.focusstack.cr3.raw.codec.InverseWaveletTransform;
import com.photographer.focusstack.cr3.raw.codec.InverseWaveletTransform.FilterType;
import com.photographer.focusstack.cr3.raw.codec.SubbandExtractor;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 3 of the patent-cleanroom roadmap (see
 * {@code docs/patent-us20160323602a1-analysis.md} §4): validate the
 * <em>plane-vs-subband</em> interpretation by feeding the four "planes" of one
 * tile (decoded by the patent candidates) into
 * {@link InverseWaveletTransform#inverseSynthesis2D} and comparing the
 * reconstructed Bayer channel against the PRVW-derived reference channel.
 *
 * <p>The previous milestone (Step 2 — see {@code SubbandFixtures.md} "Update
 * 2026-04-30 (c)") established that the patent K-update lets the four C_patent_*
 * candidates exactly consume the bit budget of every plane of sample.cr3. The
 * Pearson r per single subband stayed modest because each subband only carries
 * a 1/4-band fraction of the image — the hypothesis is that the four planes
 * are LL/HL/LH/HH of one wavelet level of one Bayer channel.</p>
 *
 * <p>This test reconstructs all four planes with the role-appropriate patent
 * decoder, runs them through the existing Le Gall 5/3 IDWT, and scores the
 * full reconstruction against the same PRVW-derived reference grid that the
 * sweep harness uses (downsampled to the output size). The test always
 * succeeds — its real artefact is the per-permutation table printed to stdout,
 * which is captured in {@code SubbandFixtures.md} so future sessions can
 * decide whether the four-subband hypothesis is correct, which permutation
 * matches Canon's FIG. 5 ordering, and whether a different filter (CDF 9/7)
 * would be a better fit.</p>
 *
 * <p>The acceptance criterion documented in the analysis doc is Pearson r
 * &gt; 0.7 after IDWT against the preview reference. This IT does <em>not</em>
 * assert it (because the hypothesis is being tested, not enforced); it merely
 * records whether the threshold was reached, so a future commit can promote
 * the winning permutation behind a {@code Cr3DecodeOptions} flag (Step 5).</p>
 */
class CrxPatentIdwtValidationIT {

    private static final String SAMPLE = "src/test/resources/samples/sample.cr3";

    /**
     * The four roles a CR3 "plane" might fulfil under the wavelet-subband
     * hypothesis from the patent FIG. 5.
     */
    private enum Role { LL, HL, LH, HH }

    @Test
    void validateIdwtAgainstPreviewForTile0() throws Exception {
        File f = new File(SAMPLE);
        assertThat(f).as(SAMPLE + " fixture").exists();

        List<SubbandExtractor.SubbandRef> subs = SubbandExtractor.list(f);
        assertThat(subs).hasSizeGreaterThanOrEqualTo(4);

        // Tile 0 = the first four subbands (one per plane, one subband per plane).
        SubbandExtractor.SubbandRef p0 = subs.get(0);
        SubbandExtractor.SubbandRef p1 = subs.get(1);
        SubbandExtractor.SubbandRef p2 = subs.get(2);
        SubbandExtractor.SubbandRef p3 = subs.get(3);

        int w = p0.planeWidth();
        int h = p0.planeHeight();
        int bitDepth = p0.bitDepth();
        int q = p0.quantValue();
        // Sanity: all four planes must share dimensions for the LL/HL/LH/HH
        // hypothesis to be even geometrically possible.
        assertThat(p1.planeWidth()).isEqualTo(w);
        assertThat(p2.planeWidth()).isEqualTo(w);
        assertThat(p3.planeWidth()).isEqualTo(w);
        assertThat(p1.planeHeight()).isEqualTo(h);
        assertThat(p2.planeHeight()).isEqualTo(h);
        assertThat(p3.planeHeight()).isEqualTo(h);

        byte[] b0 = SubbandExtractor.read(f, p0);
        byte[] b1 = SubbandExtractor.read(f, p1);
        byte[] b2 = SubbandExtractor.read(f, p2);
        byte[] b3 = SubbandExtractor.read(f, p3);
        byte[][] bytes = {b0, b1, b2, b3};

        // Pre-decode each plane under each of the 4 patent roles. 16 total.
        // (Cheap: 4 bytes × 4 roles = 16 decodings, ~2.6 MP each.)
        int[][][] perPlanePerRole = new int[4][Role.values().length][];
        for (int p = 0; p < 4; p++) {
            for (Role role : Role.values()) {
                perPlanePerRole[p][role.ordinal()] = decodePatent(bytes[p], w, h, bitDepth, q, role);
            }
        }

        // The reconstructed Bayer channel will be 2·w × 2·h. Use red as the
        // preview channel: tile 0 of sample.cr3 is the upper-left quadrant of
        // the RAW frame, where plane 0 (R) lives — and each plane's IDWT
        // recovers that plane's full-resolution data (per the hypothesis).
        int outW = 2 * w;
        int outH = 2 * h;
        PreviewReferenceExtractor.Reference ref =
                PreviewReferenceExtractor.extract(f, /*planeIdx=R*/0, outW, outH);

        StringBuilder report = new StringBuilder();
        report.append(String.format(
                "%n=== CRX Patent IDWT Validation — %s tile=0, planes=4×(%dx%d, %d-bit, q=%d) ===%n",
                f.getName(), w, h, bitDepth, q));
        report.append(String.format("%-28s %-12s %-12s %-10s%n",
                "permutation (LL,HL,LH,HH)", "pearsonR", "meanAbsErr", "outLen"));

        // Six promising permutations to test:
        //   - "natural"  (0,1,2,3) — Bayer plane index = subband role index
        //   - "fig5"     (0,2,1,3) — FIG. 5 swaps HL/LH (LL, LH first then HL, HH)
        //   - "rggb-as-lh" (0,1,3,2) — green-low / blue-mid swap
        //   - and three reverses to surface any inverted convention
        int[][] permutations = new int[][]{
                {0, 1, 2, 3},
                {0, 2, 1, 3},
                {0, 1, 3, 2},
                {3, 2, 1, 0},
                {0, 3, 2, 1},
                {3, 0, 1, 2},
        };

        for (int[] perm : permutations) {
            int[] ll = perPlanePerRole[perm[0]][Role.LL.ordinal()];
            int[] hl = perPlanePerRole[perm[1]][Role.HL.ordinal()];
            int[] lh = perPlanePerRole[perm[2]][Role.LH.ordinal()];
            int[] hh = perPlanePerRole[perm[3]][Role.HH.ordinal()];
            // Skip permutations whose role-decoded plane lengths fall short
            // (defensive — patent decoders always fill the full grid for
            // sample.cr3 per Update (c), but cheap to guard against future
            // changes).
            if (ll.length < w * h || hl.length < w * h || lh.length < w * h
                    || hh.length < w * h) {
                report.append(String.format("%-28s %-12s %-12s %-10s%n",
                        permString(perm), "short", "n/a", "skipped"));
                continue;
            }

            int[] reconstructed;
            try {
                reconstructed = InverseWaveletTransform.inverseSynthesis2D(
                        ll, lh, hl, hh, w, h, outW, outH, FilterType.LEGALL_5_3);
            } catch (RuntimeException e) {
                report.append(String.format("%-28s %-12s %-12s %-10s%n",
                        permString(perm), e.getClass().getSimpleName(), "n/a", "0"));
                continue;
            }

            ScoredStats stats = scoreAgainstReference(reconstructed, ref.pixels(), bitDepth);
            report.append(String.format("%-28s %-12s %-12s %-10d%n",
                    permString(perm),
                    String.format("%.4f", stats.pearsonR),
                    String.format("%.2f", stats.meanAbsErr),
                    reconstructed.length));
        }

        // Always print so the CI log captures the result table.
        System.out.println(report);

        // The assertion is intentionally weak: this IT exists to record results,
        // not to enforce a threshold. The threshold (r > 0.7) is the goal of a
        // future production-promotion commit.
        assertThat(permutations.length).isEqualTo(6);
    }

    private static String permString(int[] perm) {
        return "(" + perm[0] + "," + perm[1] + "," + perm[2] + "," + perm[3] + ")";
    }

    /**
     * Patent candidate decoder, mirroring the C_patent_* family in
     * {@link CrxCandidateDecoders} but parameterised by subband {@link Role}.
     * Implements the per-subband predictor + lookahead-bracket K-update from
     * Patent US 2016/0323602 A1 §1.5–§1.8, with K0 = 0.
     */
    private static int[] decodePatent(byte[] payload, int w, int h, int bitDepth,
                                      int quantValue, Role role) {
        try {
            return decodePatentImpl(payload, w, h, bitDepth, quantValue, role);
        } catch (RuntimeException | IOException e) {
            return new int[0];
        }
    }

    private static int[] decodePatentImpl(byte[] payload, int w, int h, int bitDepth,
                                          int quantValue, Role role) throws IOException {
        int[] result = new int[w * h];
        int maxVal = (1 << bitDepth) - 1;
        BitReader reader = new BitReader(payload, 0, payload.length);
        // K0 = 0 (the more conservative seed; converges fast under the bracket
        // rule). The C_patent_*_kQ_bracket variant in the sweep showed near-
        // identical bit-budget consumption to k0; choose k0 here for clarity.
        int k = 0;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!reader.hasMore()) {
                    return java.util.Arrays.copyOf(result, y * w + x);
                }
                int residual = decodeRice(reader, k);
                // Patent zig-zag mapping: even → +half, odd → −half−1.
                int signed = ((residual & 1) == 0) ? (residual >> 1) : (-(residual >> 1) - 1);
                int predicted = predictForRole(result, w, x, y, bitDepth, role);
                int pixel = predicted + signed;
                if (pixel < 0) pixel = 0;
                else if (pixel > maxVal) pixel = maxVal;
                result[y * w + x] = pixel;

                // Patent §1.8 lookahead-bracket K-update (Sn = Sx for non-LL,
                // collapses to Sx for LL when no deeper level — see spec §4).
                int sn = residual;
                int kStar = (sn <= 0) ? 0 : 31 - Integer.numberOfLeadingZeros(sn + 1);
                int kMin = Math.max(0, kStar - 1);
                int kMax = Math.min(bitDepth, kStar + 1);
                if (k < kMin) k++;
                else if (k > kMax) k--;
            }
        }
        return result;
    }

    /** Per-subband predictor: LL=MED, HL=above (b), LH=left (a), HH=zero. */
    private static int predictForRole(int[] result, int w, int x, int y, int bitDepth, Role r) {
        if (r == Role.HH) {
            return 0;
        }
        if (x == 0 && y == 0) {
            return 1 << (bitDepth - 1);
        }
        if (y == 0) {
            return result[x - 1];
        }
        if (x == 0) {
            return result[(y - 1) * w];
        }
        int a = result[y * w + x - 1];
        int b = result[(y - 1) * w + x];
        int c = result[(y - 1) * w + x - 1];
        return switch (r) {
            case LL -> {
                if (c >= Math.max(a, b)) yield Math.min(a, b);
                else if (c <= Math.min(a, b)) yield Math.max(a, b);
                else yield a + b - c;
            }
            case HL -> b;       // p = b (above)
            case LH -> a;       // p = a (left)
            case HH -> 0;       // already handled above
        };
    }

    private static int decodeRice(BitReader reader, int k) throws IOException {
        int q = 0;
        while (reader.hasMore()) {
            if (reader.readBit() != 0) {
                break;
            }
            q++;
            if (q > 64) break;  // defensive cap, see CrxCandidateDecoders
        }
        int r = 0;
        for (int i = 0; i < k && reader.hasMore(); i++) {
            r = (r << 1) | reader.readBit();
        }
        return (q << k) | r;
    }

    private record ScoredStats(double pearsonR, double meanAbsErr) {}

    /**
     * Rescales the reconstructed signal into [0, 255] (using observed
     * min/max), then computes Pearson r and mean absolute error against the
     * 8-bit preview reference. Same shape as
     * {@code CrxHypothesisSweepIT#scoreAgainstReference}.
     */
    private static ScoredStats scoreAgainstReference(int[] candidate, int[] reference,
                                                     int bitDepth) {
        int n = Math.min(candidate.length, reference.length);
        if (n < 2) return new ScoredStats(Double.NaN, Double.NaN);

        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            int v = candidate[i];
            if (v < min) min = v;
            if (v > max) max = v;
        }
        int range = Math.max(1, max - min);

        double sumA = 0, sumB = 0, sumAA = 0, sumBB = 0, sumAB = 0;
        long sumAbs = 0;
        for (int i = 0; i < n; i++) {
            double a = (candidate[i] - min) * 255.0 / range;
            double b = reference[i];
            sumA += a; sumB += b;
            sumAA += a * a; sumBB += b * b;
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
        return new ScoredStats(r, (double) sumAbs / n);
    }
}
