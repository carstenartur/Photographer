package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.raw.codec.BitReader;
import com.photographer.focusstack.cr3.raw.codec.SubbandExtractor;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-7 follow-up to {@link CrxPlaneFollowupRound6IT}, per the new
 * requirement (2026-05-01, after Update (j)):
 *
 * <ul>
 *   <li>Sign-bit flip variants are refuted as the main lever.</li>
 *   <li>Plane→Bayer permutation {@code [1023]} is only a small Δ Σ|r|.</li>
 *   <li>Per-tile K-init is the strongest new lever, and tile0 vs tile1
 *       prefer different K-init bands.</li>
 *   <li>{@code LEAD_ZERO_TILE_FIRST} stays part of the best known path.</li>
 * </ul>
 *
 * <p><b>Strict test scope.</b> No production code is modified, no flag is
 * added, no hypothesis is promoted. Output dir:
 * {@code canon-cr3-imageio/target/crx-followup-round7/}.</p>
 *
 * <h3>Section M7 — Per-plane K-init combinations (greedy)</h3>
 * Holding predictor = {@code P_LEFT_BASE}, lead-zero =
 * {@code LZ_TILE_FIRST}, sign = {@code NONE}, vary K_INIT independently
 * per plane within a tile. The full Cartesian product over K∈{0..4} is
 * 5^4 = 625 configurations per tile, which is cheap on the existing
 * harness, so a coordinate-greedy pass is followed by a small exhaustive
 * search to confirm convergence. Reported separately as:
 * <ul>
 *   <li><b>best_for_tile0</b> — the K-init quadruple that maximises
 *       Σ|r| over the four planes of tile0.</li>
 *   <li><b>best_for_tile1</b> — same for tile1.</li>
 *   <li><b>best_combined</b> — the K-init quadruple that maximises
 *       Σ|r| over <em>both</em> tiles simultaneously (i.e. a single
 *       per-plane K-init applied to both tiles).</li>
 * </ul>
 *
 * <h3>Section N7 — K-init derived from {@code CMP1.quantValue}</h3>
 * On {@code sample.cr3} every subband reports {@code quantValue=4}
 * (lossless). Test simple metadata-near mappings as a per-subband seed:
 * <ul>
 *   <li>{@code Q_DIRECT} : K_INIT = q</li>
 *   <li>{@code Q_MINUS_1} : K_INIT = max(0, q − 1)</li>
 *   <li>{@code Q_PLUS_1} : K_INIT = min(7, q + 1)</li>
 *   <li>{@code Q_LOG2} : K_INIT = floor(log2(q))</li>
 *   <li>{@code Q_LOG2_PLUS_1} : K_INIT = floor(log2(q)) + 1</li>
 *   <li>{@code Q_CLAMP_0_4} : K_INIT = clamp(q, 0, 4)</li>
 *   <li>{@code Q_PER_PLANE} : K_INIT = q for green planes (Gr/Gb),
 *       max(0, q − 2) for non-green (R/B)</li>
 * </ul>
 *
 * <h3>Section O7 — Combined refinement</h3>
 * Combine the three Round-6/7 wins:
 * <ul>
 *   <li>{@code LEAD_ZERO_TILE_FIRST}</li>
 *   <li>Per-plane K-init = {@code best_combined} from M7</li>
 *   <li>Plane→Bayer permutation = {@code [1023]} (Round-6 K6 winner)</li>
 * </ul>
 * Compare against the Round-6 baseline (LZ + sign=NONE + K_INIT_0 +
 * permutation {@code [1032]}) and report whether the per-channel maxima
 * cross the |r| > 0.7 promotion threshold.
 *
 * <p>Like the previous Round ITs, this test always succeeds — its
 * purpose is artefact production for {@code SubbandFixtures.md}
 * Update (k) and analysis-doc §5.12.</p>
 */
class CrxPlaneFollowupRound7IT {

    private static final String SAMPLE = "src/test/resources/samples/sample.cr3";
    private static final Path OUT_DIR = Path.of("target", "crx-followup-round7");

    /** Coordinate-greedy + small exhaustive search range, per plane. */
    private static final int K_LO = 0;
    private static final int K_HI = 4;

    /** Decoder configuration (reuses the Round-6 P_LEFT_BASE/LZ baseline). */
    private record Config(int kInit) { }

