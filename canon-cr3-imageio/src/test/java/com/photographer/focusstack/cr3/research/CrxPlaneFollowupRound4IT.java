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
 * Round-4 follow-up to {@link CrxPlaneFollowupRound3IT}, per the new
 * requirement (2026-05-01, after Update (g) confirmed P_LEFT_BASE |r| ≈ 0.39
 * is the ceiling and that <b>tile1 dominates tile0</b> in correlation under
 * every variant — pointing at an undocumented leading state per tile / tile
 * start).
 *
 * <p><b>Strict test scope.</b> No production code is modified, no flag is
 * added, no hypothesis is promoted. Output dir:
 * {@code canon-cr3-imageio/target/crx-followup-round4/}.</p>
 *
 * <h3>Section E — Tile-leading-state</h3>
 * The very first decoded pixel of each tile is overridden, then the standard
 * P_LEFT_BASE pathway resumes:
 * <ul>
 *   <li><b>LEAD_ZERO</b>: first sample = 0</li>
 *   <li><b>LEAD_PREDINIT</b>: first sample = midgrey (1&lt;&lt;(bd-1)) — the round-3 baseline</li>
 *   <li><b>LEAD_BIAS_HI</b>: first sample = midgrey + 1024</li>
 *   <li><b>LEAD_BIAS_LO</b>: first sample = midgrey − 1024</li>
 *   <li><b>LEAD_COPY_NEXT</b>: first sample = decoded second sample (one-sample lookahead)</li>
 *   <li><b>LEAD_NEG_FIRST</b>: first residual sign-flipped, then standard predInit</li>
 * </ul>
 *
 * <h3>Section F — Warmup-prefix</h3>
 * For the first {@code N ∈ {1, 2, 4, 8, 16}} samples of each row the predictor
 * is forced to {@code predInit} (midgrey); after the warmup the standard
 * P_LEFT_BASE rule applies. Tests whether a short row-start warmup explains
 * the persistent negative-r dominance.
 *
 * <h3>Section G — Tile0 vs Tile1 differential analysis</h3>
 * Tile-wise dump of: mean, std, min, max, Pearson r vs preview reference,
 * fraction of positive residuals, best constant additive offset (which is
 * Pearson-invariant — reported for completeness), best sign convention.
 * Goal: identify which transformation closes the tile0↔tile1 gap.
 *
 * <h3>Section H — Local polarity / bias</h3>
 * Per-tile: report |r| under the optimal sign (sign-flip is the only
 * affine that changes |Pearson r|). Sweep additive bias in {-2048, -1024,
 * 0, +1024, +2048} and report Δ|r| (which by construction is ≈0 because
 * Pearson r is translation-invariant — this is documented as a sanity check
 * confirming the metric, not a hypothesis test).
 *
 * <p>The test always succeeds — its purpose is artefact production for
 * {@code SubbandFixtures.md} Update (h) and analysis-doc §5.9.</p>
 */
class CrxPlaneFollowupRound4IT {

    private static final String SAMPLE = "src/test/resources/samples/sample.cr3";
    private static final Path OUT_DIR = Path.of("target", "crx-followup-round4");

    private enum LeadInit {
        LEAD_ZERO, LEAD_PREDINIT, LEAD_BIAS_HI, LEAD_BIAS_LO,
        LEAD_COPY_NEXT, LEAD_NEG_FIRST
    }

    /** A scored experimental run. */
    private record Score(String section, String tile, int plane, String variant,
                         int previewChan, double r) { }

