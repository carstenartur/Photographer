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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-3 follow-up to {@link CrxPlaneFollowupExtendedIT}, per the new
 * requirement (2026-04-30, after Update (f) confirmed P_LEFT |r| ≈ 0.41 vs
 * patent MED's 0.085 — directionally favoured but well below the 0.7
 * threshold, with consistently NEGATIVE r → structural sign-inversion).
 *
 * <p><b>Strict test scope.</b> No production code is modified, no flag is
 * added, no hypothesis is promoted. Output dir:
 * {@code canon-cr3-imageio/target/crx-followup-round3/}.</p>
 *
 * <h3>Section A — P_LEFT scanline reset variants</h3>
 * Some Canon/Pentax codecs reset their predictor at every scanline. We
 * compare two variants with otherwise identical decoders:
 * <ul>
 *   <li><b>P_LEFT_BASE</b>: at row start, fall back to pixel above (current
 *       round-2 winner — same as {@link CrxPlaneFollowupExtendedIT}).</li>
 *   <li><b>P_LEFT_LINE_RESET</b>: at row start, fall back to {@code predInit}
 *       (midgrey). Hard reset, no horizontal continuity across rows.</li>
 * </ul>
 *
 * <h3>Section B — Alternative scan orders</h3>
 * Holding predictor = P_LEFT_LINE_RESET, the traversal alone is varied:
 * <ul>
 *   <li><b>ROW_MAJOR</b>: standard L→R, T→B (same cells but no per-line reset).</li>
 *   <li><b>ROW_MAJOR_RESET</b>: alias for P_LEFT_LINE_RESET row-major.</li>
 *   <li><b>COL_MAJOR</b>: T→B, L→R (predictor refers to pixel above).</li>
 *   <li><b>SNAKE</b>: alternating L→R / R→L per row (boustrophedon).</li>
 *   <li><b>ZIGZAG</b>: anti-diagonal traversal (a..b along anti-diagonals).</li>
 * </ul>
 *
 * <h3>Section C — Gradient / averaging predictors</h3>
 * On the best traversal of (B), test:
 * <ul>
 *   <li><b>G_AVG</b>: {@code (a+b)/2}</li>
 *   <li><b>G_PLANE</b>: {@code a+b-c}</li>
 *   <li><b>G_2A_C</b>: {@code 2a-c}</li>
 *   <li><b>G_AVG_CLIP</b>: {@code clamp((a+b)/2)}</li>
 *   <li><b>G_PLANE_CLIP</b>: {@code clamp(a+b-c)}</li>
 * </ul>
 *
 * <h3>Section D — Tile-cropped reference scoring</h3>
 * Re-score top-3 of each section against
 * {@link TileCroppedPreviewExtractor} (tile-local crop of preview JPEG)
 * instead of the full preview scaled to plane size. Should remove global
 * crop/scale bias and either confirm the current ~0.41 or surface a tighter
 * tile-local correlation.
 *
 * <p>The test always succeeds — its purpose is artefact production for
 * {@code SubbandFixtures.md} Update (g) and analysis-doc §5.7.</p>
 */
class CrxPlaneFollowupRound3IT {

    private static final String SAMPLE = "src/test/resources/samples/sample.cr3";
    private static final Path OUT_DIR = Path.of("target", "crx-followup-round3");

    private enum Predictor {
        P_LEFT_BASE, P_LEFT_LINE_RESET,
        G_AVG, G_PLANE, G_2A_C, G_AVG_CLIP, G_PLANE_CLIP
    }

    private enum Scan { ROW_MAJOR, ROW_MAJOR_RESET, COL_MAJOR, SNAKE, ZIGZAG }

    /** A scored variant for tile-cropped re-evaluation. */
    private record Score(String section, String tile, int plane, String variant,
                         int previewChan, double r) { }

