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
 * Round-5 follow-up to {@link CrxPlaneFollowupRound4IT}, per the new
 * requirement (2026-05-01, after Update (h) showed that LEAD_ZERO at the very
 * first sample of each tile lifts |Pearson r| from 0.39 → 0.56 (tile1) and
 * 0.12 → 0.49 (tile0) — closing the tile asymmetry but still well short of the
 * 0.7 promotion threshold).
 *
 * <p><b>Strict test scope.</b> No production code is modified, no flag is
 * added, no hypothesis is promoted. Output dir:
 * {@code canon-cr3-imageio/target/crx-followup-round5/}.</p>
 *
 * <h3>Section E5 — LEAD_ZERO scope</h3>
 * Same predictor (P_LEFT_BASE) but vary <em>where</em> a forced-zero is
 * injected:
 * <ul>
 *   <li><b>LZ_TILE_FIRST</b>: round-4 baseline, only the very first decoded
 *       sample of the tile is forced to zero.</li>
 *   <li><b>LZ_PLANE_FIRST</b>: first sample of each plane within a tile is
 *       forced to zero. For sample.cr3 with 1 subband per plane, this is
 *       identical to LZ_TILE_FIRST and serves as a control.</li>
 *   <li><b>LZ_ROW_FIRST</b>: first sample of every row.</li>
 *   <li><b>LZ_BLOCK_FIRST_64 / _128 / _256 / _512</b>: first sample of every
 *       internal block of N samples (raster-order).</li>
 * </ul>
 *
 * <h3>Section F5 — Early-sample rules</h3>
 * Keep {@code result[0] = 0} (LEAD_ZERO_TILE_FIRST) and override only the
 * <em>second</em> sample with one of:
 * {@code ABOVE} (pixel above), {@code ZERO}, {@code AVG = (left+above)/2},
 * {@code GRAD = 2·left − above}, {@code RAW} (no override = round-4 baseline).
 * Tests whether one extra rule remains after the first pixel.
 *
 * <h3>Section G5 — First-row / first-column / first-tile-row handling</h3>
 * With LEAD_ZERO active, additionally override:
 * <ul>
 *   <li>whole row 0 of tile: {@code ROW0_PREDINIT}, {@code ROW0_ZERO},
 *       {@code ROW0_LINEAR_RAMP_FROM_LEFT}</li>
 *   <li>whole column 0 of tile: {@code COL0_PREDINIT}, {@code COL0_ZERO}</li>
 * </ul>
 *
 * <h3>Section H5 — Residual error localization</h3>
 * For the round-4 LEAD_ZERO winner, build a per-pixel error map vs the
 * preview reference (after sign-fix and per-tile rescale). Then summarise the
 * error concentration in:
 * <ul>
 *   <li>row 0 of tile</li>
 *   <li>column 0 of tile</li>
 *   <li>periodic columns (RMS aggregated by column index modulo 16)</li>
 *   <li>bulk RMS (everything else)</li>
 * </ul>
 * Per-tile {@code tileN_p0_errmap.pgm} (8-bit log-magnitude) is exported.
 *
 * <h3>Section I5 — Top-candidate refinement</h3>
 * Combine the best E5 LEAD_ZERO scope with the best F5 second-sample rule and
 * the best G5 row0/col0 override; report only Δ|r| versus the LEAD_ZERO
 * baseline.
 *
 * <p>The test always succeeds — its purpose is artefact production for
 * {@code SubbandFixtures.md} Update (i) and analysis-doc §5.10.</p>
 */
class CrxPlaneFollowupRound5IT {

    private static final String SAMPLE = "src/test/resources/samples/sample.cr3";
    private static final Path OUT_DIR = Path.of("target", "crx-followup-round5");

    /** Where forced-zeros are injected. */
    private enum LeadZeroScope {
        LZ_TILE_FIRST,
        LZ_PLANE_FIRST,
        LZ_ROW_FIRST,
        LZ_BLOCK_FIRST_64,
        LZ_BLOCK_FIRST_128,
        LZ_BLOCK_FIRST_256,
        LZ_BLOCK_FIRST_512
    }

