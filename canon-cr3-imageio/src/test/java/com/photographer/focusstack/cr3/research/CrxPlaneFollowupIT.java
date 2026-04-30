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
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Follow-up diagnostic experiments after {@link CrxPlaneSemanticsIT} (Update e).
 *
 * <p>This IT lives entirely in test scope. <em>No production code is modified,
 * no hypothesis is promoted, no flag is added.</em> The IT always succeeds —
 * the goal is artefact production for the next session. Output dir:
 * {@code canon-cr3-imageio/target/crx-followup/}.</p>
 *
 * <p>Three experiments, each named and evaluated in isolation, addressing the
 * three open questions from {@code SubbandFixtures.md} Update (e):</p>
 *
 * <ul>
 *   <li><b>Exp 1 — Predictor-zero baseline.</b> The patent LL/MED decode of
 *       sample.cr3 plane 0 has left-neighbour Pearson r ≈ 0.63. Is that real
 *       2D image structure, or is it an artefact of the predictor cascade
 *       (each pixel is the median of its causal neighbours plus a small
 *       residual; that alone produces high correlation)? We re-run the
 *       decode with every residual forced to zero — the result is purely the
 *       predictor cascade, with no input from the bitstream payload — and
 *       compare neighbour-correlation. If the corr stays high → 0.63 is a
 *       predictor artefact; if it drops to ~0 → real residuals carry the
 *       signal and the bottleneck is downstream (output transform / sign
 *       convention / spatial layout).</li>
 *   <li><b>Exp 2 — Half-pixel scanline interleave.</b> Some lossless RAW
 *       codecs encode rows in two passes: first all even rows, then all odd
 *       rows ("row_eo"); or interleave even/odd row by row ("row_pingpong").
 *       We re-run the LL decode under each layout and score the resulting
 *       plane against the matching preview Bayer channel.</li>
 *   <li><b>Exp 3 — Tile-1 comparison.</b> sample.cr3 has 2 tiles. Repeat the
 *       LL decode + plane-as-Bayer-channel scoring on tile 1 (subbands 4–7)
 *       and compare with tile 0. If statistics + scores mirror tile 0
 *       closely → predictor cascade is dominating the output (real signal
 *       isn't getting through); if they differ sharply → the codec succeeds
 *       per-tile and the bottleneck is tile-stitching.</li>
 * </ul>
 *
 * <p>Numbers (per-plane neighCorr deltas, per-plane Pearson r) are written
 * to {@code target/crx-followup/metrics.tsv} and to stdout for inclusion in
 * {@code SubbandFixtures.md}.</p>
 */
class CrxPlaneFollowupIT {

    private static final String SAMPLE = "src/test/resources/samples/sample.cr3";
    private static final Path OUT_DIR = Path.of("target", "crx-followup");

    @Test
    void runFollowupExperiments() throws Exception {
        File f = new File(SAMPLE);
        assertThat(f).exists();
        Files.createDirectories(OUT_DIR);

        Cmp1Box cmp1 = readCmp1(f);
        assertThat(cmp1).as("CMP1 box").isNotNull();
        int[] cfaToPreview = cfaLayoutMap(cmp1.cfaLayout());
        String[] chanName = {"R", "Gr", "Gb", "B"};

        List<SubbandExtractor.SubbandRef> subs = SubbandExtractor.list(f);
        assertThat(subs).as("subbands").hasSizeGreaterThanOrEqualTo(4);

        try (PrintWriter tsv = new PrintWriter(OUT_DIR.resolve("metrics.tsv").toFile())) {
            tsv.println("section\tlabel\tmetric\tvalue");

            // ============================================================
            // Tile 0 (subs 0..3)
            // ============================================================
            SubbandExtractor.SubbandRef[] t0 = {
                    subs.get(0), subs.get(1), subs.get(2), subs.get(3)
            };
            runOneTile(f, t0, "tile0", cfaToPreview, chanName, cmp1, tsv);

            // ============================================================
            // Exp 3 — Tile 1 (subs 4..7) if available
            // ============================================================
            if (subs.size() >= 8) {
                SubbandExtractor.SubbandRef[] t1 = {
                        subs.get(4), subs.get(5), subs.get(6), subs.get(7)
                };
                System.out.printf("%n=== Exp 3 — Tile-1 comparison ===%n");
                runOneTile(f, t1, "tile1", cfaToPreview, chanName, cmp1, tsv);
                System.out.println();
                System.out.println("→ Compare tile0 vs tile1 rows in metrics.tsv. If neighCorr,");
                System.out.println("  mean and per-channel r are within ~10 % of each other, the");
                System.out.println("  predictor cascade is dominating the output (real residual");
                System.out.println("  signal is being washed out). If tile1 scores noticeably");
                System.out.println("  higher or lower per-channel r, tile-stitching alignment is");
                System.out.println("  the issue.");
            } else {
                System.out.printf("%n=== Exp 3 — Tile-1 comparison: SKIPPED (only %d subbands) ===%n",
                        subs.size());
                tsv.printf("Exp3\ttile1\tstatus\tSKIPPED_only_%d_subbands%n", subs.size());
            }
        }

        assertThat(OUT_DIR.resolve("metrics.tsv")).exists();
    }

    // ---------------------------------------------------------------- per-tile pipeline

    private static void runOneTile(File f, SubbandExtractor.SubbandRef[] planes,
                                   String tileTag, int[] cfaToPreview, String[] chanName,
                                   Cmp1Box cmp1, PrintWriter tsv) throws IOException {
        int w = planes[0].planeWidth();
        int h = planes[0].planeHeight();
        int bd = planes[0].bitDepth();
        int q  = planes[0].quantValue();
        System.out.printf("%n--- %s : %dx%d, %d-bit, q=%d, cfaLayout=%d (%s) ---%n",
                tileTag, w, h, bd, q, cmp1.cfaLayout(), cmp1.cfaLayoutName());

        byte[][] payload = new byte[4][];
        for (int i = 0; i < 4; i++) payload[i] = SubbandExtractor.read(f, planes[i]);

        // ============================================================
        // Exp 1 — Predictor-zero baseline (per plane)
        // ============================================================
        // Run the patent LL/MED decode twice for each plane:
        //   (a) "real"  : residuals decoded from the bitstream as usual
        //   (b) "zero"  : every signed residual forced to 0 (pure predictor cascade)
        // Compare left-neighbour 2D Pearson r and per-pixel mean: if they're
        // nearly identical, the LL decode's high neighbour correlation is a
        // predictor artefact, NOT real bitstream signal.
        System.out.printf("%n=== Exp 1 — Predictor-zero baseline (%s) ===%n", tileTag);
        System.out.printf("%-10s %12s %12s %12s %12s %12s %12s %12s%n",
                "plane",
                "real_neighR", "zero_neighR", "delta",
                "real_mean",   "zero_mean",
                "real_var",    "zero_var");
        for (int pi = 0; pi < 4; pi++) {
            int[] real = decodePatentLL(payload[pi], w, h, bd, /*forceResidualZero=*/false);
            int[] zero = decodePatentLL(payload[pi], w, h, bd, /*forceResidualZero=*/true);
            double rReal = neighbourCorr2D(real, w, h);
            double rZero = neighbourCorr2D(zero, w, h);
            double mReal = mean(real);
            double mZero = mean(zero);
            double vReal = variance(real, mReal);
            double vZero = variance(zero, mZero);
            System.out.printf("p%-9d %12.4f %12.4f %12.4f %12.2f %12.2f %12.2f %12.2f%n",
                    pi, rReal, rZero, rReal - rZero,
                    mReal, mZero, vReal, vZero);
            tsv.printf("Exp1\t%s.p%d\treal_neighR\t%.6f%n", tileTag, pi, rReal);
            tsv.printf("Exp1\t%s.p%d\tzero_neighR\t%.6f%n", tileTag, pi, rZero);
            tsv.printf("Exp1\t%s.p%d\tdelta_neighR\t%.6f%n", tileTag, pi, rReal - rZero);
            tsv.printf("Exp1\t%s.p%d\treal_mean\t%.4f%n", tileTag, pi, mReal);
            tsv.printf("Exp1\t%s.p%d\tzero_mean\t%.4f%n", tileTag, pi, mZero);
            tsv.printf("Exp1\t%s.p%d\treal_variance\t%.4f%n", tileTag, pi, vReal);
            tsv.printf("Exp1\t%s.p%d\tzero_variance\t%.4f%n", tileTag, pi, vZero);

            if (pi == 0) {
                writeHeatmapPgm(OUT_DIR.resolve(tileTag + "_Exp1_p0_real.pgm"), real, w, h);
                writeHeatmapPgm(OUT_DIR.resolve(tileTag + "_Exp1_p0_zero.pgm"), zero, w, h);
            }
        }

        // ============================================================
        // Exp 2 — Half-pixel scanline interleave (per plane)
        // ============================================================
        // The decoder produces samples in raster order. We try two row layouts:
        //   "row_eo":      first h/2 decoded rows  → all even rows  (0,2,4,...);
        //                  next  h/2 decoded rows  → all odd  rows  (1,3,5,...).
        //   "row_pingpong": for each output row r, use decoded row r (baseline,
        //                  for cross-check that the score helper agrees).
        // Compare per-plane Pearson r vs the matching Bayer channel against the
        // raster-order baseline already measured in CrxPlaneSemanticsIT.
        System.out.printf("%n=== Exp 2 — Half-pixel scanline interleave (%s) ===%n", tileTag);
        System.out.printf("%-22s %12s %12s%n", "label", "pearsonR", "meanAbsErr");
        for (int pi = 0; pi < 4; pi++) {
            int previewChan = cfaToPreview[pi];
            int[] decRaster = decodePatentLL(payload[pi], w, h, bd, false);

            // Baseline: raster (matches CrxPlaneSemanticsIT D.bayer)
            ScoredStats sBase = scoreVsPreview(f, previewChan, decRaster, w, h);
            String labelBase = tileTag + "/p" + pi + "→" + chanName[previewChan] + ":raster";
            System.out.printf("%-22s %12.4f %12.2f%n", labelBase, sBase.r, sBase.mae);
            tsv.printf("Exp2\t%s.p%d\traster_pearsonR\t%.6f%n", tileTag, pi, sBase.r);

            // row_eo layout
            int[] decEo = remapRowsEvenOdd(decRaster, w, h);
            ScoredStats sEo = scoreVsPreview(f, previewChan, decEo, w, h);
            String labelEo = tileTag + "/p" + pi + "→" + chanName[previewChan] + ":row_eo";
            System.out.printf("%-22s %12.4f %12.2f%n", labelEo, sEo.r, sEo.mae);
            tsv.printf("Exp2\t%s.p%d\trow_eo_pearsonR\t%.6f%n", tileTag, pi, sEo.r);

            // column_eo layout (also test column-interleave for completeness)
            int[] decColEo = remapColsEvenOdd(decRaster, w, h);
            ScoredStats sColEo = scoreVsPreview(f, previewChan, decColEo, w, h);
            String labelColEo = tileTag + "/p" + pi + "→" + chanName[previewChan] + ":col_eo";
            System.out.printf("%-22s %12.4f %12.2f%n", labelColEo, sColEo.r, sColEo.mae);
            tsv.printf("Exp2\t%s.p%d\tcol_eo_pearsonR\t%.6f%n", tileTag, pi, sColEo.r);

            if (pi == 0) {
                writeHeatmapPgm(OUT_DIR.resolve(tileTag + "_Exp2_p0_row_eo.pgm"), decEo, w, h);
                writeHeatmapPgm(OUT_DIR.resolve(tileTag + "_Exp2_p0_col_eo.pgm"), decColEo, w, h);
            }
        }
    }

    // ---------------------------------------------------------------- decoders

    /**
     * Patent-faithful LL/MED decoder with optional residual-zeroing.
     * If {@code forceResidualZero} is true, every signed residual is set to
     * zero — the output is purely the predictor cascade output, with no
     * influence from the bitstream payload bytes (other than terminating the
     * loop when the bit reader runs out).
     */
    private static int[] decodePatentLL(byte[] payload, int w, int h, int bd,
                                        boolean forceResidualZero) {
        try { return decodePatentLLImpl(payload, w, h, bd, forceResidualZero); }
        catch (RuntimeException | IOException e) { return new int[0]; }
    }

    private static int[] decodePatentLLImpl(byte[] payload, int w, int h, int bd,
                                            boolean forceResidualZero) throws IOException {
        int[] result = new int[w * h];
        int maxVal = (1 << bd) - 1;
        BitReader reader = new BitReader(payload, 0, payload.length);
        int k = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!reader.hasMore()) return Arrays.copyOf(result, y * w + x);
                int residual = decodeRice(reader, k);
                int signed;
                if (forceResidualZero) {
                    signed = 0;
                } else {
                    signed = ((residual & 1) == 0) ? (residual >> 1) : (-(residual >> 1) - 1);
                }
                int predicted = predictMed(result, w, x, y, bd);
                int pixel = predicted + signed;
                if (pixel < 0) pixel = 0;
                else if (pixel > maxVal) pixel = maxVal;
                result[y * w + x] = pixel;
                int sn = residual;
                int kStar = (sn <= 0) ? 0 : 31 - Integer.numberOfLeadingZeros(sn + 1);
                int kMin = Math.max(0, kStar - 1);
                int kMax = Math.min(bd, kStar + 1);
                if (k < kMin) k++;
                else if (k > kMax) k--;
            }
        }
        return result;
    }

    private static int predictMed(int[] r, int w, int x, int y, int bd) {
        if (x == 0 && y == 0) return 1 << (bd - 1);
        if (y == 0) return r[x - 1];
        if (x == 0) return r[(y - 1) * w];
        int a = r[y * w + x - 1];
        int b = r[(y - 1) * w + x];
        int c = r[(y - 1) * w + x - 1];
        if (c >= Math.max(a, b)) return Math.min(a, b);
        else if (c <= Math.min(a, b)) return Math.max(a, b);
        else return a + b - c;
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

    // ---------------------------------------------------------------- layout remaps

    /** Treat the decoded raster as: first h/2 rows = even output rows, next h/2 = odd. */
    private static int[] remapRowsEvenOdd(int[] raster, int w, int h) {
        if (raster.length < w * h) return raster;
        int[] out = new int[w * h];
        int half = h / 2;
        for (int y = 0; y < h; y++) {
            int srcRow = (y % 2 == 0) ? (y / 2) : (half + y / 2);
            System.arraycopy(raster, srcRow * w, out, y * w, w);
        }
        return out;
    }

    /** Treat each row's first w/2 samples as even cols, next w/2 as odd cols. */
    private static int[] remapColsEvenOdd(int[] raster, int w, int h) {
        if (raster.length < w * h) return raster;
        int[] out = new int[w * h];
        int half = w / 2;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int srcCol = (x % 2 == 0) ? (x / 2) : (half + x / 2);
                out[y * w + x] = raster[y * w + srcCol];
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- preview scoring

    private static ScoredStats scoreVsPreview(File f, int previewChan,
                                              int[] dec, int w, int h) throws IOException {
        if (dec.length < w * h) {
            // partial decode — score on what we have
            int n = dec.length;
            int hh = n / w;
            if (hh < 2) return new ScoredStats(Double.NaN, Double.NaN);
            PreviewReferenceExtractor.Reference ref =
                    PreviewReferenceExtractor.extract(f, previewChan, w, hh);
            return score(dec, ref.pixels());
        }
        PreviewReferenceExtractor.Reference ref =
                PreviewReferenceExtractor.extract(f, previewChan, w, h);
        return score(dec, ref.pixels());
    }

    private record ScoredStats(double r, double mae) {}

    private static ScoredStats score(int[] cand, int[] ref) {
        int n = Math.min(cand.length, ref.length);
        if (n < 2) return new ScoredStats(Double.NaN, Double.NaN);
        int mn = Integer.MAX_VALUE, mx = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            int v = cand[i];
            if (v < mn) mn = v;
            if (v > mx) mx = v;
        }
        int range = Math.max(1, mx - mn);
        double sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0;
        long sum = 0;
        for (int i = 0; i < n; i++) {
            double a = (cand[i] - mn) * 255.0 / range;
            double b = ref[i];
            sa += a; sb += b;
            saa += a * a; sbb += b * b;
            sab += a * b;
            sum += Math.abs((int) Math.round(a) - ref[i]);
        }
        double ma = sa / n, mb = sb / n;
        double cov = sab / n - ma * mb;
        double va = saa / n - ma * ma;
        double vb = sbb / n - mb * mb;
        double denom = Math.sqrt(Math.max(0, va) * Math.max(0, vb));
        return new ScoredStats(denom < 1e-9 ? 0 : cov / denom, (double) sum / n);
    }

    // ---------------------------------------------------------------- stats helpers

    private static double mean(int[] a) {
        if (a.length == 0) return 0;
        double s = 0;
        for (int v : a) s += v;
        return s / a.length;
    }

    private static double variance(int[] a, double m) {
        if (a.length == 0) return 0;
        double s = 0;
        for (int v : a) {
            double d = v - m;
            s += d * d;
        }
        return s / a.length;
    }

    private static double neighbourCorr2D(int[] arr, int w, int h) {
        if (arr.length < w * h) return 0;
        int count = 0;
        double sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 1; x < w; x++) {
                double a = arr[y * w + x];
                double b = arr[y * w + x - 1];
                sa += a; sb += b;
                saa += a * a; sbb += b * b;
                sab += a * b;
                count++;
            }
        }
        if (count == 0) return 0;
        double ma = sa / count, mb = sb / count;
        double cov = sab / count - ma * mb;
        double va = saa / count - ma * ma;
        double vb = sbb / count - mb * mb;
        double denom = Math.sqrt(Math.max(0, va) * Math.max(0, vb));
        return denom < 1e-9 ? 0 : cov / denom;
    }

    // ---------------------------------------------------------------- misc

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

    private static void writeHeatmapPgm(Path path, int[] arr, int w, int h)
            throws IOException {
        if (arr.length < w * h) return;
        int stride = Math.max(1, Math.max(w, h) / 1024);
        int dw = w / stride;
        int dh = h / stride;
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int y = 0; y < dh; y++) {
            for (int x = 0; x < dw; x++) {
                int v = arr[(y * stride) * w + x * stride];
                if (v < min) min = v;
                if (v > max) max = v;
            }
        }
        int range = Math.max(1, max - min);
        try (var os = new java.io.BufferedOutputStream(Files.newOutputStream(path))) {
            os.write(("P5\n" + dw + " " + dh + "\n255\n").getBytes());
            for (int y = 0; y < dh; y++) {
                for (int x = 0; x < dw; x++) {
                    int v = arr[(y * stride) * w + x * stride];
                    int qv = (int) ((long) (v - min) * 255L / range);
                    os.write(Math.max(0, Math.min(255, qv)));
                }
            }
        }
    }
}