    @Test
    void runRound3Sweep() throws Exception {
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

        // Tiles for sample.cr3 are split horizontally (2 tiles per row, 1 tile row).
        // SubbandExtractor sets tileWidth = trackWidth/numTiles, so the preview must
        // be cropped along the X axis. Generalised via tilesPerRow/tilesPerCol.
        int tilesPerRow = (tile1 != null) ? 2 : 1;
        int tilesPerCol = 1;

        List<Score> scores = new ArrayList<>();

        try (PrintWriter tsv = new PrintWriter(OUT_DIR.resolve("metrics.tsv").toFile())) {
            tsv.println("section\ttile\tplane\tvariant\tmetric\tvalue");

            // ------------------ A: P_LEFT reset variants ------------------
            System.out.printf("%n=== Section A — P_LEFT scanline reset ===%n");
            System.out.printf("%-6s %-3s %-22s %12s %12s %12s%n",
                    "tile", "p", "predictor", "outMean", "neighR", "bayerR");
            sweepPredictorScan(f, tile0, "tile0", cfaToPreview, chanName, tsv, scores,
                    new Predictor[]{Predictor.P_LEFT_BASE, Predictor.P_LEFT_LINE_RESET},
                    new Scan[]{Scan.ROW_MAJOR}, "A");
            if (tile1 != null) {
                sweepPredictorScan(f, tile1, "tile1", cfaToPreview, chanName, tsv, scores,
                        new Predictor[]{Predictor.P_LEFT_BASE, Predictor.P_LEFT_LINE_RESET},
                        new Scan[]{Scan.ROW_MAJOR}, "A");
            }

            // ------------------ B: alternative scan orders ----------------
            System.out.printf("%n=== Section B — alternative scan orders (predictor = P_LEFT_LINE_RESET) ===%n");
            System.out.printf("%-6s %-3s %-22s %-16s %12s %12s%n",
                    "tile", "p", "predictor", "scan", "neighR", "bayerR");
            sweepPredictorScan(f, tile0, "tile0", cfaToPreview, chanName, tsv, scores,
                    new Predictor[]{Predictor.P_LEFT_LINE_RESET}, Scan.values(), "B");
            if (tile1 != null) {
                sweepPredictorScan(f, tile1, "tile1", cfaToPreview, chanName, tsv, scores,
                        new Predictor[]{Predictor.P_LEFT_LINE_RESET}, Scan.values(), "B");
            }

            // ------------------ C: gradient predictors --------------------
            System.out.printf("%n=== Section C — gradient/averaging predictors (scan = ROW_MAJOR_RESET) ===%n");
            System.out.printf("%-6s %-3s %-22s %12s %12s%n",
                    "tile", "p", "predictor", "neighR", "bayerR");
            Predictor[] grad = {
                    Predictor.G_AVG, Predictor.G_PLANE, Predictor.G_2A_C,
                    Predictor.G_AVG_CLIP, Predictor.G_PLANE_CLIP
            };
            sweepPredictorScan(f, tile0, "tile0", cfaToPreview, chanName, tsv, scores,
                    grad, new Scan[]{Scan.ROW_MAJOR_RESET}, "C");
            if (tile1 != null) {
                sweepPredictorScan(f, tile1, "tile1", cfaToPreview, chanName, tsv, scores,
                        grad, new Scan[]{Scan.ROW_MAJOR_RESET}, "C");
            }

            // ------------------ D: tile-cropped re-scoring ----------------
            System.out.printf("%n=== Section D — tile-cropped reference re-scoring (top-3 per section) ===%n");
            System.out.printf("%-6s %-3s %-32s %-32s %10s %10s %10s%n",
                    "tile", "p", "section/variant", "predictor",
                    "fullR", "tileR", "delta");
            rescoreTopWithTileCrop(f, tile0, tile1, cfaToPreview, chanName, tsv, scores,
                    tilesPerRow, tilesPerCol);
        }

        summariseWinners();
        exportHeatmaps(f, tile0, cfaToPreview);

        assertThat(OUT_DIR.resolve("metrics.tsv")).exists();
        assertThat(OUT_DIR.resolve("winners.txt")).exists();
    }

    // ============================================================ sweep core