    @Test
    void runRound7Sweep() throws Exception {
        File f = new File(SAMPLE);
        assertThat(f).exists();
        Files.createDirectories(OUT_DIR);

        Cmp1Box cmp1 = readCmp1(f);
        assertThat(cmp1).as("CMP1 box").isNotNull();
        int[] cfaToPreview = cfaLayoutMap(cmp1.cfaLayout());
        String[] chanName = {"R", "Gr", "Gb", "B"};

        List<SubbandExtractor.SubbandRef> subs = SubbandExtractor.list(f);
        assertThat(subs).as("subbands").hasSizeGreaterThanOrEqualTo(4);

        SubbandExtractor.SubbandRef[] tile0 = {subs.get(0), subs.get(1), subs.get(2), subs.get(3)};
        SubbandExtractor.SubbandRef[] tile1 = subs.size() >= 8
                ? new SubbandExtractor.SubbandRef[]{subs.get(4), subs.get(5), subs.get(6), subs.get(7)}
                : null;

        // Reference channels (R, Gr, Gb, B), one per preview Bayer position.
        int w = tile0[0].planeWidth();
        int h = tile0[0].planeHeight();
        PreviewReferenceExtractor.Reference[] refs = new PreviewReferenceExtractor.Reference[4];
        for (int c = 0; c < 4; c++) {
            refs[c] = PreviewReferenceExtractor.extract(f, c, w, h);
        }

        try (PrintWriter tsv = new PrintWriter(OUT_DIR.resolve("metrics.tsv").toFile())) {
            tsv.println("section\ttile\tplane\tvariant\tmetric\tvalue");

            // ---------- Section M7 — per-plane K-init combinations ----------
            System.out.printf("%n=== Section M7 — per-plane K-init combinations under LEAD_ZERO ===%n");
            int[] bestT0 = perPlaneKInitSearch(f, tile0, "tile0", cfaToPreview, refs, chanName, tsv);
            int[] bestT1 = (tile1 != null)
                    ? perPlaneKInitSearch(f, tile1, "tile1", cfaToPreview, refs, chanName, tsv)
                    : new int[]{0, 0, 0, 0};
            int[] bestCombined = (tile1 != null)
                    ? perPlaneKInitCombinedSearch(f, tile0, tile1, cfaToPreview, refs, chanName, tsv)
                    : bestT0.clone();
            System.out.printf("M7: best_for_tile0   = %s%n", Arrays.toString(bestT0));
            System.out.printf("M7: best_for_tile1   = %s%n", Arrays.toString(bestT1));
            System.out.printf("M7: best_combined    = %s%n", Arrays.toString(bestCombined));

            // ---------- Section N7 — K-init derived from CMP1.quantValue ----------
            System.out.printf("%n=== Section N7 — K-init from CMP1.quantValue ===%n");
            for (QuantMapping qm : QuantMapping.values()) {
                runQuantMapping(f, tile0, "tile0", false, cfaToPreview, refs, chanName, tsv, qm);
                if (tile1 != null) {
                    runQuantMapping(f, tile1, "tile1", true, cfaToPreview, refs, chanName, tsv, qm);
                }
            }

            // ---------- Section O7 — Combined refinement ----------
            System.out.printf("%n=== Section O7 — combined LEAD_ZERO + per-plane K-init + perm[1023] ===%n");
            // Decode tile0/tile1 once with bestCombined K-init.
            int[][] dec0 = decodeAllPlanes(f, tile0, bestCombined);
            int[][] dec1 = (tile1 != null) ? decodeAllPlanes(f, tile1, bestCombined) : null;

            int[] permBaseline = cfaToPreview;        // existing GRBG mapping (= [1032])
            int[] permRound6   = {1, 0, 2, 3};        // Round-6 K6 winner [1023]

            evaluateCombined(dec0, "tile0", permBaseline, "perm=[1032]_baseline", refs, chanName, tsv,
                    bestCombined);
            evaluateCombined(dec0, "tile0", permRound6, "perm=[1023]_round6winner", refs, chanName, tsv,
                    bestCombined);
            if (dec1 != null) {
                evaluateCombined(dec1, "tile1", permBaseline, "perm=[1032]_baseline", refs, chanName, tsv,
                        bestCombined);
                evaluateCombined(dec1, "tile1", permRound6, "perm=[1023]_round6winner", refs, chanName, tsv,
                        bestCombined);
            }
            // Control: same composition but with K_INIT_0 (Round-6 baseline) so we can isolate
            // the K-init contribution from the permutation contribution.
            int[][] dec0Base = decodeAllPlanes(f, tile0, new int[]{0, 0, 0, 0});
            int[][] dec1Base = (tile1 != null) ? decodeAllPlanes(f, tile1, new int[]{0, 0, 0, 0}) : null;
            evaluateCombined(dec0Base, "tile0", permRound6, "perm=[1023]_kInit0_control", refs, chanName, tsv,
                    new int[]{0, 0, 0, 0});
            if (dec1Base != null) {
                evaluateCombined(dec1Base, "tile1", permRound6, "perm=[1023]_kInit0_control", refs, chanName, tsv,
                        new int[]{0, 0, 0, 0});
            }
        }

        summariseTilewiseWinners();

        assertThat(OUT_DIR.resolve("metrics.tsv")).exists();
        assertThat(OUT_DIR.resolve("winners.txt")).exists();
    }

