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
 * Round-8 follow-up to {@link CrxPlaneFollowupRound7IT}, per the new
 * requirement (2026-05-01, after Update (k)):
 *
 * <p><b>Working hypothesis going into Round-8:</b></p>
 * <ul>
 *   <li>{@code LEAD_ZERO_TILE_FIRST} stays part of the best known
 *       path.</li>
 *   <li>{@code K_INIT = CMP1.quantValue} (= 4 on {@code sample.cr3})
 *       is the best fixed metadata-near rule and matches the joint
 *       per-plane greedy optimum to Δ Σ|r| ≤ 0.01.</li>
 *   <li>Per-channel {@code max|r|} is still 0.56 (Gr) — therefore at
 *       least one further state rule is missing. Round-8 looks at the
 *       <em>K-evolution</em> after init rather than at additional
 *       K_INIT sweeps.</li>
 * </ul>
 *
 * <p><b>Strict test scope.</b> No production code is modified, no
 * flag is added, no hypothesis is promoted. Output dir:
 * {@code canon-cr3-imageio/target/crx-followup-round8/}.</p>
 *
 * <h3>Section P8 — K-evolution after init</h3>
 * Holding predictor = {@code P_LEFT_BASE}, lead-zero =
 * {@code LZ_TILE_FIRST}, sign = {@code NONE},
 * {@code K_INIT = CMP1.quantValue} on every plane, vary the K-update
 * rule that runs <em>after</em> init:
 * <ul>
 *   <li>{@code K_CONST} — never update K (stays at the seed).</li>
 *   <li>{@code K_BRACKET_PATENT} — current Round-6 baseline:
 *       {@code K* = floor(log2(s + 1)); K ∈ [K* − 1, K* + 1]}.</li>
 *   <li>{@code K_BRACKET_WIDE} — same, ±2 instead of ±1.</li>
 *   <li>{@code K_BRACKET_TIGHT} — same, ±0 (snap to {@code K*}).</li>
 *   <li>{@code K_BRACKET_LO_ONLY} — only allow {@code k++} (no
 *       decrement) so K can drift up away from the seed.</li>
 *   <li>{@code K_BRACKET_HI_ONLY} — only allow {@code k--} (no
 *       increment) so K can only drift down towards 0.</li>
 *   <li>{@code K_PER_ROW_RESET} — apply patent bracket inside each
 *       row but reseed {@code k = K_INIT} at the start of every
 *       row.</li>
 *   <li>{@code K_PREV_SYMBOL} — at each step set
 *       {@code K = clamp(floor(log2(prev_s + 1)), 0, bd)}; ignores
 *       the bracket entirely.</li>
 *   <li>{@code K_RUNNING_MEAN} — a 16-symbol exponential running
 *       mean of {@code s} feeds into {@code K* = floor(log2(mean +
 *       1))} with the same ±1 bracket.</li>
 * </ul>
 *
 * <h3>Section Q8 — Multi-file validation of {@code K_INIT = CMP1.quantValue}</h3>
 * Iterate over additional CR3 fixtures from
 * {@code canon-cr3-imageio/src/test/resources/samples/}. For each
 * file: extract {@code quantValue} per subband, decode tile0 under
 * five fixed K_INIT rules ({@code Q}, {@code Q-1}, {@code Q+1},
 * {@code 0}, {@code floor(log2 q)}) and report per-plane Σ|r|. The
 * point is to test whether the positive correlation between
 * {@code quantValue} and the best K_INIT is stable across fixtures
 * with different {@code quantValue} histograms.
 *
 * <h3>Section R8 — Additional CMP1/header links</h3>
 * Document, per fixture and per subband, the auxiliary CMP1/header
 * fields ({@code encodingType}, {@code waveletLevels},
 * {@code bitsPerSample}, {@code numPlanes}, {@code roundedBits},
 * {@code supportsPartial}) so any future per-plane K rule can be
 * cross-checked against them.
 *
 * <h3>Section S8 — Combined refinement</h3>
 * Take the Round-7 winning composition (LZ + K_INIT=q + perm)
 * and additionally apply the best K-update rule from P8. Compare
 * Σ|r| / max|r| against the Round-7 baseline.
 *
 * <p>Like the previous Round ITs, this test always succeeds — its
 * purpose is artefact production for {@code SubbandFixtures.md}
 * Update (l) and analysis-doc §5.13.</p>
 */
