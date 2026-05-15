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
 * Systematic extension of {@link CrxPlaneFollowupIT}, per the new requirement
 * (2026-04-30, after the baseline confirmed: predictor cascade alone produces
 * ZERO 2D structure → real residuals carry signal; tile-stitching is NOT the
 * bottleneck). All work strictly in test scope. <em>No production code is
 * modified, no flag is added, no hypothesis is promoted.</em>
 *
 * <p>Three experiment groups, each a clean Cartesian sweep, with each variant
 * named explicitly. Output dir: {@code canon-cr3-imageio/target/crx-followup-ext/}.</p>
 *
 * <h3>Exp 1 ext — predictor × sign × inverse-mapping sweep</h3>
 * <ul>
 *   <li><b>Predictors</b>: P_NONE (constant midgrey), P_LEFT (previous-left),
 *       P_UP (previous-up), P_MED (patent JPEG-LS MED, baseline).</li>
 *   <li><b>Sign mappings</b> (unsigned residual {@code s} → signed difference):
 *       <ul>
 *         <li>S_LSB           : {@code (s&1)==0 ? s>>1 : -((s>>1)+1)} — current/baseline.</li>
 *         <li>S_LSB_ALT       : {@code (s&1)==0 ? s>>1 : -(s>>1)} — alt off-by-one.</li>
 *         <li>S_HI_BIT_SIGN   : sign from MSB; magnitude is the lower bits.</li>
 *         <li>S_RAW_POS       : keep s as-is (positive only).</li>
 *         <li>S_RAW_NEG       : negate s.</li>
 *         <li>S_OFFSET_HALF   : {@code s - midgrey} (no sign mapping at all).</li>
 *         <li>S_ZIGZAG_INV_LSB: alternative zig-zag inverse: even maps to {@code (s+1)>>1},
 *             odd to negative {@code (s+1)>>1}.</li>
 *       </ul></li>
 *   <li>Per (predictor, sign, plane, tile): output mean, variance, left-neighbour
 *       Pearson r, and Pearson r vs the matching preview Bayer channel.</li>
 * </ul>
 *
 * <h3>Exp 2 ext — Bayer permutations + spatial shifts</h3>
 * <ul>
 *   <li>All 4 cyclic plane→Bayer-channel mappings (RGGB, GRBG=baseline, GBRG, BGGR)
 *       — score each plane vs the preview channel implied by that mapping.</li>
 *   <li>Spatial shift ±1 row, ±1 column (4 variants + identity = 5 per plane).</li>
 * </ul>
 *
 * <h3>Exp 3 ext — tile-local reset/init</h3>
 * <ul>
 *   <li><b>predictorInit</b> ∈ {0, midgrey=2^(bd-1), max=2^bd-1} (3 variants).</li>
 *   <li><b>kInit</b> ∈ {0, 4, 7} (3 variants).</li>
 *   <li>9 combinations × 4 planes × 2 tiles = 72 rows. Per combination:
 *       Pearson r vs matching Bayer channel + neighCorr + decoded length.</li>
 * </ul>
 *
 * <p>The test always succeeds — its purpose is artefact production for
 * {@code SubbandFixtures.md} Update (f) and analysis-doc §5.6.</p>
 */
class CrxPlaneFollowupExtendedIT {

    private static final String SAMPLE = "src/test/resources/samples/sample.cr3";
    private static final Path OUT_DIR = Path.of("target", "crx-followup-ext");

    private enum Predictor { P_NONE, P_LEFT, P_UP, P_MED }

    private enum SignMap {
        S_LSB, S_LSB_ALT, S_HI_BIT_SIGN, S_RAW_POS, S_RAW_NEG, S_OFFSET_HALF, S_ZIGZAG_INV_LSB
    }