    private static void sweepPredictorScan(
            File f, SubbandExtractor.SubbandRef[] planes, String tileTag,
            int[] cfaToPreview, String[] chanName, PrintWriter tsv,
            List<Score> scores, Predictor[] predictors, Scan[] scans, String section)
            throws IOException {
        int w = planes[0].planeWidth();
        int h = planes[0].planeHeight();
        int bd = planes[0].bitDepth();
        int midgrey = 1 << (bd - 1);

        byte[][] payload = new byte[4][];
        for (int i = 0; i < 4; i++) payload[i] = SubbandExtractor.read(f, planes[i]);

        for (int pi = 0; pi < 4; pi++) {
            int previewChan = cfaToPreview[pi];
            PreviewReferenceExtractor.Reference ref =
                    PreviewReferenceExtractor.extract(f, previewChan, w, h);
            for (Predictor pr : predictors) {
                for (Scan sc : scans) {
                    int[] dec = decode(payload[pi], w, h, bd, pr, sc, midgrey, 0);
                    if (dec.length < w * h) continue;
                    double m = mean(dec);
                    double nc = neighbourCorrAlongScan(dec, w, h, sc);
                    double br = pearson(dec, ref.pixels());
                    String variant = pr + "/" + sc;
                    if (predictors.length == 1 && scans.length == 1) {
                        System.out.printf("%-6s p%-2d %-22s %12.2f %12.4f %12.4f%n",
                                tileTag, pi, pr.toString(), m, nc, br);
                    } else if (predictors.length == 1) {
                        System.out.printf("%-6s p%-2d %-22s %-16s %12.4f %12.4f%n",
                                tileTag, pi, pr, sc, nc, br);
                    } else if (scans.length == 1) {
                        System.out.printf("%-6s p%-2d %-22s %12.4f %12.4f%n",
                                tileTag, pi, pr, nc, br);
                    } else {
                        System.out.printf("%-6s p%-2d %-22s %-16s %12.4f %12.4f%n",
                                tileTag, pi, pr, sc, nc, br);
                    }
                    tsv.printf("%s\t%s\tp%d\t%s\toutMean\t%.4f%n",
                            section, tileTag, pi, variant, m);
                    tsv.printf("%s\t%s\tp%d\t%s\tneighR\t%.6f%n",
                            section, tileTag, pi, variant, nc);
                    tsv.printf("%s\t%s\tp%d\t%s\tbayer_%s_R\t%.6f%n",
                            section, tileTag, pi, variant, chanName[previewChan], br);
                    scores.add(new Score(section, tileTag, pi, variant, previewChan, br));
                }
            }
        }
    }

    // ============================================================ tile-crop rescoring

    private static void rescoreTopWithTileCrop(
            File f, SubbandExtractor.SubbandRef[] tile0,
            SubbandExtractor.SubbandRef[] tile1,
            int[] cfaToPreview, String[] chanName, PrintWriter tsv,
            List<Score> scores, int tilesPerRow, int tilesPerCol) throws IOException {

        // Pick top-3 by |r| per section.
        Map<String, List<Score>> bySection = new LinkedHashMap<>();
        for (Score s : scores) {
            bySection.computeIfAbsent(s.section, k -> new ArrayList<>()).add(s);
        }
        for (var e : bySection.entrySet()) {
            e.getValue().sort((a, b) -> Double.compare(Math.abs(b.r), Math.abs(a.r)));
        }

        for (var e : bySection.entrySet()) {
            int n = Math.min(3, e.getValue().size());
            for (int i = 0; i < n; i++) {
                Score s = e.getValue().get(i);
                SubbandExtractor.SubbandRef[] planes =
                        s.tile.equals("tile0") ? tile0 : tile1;
                if (planes == null) continue;
                int tileIdx = s.tile.equals("tile0") ? 0 : 1;
                int w = planes[0].planeWidth();
                int h = planes[0].planeHeight();
                int bd = planes[0].bitDepth();
                int midgrey = 1 << (bd - 1);

                // Re-decode with the same variant.
                String[] parts = s.variant.split("/");
                Predictor pr = Predictor.valueOf(parts[0]);
                Scan sc = Scan.valueOf(parts[1]);
                byte[] payload = SubbandExtractor.read(f, planes[s.plane]);
                int[] dec = decode(payload, w, h, bd, pr, sc, midgrey, 0);
                if (dec.length < w * h) continue;

                PreviewReferenceExtractor.Reference tileRef =
                        TileCroppedPreviewExtractor.extract(
                                f, s.previewChan, w, h, tileIdx, tilesPerRow, tilesPerCol);
                double tileR = pearson(dec, tileRef.pixels());

                System.out.printf("%-6s p%-2d %-32s %-32s %+10.4f %+10.4f %+10.4f%n",
                        s.tile, s.plane, s.section + "/" + (i + 1), s.variant,
                        s.r, tileR, tileR - s.r);
                tsv.printf("D\t%s\tp%d\t%s\tbayer_%s_R_tileCrop\t%.6f%n",
                        s.tile, s.plane, s.variant, chanName[s.previewChan], tileR);
                tsv.printf("D\t%s\tp%d\t%s\tbayer_%s_R_full\t%.6f%n",
                        s.tile, s.plane, s.variant, chanName[s.previewChan], s.r);
            }
        }
    }

    // ============================================================ winner summary