    // ============================================================ Section M7

    /**
     * Coordinate-greedy per-plane K-init search:
     * start at K=[0,0,0,0], for each plane sweep K∈[K_LO..K_HI] holding
     * the others fixed, accept the K that maximises Σ|r| over the four
     * planes, repeat until no change. Then a small exhaustive sweep over
     * the immediate neighbourhood (±1 per plane = 3^4 = 81 evaluations)
     * confirms convergence.
     */
    private static int[] perPlaneKInitSearch(File f, SubbandExtractor.SubbandRef[] planes,
                                             String tileTag, int[] cfaToPreview,
                                             PreviewReferenceExtractor.Reference[] refs,
                                             String[] chanName, PrintWriter tsv) throws IOException {
        int[] cur = new int[]{0, 0, 0, 0};
        double curScore = scoreConfig(f, planes, cur, cfaToPreview, refs);
        boolean changed = true;
        int iter = 0;
        while (changed && iter < 8) {
            changed = false;
            iter++;
            for (int pi = 0; pi < 4; pi++) {
                int bestK = cur[pi];
                double bestS = curScore;
                for (int k = K_LO; k <= K_HI; k++) {
                    if (k == cur[pi]) continue;
                    int[] trial = cur.clone();
                    trial[pi] = k;
                    double s = scoreConfig(f, planes, trial, cfaToPreview, refs);
                    if (s > bestS + 1e-6) { bestS = s; bestK = k; }
                }
                if (bestK != cur[pi]) {
                    cur[pi] = bestK;
                    curScore = bestS;
                    changed = true;
                }
            }
        }
        // Local exhaustive refinement around the greedy optimum (±1 per plane, clipped to [K_LO..K_HI]).
        int[] best = cur.clone();
        double bestScore = curScore;
        for (int a = Math.max(K_LO, cur[0] - 1); a <= Math.min(K_HI, cur[0] + 1); a++)
        for (int b = Math.max(K_LO, cur[1] - 1); b <= Math.min(K_HI, cur[1] + 1); b++)
        for (int c = Math.max(K_LO, cur[2] - 1); c <= Math.min(K_HI, cur[2] + 1); c++)
        for (int d = Math.max(K_LO, cur[3] - 1); d <= Math.min(K_HI, cur[3] + 1); d++) {
            int[] trial = {a, b, c, d};
            double s = scoreConfig(f, planes, trial, cfaToPreview, refs);
            if (s > bestScore + 1e-6) { bestScore = s; best = trial.clone(); }
        }

        // Emit per-plane |r| for the winning quadruple.
        double[] perPlaneR = perPlaneR(f, planes, best, cfaToPreview, refs);
        String variant = "M7/perPlaneKInit=" + kVec(best);
        for (int pi = 0; pi < 4; pi++) {
            int previewChan = cfaToPreview[pi];
            tsv.printf("%s\t%s\tp%d\t%s\tbayer_%s_R\t%.6f%n",
                    "M7", tileTag, pi, variant, chanName[previewChan], perPlaneR[pi]);
        }
        tsv.printf("%s\t%s\t-\t%s\tsum_abs_R\t%.6f%n", "M7", tileTag, variant, bestScore);
        System.out.printf("M7 %s greedy: K=%s sum|r|=%.4f  perPlane=[%+.4f %+.4f %+.4f %+.4f]%n",
                tileTag, kVec(best), bestScore,
                perPlaneR[0], perPlaneR[1], perPlaneR[2], perPlaneR[3]);
        return best;
    }