    @Test
    void runExtendedSweep() throws Exception {
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

        try (PrintWriter tsv = new PrintWriter(OUT_DIR.resolve("metrics.tsv").toFile())) {
            tsv.println("section\ttile\tplane\tvariant\tmetric\tvalue");

            // ------------------ Exp 1 ext ------------------
            System.out.printf("%n=== Exp 1 ext — predictor × sign sweep ===%n");
            System.out.printf("%-6s %-3s %-12s %-18s %12s %12s %12s %12s%n",
                    "tile", "p", "predictor", "sign",
                    "outMean", "outVar", "neighR", "bayerR");
            sweepPredictorSign(f, tile0, "tile0", cfaToPreview, chanName, tsv);
            if (tile1 != null) {
                sweepPredictorSign(f, tile1, "tile1", cfaToPreview, chanName, tsv);
            }

            // ------------------ Exp 2 ext ------------------
            System.out.printf("%n=== Exp 2 ext — Bayer permutations + spatial shifts ===%n");
            System.out.printf("%-6s %-3s %-14s %-12s %12s%n",
                    "tile", "p", "cfaMap", "shift", "bayerR");
            sweepBayerShifts(f, tile0, "tile0", chanName, tsv);
            if (tile1 != null) {
                sweepBayerShifts(f, tile1, "tile1", chanName, tsv);
            }

            // ------------------ Exp 3 ext ------------------
            System.out.printf("%n=== Exp 3 ext — tile-local reset/init sweep ===%n");
            System.out.printf("%-6s %-3s %-12s %-8s %12s %12s %10s%n",
                    "tile", "p", "predInit", "kInit", "neighR", "bayerR", "outLen");
            sweepInits(f, tile0, "tile0", cfaToPreview, chanName, tsv);
            if (tile1 != null) {
                sweepInits(f, tile1, "tile1", cfaToPreview, chanName, tsv);
            }
        }

        // ------------------ Top winners summary ------------------
        summariseWinners();

        assertThat(OUT_DIR.resolve("metrics.tsv")).exists();
    }

    // ============================================================ Exp 1 ext

    private static void sweepPredictorSign(File f, SubbandExtractor.SubbandRef[] planes,
                                           String tileTag, int[] cfaToPreview,
                                           String[] chanName, PrintWriter tsv) throws IOException {
        int w = planes[0].planeWidth();
        int h = planes[0].planeHeight();
        int bd = planes[0].bitDepth();

        byte[][] payload = new byte[4][];
        for (int i = 0; i < 4; i++) payload[i] = SubbandExtractor.read(f, planes[i]);

        for (int pi = 0; pi < 4; pi++) {
            int previewChan = cfaToPreview[pi];
            PreviewReferenceExtractor.Reference ref =
                    PreviewReferenceExtractor.extract(f, previewChan, w, h);
            for (Predictor pr : Predictor.values()) {
                for (SignMap sm : SignMap.values()) {
                    int[] dec = decodeWithVariant(payload[pi], w, h, bd, pr, sm,
                            /*predInit*/ 1 << (bd - 1), /*kInit*/ 0);
                    if (dec.length < w * h) continue;
                    double m = mean(dec);
                    double v = variance(dec, m);
                    double nc = neighbourCorr2D(dec, w, h);
                    double br = pearson(dec, ref.pixels());
                    String variant = pr + "/" + sm;
                    System.out.printf("%-6s p%-2d %-12s %-18s %12.2f %12.2f %12.4f %12.4f%n",
                            tileTag, pi, pr, sm, m, v, nc, br);
                    String row = String.format(
                            "Exp1ext\t%s\tp%d\t%s\toutMean\t%.4f%n"
                          + "Exp1ext\t%s\tp%d\t%s\toutVariance\t%.4f%n"
                          + "Exp1ext\t%s\tp%d\t%s\tneighR\t%.6f%n"
                          + "Exp1ext\t%s\tp%d\t%s\tbayer_%s_R\t%.6f%n",
                            tileTag, pi, variant, m,
                            tileTag, pi, variant, v,
                            tileTag, pi, variant, nc,
                            tileTag, pi, variant, chanName[previewChan], br);
                    tsv.print(row);
                }
            }
        }
    }

    // ============================================================ Exp 2 ext

    private static void sweepBayerShifts(File f, SubbandExtractor.SubbandRef[] planes,
                                         String tileTag, String[] chanName,
                                         PrintWriter tsv) throws IOException {
        int w = planes[0].planeWidth();
        int h = planes[0].planeHeight();
        int bd = planes[0].bitDepth();

        byte[][] payload = new byte[4][];
        for (int i = 0; i < 4; i++) payload[i] = SubbandExtractor.read(f, planes[i]);

        // Decode each plane once with the patent baseline (P_MED + S_LSB)
        int[][] dec = new int[4][];
        for (int pi = 0; pi < 4; pi++) {
            dec[pi] = decodeWithVariant(payload[pi], w, h, bd,
                    Predictor.P_MED, SignMap.S_LSB, 1 << (bd - 1), 0);
        }

        // Try all 4 cyclic CFA maps as scoring lookup × 5 spatial shifts (incl. identity)
        int[][] cfaMaps = {
                {0, 1, 2, 3},   // RGGB
                {1, 0, 3, 2},   // GRBG (baseline)
                {2, 3, 0, 1},   // GBRG
                {3, 2, 1, 0},   // BGGR
        };
        String[] cfaNames = {"RGGB", "GRBG", "GBRG", "BGGR"};
        int[][] shifts = {
                {0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}
        };
        String[] shiftNames = {"identity", "shift_x+1", "shift_x-1", "shift_y+1", "shift_y-1"};

        for (int pi = 0; pi < 4; pi++) {
            if (dec[pi].length < w * h) continue;
            for (int ci = 0; ci < cfaMaps.length; ci++) {
                int previewChan = cfaMaps[ci][pi];
                PreviewReferenceExtractor.Reference ref =
                        PreviewReferenceExtractor.extract(f, previewChan, w, h);
                for (int si = 0; si < shifts.length; si++) {
                    int[] shifted = applyShift(dec[pi], w, h, shifts[si][0], shifts[si][1]);
                    double br = pearson(shifted, ref.pixels());
                    System.out.printf("%-6s p%-2d %-14s %-12s %12.4f%n",
                            tileTag, pi,
                            cfaNames[ci] + "→" + chanName[previewChan],
                            shiftNames[si], br);
                    String variant = cfaNames[ci] + "_" + shiftNames[si];
                    tsv.printf("Exp2ext\t%s\tp%d\t%s\tbayer_%s_R\t%.6f%n",
                            tileTag, pi, variant, chanName[previewChan], br);
                }
            }
        }
    }