class CrxPlaneFollowupRound8IT {

    private static final String SAMPLES_DIR = "src/test/resources/samples";
    private static final String SAMPLE_PRIMARY = "sample.cr3";
    /** Additional fixtures used for Section Q8 multi-file validation. */
    private static final String[] SAMPLES_MULTIFILE = {
            "sample.cr3",
            "sample-iso-high.cr3",
            "eosr-iso-low.cr3"
    };
    private static final Path OUT_DIR = Path.of("target", "crx-followup-round8");

    /** All K-evolution variants tested in Section P8. */
    private enum KUpdate {
        K_CONST,
        K_BRACKET_PATENT,
        K_BRACKET_WIDE,
        K_BRACKET_TIGHT,
        K_BRACKET_LO_ONLY,
        K_BRACKET_HI_ONLY,
        K_PER_ROW_RESET,
        K_PREV_SYMBOL,
        K_RUNNING_MEAN
    }

    /** Per-plane decode configuration. */
    private record Config(int kInit, KUpdate update) { }

    @Test
    void runRound8Sweep() throws Exception {
        File primary = new File(SAMPLES_DIR + "/" + SAMPLE_PRIMARY);
        assertThat(primary).exists();
        Files.createDirectories(OUT_DIR);

        Cmp1Box primaryCmp1 = readCmp1(primary);
        assertThat(primaryCmp1).as("CMP1 box on " + SAMPLE_PRIMARY).isNotNull();
        int[] cfaToPreview = cfaLayoutMap(primaryCmp1.cfaLayout());
        String[] chanName = {"R", "Gr", "Gb", "B"};

        List<SubbandExtractor.SubbandRef> subs = SubbandExtractor.list(primary);
        assertThat(subs).as("primary subbands").hasSizeGreaterThanOrEqualTo(4);

        SubbandExtractor.SubbandRef[] tile0 = {subs.get(0), subs.get(1), subs.get(2), subs.get(3)};
        SubbandExtractor.SubbandRef[] tile1 = subs.size() >= 8
                ? new SubbandExtractor.SubbandRef[]{subs.get(4), subs.get(5), subs.get(6), subs.get(7)}
                : null;

        int w = tile0[0].planeWidth();
        int h = tile0[0].planeHeight();
        PreviewReferenceExtractor.Reference[] refs = new PreviewReferenceExtractor.Reference[4];
        for (int c = 0; c < 4; c++) {
            refs[c] = PreviewReferenceExtractor.extract(primary, c, w, h);
        }

        try (PrintWriter tsv = new PrintWriter(OUT_DIR.resolve("metrics.tsv").toFile())) {
            tsv.println("section\tfile\ttile\tplane\tvariant\tmetric\tvalue");

            // Per-plane K_INIT = q seed for the primary fixture.
            int[] kInitFromQ = perPlaneKInitFromQ(tile0);

            // ---------- Section P8 — K-evolution after init ----------
            System.out.printf("%n=== Section P8 — K-evolution under K_INIT=quantValue ===%n");
            KUpdate bestUpd = KUpdate.K_BRACKET_PATENT;
            double bestUpdScore = Double.NEGATIVE_INFINITY;
            for (KUpdate upd : KUpdate.values()) {
                double s0 = runEvolutionVariant(primary, SAMPLE_PRIMARY, tile0, "tile0", kInitFromQ, upd,
                        cfaToPreview, refs, chanName, tsv);
                double s1 = (tile1 != null)
                        ? runEvolutionVariant(primary, SAMPLE_PRIMARY, tile1, "tile1",
                                perPlaneKInitFromQ(tile1), upd, cfaToPreview, refs, chanName, tsv)
                        : 0;
                double total = s0 + s1;
                if (total > bestUpdScore) {
                    bestUpdScore = total;
                    bestUpd = upd;
                }
            }
            System.out.printf("P8 best K-update on primary fixture: %s (Σ|r|tile0+tile1=%.4f)%n",
                    bestUpd, bestUpdScore);

            // ---------- Section Q8 — Multi-file validation ----------
            System.out.printf("%n=== Section Q8 — Multi-file K_INIT=quantValue validation ===%n");
            for (String name : SAMPLES_MULTIFILE) {
                File f = new File(SAMPLES_DIR + "/" + name);
                if (!f.exists()) {
                    System.out.printf("Q8 %-32s SKIPPED (file not present)%n", name);
                    tsv.printf("Q8\t%s\t-\t-\t(skipped: file missing)\tnote\t0%n", name);
                    continue;
                }
                runMultiFileQuantValueRule(f, name, tsv);
            }

            // ---------- Section R8 — Additional CMP1/header links ----------
            System.out.printf("%n=== Section R8 — Additional CMP1/header links ===%n");
            for (String name : SAMPLES_MULTIFILE) {
                File f = new File(SAMPLES_DIR + "/" + name);
                if (!f.exists()) continue;
                emitHeaderLinks(f, name, tsv);
            }

            // ---------- Section S8 — Combined refinement ----------
            System.out.printf("%n=== Section S8 — Combined LZ + K_INIT=q + best-K-update + perm[1023] ===%n");
            int[] permRound6   = {1, 0, 2, 3};        // Round-6 K6 winner [1023]
            int[] permBaseline = cfaToPreview;        // existing GRBG mapping (= [1032])

            // Decode each tile under the best K-update from P8.
            int[][] dec0 = decodeAllPlanes(primary, tile0, kInitFromQ, bestUpd);
            int[][] dec1 = (tile1 != null)
                    ? decodeAllPlanes(primary, tile1, perPlaneKInitFromQ(tile1), bestUpd)
                    : null;

            evaluateCombined(dec0, "tile0", permBaseline, "perm=[1032]+upd=" + bestUpd, refs, chanName, tsv,
                    kInitFromQ, bestUpd);
            evaluateCombined(dec0, "tile0", permRound6, "perm=[1023]+upd=" + bestUpd, refs, chanName, tsv,
                    kInitFromQ, bestUpd);
            if (dec1 != null) {
                int[] kInit1 = perPlaneKInitFromQ(tile1);
                evaluateCombined(dec1, "tile1", permBaseline, "perm=[1032]+upd=" + bestUpd, refs, chanName, tsv,
                        kInit1, bestUpd);
                evaluateCombined(dec1, "tile1", permRound6, "perm=[1023]+upd=" + bestUpd, refs, chanName, tsv,
                        kInit1, bestUpd);
            }

            // Control: same composition with the Round-7-baseline K-update (PATENT bracket).
            if (bestUpd != KUpdate.K_BRACKET_PATENT) {
                int[][] dec0Ctl = decodeAllPlanes(primary, tile0, kInitFromQ, KUpdate.K_BRACKET_PATENT);
                evaluateCombined(dec0Ctl, "tile0", permRound6,
                        "perm=[1023]+upd=PATENT_R7control", refs, chanName, tsv,
                        kInitFromQ, KUpdate.K_BRACKET_PATENT);
                if (tile1 != null) {
                    int[] kInit1 = perPlaneKInitFromQ(tile1);
                    int[][] dec1Ctl = decodeAllPlanes(primary, tile1, kInit1, KUpdate.K_BRACKET_PATENT);
                    evaluateCombined(dec1Ctl, "tile1", permRound6,
                            "perm=[1023]+upd=PATENT_R7control", refs, chanName, tsv,
                            kInit1, KUpdate.K_BRACKET_PATENT);
                }
            }
        }

        summariseTilewiseWinners();

        assertThat(OUT_DIR.resolve("metrics.tsv")).exists();
        assertThat(OUT_DIR.resolve("winners.txt")).exists();
    }