    /**
     * Search a single per-plane K-init quadruple that maximises the sum of
     * Σ|r| across <em>both</em> tiles. Uses the same coordinate-greedy
     * scheme as {@link #perPlaneKInitSearch} but joins the two tile
     * scores.
     */
    private static int[] perPlaneKInitCombinedSearch(File f,
                                                     SubbandExtractor.SubbandRef[] planes0,
                                                     SubbandExtractor.SubbandRef[] planes1,
                                                     int[] cfaToPreview,
                                                     PreviewReferenceExtractor.Reference[] refs,
                                                     String[] chanName,
                                                     PrintWriter tsv) throws IOException {
        int[] cur = new int[]{0, 0, 0, 0};
        double curScore = scoreConfig(f, planes0, cur, cfaToPreview, refs)
                        + scoreConfig(f, planes1, cur, cfaToPreview, refs);
        boolean changed = true;
        int iter = 0;
        while (changed && iter < 8) {
            changed = false;
            iter++;
            for (int pi = 0; pi < 4; pi++) {
                int bestK = cur[pi];
                double bestS = curScore;
                for (int k = K_LO; k <= K_HI; k++) {
                    if (k == cur[pi]) continue;
                    int[] trial = cur.clone();
                    trial[pi] = k;
                    double s = scoreConfig(f, planes0, trial, cfaToPreview, refs)
                            + scoreConfig(f, planes1, trial, cfaToPreview, refs);
                    if (s > bestS + 1e-6) { bestS = s; bestK = k; }
                }
                if (bestK != cur[pi]) {
                    cur[pi] = bestK;
                    curScore = bestS;
                    changed = true;
                }
            }
        }
        int[] best = cur.clone();
        double bestScore = curScore;
        for (int a = Math.max(K_LO, cur[0] - 1); a <= Math.min(K_HI, cur[0] + 1); a++)
        for (int b = Math.max(K_LO, cur[1] - 1); b <= Math.min(K_HI, cur[1] + 1); b++)
        for (int c = Math.max(K_LO, cur[2] - 1); c <= Math.min(K_HI, cur[2] + 1); c++)
        for (int d = Math.max(K_LO, cur[3] - 1); d <= Math.min(K_HI, cur[3] + 1); d++) {
            int[] trial = {a, b, c, d};
            double s = scoreConfig(f, planes0, trial, cfaToPreview, refs)
                    + scoreConfig(f, planes1, trial, cfaToPreview, refs);
            if (s > bestScore + 1e-6) { bestScore = s; best = trial.clone(); }
        }
        String variant = "M7/perPlaneKInit_combined=" + kVec(best);
        double s0 = scoreConfig(f, planes0, best, cfaToPreview, refs);
        double s1 = scoreConfig(f, planes1, best, cfaToPreview, refs);
        tsv.printf("%s\t%s\t-\t%s\tsum_abs_R\t%.6f%n", "M7", "tile0", variant, s0);
        tsv.printf("%s\t%s\t-\t%s\tsum_abs_R\t%.6f%n", "M7", "tile1", variant, s1);
        System.out.printf("M7 combined: K=%s tile0Σ|r|=%.4f tile1Σ|r|=%.4f total=%.4f%n",
                kVec(best), s0, s1, bestScore);
        return best;
    }

    private static double scoreConfig(File f, SubbandExtractor.SubbandRef[] planes,
                                      int[] kInit, int[] cfaToPreview,
                                      PreviewReferenceExtractor.Reference[] refs) throws IOException {
        double[] r = perPlaneR(f, planes, kInit, cfaToPreview, refs);
        double s = 0;
        for (double v : r) s += Math.abs(v);
        return s;
    }