    private static int[] applyShift(int[] arr, int w, int h, int dx, int dy) {
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            int sy = y - dy;
            if (sy < 0 || sy >= h) continue;
            for (int x = 0; x < w; x++) {
                int sx = x - dx;
                if (sx < 0 || sx >= w) continue;
                out[y * w + x] = arr[sy * w + sx];
            }
        }
        return out;
    }

    // ============================================================ Exp 3 ext

    private static void sweepInits(File f, SubbandExtractor.SubbandRef[] planes,
                                   String tileTag, int[] cfaToPreview,
                                   String[] chanName, PrintWriter tsv) throws IOException {
        int w = planes[0].planeWidth();
        int h = planes[0].planeHeight();
        int bd = planes[0].bitDepth();
        int midgrey = 1 << (bd - 1);
        int maxval = (1 << bd) - 1;

        byte[][] payload = new byte[4][];
        for (int i = 0; i < 4; i++) payload[i] = SubbandExtractor.read(f, planes[i]);

        int[] predInits = {0, midgrey, maxval};
        String[] predInitNames = {"0", "midgrey", "max"};
        int[] kInits = {0, 4, 7};

        for (int pi = 0; pi < 4; pi++) {
            int previewChan = cfaToPreview[pi];
            PreviewReferenceExtractor.Reference ref =
                    PreviewReferenceExtractor.extract(f, previewChan, w, h);
            for (int piIdx = 0; piIdx < predInits.length; piIdx++) {
                for (int kInit : kInits) {
                    int[] dec = decodeWithVariant(payload[pi], w, h, bd,
                            Predictor.P_MED, SignMap.S_LSB, predInits[piIdx], kInit);
                    int outLen = dec.length;
                    double nc = (outLen >= w * h) ? neighbourCorr2D(dec, w, h) : 0;
                    double br = (outLen >= w * h) ? pearson(dec, ref.pixels()) : 0;
                    System.out.printf("%-6s p%-2d %-12s %-8d %12.4f %12.4f %10d%n",
                            tileTag, pi, predInitNames[piIdx], kInit, nc, br, outLen);
                    String variant = "predInit=" + predInitNames[piIdx] + ",kInit=" + kInit;
                    tsv.printf("Exp3ext\t%s\tp%d\t%s\tneighR\t%.6f%n",
                            tileTag, pi, variant, nc);
                    tsv.printf("Exp3ext\t%s\tp%d\t%s\tbayer_%s_R\t%.6f%n",
                            tileTag, pi, variant, chanName[previewChan], br);
                    tsv.printf("Exp3ext\t%s\tp%d\t%s\toutLen\t%d%n",
                            tileTag, pi, variant, outLen);
                }
            }
        }
    }

    // ============================================================ winner summary

    private static void summariseWinners() throws IOException {
        Path tsvPath = OUT_DIR.resolve("metrics.tsv");
        if (!Files.exists(tsvPath)) return;
        // group by experiment and find top-5 absolute Pearson r per experiment
        java.util.List<String> lines = Files.readAllLines(tsvPath);
        java.util.Map<String, java.util.List<double[]>> byExp = new java.util.LinkedHashMap<>();
        java.util.Map<String, java.util.List<String>> byExpLabel = new java.util.LinkedHashMap<>();
        for (String l : lines) {
            String[] f = l.split("\t");
            if (f.length != 6) continue;
            if (!f[4].startsWith("bayer_") || !f[4].endsWith("_R")) continue;
            try {
                double v = Double.parseDouble(f[5]);
                String exp = f[0];
                byExp.computeIfAbsent(exp, k -> new java.util.ArrayList<>())
                        .add(new double[]{Math.abs(v), v});
                byExpLabel.computeIfAbsent(exp, k -> new java.util.ArrayList<>())
                        .add(f[1] + "/" + f[2] + "/" + f[3] + "/" + f[4]);
            } catch (NumberFormatException ignored) { }
        }
        try (PrintWriter pw = new PrintWriter(OUT_DIR.resolve("winners.txt").toFile())) {
            for (var exp : byExp.keySet()) {
                var values = byExp.get(exp);
                var labels = byExpLabel.get(exp);
                Integer[] idx = new Integer[values.size()];
                for (int i = 0; i < idx.length; i++) idx[i] = i;
                java.util.Arrays.sort(idx, (a, b) -> Double.compare(values.get(b)[0], values.get(a)[0]));
                pw.println("=== " + exp + " — top 8 by |bayerR| ===");
                System.out.println();
                System.out.println("=== " + exp + " — top 8 by |bayerR| ===");
                int n = Math.min(8, idx.length);
                for (int i = 0; i < n; i++) {
                    int j = idx[i];
                    String row = String.format("  %2d. r=%+.4f  %s", i + 1, values.get(j)[1], labels.get(j));
                    pw.println(row);
                    System.out.println(row);
                }
            }
        }
    }

    // ============================================================ decoder

    /**
     * Single-pass adaptive Rice decoder with patent K-update plus injected
     * predictor and sign-mapping variants. Returns full plane on success or a
     * partial plane if the bitstream exhausts (caller checks length).
     */
    private static int[] decodeWithVariant(byte[] payload, int w, int h, int bd,
                                           Predictor pr, SignMap sm,
                                           int predInit, int kInit) {
        try { return decodeImpl(payload, w, h, bd, pr, sm, predInit, kInit); }
        catch (RuntimeException | IOException e) { return new int[0]; }
    }

    private static int[] decodeImpl(byte[] payload, int w, int h, int bd,
                                    Predictor pr, SignMap sm,
                                    int predInit, int kInit) throws IOException {
        int[] result = new int[w * h];
        int maxVal = (1 << bd) - 1;
        int midgrey = 1 << (bd - 1);
        BitReader reader = new BitReader(payload, 0, payload.length);
        int k = Math.max(0, Math.min(bd, kInit));
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!reader.hasMore()) return Arrays.copyOf(result, y * w + x);
                int residual = decodeRice(reader, k);
                int signed = mapSign(residual, sm, bd, midgrey);
                int predicted = predict(result, w, x, y, bd, pr, predInit);
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

    private static int mapSign(int s, SignMap sm, int bd, int midgrey) {
        return switch (sm) {
            case S_LSB           -> ((s & 1) == 0) ? (s >> 1) : -((s >> 1) + 1);
            case S_LSB_ALT       -> ((s & 1) == 0) ? (s >> 1) : -(s >> 1);
            case S_HI_BIT_SIGN -> {
                int half = 1 << bd;
                if (s < half / 2) yield s;
                else yield s - half;
            }
            case S_RAW_POS       -> s;
            case S_RAW_NEG       -> -s;
            case S_OFFSET_HALF   -> s - midgrey;
            case S_ZIGZAG_INV_LSB -> ((s & 1) == 0) ? ((s + 1) >> 1) : -((s + 1) >> 1);
        };
    }

    private static int predict(int[] r, int w, int x, int y, int bd,
                               Predictor pr, int predInit) {
        return switch (pr) {
            case P_NONE -> predInit;
            case P_LEFT -> {
                // Left-edge fallback uses the pixel above (conventional choice
                // for a "previous-left" predictor). This avoids the horizontal
                // discontinuity that would result from wrapping to the previous
                // row's last column. At (0,0) the predictor seed `predInit` is
                // used (typically midgrey).
                if (x == 0 && y == 0) yield predInit;
                if (x == 0) yield r[(y - 1) * w + x];
                yield r[y * w + x - 1];
            }
            case P_UP -> {
                if (y == 0) yield predInit;
                yield r[(y - 1) * w + x];
            }
            case P_MED -> {
                if (x == 0 && y == 0) yield predInit;
                if (y == 0) yield r[x - 1];
                if (x == 0) yield r[(y - 1) * w];
                int a = r[y * w + x - 1];
                int b = r[(y - 1) * w + x];
                int c = r[(y - 1) * w + x - 1];
                if (c >= Math.max(a, b)) yield Math.min(a, b);
                else if (c <= Math.min(a, b)) yield Math.max(a, b);
                else yield a + b - c;
            }
        };
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

    // ============================================================ stats helpers

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