    // ============================================================ Section P8

    private static double runEvolutionVariant(File f, String fileName,
                                              SubbandExtractor.SubbandRef[] planes, String tileTag,
                                              int[] kInit, KUpdate upd,
                                              int[] cfaToPreview,
                                              PreviewReferenceExtractor.Reference[] refs,
                                              String[] chanName, PrintWriter tsv) throws IOException {
        int w = planes[0].planeWidth();
        int h = planes[0].planeHeight();
        int bd = planes[0].bitDepth();
        int midgrey = 1 << (bd - 1);
        double sumAbs = 0;
        StringBuilder line = new StringBuilder();
        line.append(String.format("%-6s %-22s ", tileTag, upd.name()));
        for (int pi = 0; pi < 4; pi++) {
            int previewChan = cfaToPreview[pi];
            byte[] payload = SubbandExtractor.read(f, planes[pi]);
            int[] dec = decode(payload, w, h, bd, midgrey, new Config(kInit[pi], upd));
            double r = (dec.length < w * h) ? 0 : pearson(dec, refs[previewChan].pixels());
            sumAbs += Math.abs(r);
            String variant = "P8/" + upd.name() + "/kInit=" + kInit[pi];
            tsv.printf("%s\t%s\t%s\tp%d\t%s\tbayer_%s_R\t%.6f%n",
                    "P8", fileName, tileTag, pi, variant, chanName[previewChan], r);
            line.append(String.format("p%d=%+.4f ", pi, r));
        }
        tsv.printf("%s\t%s\t%s\t-\tP8/%s\tsum_abs_R\t%.6f%n",
                "P8", fileName, tileTag, upd.name(), sumAbs);
        System.out.println(line + "Σ|r|=" + String.format("%.4f", sumAbs));
        return sumAbs;
    }