    private static double[] perPlaneR(File f, SubbandExtractor.SubbandRef[] planes,
                                      int[] kInit, int[] cfaToPreview,
                                      PreviewReferenceExtractor.Reference[] refs) throws IOException {
        int w = planes[0].planeWidth();
        int h = planes[0].planeHeight();
        int bd = planes[0].bitDepth();
        int midgrey = 1 << (bd - 1);
        double[] r = new double[4];
        for (int pi = 0; pi < 4; pi++) {
            byte[] payload = SubbandExtractor.read(f, planes[pi]);
            int[] dec = decode(payload, w, h, bd, midgrey, new Config(kInit[pi]));
            if (dec.length < w * h) { r[pi] = 0; continue; }
            r[pi] = pearson(dec, refs[cfaToPreview[pi]].pixels());
        }
        return r;
    }

    private static String kVec(int[] k) {
        return "[" + k[0] + "," + k[1] + "," + k[2] + "," + k[3] + "]";
    }

    // ============================================================ Section N7

    /** Mappings from {@code CMP1.quantValue} (= 4 on sample.cr3) to a per-plane K-init seed. */
    private enum QuantMapping {
        Q_DIRECT,
        Q_MINUS_1,
        Q_PLUS_1,
        Q_LOG2,
        Q_LOG2_PLUS_1,
        Q_CLAMP_0_4,
        Q_PER_PLANE
    }