    @Test
    void runRound4Sweep() throws Exception {
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

        int tilesPerRow = (tile1 != null) ? 2 : 1;

        List<Score> scores = new ArrayList<>();

        try (PrintWriter tsv = new PrintWriter(OUT_DIR.resolve("metrics.tsv").toFile())) {
            tsv.println("section\ttile\tplane\tvariant\tmetric\tvalue");

            // ------------------ E: tile-leading-state ------------------
            System.out.printf("%n=== Section E — tile-leading-state (predictor = P_LEFT_BASE) ===%n");
            System.out.printf("%-6s %-3s %-18s %12s %12s%n", "tile", "p", "leadInit", "outMean", "bayerR");
            for (LeadInit li : LeadInit.values()) {
                sweepLead(f, tile0, "tile0", cfaToPreview, chanName, tsv, scores, li, "E");
                if (tile1 != null) {
                    sweepLead(f, tile1, "tile1", cfaToPreview, chanName, tsv, scores, li, "E");
                }
            }

            // ------------------ F: warmup-prefix -----------------------
            System.out.printf("%n=== Section F — warmup-prefix (predictor = P_LEFT_BASE, first N per row = predInit) ===%n");
            System.out.printf("%-6s %-3s %-18s %12s %12s%n", "tile", "p", "warmupN", "outMean", "bayerR");
            int[] warmups = {1, 2, 4, 8, 16};
            for (int n : warmups) {
                sweepWarmup(f, tile0, "tile0", cfaToPreview, chanName, tsv, scores, n, "F");
                if (tile1 != null) {
                    sweepWarmup(f, tile1, "tile1", cfaToPreview, chanName, tsv, scores, n, "F");
                }
            }

            // ------------------ G: tile differential -------------------
            System.out.printf("%n=== Section G — tile0 vs tile1 differential (predictor = P_LEFT_BASE / LEAD_PREDINIT) ===%n");
            System.out.printf("%-6s %-3s %12s %12s %8s %8s %12s %12s %12s %8s%n",
                    "tile", "p", "mean", "std", "min", "max", "pearsonR", "absR_signFix",
                    "bestAddOff", "fracPos");
            tileDifferential(f, tile0, "tile0", cfaToPreview, chanName, tsv, scores, "G");
            if (tile1 != null) {
                tileDifferential(f, tile1, "tile1", cfaToPreview, chanName, tsv, scores, "G");
            }

            // ------------------ H: local polarity / bias ---------------
            System.out.printf("%n=== Section H — local polarity / bias (per-tile, per-plane) ===%n");
            System.out.printf("%-6s %-3s %12s %12s %14s %12s%n",
                    "tile", "p", "rRaw", "rSignFix", "biasOffset", "rAtBias");
            polarityBias(f, tile0, "tile0", cfaToPreview, chanName, tsv, "H");
            if (tile1 != null) {
                polarityBias(f, tile1, "tile1", cfaToPreview, chanName, tsv, "H");
            }
        }

        summariseTilewiseWinners();
        exportTilewiseHeatmaps(f, tile0, "tile0");
        if (tile1 != null) exportTilewiseHeatmaps(f, tile1, "tile1");

        assertThat(OUT_DIR.resolve("metrics.tsv")).exists();
        assertThat(OUT_DIR.resolve("winners.txt")).exists();
    }

    // ============================================================ Section E sweep

    private static void sweepLead(File f, SubbandExtractor.SubbandRef[] planes, String tileTag,
                                  int[] cfaToPreview, String[] chanName, PrintWriter tsv,
                                  List<Score> scores, LeadInit li, String section) throws IOException {
        int w = planes[0].planeWidth();
        int h = planes[0].planeHeight();
        int bd = planes[0].bitDepth();
        int midgrey = 1 << (bd - 1);
        for (int pi = 0; pi < 4; pi++) {
            int previewChan = cfaToPreview[pi];
            PreviewReferenceExtractor.Reference ref =
                    PreviewReferenceExtractor.extract(f, previewChan, w, h);
            byte[] payload = SubbandExtractor.read(f, planes[pi]);
            int[] dec = decode(payload, w, h, bd, midgrey, 0, li, /* warmupN */ 0);
            if (dec.length < w * h) continue;
            double m = mean(dec);
            double br = pearson(dec, ref.pixels());
            String variant = "P_LEFT_BASE/" + li;
            System.out.printf("%-6s p%-2d %-18s %12.2f %+12.4f%n", tileTag, pi, li, m, br);
            tsv.printf("%s\t%s\tp%d\t%s\toutMean\t%.4f%n", section, tileTag, pi, variant, m);
            tsv.printf("%s\t%s\tp%d\t%s\tbayer_%s_R\t%.6f%n",
                    section, tileTag, pi, variant, chanName[previewChan], br);
            scores.add(new Score(section, tileTag, pi, variant, previewChan, br));
        }
    }