    private static int[] perPlaneKInitFromQ(SubbandExtractor.SubbandRef[] planes) {
        int[] k = new int[4];
        for (int pi = 0; pi < 4; pi++) k[pi] = clamp(planes[pi].quantValue(), 0, planes[pi].bitDepth());
        return k;
    }

    // ============================================================ Section Q8

    private static void runMultiFileQuantValueRule(File f, String fileName, PrintWriter tsv) throws IOException {
        Cmp1Box cmp1 = null;
        try { cmp1 = readCmp1(f); } catch (Exception ignored) { }
        if (cmp1 == null) {
            System.out.printf("Q8 %-32s SKIPPED (no CMP1)%n", fileName);
            tsv.printf("Q8\t%s\t-\t-\t(skipped: no CMP1)\tnote\t0%n", fileName);
            return;
        }
        List<SubbandExtractor.SubbandRef> subs;
        try {
            subs = SubbandExtractor.list(f);
        } catch (Exception e) {
            System.out.printf("Q8 %-32s SKIPPED (subband list failed: %s)%n", fileName, e.getMessage());
            tsv.printf("Q8\t%s\t-\t-\t(skipped: subband list failed)\tnote\t0%n", fileName);
            return;
        }
        if (subs.size() < 4) {
            System.out.printf("Q8 %-32s SKIPPED (only %d subbands)%n", fileName, subs.size());
            tsv.printf("Q8\t%s\t-\t-\t(skipped: <4 subbands)\tnote\t0%n", fileName);
            return;
        }
        SubbandExtractor.SubbandRef[] tile0 = {subs.get(0), subs.get(1), subs.get(2), subs.get(3)};

        int w = tile0[0].planeWidth();
        int h = tile0[0].planeHeight();
        int bd = tile0[0].bitDepth();
        int midgrey = 1 << (bd - 1);

        int[] cfaToPreview = cfaLayoutMap(cmp1.cfaLayout());
        String[] chanName = {"R", "Gr", "Gb", "B"};

        // Per-plane reference grid (downsampled from the embedded preview JPEG).
        PreviewReferenceExtractor.Reference[] refs;
        try {
            refs = new PreviewReferenceExtractor.Reference[4];
            for (int c = 0; c < 4; c++) refs[c] = PreviewReferenceExtractor.extract(f, c, w, h);
        } catch (Exception e) {
            System.out.printf("Q8 %-32s SKIPPED (preview JPEG failed: %s)%n", fileName, e.getMessage());
            tsv.printf("Q8\t%s\t-\t-\t(skipped: preview missing)\tnote\t0%n", fileName);
            return;
        }

        // Per-plane quantValue summary.
        StringBuilder qLine = new StringBuilder();
        for (int pi = 0; pi < 4; pi++) {
            int q = tile0[pi].quantValue();
            qLine.append(String.format("p%d(q=%d) ", pi, q));
            tsv.printf("%s\t%s\t%s\tp%d\t-\tquantValue\t%d%n", "Q8", fileName, "tile0", pi, q);
        }
        System.out.printf("Q8 %-32s tile0 quantValues: %s%n", fileName, qLine);

        String[] ruleNames = {"K_INIT=0", "K_INIT=q", "K_INIT=q-1", "K_INIT=q+1", "K_INIT=floor(log2(q))"};
        for (int rule = 0; rule < ruleNames.length; rule++) {
            double sumAbs = 0;
            StringBuilder line = new StringBuilder();
            line.append(String.format("Q8 %-32s tile0 %-22s ", fileName, ruleNames[rule]));
            for (int pi = 0; pi < 4; pi++) {
                int previewChan = cfaToPreview[pi];
                int q = tile0[pi].quantValue();
                int kInit = switch (rule) {
                    case 0 -> 0;
                    case 1 -> clamp(q, 0, bd);
                    case 2 -> clamp(q - 1, 0, bd);
                    case 3 -> clamp(q + 1, 0, bd);
                    case 4 -> (q <= 0) ? 0 : clamp(31 - Integer.numberOfLeadingZeros(q), 0, bd);
                    default -> 0;
                };
                byte[] payload = SubbandExtractor.read(f, tile0[pi]);
                int[] dec = decode(payload, w, h, bd, midgrey,
                        new Config(kInit, KUpdate.K_BRACKET_PATENT));
                double r = (dec.length < w * h) ? 0 : pearson(dec, refs[previewChan].pixels());
                sumAbs += Math.abs(r);
                String variant = "Q8/" + ruleNames[rule] + "(k=" + kInit + ")";
                tsv.printf("%s\t%s\t%s\tp%d\t%s\tbayer_%s_R\t%.6f%n",
                        "Q8", fileName, "tile0", pi, variant, chanName[previewChan], r);
                line.append(String.format("p%d=%+.4f ", pi, r));
            }
            tsv.printf("%s\t%s\t%s\t-\tQ8/%s\tsum_abs_R\t%.6f%n",
                    "Q8", fileName, "tile0", ruleNames[rule], sumAbs);
            System.out.println(line + "Σ|r|=" + String.format("%.4f", sumAbs));
        }
    }