    /** Override applied to the second decoded sample only. */
    private enum SecondSampleRule {
        RAW, ABOVE, ZERO, AVG, GRAD
    }

    /** Override applied to the entire first row of the tile. */
    private enum Row0Rule {
        RAW, ROW0_PREDINIT, ROW0_ZERO, ROW0_LINEAR_RAMP_FROM_LEFT
    }

    /** Override applied to the entire first column of the tile. */
    private enum Col0Rule {
        RAW, COL0_PREDINIT, COL0_ZERO
    }

    /** Decoder configuration used by all sections. */
    private record Config(LeadZeroScope leadScope,
                          SecondSampleRule secondRule,
                          Row0Rule row0Rule,
                          Col0Rule col0Rule) {
        static Config baseline() {
            return new Config(LeadZeroScope.LZ_TILE_FIRST, SecondSampleRule.RAW,
                    Row0Rule.RAW, Col0Rule.RAW);
        }
    }

    @Test
    void runRound5Sweep() throws Exception {
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

            // ---------- Section E5 — LEAD_ZERO scope ----------
            System.out.printf("%n=== Section E5 — LEAD_ZERO scope (predictor = P_LEFT_BASE) ===%n");
            System.out.printf("%-6s %-3s %-22s %12s %12s%n",
                    "tile", "p", "leadScope", "outMean", "bayerR");
            for (LeadZeroScope ls : LeadZeroScope.values()) {
                Config cfg = new Config(ls, SecondSampleRule.RAW, Row0Rule.RAW, Col0Rule.RAW);
                sweepConfig(f, tile0, "tile0", cfaToPreview, chanName, tsv, cfg, "E5", ls.name());
                if (tile1 != null) {
                    sweepConfig(f, tile1, "tile1", cfaToPreview, chanName, tsv, cfg, "E5", ls.name());
                }
            }

            // ---------- Section F5 — second-sample rules ----------
            System.out.printf("%n=== Section F5 — second-sample rules (LEAD_ZERO_TILE_FIRST baseline) ===%n");
            System.out.printf("%-6s %-3s %-22s %12s %12s%n",
                    "tile", "p", "secondRule", "outMean", "bayerR");
            for (SecondSampleRule sr : SecondSampleRule.values()) {
                Config cfg = new Config(LeadZeroScope.LZ_TILE_FIRST, sr, Row0Rule.RAW, Col0Rule.RAW);
                sweepConfig(f, tile0, "tile0", cfaToPreview, chanName, tsv, cfg, "F5",
                        "second=" + sr.name());
                if (tile1 != null) {
                    sweepConfig(f, tile1, "tile1", cfaToPreview, chanName, tsv, cfg, "F5",
                            "second=" + sr.name());
                }
            }

            // ---------- Section G5 — first-row / first-col handling ----------
            System.out.printf("%n=== Section G5 — first-row / first-col handling (LEAD_ZERO baseline) ===%n");
            System.out.printf("%-6s %-3s %-30s %12s %12s%n",
                    "tile", "p", "row0/col0", "outMean", "bayerR");
            for (Row0Rule r0 : Row0Rule.values()) {
                for (Col0Rule c0 : Col0Rule.values()) {
                    if (r0 == Row0Rule.RAW && c0 == Col0Rule.RAW) continue; // baseline measured in E5
                    Config cfg = new Config(LeadZeroScope.LZ_TILE_FIRST,
                            SecondSampleRule.RAW, r0, c0);
                    String label = "row0=" + r0.name() + "/col0=" + c0.name();
                    sweepConfig(f, tile0, "tile0", cfaToPreview, chanName, tsv, cfg, "G5", label);
                    if (tile1 != null) {
                        sweepConfig(f, tile1, "tile1", cfaToPreview, chanName, tsv, cfg, "G5", label);
                    }
                }
            }

            // ---------- Section H5 — residual error localization ----------
            System.out.printf("%n=== Section H5 — residual error localization (LEAD_ZERO baseline) ===%n");
            try (PrintWriter zones = new PrintWriter(OUT_DIR.resolve("error_zones.tsv").toFile())) {
                zones.println("tile\tplane\tzone\trmsErr\tcount");
                errorLocalization(f, tile0, "tile0", cfaToPreview, zones);
                if (tile1 != null) errorLocalization(f, tile1, "tile1", cfaToPreview, zones);
            }

            // ---------- Section I5 — top-candidate refinement ----------
            System.out.printf("%n=== Section I5 — top-candidate refinement ===%n");
            // Use a focused combination: best LEAD_ZERO scope + best second-sample
            // override (ABOVE, common winner) + ROW0_PREDINIT / COL0_PREDINIT.
            Config refined = new Config(LeadZeroScope.LZ_TILE_FIRST,
                    SecondSampleRule.ABOVE, Row0Rule.ROW0_PREDINIT, Col0Rule.COL0_PREDINIT);
            sweepConfig(f, tile0, "tile0", cfaToPreview, chanName, tsv, refined, "I5",
                    "refined_TILE_ABOVE_ROW0PI_COL0PI");
            if (tile1 != null) {
                sweepConfig(f, tile1, "tile1", cfaToPreview, chanName, tsv, refined, "I5",
                        "refined_TILE_ABOVE_ROW0PI_COL0PI");
            }
        }