    // ============================================================ Section F sweep

    private static void sweepWarmup(File f, SubbandExtractor.SubbandRef[] planes, String tileTag,
                                    int[] cfaToPreview, String[] chanName, PrintWriter tsv,
                                    List<Score> scores, int warmupN, String section) throws IOException {
        int w = planes[0].planeWidth();
        int h = planes[0].planeHeight();
        int bd = planes[0].bitDepth();
        int midgrey = 1 << (bd - 1);
        for (int pi = 0; pi < 4; pi++) {
            int previewChan = cfaToPreview[pi];
            PreviewReferenceExtractor.Reference ref =
                    PreviewReferenceExtractor.extract(f, previewChan, w, h);
            byte[] payload = SubbandExtractor.read(f, planes[pi]);
            int[] dec = decode(payload, w, h, bd, midgrey, 0, LeadInit.LEAD_PREDINIT, warmupN);
            if (dec.length < w * h) continue;
            double m = mean(dec);
            double br = pearson(dec, ref.pixels());
            String variant = "P_LEFT_BASE/warmup=" + warmupN;
            System.out.printf("%-6s p%-2d %-18s %12.2f %+12.4f%n", tileTag, pi, "warmup=" + warmupN, m, br);
            tsv.printf("%s\t%s\tp%d\t%s\toutMean\t%.4f%n", section, tileTag, pi, variant, m);
            tsv.printf("%s\t%s\tp%d\t%s\tbayer_%s_R\t%.6f%n",
                    section, tileTag, pi, variant, chanName[previewChan], br);
            scores.add(new Score(section, tileTag, pi, variant, previewChan, br));
        }
    }

    // ============================================================ Section G — tile differential

    private static void tileDifferential(File f, SubbandExtractor.SubbandRef[] planes, String tileTag,
                                         int[] cfaToPreview, String[] chanName, PrintWriter tsv,
                                         List<Score> scores, String section) throws IOException {
        int w = planes[0].planeWidth();
        int h = planes[0].planeHeight();
        int bd = planes[0].bitDepth();
        int midgrey = 1 << (bd - 1);
        for (int pi = 0; pi < 4; pi++) {
            int previewChan = cfaToPreview[pi];
            PreviewReferenceExtractor.Reference ref =
                    PreviewReferenceExtractor.extract(f, previewChan, w, h);
            byte[] payload = SubbandExtractor.read(f, planes[pi]);
            int[] dec = decode(payload, w, h, bd, midgrey, 0, LeadInit.LEAD_PREDINIT, 0);
            if (dec.length < w * h) continue;

            double m = mean(dec);
            double sd = std(dec, m);
            int mn = Integer.MAX_VALUE, mx = Integer.MIN_VALUE;
            for (int v : dec) { if (v < mn) mn = v; if (v > mx) mx = v; }
            double br = pearson(dec, ref.pixels());
            // Pearson r is invariant under additive bias; the optimal additive
            // offset minimising L2 is mean(ref) - mean(dec_normalised). We
            // compute it on the raw scale for human-readability only.
            double refMean = mean(ref.pixels());
            double bestAddOff = refMean - m;
            // fraction of "positive residuals" — predictor=P_LEFT_BASE means
            // residual = current − previous on raster left.
            int posCount = 0, totCount = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 1; x < w; x++) {
                    int d = dec[y * w + x] - dec[y * w + x - 1];
                    if (d > 0) posCount++;
                    if (d != 0) totCount++;
                }
            }
            double fracPos = totCount == 0 ? 0 : (double) posCount / totCount;
            double absRsignFix = Math.abs(br);