    // ============================================================ Section R8

    private static void emitHeaderLinks(File f, String fileName, PrintWriter tsv) throws IOException {
        Cmp1Box cmp1;
        try { cmp1 = readCmp1(f); } catch (Exception e) { return; }
        if (cmp1 == null) return;
        List<SubbandExtractor.SubbandRef> subs;
        try {
            subs = SubbandExtractor.list(f);
        } catch (Exception e) { return; }

        // CMP1-level fields.
        tsv.printf("%s\t%s\t-\t-\tR8/CMP1\tencodingType\t%d%n", "R8", fileName, cmp1.encodingType());
        tsv.printf("%s\t%s\t-\t-\tR8/CMP1\twaveletLevels\t%d%n", "R8", fileName, cmp1.waveletLevels());
        tsv.printf("%s\t%s\t-\t-\tR8/CMP1\tbitsPerSample\t%d%n", "R8", fileName, cmp1.bitsPerSample());
        tsv.printf("%s\t%s\t-\t-\tR8/CMP1\tnumPlanes\t%d%n", "R8", fileName, cmp1.numPlanes());
        tsv.printf("%s\t%s\t-\t-\tR8/CMP1\tcfaLayout\t%d%n", "R8", fileName, cmp1.cfaLayout());
        System.out.printf("R8 %-32s CMP1 encType=%d waveLvl=%d bps=%d nPlanes=%d cfa=%d numSub=%d%n",
                fileName, cmp1.encodingType(), cmp1.waveletLevels(), cmp1.bitsPerSample(),
                cmp1.numPlanes(), cmp1.cfaLayout(), subs.size());

        // Per-subband fields.
        int n = Math.min(subs.size(), 8);
        for (int i = 0; i < n; i++) {
            SubbandExtractor.SubbandRef s = subs.get(i);
            String tag = "tile" + s.tileSequence() + "/p" + s.planeIndex() + "/sb" + s.subbandIndex();
            tsv.printf("%s\t%s\t%s\t-\tR8/SB\tquantValue\t%d%n", "R8", fileName, tag, s.quantValue());
            tsv.printf("%s\t%s\t%s\t-\tR8/SB\troundedBits\t%d%n", "R8", fileName, tag, s.roundedBits());
            tsv.printf("%s\t%s\t%s\t-\tR8/SB\tsupportsPartial\t%d%n",
                    "R8", fileName, tag, s.supportsPartial() ? 1 : 0);
            tsv.printf("%s\t%s\t%s\t-\tR8/SB\tencodingType\t%d%n", "R8", fileName, tag, s.encodingType());
            tsv.printf("%s\t%s\t%s\t-\tR8/SB\twaveletLevels\t%d%n", "R8", fileName, tag, s.waveletLevels());
        }
    }