        summariseTilewiseWinners();
        exportTilewiseHeatmaps(f, tile0, "tile0", cfaToPreview);
        if (tile1 != null) exportTilewiseHeatmaps(f, tile1, "tile1", cfaToPreview);

        assertThat(OUT_DIR.resolve("metrics.tsv")).exists();
        assertThat(OUT_DIR.resolve("winners.txt")).exists();
        assertThat(OUT_DIR.resolve("error_zones.tsv")).exists();
    }

    // ============================================================ generic sweep

    private static void sweepConfig(File f, SubbandExtractor.SubbandRef[] planes, String tileTag,
                                    int[] cfaToPreview, String[] chanName, PrintWriter tsv,
                                    Config cfg, String section, String label) throws IOException {
        int w = planes[0].planeWidth();
        int h = planes[0].planeHeight();
        int bd = planes[0].bitDepth();
        int midgrey = 1 << (bd - 1);
        for (int pi = 0; pi < 4; pi++) {
            int previewChan = cfaToPreview[pi];
            PreviewReferenceExtractor.Reference ref =
                    PreviewReferenceExtractor.extract(f, previewChan, w, h);
            byte[] payload = SubbandExtractor.read(f, planes[pi]);
            int[] dec = decode(payload, w, h, bd, midgrey, 0, cfg);
            if (dec.length < w * h) continue;
            double m = mean(dec);
            double br = pearson(dec, ref.pixels());
            String variant = "P_LEFT_BASE/" + label;
            System.out.printf("%-6s p%-2d %-30s %12.2f %+12.4f%n", tileTag, pi, label, m, br);
            tsv.printf("%s\t%s\tp%d\t%s\toutMean\t%.4f%n", section, tileTag, pi, variant, m);
            tsv.printf("%s\t%s\tp%d\t%s\tbayer_%s_R\t%.6f%n",
                    section, tileTag, pi, variant, chanName[previewChan], br);
        }
    }

    // ============================================================ Section H5 — error localization

    private static void errorLocalization(File f, SubbandExtractor.SubbandRef[] planes, String tileTag,
                                          int[] cfaToPreview, PrintWriter zones) throws IOException {
        int w = planes[0].planeWidth();
        int h = planes[0].planeHeight();
        int bd = planes[0].bitDepth();
        int midgrey = 1 << (bd - 1);
        Config cfg = Config.baseline(); // LEAD_ZERO_TILE_FIRST winner
        for (int pi = 0; pi < 4; pi++) {
            int previewChan = cfaToPreview[pi];
            PreviewReferenceExtractor.Reference ref =
                    PreviewReferenceExtractor.extract(f, previewChan, w, h);
            byte[] payload = SubbandExtractor.read(f, planes[pi]);
            int[] dec = decode(payload, w, h, bd, midgrey, 0, cfg);
            if (dec.length < w * h) continue;

            // Sign-fix + per-tile rescale to preview range [0..255], so absolute
            // residuals are comparable across planes/tiles. Use the sign that
            // maximises Pearson |r| (round-4 finding: negative dominates).
            double r = pearson(dec, ref.pixels());
            int sign = r >= 0 ? +1 : -1;
            // Linear LSQ fit: ref ≈ a + b*sign*dec
            double sx = 0, sy = 0, sxx = 0, sxy = 0;
            int n = w * h;
            for (int i = 0; i < n; i++) {
                double x = sign * dec[i];
                double y = ref.pixels()[i];
                sx += x; sy += y; sxx += x * x; sxy += x * y;
            }
            double mx = sx / n, my = sy / n;
            double denom = sxx / n - mx * mx;
            double b = denom < 1e-9 ? 0 : (sxy / n - mx * my) / denom;
            double a = my - b * mx;

            // Build absolute error per pixel.
            double[] err = new double[n];
            for (int i = 0; i < n; i++) {
                double pred = a + b * sign * dec[i];
                err[i] = Math.abs(pred - ref.pixels()[i]);
            }

            // Zone summaries.
            double row0Sq = 0; int row0N = 0;
            for (int x = 0; x < w; x++) { row0Sq += err[x] * err[x]; row0N++; }
            double col0Sq = 0; int col0N = 0;
            for (int y = 1; y < h; y++) { col0Sq += err[y * w] * err[y * w]; col0N++; }
            // Periodic columns by raster-x mod 16 — only if at least one full period.
            int period = 16;
            double[] periodSq = new double[period];
            int[] periodN = new int[period];
            for (int y = 1; y < h; y++) {
                for (int x = 1; x < w; x++) {
                    int idx = x % period;
                    double e = err[y * w + x];
                    periodSq[idx] += e * e;
                    periodN[idx]++;
                }
            }
            double bulkSq = 0; int bulkN = 0;
            for (int y = 1; y < h; y++) {
                for (int x = 1; x < w; x++) {
                    double e = err[y * w + x];
                    bulkSq += e * e;
                    bulkN++;
                }
            }
            zones.printf("%s\tp%d\trow0\t%.4f\t%d%n",
                    tileTag, pi, Math.sqrt(row0Sq / Math.max(1, row0N)), row0N);
            zones.printf("%s\tp%d\tcol0\t%.4f\t%d%n",
                    tileTag, pi, Math.sqrt(col0Sq / Math.max(1, col0N)), col0N);
            zones.printf("%s\tp%d\tbulk\t%.4f\t%d%n",
                    tileTag, pi, Math.sqrt(bulkSq / Math.max(1, bulkN)), bulkN);
            for (int p = 0; p < period; p++) {
                zones.printf("%s\tp%d\tperiodCol%%16=%d\t%.4f\t%d%n",
                        tileTag, pi, p,
                        Math.sqrt(periodSq[p] / Math.max(1, periodN[p])), periodN[p]);
            }
            System.out.printf("  %s p%d  row0=%.2f col0=%.2f bulk=%.2f%n",
                    tileTag, pi,
                    Math.sqrt(row0Sq / Math.max(1, row0N)),
                    Math.sqrt(col0Sq / Math.max(1, col0N)),
                    Math.sqrt(bulkSq / Math.max(1, bulkN)));

            // Export per-plane error map for plane 0 only (cost control).
            if (pi == 0) {
                writeLogPgm(OUT_DIR.resolve(tileTag + "_p0_errmap.pgm").toFile(), err, w, h);
            }
        }
    }

    private static void writeLogPgm(File out, double[] err, int w, int h) throws IOException {
        double maxE = 0;
        for (double e : err) if (e > maxE) maxE = e;
        if (maxE <= 0) maxE = 1;
        byte[] buf = new byte[w * h];
        for (int i = 0; i < buf.length; i++) {
            double v = Math.log1p(err[i]) / Math.log1p(maxE);
            int g = (int) Math.round(v * 255.0);
            if (g < 0) g = 0; else if (g > 255) g = 255;
            buf[i] = (byte) g;
        }
        try (var os = new java.io.FileOutputStream(out)) {
            String header = "P5\n" + w + " " + h + "\n255\n";
            os.write(header.getBytes());
            os.write(buf);
        }
    }

    // ============================================================ tilewise winners

    private static void summariseTilewiseWinners() throws IOException {
        Path tsvPath = OUT_DIR.resolve("metrics.tsv");
        if (!Files.exists(tsvPath)) return;
        List<String> lines = Files.readAllLines(tsvPath);

        Map<String, List<double[]>> bins = new LinkedHashMap<>();
        Map<String, List<String>> binLabels = new LinkedHashMap<>();
        for (String l : lines) {
            String[] f = l.split("\t");
            if (f.length != 6) continue;
            if (!f[4].startsWith("bayer_") || !f[4].endsWith("_R")) continue;
            try {
                double v = Double.parseDouble(f[5]);
                String key = f[0] + "/" + f[1];   // e.g. "E5/tile0"
                bins.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new double[]{Math.abs(v), v});
                binLabels.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(f[2] + "/" + f[3] + "/" + f[4]);
            } catch (NumberFormatException ignored) { }
        }

        try (PrintWriter pw = new PrintWriter(OUT_DIR.resolve("winners.txt").toFile())) {
            pw.println("Round-5 winners — tilewise top 5 per section (by |bayerR|)");
            pw.println();
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

    /** Per-tile PGM heatmaps for plane 0 under the round-5 candidate variants. */
    private static void exportTilewiseHeatmaps(File f, SubbandExtractor.SubbandRef[] planes,
                                               String tileTag, int[] cfaToPreview)
            throws IOException {
        int w = planes[0].planeWidth();
        int h = planes[0].planeHeight();
        int bd = planes[0].bitDepth();
        int midgrey = 1 << (bd - 1);
        byte[] payload = SubbandExtractor.read(f, planes[0]);

        Object[][] choices = {
                {"E5_LZ_TILE_FIRST", new Config(LeadZeroScope.LZ_TILE_FIRST,
                        SecondSampleRule.RAW, Row0Rule.RAW, Col0Rule.RAW)},
                {"E5_LZ_ROW_FIRST", new Config(LeadZeroScope.LZ_ROW_FIRST,
                        SecondSampleRule.RAW, Row0Rule.RAW, Col0Rule.RAW)},
                {"F5_secondABOVE", new Config(LeadZeroScope.LZ_TILE_FIRST,
                        SecondSampleRule.ABOVE, Row0Rule.RAW, Col0Rule.RAW)},
                {"G5_ROW0PREDINIT", new Config(LeadZeroScope.LZ_TILE_FIRST,
                        SecondSampleRule.RAW, Row0Rule.ROW0_PREDINIT, Col0Rule.RAW)},
        };
        for (Object[] c : choices) {
            String name = (String) c[0];
            Config cfg = (Config) c[1];
            int[] dec = decode(payload, w, h, bd, midgrey, 0, cfg);
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
     * P_LEFT_BASE row-major decoder with round-5 knobs:
     *   - leadScope:   where forced-zeros are injected
     *   - secondRule:  override applied to the second decoded sample only
     *   - row0Rule:    override applied to the entire first row of the tile
     *   - col0Rule:    override applied to the entire first column of the tile
     *
     * Residuals are always consumed from the bitstream so K-state stays
     * consistent regardless of overrides.
     */
    private static int[] decode(byte[] payload, int w, int h, int bd,
                                int predInit, int kInit, Config cfg) {
        try { return decodeImpl(payload, w, h, bd, predInit, kInit, cfg); }
        catch (RuntimeException | IOException e) { return new int[0]; }
    }

    private static int[] decodeImpl(byte[] payload, int w, int h, int bd,
                                    int predInit, int kInit, Config cfg) throws IOException {
        int[] result = new int[w * h];
        int maxVal = (1 << bd) - 1;
        BitReader reader = new BitReader(payload, 0, payload.length);
        int k = Math.max(0, Math.min(bd, kInit));

        for (int n = 0; n < w * h; n++) {
            if (!reader.hasMore()) return Arrays.copyOf(result, n);
            int residual = decodeRice(reader, k);
            int signed = mapSignLsb(residual);
            int x = n % w;
            int y = n / w;

            int predicted;
            if (n == 0 || isLeadZeroPosition(n, x, w, cfg.leadScope)) {
                // Forced-zero injection: result becomes 0 regardless of residual.
                predicted = -signed;
            } else if (cfg.secondRule != SecondSampleRule.RAW && n == 1) {
                predicted = applySecondSampleRule(cfg.secondRule, result, x, y, w, predInit);
            } else if (y == 0 && cfg.row0Rule != Row0Rule.RAW) {
                predicted = applyRow0Rule(cfg.row0Rule, result, x, w, predInit);
            } else if (x == 0 && cfg.col0Rule != Col0Rule.RAW) {
                predicted = applyCol0Rule(cfg.col0Rule, result, y, w, predInit);
            } else if (x == 0) {
                predicted = result[(y - 1) * w + x];   // P_LEFT_BASE left-edge fallback (pixel above)
            } else {
                predicted = result[y * w + x - 1];
            }

            int pixel = predicted + signed;
            if (pixel < 0) pixel = 0;
            else if (pixel > maxVal) pixel = maxVal;
            result[n] = pixel;
            k = updateK(k, residual, bd);
        }
        return result;
    }

    /** Returns true iff index {@code n} is a non-zero position where a forced-zero is injected. */
    private static boolean isLeadZeroPosition(int n, int x, int w, LeadZeroScope scope) {
        return switch (scope) {
            case LZ_TILE_FIRST, LZ_PLANE_FIRST -> false;          // n==0 is already handled
            case LZ_ROW_FIRST -> x == 0;
            case LZ_BLOCK_FIRST_64 -> (n % 64) == 0;
            case LZ_BLOCK_FIRST_128 -> (n % 128) == 0;
            case LZ_BLOCK_FIRST_256 -> (n % 256) == 0;
            case LZ_BLOCK_FIRST_512 -> (n % 512) == 0;
        };
    }

    private static int applySecondSampleRule(SecondSampleRule rule, int[] result,
                                             int x, int y, int w, int predInit) {
        // n == 1 ⇒ x = 1, y = 0 ⇒ no row-above.
        int left = result[0];
        int above = predInit; // no row above on row 0
        return switch (rule) {
            case RAW -> left;
            case ABOVE -> above;
            case ZERO -> 0;
            case AVG -> (left + above) / 2;
            case GRAD -> 2 * left - above;
        };
    }

    private static int applyRow0Rule(Row0Rule rule, int[] result, int x, int w, int predInit) {
        return switch (rule) {
            case RAW -> (x == 0) ? predInit : result[x - 1];
            case ROW0_PREDINIT -> predInit;
            case ROW0_ZERO -> 0;
            case ROW0_LINEAR_RAMP_FROM_LEFT ->
                    (x == 0) ? 0 : Math.min(predInit, result[x - 1] + 1);
        };
    }

    private static int applyCol0Rule(Col0Rule rule, int[] result, int y, int w, int predInit) {
        return switch (rule) {
            case RAW -> result[(y - 1) * w];
            case COL0_PREDINIT -> predInit;
            case COL0_ZERO -> 0;
        };
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