            System.out.printf("%-6s p%-2d %12.2f %12.2f %8d %8d %+12.4f %+12.4f %+12.2f %8.4f%n",
                    tileTag, pi, m, sd, mn, mx, br, absRsignFix, bestAddOff, fracPos);

            String variant = "diff";
            tsv.printf("%s\t%s\tp%d\t%s\tmean\t%.4f%n", section, tileTag, pi, variant, m);
            tsv.printf("%s\t%s\tp%d\t%s\tstd\t%.4f%n", section, tileTag, pi, variant, sd);
            tsv.printf("%s\t%s\tp%d\t%s\tmin\t%d%n", section, tileTag, pi, variant, mn);
            tsv.printf("%s\t%s\tp%d\t%s\tmax\t%d%n", section, tileTag, pi, variant, mx);
            tsv.printf("%s\t%s\tp%d\t%s\tbayer_%s_R\t%.6f%n",
                    section, tileTag, pi, variant, chanName[previewChan], br);
            tsv.printf("%s\t%s\tp%d\t%s\tabsR_signFix\t%.6f%n",
                    section, tileTag, pi, variant, absRsignFix);
            tsv.printf("%s\t%s\tp%d\t%s\tbestAddOff\t%.4f%n",
                    section, tileTag, pi, variant, bestAddOff);
            tsv.printf("%s\t%s\tp%d\t%s\tfracPosResidual\t%.6f%n",
                    section, tileTag, pi, variant, fracPos);
            scores.add(new Score(section, tileTag, pi, variant, previewChan, br));
        }
    }

    // ============================================================ Section H — polarity / bias

    private static void polarityBias(File f, SubbandExtractor.SubbandRef[] planes, String tileTag,
                                     int[] cfaToPreview, String[] chanName, PrintWriter tsv,
                                     String section) throws IOException {
        int w = planes[0].planeWidth();
        int h = planes[0].planeHeight();
        int bd = planes[0].bitDepth();
        int midgrey = 1 << (bd - 1);
        int[] biases = {-2048, -1024, 0, 1024, 2048};
        for (int pi = 0; pi < 4; pi++) {
            int previewChan = cfaToPreview[pi];
            PreviewReferenceExtractor.Reference ref =
                    PreviewReferenceExtractor.extract(f, previewChan, w, h);
            byte[] payload = SubbandExtractor.read(f, planes[pi]);
            int[] dec = decode(payload, w, h, bd, midgrey, 0, LeadInit.LEAD_PREDINIT, 0);
            if (dec.length < w * h) continue;

            double rRaw = pearson(dec, ref.pixels());
            double rSignFix = Math.abs(rRaw);

            // Sweep additive bias for documentation. Pearson r is invariant
            // under translation; this run confirms the metric is correctly
            // implemented (and that no clipping artefacts skew the result).
            int bestBias = 0;
            double bestRAtBias = Math.abs(rRaw);
            for (int b : biases) {
                int[] biased = new int[dec.length];
                int max = (1 << bd) - 1;
                for (int i = 0; i < dec.length; i++) {
                    int v = dec[i] + b;
                    if (v < 0) v = 0; else if (v > max) v = max;
                    biased[i] = v;
                }
                double r = Math.abs(pearson(biased, ref.pixels()));
                if (r > bestRAtBias) { bestRAtBias = r; bestBias = b; }
            }

            System.out.printf("%-6s p%-2d %+12.4f %+12.4f %14d %+12.4f%n",
                    tileTag, pi, rRaw, rSignFix, bestBias, bestRAtBias);
            String variant = "polarityBias";
            tsv.printf("%s\t%s\tp%d\t%s\tbayer_%s_R_raw\t%.6f%n",
                    section, tileTag, pi, variant, chanName[previewChan], rRaw);
            tsv.printf("%s\t%s\tp%d\t%s\tbayer_%s_absR_signFix\t%.6f%n",
                    section, tileTag, pi, variant, chanName[previewChan], rSignFix);
            tsv.printf("%s\t%s\tp%d\t%s\tbestBias\t%d%n",
                    section, tileTag, pi, variant, bestBias);
            tsv.printf("%s\t%s\tp%d\t%s\tabsR_atBestBias\t%.6f%n",
                    section, tileTag, pi, variant, bestRAtBias);
        }
    }

    // ============================================================ tilewise winners

    private static void summariseTilewiseWinners() throws IOException {
        Path tsvPath = OUT_DIR.resolve("metrics.tsv");
        if (!Files.exists(tsvPath)) return;
        List<String> lines = Files.readAllLines(tsvPath);

        // Map: (section, tile) → list of (|r|, signed r, label)
        Map<String, List<double[]>> bins = new LinkedHashMap<>();
        Map<String, List<String>> binLabels = new LinkedHashMap<>();

        for (String l : lines) {
            String[] f = l.split("\t");
            if (f.length != 6) continue;
            if (!f[4].startsWith("bayer_") || !f[4].endsWith("_R")) continue;
            try {
                double v = Double.parseDouble(f[5]);
                String key = f[0] + "/" + f[1];   // e.g. "E/tile0"
                bins.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new double[]{Math.abs(v), v});
                binLabels.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(f[2] + "/" + f[3] + "/" + f[4]);
            } catch (NumberFormatException ignored) { }
        }

        try (PrintWriter pw = new PrintWriter(OUT_DIR.resolve("winners.txt").toFile())) {
            pw.println("Round-4 winners — tilewise top 5 per section (by |bayerR|)");
            pw.println();
            // sort keys for stable output: section first, then tile
            List<String> keys = new ArrayList<>(bins.keySet());
            keys.sort(Comparator.naturalOrder());
            for (String key : keys) {
                List<double[]> values = bins.get(key);
                List<String> labels = binLabels.get(key);
                Integer[] idx = new Integer[values.size()];
                for (int i = 0; i < idx.length; i++) idx[i] = i;
                Arrays.sort(idx, (a, b) -> Double.compare(values.get(b)[0], values.get(a)[0]));
                pw.println("=== " + key + " — top 5 by |bayerR| ===");
                System.out.println();
                System.out.println("=== " + key + " — top 5 by |bayerR| ===");
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

    // ============================================================ heatmap export

    /** Export per-tile PGM heatmaps for plane 0 under the round-3 baseline. */
    private static void exportTilewiseHeatmaps(File f, SubbandExtractor.SubbandRef[] planes, String tileTag)
            throws IOException {
        int w = planes[0].planeWidth();
        int h = planes[0].planeHeight();
        int bd = planes[0].bitDepth();
        int midgrey = 1 << (bd - 1);
        byte[] payload = SubbandExtractor.read(f, planes[0]);

        Object[][] choices = {
                {"E_LEAD_PREDINIT", LeadInit.LEAD_PREDINIT, 0},
                {"E_LEAD_ZERO", LeadInit.LEAD_ZERO, 0},
                {"F_warmup8", LeadInit.LEAD_PREDINIT, 8},
        };
        for (Object[] c : choices) {
            String name = (String) c[0];
            LeadInit li = (LeadInit) c[1];
            int warmup = (int) c[2];
            int[] dec = decode(payload, w, h, bd, midgrey, 0, li, warmup);
            if (dec.length < w * h) continue;
            writePgm(OUT_DIR.resolve(tileTag + "_p0_" + name + ".pgm").toFile(), dec, w, h, bd);
        }
    }

    private static void writePgm(File out, int[] pixels, int w, int h, int bd) throws IOException {
        int max = (1 << bd) - 1;
        byte[] buf = new byte[w * h];
        for (int i = 0; i < buf.length; i++) {
            int v = pixels[i];
            if (v < 0) v = 0; else if (v > max) v = max;
            buf[i] = (byte) ((v * 255) / max);
        }
        try (var os = new java.io.FileOutputStream(out)) {
            String header = "P5\n" + w + " " + h + "\n255\n";
            os.write(header.getBytes());
            os.write(buf);
        }
    }

    // ============================================================ decoder

    /**
     * P_LEFT_BASE row-major decoder with two new knobs:
     *   - leadInit:  overrides the very first pixel's predictor / residual sign
     *   - warmupN:   first {@code warmupN} samples of each row use predInit
     *                instead of the raster-left pixel
     */
    private static int[] decode(byte[] payload, int w, int h, int bd,
                                int predInit, int kInit, LeadInit leadInit, int warmupN) {
        try { return decodeImpl(payload, w, h, bd, predInit, kInit, leadInit, warmupN); }
        catch (RuntimeException | IOException e) { return new int[0]; }
    }

    private static int[] decodeImpl(byte[] payload, int w, int h, int bd,
                                    int predInit, int kInit, LeadInit leadInit, int warmupN)
            throws IOException {
        int[] result = new int[w * h];
        int maxVal = (1 << bd) - 1;
        BitReader reader = new BitReader(payload, 0, payload.length);
        int k = Math.max(0, Math.min(bd, kInit));

        // First pixel handled specially per leadInit. We always still consume
        // one residual from the bitstream so subsequent state is consistent
        // with the round-3 baseline.
        if (!reader.hasMore()) return new int[0];
        int residual0 = decodeRice(reader, k);
        int signed0 = mapSignLsb(residual0);
        int predicted0;
        switch (leadInit) {
            case LEAD_ZERO -> predicted0 = -signed0;     // result[0] becomes 0
            case LEAD_PREDINIT -> predicted0 = predInit;
            case LEAD_BIAS_HI -> predicted0 = predInit + 1024;
            case LEAD_BIAS_LO -> predicted0 = predInit - 1024;
            case LEAD_NEG_FIRST -> { predicted0 = predInit; signed0 = -signed0; }
            case LEAD_COPY_NEXT -> predicted0 = predInit;  // patched after second sample below
            default -> predicted0 = predInit;
        }
        int pixel0 = predicted0 + signed0;
        if (pixel0 < 0) pixel0 = 0;
        else if (pixel0 > maxVal) pixel0 = maxVal;
        result[0] = pixel0;
        // K-update after first sample
        k = updateK(k, residual0, bd);

        for (int n = 1; n < w * h; n++) {
            if (!reader.hasMore()) return Arrays.copyOf(result, n);
            int residual = decodeRice(reader, k);
            int signed = mapSignLsb(residual);
            int x = n % w;
            int y = n / w;

            int predicted;
            // Warmup-prefix override: first warmupN samples of each row use
            // predInit instead of raster-left.
            if (warmupN > 0 && x < warmupN) {
                predicted = predInit;
            } else if (x == 0) {
                // Standard P_LEFT_BASE left-edge fallback (pixel above).
                predicted = result[(y - 1) * w + x];
            } else {
                predicted = result[y * w + x - 1];
            }

            int pixel = predicted + signed;
            if (pixel < 0) pixel = 0;
            else if (pixel > maxVal) pixel = maxVal;
            result[n] = pixel;

            // LEAD_COPY_NEXT: after second sample is decoded, patch pixel0.
            if (n == 1 && leadInit == LeadInit.LEAD_COPY_NEXT) {
                result[0] = result[1];
            }

            k = updateK(k, residual, bd);
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

    private static double mean(int[] a) {
        if (a.length == 0) return 0;
        double s = 0;
        for (int v : a) s += v;
        return s / a.length;
    }

    private static double std(int[] a, double mean) {
        if (a.length == 0) return 0;
        double s = 0;
        for (int v : a) {
            double d = v - mean;
            s += d * d;
        }
        return Math.sqrt(s / a.length);
    }

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