    private static int kFromQ(QuantMapping qm, int q, boolean planeIsGreen) {
        return switch (qm) {
            case Q_DIRECT       -> clamp(q, 0, 7);
            case Q_MINUS_1      -> clamp(q - 1, 0, 7);
            case Q_PLUS_1       -> clamp(q + 1, 0, 7);
            case Q_LOG2         -> (q <= 0) ? 0 : 31 - Integer.numberOfLeadingZeros(q);
            case Q_LOG2_PLUS_1  -> ((q <= 0) ? 0 : 31 - Integer.numberOfLeadingZeros(q)) + 1;
            case Q_CLAMP_0_4    -> clamp(q, 0, 4);
            case Q_PER_PLANE    -> planeIsGreen ? clamp(q, 0, 7) : clamp(q - 2, 0, 7);
        };
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static void runQuantMapping(File f, SubbandExtractor.SubbandRef[] planes,
                                        String tileTag, boolean isTile1,
                                        int[] cfaToPreview,
                                        PreviewReferenceExtractor.Reference[] refs,
                                        String[] chanName, PrintWriter tsv,
                                        QuantMapping qm) throws IOException {
        int w = planes[0].planeWidth();
        int h = planes[0].planeHeight();
        int bd = planes[0].bitDepth();
        int midgrey = 1 << (bd - 1);
        double sumAbs = 0;
        StringBuilder line = new StringBuilder();
        line.append(String.format("%-6s %-18s ", tileTag, qm.name()));
        for (int pi = 0; pi < 4; pi++) {
            int previewChan = cfaToPreview[pi];
            boolean planeIsGreen = (previewChan == 1 || previewChan == 2);
            int q = planes[pi].quantValue();
            int kInit = kFromQ(qm, q, planeIsGreen);
            byte[] payload = SubbandExtractor.read(f, planes[pi]);
            int[] dec = decode(payload, w, h, bd, midgrey, new Config(kInit));
            double r = (dec.length < w * h) ? 0 : pearson(dec, refs[previewChan].pixels());
            sumAbs += Math.abs(r);
            String variant = "N7/" + qm.name() + "(q=" + q + ",k=" + kInit + ")";
            tsv.printf("%s\t%s\tp%d\t%s\tbayer_%s_R\t%.6f%n",
                    "N7", tileTag, pi, variant, chanName[previewChan], r);
            line.append(String.format("p%d(k=%d)=%+.4f ", pi, kInit, r));
        }
        tsv.printf("%s\t%s\t-\tN7/%s\tsum_abs_R\t%.6f%n", "N7", tileTag, qm.name(), sumAbs);
        System.out.println(line + "Σ|r|=" + String.format("%.4f", sumAbs));
    }

    // ============================================================ Section O7

    /** Decode all four planes under per-plane K-init (sign=NONE, LZ_TILE_FIRST). */
    private static int[][] decodeAllPlanes(File f, SubbandExtractor.SubbandRef[] planes,
                                           int[] kInit) throws IOException {
        int w = planes[0].planeWidth();
        int h = planes[0].planeHeight();
        int bd = planes[0].bitDepth();
        int midgrey = 1 << (bd - 1);
        int[][] out = new int[4][];
        for (int pi = 0; pi < 4; pi++) {
            byte[] payload = SubbandExtractor.read(f, planes[pi]);
            int[] dec = decode(payload, w, h, bd, midgrey, new Config(kInit[pi]));
            out[pi] = (dec.length >= w * h) ? dec : new int[w * h];
        }
        return out;
    }

    private static void evaluateCombined(int[][] dec, String tileTag, int[] perm,
                                         String permTag,
                                         PreviewReferenceExtractor.Reference[] refs,
                                         String[] chanName, PrintWriter tsv,
                                         int[] kInit) {
        double sumAbs = 0;
        double maxAbs = 0;
        StringBuilder line = new StringBuilder();
        line.append(String.format("%-6s %-32s K=%s ", tileTag, permTag, kVec(kInit)));
        for (int pi = 0; pi < 4; pi++) {
            int previewChan = perm[pi];
            double r = pearson(dec[pi], refs[previewChan].pixels());
            sumAbs += Math.abs(r);
            if (Math.abs(r) > maxAbs) maxAbs = Math.abs(r);
            String variant = "O7/LZ+kInit=" + kVec(kInit) + "+" + permTag;
            tsv.printf("%s\t%s\tp%d\t%s\tbayer_%s_R\t%.6f%n",
                    "O7", tileTag, pi, variant, chanName[previewChan], r);
            line.append(String.format("p%d=%+.4f ", pi, r));
        }
        tsv.printf("%s\t%s\t-\tO7/LZ+kInit=%s+%s\tsum_abs_R\t%.6f%n",
                "O7", tileTag, kVec(kInit), permTag, sumAbs);
        tsv.printf("%s\t%s\t-\tO7/LZ+kInit=%s+%s\tmax_abs_R\t%.6f%n",
                "O7", tileTag, kVec(kInit), permTag, maxAbs);
        System.out.println(line + String.format("Σ|r|=%.4f max|r|=%.4f", sumAbs, maxAbs));
    }

    // ============================================================ winners summary

    private static void summariseTilewiseWinners() throws IOException {
        Path tsvPath = OUT_DIR.resolve("metrics.tsv");
        if (!Files.exists(tsvPath)) return;
        List<String> lines = Files.readAllLines(tsvPath);

        Map<String, List<double[]>> bins = new LinkedHashMap<>();
        Map<String, List<String>> binLabels = new LinkedHashMap<>();
        for (String l : lines) {
            String[] f = l.split("\t");
            if (f.length != 6) continue;
            if (!f[4].startsWith("bayer_") && !f[4].equals("sum_abs_R") && !f[4].equals("max_abs_R")) continue;
            try {
                double v = Double.parseDouble(f[5]);
                String key = f[0] + "/" + f[1];
                bins.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new double[]{Math.abs(v), v});
                binLabels.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(f[2] + "/" + f[3] + "/" + f[4]);
            } catch (NumberFormatException ignored) { }
        }

        try (PrintWriter pw = new PrintWriter(OUT_DIR.resolve("winners.txt").toFile())) {
            pw.println("Round-7 winners — tilewise top 5 per section (by |r|, sum|r|, max|r|)");
            pw.println();
            List<String> keys = new ArrayList<>(bins.keySet());
            keys.sort(Comparator.naturalOrder());
            for (String key : keys) {
                List<double[]> values = bins.get(key);
                List<String> labels = binLabels.get(key);
                Integer[] idx = new Integer[values.size()];
                for (int i = 0; i < idx.length; i++) idx[i] = i;
                Arrays.sort(idx, (a, b) -> Double.compare(values.get(b)[0], values.get(a)[0]));
                pw.println("=== " + key + " — top 5 ===");
                System.out.println();
                System.out.println("=== " + key + " — top 5 ===");
                int n = Math.min(5, idx.length);
                for (int i = 0; i < n; i++) {
                    int j = idx[i];
                    String row = String.format("  %2d. r=%+.4f  %s", i + 1, values.get(j)[1], labels.get(j));
                    pw.println(row);
                    System.out.println(row);
                }
                pw.println();
            }
        }
    }

    // ============================================================ decoder