    // ============================================================ Section S8

    private static int[][] decodeAllPlanes(File f, SubbandExtractor.SubbandRef[] planes,
                                           int[] kInit, KUpdate upd) throws IOException {
        int w = planes[0].planeWidth();
        int h = planes[0].planeHeight();
        int bd = planes[0].bitDepth();
        int midgrey = 1 << (bd - 1);
        int[][] out = new int[4][];
        for (int pi = 0; pi < 4; pi++) {
            byte[] payload = SubbandExtractor.read(f, planes[pi]);
            int[] dec = decode(payload, w, h, bd, midgrey, new Config(kInit[pi], upd));
            out[pi] = (dec.length >= w * h) ? dec : new int[w * h];
        }
        return out;
    }

    private static void evaluateCombined(int[][] dec, String tileTag, int[] perm,
                                         String permTag,
                                         PreviewReferenceExtractor.Reference[] refs,
                                         String[] chanName, PrintWriter tsv,
                                         int[] kInit, KUpdate upd) {
        double sumAbs = 0;
        double maxAbs = 0;
        StringBuilder line = new StringBuilder();
        line.append(String.format("%-6s %-44s K=%s ", tileTag, permTag, kVec(kInit)));
        for (int pi = 0; pi < 4; pi++) {
            int previewChan = perm[pi];
            double r = pearson(dec[pi], refs[previewChan].pixels());
            sumAbs += Math.abs(r);
            if (Math.abs(r) > maxAbs) maxAbs = Math.abs(r);
            String variant = "S8/LZ+kInit=" + kVec(kInit) + "+upd=" + upd + "+" + permTag;
            tsv.printf("%s\t%s\t%s\tp%d\t%s\tbayer_%s_R\t%.6f%n",
                    "S8", "sample.cr3", tileTag, pi, variant, chanName[previewChan], r);
            line.append(String.format("p%d=%+.4f ", pi, r));
        }
        tsv.printf("%s\t%s\t%s\t-\tS8/LZ+kInit=%s+upd=%s+%s\tsum_abs_R\t%.6f%n",
                "S8", "sample.cr3", tileTag, kVec(kInit), upd, permTag, sumAbs);
        tsv.printf("%s\t%s\t%s\t-\tS8/LZ+kInit=%s+upd=%s+%s\tmax_abs_R\t%.6f%n",
                "S8", "sample.cr3", tileTag, kVec(kInit), upd, permTag, maxAbs);
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
            if (f.length != 7) continue;
            if (!f[5].startsWith("bayer_") && !f[5].equals("sum_abs_R") && !f[5].equals("max_abs_R")) continue;
            try {
                double v = Double.parseDouble(f[6]);
                String key = f[0] + "/" + f[1] + "/" + f[2];
                bins.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new double[]{Math.abs(v), v});
                binLabels.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(f[3] + "/" + f[4] + "/" + f[5]);
            } catch (NumberFormatException ignored) { }
        }

        try (PrintWriter pw = new PrintWriter(OUT_DIR.resolve("winners.txt").toFile())) {
            pw.println("Round-8 winners — top 5 per (section/file/tile) by |r| / sum|r| / max|r|");
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
     * sign mode = NONE, per-plane K-init seed and a configurable
     * post-init K-update rule.
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
        int kInit = clamp(cfg.kInit, 0, bd);
        int k = kInit;
        int prevSn = 0;
        double meanSn = Math.max(1.0, kInit);
        final double meanAlpha = 1.0 / 16.0;

        for (int n = 0; n < w * h; n++) {
            if (!reader.hasMore()) return Arrays.copyOf(result, n);

            int x = n % w;
            int y = n / w;

            // Per-row reseed must happen BEFORE consuming bits at row start.
            if (cfg.update == KUpdate.K_PER_ROW_RESET && x == 0 && n > 0) {
                k = kInit;
            }
            // Snap variants: K is set from current statistics BEFORE reading the symbol.
            if (cfg.update == KUpdate.K_PREV_SYMBOL && n > 0) {
                k = (prevSn <= 0) ? 0 : clamp(31 - Integer.numberOfLeadingZeros(prevSn + 1), 0, bd);
            } else if (cfg.update == KUpdate.K_RUNNING_MEAN && n > 0) {
                int kStar = (meanSn < 1.0) ? 0
                        : clamp(31 - Integer.numberOfLeadingZeros((int) Math.round(meanSn) + 1), 0, bd);
                if (k < kStar - 1) k++; else if (k > kStar + 1) k--;
            }

            int raw = decodeRice(reader, k);
            int signed = mapSignLsb(raw);

            int predicted;
            if (n == 0) {
                predicted = -signed;          // LEAD_ZERO_TILE_FIRST
            } else if (x == 0) {
                predicted = result[(y - 1) * w + x];
            } else {
                predicted = result[y * w + x - 1];
            }

            int pixel = predicted + signed;
            if (pixel < 0) pixel = 0;
            else if (pixel > maxVal) pixel = maxVal;
            result[n] = pixel;

            // Post-symbol K update — applied AFTER consumption.
            switch (cfg.update) {
                case K_CONST:
                    break;
                case K_BRACKET_PATENT: {
                    int kStar = (raw <= 0) ? 0 : 31 - Integer.numberOfLeadingZeros(raw + 1);
                    int kMin = Math.max(0, kStar - 1);
                    int kMax = Math.min(bd, kStar + 1);
                    if (k < kMin) k++;
                    else if (k > kMax) k--;
                    break;
                }
                case K_BRACKET_WIDE: {
                    int kStar = (raw <= 0) ? 0 : 31 - Integer.numberOfLeadingZeros(raw + 1);
                    int kMin = Math.max(0, kStar - 2);
                    int kMax = Math.min(bd, kStar + 2);
                    if (k < kMin) k++;
                    else if (k > kMax) k--;
                    break;
                }
                case K_BRACKET_TIGHT: {
                    int kStar = (raw <= 0) ? 0 : 31 - Integer.numberOfLeadingZeros(raw + 1);
                    k = clamp(kStar, 0, bd);
                    break;
                }
                case K_BRACKET_LO_ONLY: {
                    int kStar = (raw <= 0) ? 0 : 31 - Integer.numberOfLeadingZeros(raw + 1);
                    int kMin = Math.max(0, kStar - 1);
                    if (k < kMin && k < bd) k++;
                    break;
                }
                case K_BRACKET_HI_ONLY: {
                    int kStar = (raw <= 0) ? 0 : 31 - Integer.numberOfLeadingZeros(raw + 1);
                    int kMax = Math.min(bd, kStar + 1);
                    if (k > kMax && k > 0) k--;
                    break;
                }
                case K_PER_ROW_RESET: {
                    int kStar = (raw <= 0) ? 0 : 31 - Integer.numberOfLeadingZeros(raw + 1);
                    int kMin = Math.max(0, kStar - 1);
                    int kMax = Math.min(bd, kStar + 1);
                    if (k < kMin) k++;
                    else if (k > kMax) k--;
                    break;
                }
                case K_PREV_SYMBOL:
                    // K is set from prevSn BEFORE the next read; nothing to do post-symbol.
                    break;
                case K_RUNNING_MEAN:
                    meanSn = (1.0 - meanAlpha) * meanSn + meanAlpha * raw;
                    break;
            }
            prevSn = raw;
        }
        return result;
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

    // ============================================================ utilities

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String kVec(int[] k) {
        return "[" + k[0] + "," + k[1] + "," + k[2] + "," + k[3] + "]";
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