    private static void summariseWinners() throws IOException {
        Path tsvPath = OUT_DIR.resolve("metrics.tsv");
        if (!Files.exists(tsvPath)) return;
        List<String> lines = Files.readAllLines(tsvPath);
        Map<String, List<double[]>> byExp = new LinkedHashMap<>();
        Map<String, List<String>> byExpLabel = new LinkedHashMap<>();
        for (String l : lines) {
            String[] f = l.split("\t");
            if (f.length != 6) continue;
            if (!f[4].startsWith("bayer_")) continue;
            try {
                double v = Double.parseDouble(f[5]);
                String exp = f[0] + (f[4].endsWith("_tileCrop") ? "_tileCrop"
                        : (f[4].endsWith("_full") ? "_full" : ""));
                byExp.computeIfAbsent(exp, k -> new ArrayList<>())
                        .add(new double[]{Math.abs(v), v});
                byExpLabel.computeIfAbsent(exp, k -> new ArrayList<>())
                        .add(f[1] + "/" + f[2] + "/" + f[3] + "/" + f[4]);
            } catch (NumberFormatException ignored) { }
        }
        try (PrintWriter pw = new PrintWriter(OUT_DIR.resolve("winners.txt").toFile())) {
            for (var exp : byExp.keySet()) {
                var values = byExp.get(exp);
                var labels = byExpLabel.get(exp);
                Integer[] idx = new Integer[values.size()];
                for (int i = 0; i < idx.length; i++) idx[i] = i;
                Arrays.sort(idx, (a, b) -> Double.compare(values.get(b)[0], values.get(a)[0]));
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

    // ============================================================ heatmap export

    /** Export PGM heatmaps for tile0 plane 0 under the three best variants of each section. */
    private static void exportHeatmaps(File f, SubbandExtractor.SubbandRef[] tile0, int[] cfaToPreview)
            throws IOException {
        int w = tile0[0].planeWidth();
        int h = tile0[0].planeHeight();
        int bd = tile0[0].bitDepth();
        int midgrey = 1 << (bd - 1);
        byte[] payload = SubbandExtractor.read(f, tile0[0]);
        // 4 representative variants
        Object[][] choices = {
                {"A_baseline_PLEFT_BASE", Predictor.P_LEFT_BASE, Scan.ROW_MAJOR},
                {"A_lineReset_PLEFT", Predictor.P_LEFT_LINE_RESET, Scan.ROW_MAJOR},
                {"B_snake_PLEFT_LR", Predictor.P_LEFT_LINE_RESET, Scan.SNAKE},
                {"C_avg_clip", Predictor.G_AVG_CLIP, Scan.ROW_MAJOR_RESET},
        };
        for (Object[] c : choices) {
            String name = (String) c[0];
            Predictor pr = (Predictor) c[1];
            Scan sc = (Scan) c[2];
            int[] dec = decode(payload, w, h, bd, pr, sc, midgrey, 0);
            if (dec.length < w * h) continue;
            writePgm(OUT_DIR.resolve("tile0_p0_" + name + ".pgm").toFile(), dec, w, h, bd);
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

    private static int[] decode(byte[] payload, int w, int h, int bd,
                                Predictor pr, Scan sc, int predInit, int kInit) {
        try { return decodeImpl(payload, w, h, bd, pr, sc, predInit, kInit); }
        catch (RuntimeException | IOException e) { return new int[0]; }
    }

    private static int[] decodeImpl(byte[] payload, int w, int h, int bd,
                                    Predictor pr, Scan sc, int predInit, int kInit) throws IOException {
        int[] result = new int[w * h];
        int maxVal = (1 << bd) - 1;
        BitReader reader = new BitReader(payload, 0, payload.length);
        int k = Math.max(0, Math.min(bd, kInit));
        int[] order = scanOrder(sc, w, h);
        boolean lineReset = (pr == Predictor.P_LEFT_LINE_RESET) || (sc == Scan.ROW_MAJOR_RESET);

        for (int n = 0; n < order.length; n++) {
            int idx = order[n];
            if (!reader.hasMore()) return Arrays.copyOf(result, lastFilled(order, n));
            int residual = decodeRice(reader, k);
            int signed = mapSignLsb(residual);
            int x = idx % w;
            int y = idx / w;
            int predicted = predict(result, w, h, x, y, n, order, pr, predInit, lineReset, sc);
            int pixel = predicted + signed;
            if (pixel < 0) pixel = 0;
            else if (pixel > maxVal) pixel = maxVal;
            result[idx] = pixel;
            // Patent K-update.
            int sn = residual;
            int kStar = (sn <= 0) ? 0 : 31 - Integer.numberOfLeadingZeros(sn + 1);
            int kMin = Math.max(0, kStar - 1);
            int kMax = Math.min(bd, kStar + 1);
            if (k < kMin) k++;
            else if (k > kMax) k--;
        }
        return result;
    }

    private static int lastFilled(int[] order, int countDecoded) {
        // For partial decodes we can only safely report the contiguous prefix
        // size, but order may be non-contiguous. Approximate by countDecoded
        // (caller treats < w*h as failure).
        return Math.min(order.length, countDecoded);
    }

    private static int mapSignLsb(int s) {
        return ((s & 1) == 0) ? (s >> 1) : -((s >> 1) + 1);
    }

    private static int predict(int[] r, int w, int h, int x, int y, int n, int[] order,
                               Predictor pr, int predInit, boolean lineReset, Scan sc) {
        // For non-row-major scans, "left/up" is interpreted as the previously
        // visited neighbour in the scan; we use raster left/up neighbours
        // because the experiment isolates traversal effect by holding the
        // predictor's spatial reference fixed.
        int a = (x > 0) ? r[y * w + x - 1] : Integer.MIN_VALUE;
        int b = (y > 0) ? r[(y - 1) * w + x] : Integer.MIN_VALUE;
        int c = (x > 0 && y > 0) ? r[(y - 1) * w + x - 1] : Integer.MIN_VALUE;

        return switch (pr) {
            case P_LEFT_BASE -> {
                if (x == 0 && y == 0) yield predInit;
                if (x == 0) yield b;       // edge → pixel above
                yield a;
            }
            case P_LEFT_LINE_RESET -> {
                if (x == 0) yield predInit;  // hard reset every row
                yield a;
            }
            case G_AVG -> {
                if (x == 0 && y == 0) yield predInit;
                if (x == 0) yield b;
                if (y == 0) yield a;
                yield (a + b) / 2;
            }
            case G_PLANE -> {
                if (x == 0 && y == 0) yield predInit;
                if (x == 0) yield b;
                if (y == 0) yield a;
                yield a + b - c;
            }
            case G_2A_C -> {
                if (x == 0 && y == 0) yield predInit;
                if (x == 0 || y == 0) yield (x == 0) ? b : a;
                yield 2 * a - c;
            }
            case G_AVG_CLIP -> {
                if (x == 0 && y == 0) yield predInit;
                if (x == 0) yield b;
                if (y == 0) yield a;
                int p = (a + b) / 2;
                yield clamp(p, predInit);
            }
            case G_PLANE_CLIP -> {
                if (x == 0 && y == 0) yield predInit;
                if (x == 0) yield b;
                if (y == 0) yield a;
                int p = a + b - c;
                yield clamp(p, predInit);
            }
        };
    }

    private static int clamp(int v, int predInit) {
        int max = (predInit << 1) - 1;
        if (v < 0) return 0;
        if (v > max) return max;
        return v;
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

    // ============================================================ scan orders

    private static int[] scanOrder(Scan sc, int w, int h) {
        int[] o = new int[w * h];
        int p = 0;
        switch (sc) {
            case ROW_MAJOR, ROW_MAJOR_RESET -> {
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < w; x++) o[p++] = y * w + x;
            }
            case COL_MAJOR -> {
                for (int x = 0; x < w; x++)
                    for (int y = 0; y < h; y++) o[p++] = y * w + x;
            }
            case SNAKE -> {
                for (int y = 0; y < h; y++) {
                    if ((y & 1) == 0) {
                        for (int x = 0; x < w; x++) o[p++] = y * w + x;
                    } else {
                        for (int x = w - 1; x >= 0; x--) o[p++] = y * w + x;
                    }
                }
            }
            case ZIGZAG -> {
                // anti-diagonal traversal (top-left → bottom-right)
                for (int d = 0; d < w + h - 1; d++) {
                    int xs = Math.max(0, d - h + 1);
                    int xe = Math.min(w - 1, d);
                    for (int x = xs; x <= xe; x++) {
                        int y = d - x;
                        o[p++] = y * w + x;
                    }
                }
            }
        }
        return o;
    }

    // ============================================================ stats

    private static double mean(int[] a) {
        if (a.length == 0) return 0;
        double s = 0;
        for (int v : a) s += v;
        return s / a.length;
    }

    /** Neighbour correlation along the natural raster axis; useful sanity check. */
    private static double neighbourCorrAlongScan(int[] arr, int w, int h, Scan sc) {
        // Use raster-left as the canonical neighbour for all scans, so values
        // are comparable across scans. (Choosing scan-previous would mean
        // ROW_MAJOR_RESET artificially looks "uncorrelated" at row starts.)
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