    /**
     * P_LEFT_BASE row-major decoder with LEAD_ZERO_TILE_FIRST baseline,
     * sign mode = NONE (Round-6 winner), and a per-plane K-init seed
     * supplied via {@link Config}. The K-update bracket is unchanged
     * from Round-6 (patent §1.8 lookahead form).
     */
    private static int[] decode(byte[] payload, int w, int h, int bd,
                                int predInit, Config cfg) {
        try { return decodeImpl(payload, w, h, bd, predInit, cfg); }
        catch (RuntimeException | IOException e) { return new int[0]; }
    }

    private static int[] decodeImpl(byte[] payload, int w, int h, int bd,
                                    int predInit, Config cfg) throws IOException {
        int[] result = new int[w * h];
        int maxVal = (1 << bd) - 1;
        BitReader reader = new BitReader(payload, 0, payload.length);
        int k = clamp(cfg.kInit, 0, bd);

        for (int n = 0; n < w * h; n++) {
            if (!reader.hasMore()) return Arrays.copyOf(result, n);
            int raw = decodeRice(reader, k);
            int signed = mapSignLsb(raw);

            int x = n % w;
            int y = n / w;

            int predicted;
            if (n == 0) {
                // LEAD_ZERO_TILE_FIRST: first decoded pixel of the tile is forced to zero.
                predicted = -signed;
            } else if (x == 0) {
                predicted = result[(y - 1) * w + x];
            } else {
                predicted = result[y * w + x - 1];
            }

            int pixel = predicted + signed;
            if (pixel < 0) pixel = 0;
            else if (pixel > maxVal) pixel = maxVal;
            result[n] = pixel;
            k = updateK(k, raw, bd);
        }
        return result;
    }

    private static int updateK(int k, int residual, int bd) {
        int sn = residual;
        int kStar = (sn <= 0) ? 0 : 31 - Integer.numberOfLeadingZeros(sn + 1);
        int kMin = Math.max(0, kStar - 1);
        int kMax = Math.min(bd, kStar + 1);
        if (k < kMin) return k + 1;
        if (k > kMax) return k - 1;
        return k;
    }

    private static int mapSignLsb(int s) {
        return ((s & 1) == 0) ? (s >> 1) : -((s >> 1) + 1);
    }

    private static int decodeRice(BitReader rd, int k) throws IOException {
        int q = 0;
        while (rd.hasMore()) {
            if (rd.readBit() != 0) break;
            q++;
            if (q > 64) break;
        }
        int r = 0;
        for (int i = 0; i < k && rd.hasMore(); i++) {
            r = (r << 1) | rd.readBit();
        }
        return (q << k) | r;
    }

    // ============================================================ stats

    private static double pearson(int[] cand, int[] ref) {
        int n = Math.min(cand.length, ref.length);
        if (n < 2) return 0;
        int mn = Integer.MAX_VALUE, mx = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            int v = cand[i];
            if (v < mn) mn = v;
            if (v > mx) mx = v;
        }
        int range = Math.max(1, mx - mn);
        double sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0;
        for (int i = 0; i < n; i++) {
            double a = (cand[i] - mn) * 255.0 / range;
            double b = ref[i];
            sa += a; sb += b;
            saa += a * a; sbb += b * b;
            sab += a * b;
        }
        double ma = sa / n, mb = sb / n;
        double cov = sab / n - ma * mb;
        double va = saa / n - ma * ma;
        double vb = sbb / n - mb * mb;
        double denom = Math.sqrt(Math.max(0, va) * Math.max(0, vb));
        return denom < 1e-9 ? 0 : cov / denom;
    }

    private static int[] cfaLayoutMap(int cfaLayout) {
        return switch (cfaLayout) {
            case 0 -> new int[]{0, 1, 2, 3};   // RGGB
            case 1 -> new int[]{1, 0, 3, 2};   // GRBG
            case 2 -> new int[]{2, 3, 0, 1};   // GBRG
            case 3 -> new int[]{3, 2, 1, 0};   // BGGR
            default -> new int[]{0, 1, 2, 3};
        };
    }

    private static Cmp1Box readCmp1(File cr3) throws Exception {
        try (ImageInputStream in = ImageIO.createImageInputStream(cr3)) {
            Cr3Container c = Cr3Parser.parse(in);
            for (Cr3Track t : c.tracks()) {
                if (t.cmp1() != null) return t.cmp1();
            }
        }
        return null;
    }
}
