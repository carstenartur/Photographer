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
 * Round-6 follow-up to {@link CrxPlaneFollowupRound5IT}, per the new
 * requirement (2026-05-01, after Update (i) showed that the residual gap to
 * |r| &gt; 0.7 is global / bulk-dominated and not explained by any
 * first-sample / first-row / first-column / per-row / per-block lead-zero
 * rule).
 *
 * <p><b>Strict test scope.</b> No production code is modified, no flag is
 * added, no hypothesis is promoted. Output dir:
 * {@code canon-cr3-imageio/target/crx-followup-round6/}.</p>
 *
 * <h3>Section J6 — Sign-bit flip under {@code LEAD_ZERO}</h3>
 * Holding predictor = {@code P_LEFT_BASE} and lead-zero =
 * {@code LZ_TILE_FIRST} (the Round-4/5 winner), vary how the residual sign
 * is mapped:
 * <ul>
 *   <li><b>NONE</b>: round-5 baseline ({@code mapSignLsb}, no extra flip).</li>
 *   <li><b>GLOBAL_FLIP</b>: negate the signed residual everywhere.</li>
 *   <li><b>PER_PLANE_FLIP</b>: negate the signed residual only on the green
 *       planes (p0=Gr, p3=Gb under sample.cr3 GRBG).</li>
 *   <li><b>PER_TILE_FLIP</b>: negate the signed residual only inside tile1
 *       (decoded second in raster order).</li>
 *   <li><b>SYMBOL_LSB_FLIP</b>: invert the LSB of the unsigned codeword
 *       <em>before</em> {@code mapSignLsb} — symbol-near sign convention
 *       (swaps the parity of every codeword).</li>
 *   <li><b>RESIDUAL_NEG</b>: structurally identical to GLOBAL_FLIP and
 *       reported as a control to confirm symbol-near vs residual-near map
 *       differently when SYMBOL_LSB_FLIP changes the unsigned magnitude.</li>
 * </ul>
 *
 * <h3>Section K6 — Plane→Bayer permutation re-sweep under {@code LEAD_ZERO}</h3>
 * Update (f)'s permutation sweep ran at the {@code predInit} baseline where
 * |r| ceiled at 0.41. With LEAD_ZERO active and |r| at 0.49/0.56, the gap
 * between the four planes is large enough to discriminate a coherent green
 * pairing. All 24 permutations of plane→preview-Bayer-channel are tested
 * for each tile, and the top 5 by sum-|r| over four planes are reported.
 *
 * <h3>Section L6 — Per-tile K-reset</h3>
 * The K-update is per-symbol, but the seed value at tile start may
 * matter. With LEAD_ZERO active, sweep:
 * <ul>
 *   <li>{@code K_INIT_0} (round-5 baseline)</li>
 *   <li>{@code K_INIT_1} ... {@code K_INIT_7}</li>
 *   <li>{@code K_INIT_FROM_FIRST} — derive K from the first decoded
 *       residual: K = floor(log2(s+1)) (patent §1.8 K* form).</li>
 * </ul>
 *
 * <p>Like the Round-5 IT, this test always succeeds — its purpose is
 * artefact production for {@code SubbandFixtures.md} Update (j) and
 * analysis-doc §5.11.</p>
 */
class CrxPlaneFollowupRound6IT {

    private static final String SAMPLE = "src/test/resources/samples/sample.cr3";
    private static final Path OUT_DIR = Path.of("target", "crx-followup-round6");

    /** How the residual sign is mapped before adding to the predictor. */
    private enum SignMode {
        NONE,
        GLOBAL_FLIP,
        PER_PLANE_FLIP,
        PER_TILE_FLIP,
        SYMBOL_LSB_FLIP,
        RESIDUAL_NEG
    }

    /** Per-tile K-init policy. */
    private enum KInitPolicy {
        K_INIT_0,
        K_INIT_1,
        K_INIT_2,
        K_INIT_3,
        K_INIT_4,
        K_INIT_5,
        K_INIT_7,
        K_INIT_FROM_FIRST
    }

    /** Decoder configuration. */
    private record Config(SignMode signMode,
                          KInitPolicy kInit,
                          boolean planeIsGreen,
                          boolean isTile1) { }

    @Test
    void runRound6Sweep() throws Exception {
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

            // ---------- Section J6 — sign-bit flip ----------
            System.out.printf("%n=== Section J6 — sign-bit flip under LEAD_ZERO_TILE_FIRST ===%n");
            System.out.printf("%-6s %-3s %-22s %12s %12s%n",
                    "tile", "p", "signMode", "outMean", "bayerR");
            for (SignMode sm : SignMode.values()) {
                runSignSweep(f, tile0, "tile0", false, cfaToPreview, chanName, tsv, sm);
                if (tile1 != null) {
                    runSignSweep(f, tile1, "tile1", true, cfaToPreview, chanName, tsv, sm);
                }
            }

            // ---------- Section K6 — plane→Bayer permutation re-sweep ----------
            System.out.printf("%n=== Section K6 — plane→Bayer permutation re-sweep under LEAD_ZERO ===%n");
            int[][] perms = allPermutations4();
            // Decode all four planes once per tile under the round-5 winner config,
            // then evaluate every permutation by per-channel Pearson r.
            int[][] dec0 = decodeAllPlanes(f, tile0, false);
            int[][] dec1 = tile1 != null ? decodeAllPlanes(f, tile1, true) : null;
            int w = tile0[0].planeWidth();
            int h = tile0[0].planeHeight();
            evaluatePermutations(f, dec0, "tile0", perms, w, h, chanName, tsv);
            if (dec1 != null) {
                evaluatePermutations(f, dec1, "tile1", perms, w, h, chanName, tsv);
            }

            // ---------- Section L6 — per-tile K-reset ----------
            System.out.printf("%n=== Section L6 — per-tile K-reset under LEAD_ZERO ===%n");
            System.out.printf("%-6s %-3s %-22s %12s %12s%n",
                    "tile", "p", "kInitPolicy", "outMean", "bayerR");
            for (KInitPolicy kp : KInitPolicy.values()) {
                runKInitSweep(f, tile0, "tile0", false, cfaToPreview, chanName, tsv, kp);
                if (tile1 != null) {
                    runKInitSweep(f, tile1, "tile1", true, cfaToPreview, chanName, tsv, kp);
                }
            }
        }

        summariseTilewiseWinners();
        exportTilewiseHeatmaps(f, tile0, "tile0", false, cfaToPreview);
        if (tile1 != null) exportTilewiseHeatmaps(f, tile1, "tile1", true, cfaToPreview);

        assertThat(OUT_DIR.resolve("metrics.tsv")).exists();
        assertThat(OUT_DIR.resolve("winners.txt")).exists();
    }

    // ============================================================ Section J6

    private static void runSignSweep(File f, SubbandExtractor.SubbandRef[] planes, String tileTag,
                                     boolean isTile1, int[] cfaToPreview, String[] chanName,
                                     PrintWriter tsv, SignMode sm) throws IOException {
        int w = planes[0].planeWidth();
        int h = planes[0].planeHeight();
        int bd = planes[0].bitDepth();
        int midgrey = 1 << (bd - 1);
        for (int pi = 0; pi < 4; pi++) {
            int previewChan = cfaToPreview[pi];
            boolean planeIsGreen = (previewChan == 1 || previewChan == 2); // Gr or Gb
            PreviewReferenceExtractor.Reference ref =
                    PreviewReferenceExtractor.extract(f, previewChan, w, h);
            byte[] payload = SubbandExtractor.read(f, planes[pi]);
            Config cfg = new Config(sm, KInitPolicy.K_INIT_0, planeIsGreen, isTile1);
            int[] dec = decode(payload, w, h, bd, midgrey, cfg);
            if (dec.length < w * h) continue;
            double m = mean(dec);
            double br = pearson(dec, ref.pixels());
            String variant = "P_LEFT_BASE/LZ_TILE_FIRST/sign=" + sm.name();
            System.out.printf("%-6s p%-2d %-22s %12.2f %+12.4f%n", tileTag, pi, sm.name(), m, br);
            tsv.printf("%s\t%s\tp%d\t%s\toutMean\t%.4f%n", "J6", tileTag, pi, variant, m);
            tsv.printf("%s\t%s\tp%d\t%s\tbayer_%s_R\t%.6f%n",
                    "J6", tileTag, pi, variant, chanName[previewChan], br);
        }
    }

    // ============================================================ Section K6

    /** Decode all four planes under the round-5 winner config (sign=NONE, K_INIT_0). */
    private static int[][] decodeAllPlanes(File f, SubbandExtractor.SubbandRef[] planes,
                                           boolean isTile1) throws IOException {
        int w = planes[0].planeWidth();
        int h = planes[0].planeHeight();
        int bd = planes[0].bitDepth();
        int midgrey = 1 << (bd - 1);
        int[][] out = new int[4][];
        for (int pi = 0; pi < 4; pi++) {
            byte[] payload = SubbandExtractor.read(f, planes[pi]);
            // For permutation sweep, the plane itself is invariant — green/tile flags
            // are irrelevant under sign=NONE, so pass false/false.
            Config cfg = new Config(SignMode.NONE, KInitPolicy.K_INIT_0, false, isTile1);
            int[] dec = decode(payload, w, h, bd, midgrey, cfg);
            out[pi] = (dec.length >= w * h) ? dec : new int[w * h];
        }
        return out;
    }

    private static int[][] allPermutations4() {
        List<int[]> acc = new ArrayList<>();
        permute(new int[]{0, 1, 2, 3}, 0, acc);
        return acc.toArray(new int[0][]);
    }

    private static void permute(int[] a, int k, List<int[]> acc) {
        if (k == a.length) { acc.add(a.clone()); return; }
        for (int i = k; i < a.length; i++) {
            int t = a[k]; a[k] = a[i]; a[i] = t;
            permute(a, k + 1, acc);
            t = a[k]; a[k] = a[i]; a[i] = t;
        }
    }

    private static void evaluatePermutations(File f, int[][] dec, String tileTag, int[][] perms,
                                             int w, int h, String[] chanName, PrintWriter tsv)
            throws IOException {
        // Pre-extract all four preview channels (R=0, Gr=1, Gb=2, B=3).
        PreviewReferenceExtractor.Reference[] refs = new PreviewReferenceExtractor.Reference[4];
        for (int c = 0; c < 4; c++) {
            refs[c] = PreviewReferenceExtractor.extract(f, c, w, h);
        }
        System.out.printf("%-6s %-12s %12s %12s %12s %12s %12s%n",
                "tile", "perm", "rR", "rGr", "rGb", "rB", "sum|r|");
        for (int[] perm : perms) {
            // perm[planeIndex] = previewChannelAssigned
            double[] r = new double[4];
            double sumAbs = 0;
            for (int pi = 0; pi < 4; pi++) {
                r[pi] = pearson(dec[pi], refs[perm[pi]].pixels());
                sumAbs += Math.abs(r[pi]);
            }
            String permLabel = "[" + perm[0] + perm[1] + perm[2] + perm[3] + "]";
            String variant = "P_LEFT_BASE/LZ_TILE_FIRST/perm=" + permLabel;
            System.out.printf("%-6s %-12s %+12.4f %+12.4f %+12.4f %+12.4f %12.4f%n",
                    tileTag, permLabel, r[0], r[1], r[2], r[3], sumAbs);
            for (int pi = 0; pi < 4; pi++) {
                tsv.printf("%s\t%s\tp%d\t%s\tbayer_%s_R\t%.6f%n",
                        "K6", tileTag, pi, variant, chanName[perm[pi]], r[pi]);
            }
            tsv.printf("%s\t%s\t-\t%s\tsum_abs_R\t%.6f%n", "K6", tileTag, variant, sumAbs);
        }
    }

    // ============================================================ Section L6

    private static void runKInitSweep(File f, SubbandExtractor.SubbandRef[] planes, String tileTag,
                                      boolean isTile1, int[] cfaToPreview, String[] chanName,
                                      PrintWriter tsv, KInitPolicy kp) throws IOException {
        int w = planes[0].planeWidth();
        int h = planes[0].planeHeight();
        int bd = planes[0].bitDepth();
        int midgrey = 1 << (bd - 1);
        for (int pi = 0; pi < 4; pi++) {
            int previewChan = cfaToPreview[pi];
            PreviewReferenceExtractor.Reference ref =
                    PreviewReferenceExtractor.extract(f, previewChan, w, h);
            byte[] payload = SubbandExtractor.read(f, planes[pi]);
            Config cfg = new Config(SignMode.NONE, kp, false, isTile1);
            int[] dec = decode(payload, w, h, bd, midgrey, cfg);
            if (dec.length < w * h) continue;
            double m = mean(dec);
            double br = pearson(dec, ref.pixels());
            String variant = "P_LEFT_BASE/LZ_TILE_FIRST/kInit=" + kp.name();
            System.out.printf("%-6s p%-2d %-22s %12.2f %+12.4f%n", tileTag, pi, kp.name(), m, br);
            tsv.printf("%s\t%s\tp%d\t%s\toutMean\t%.4f%n", "L6", tileTag, pi, variant, m);
            tsv.printf("%s\t%s\tp%d\t%s\tbayer_%s_R\t%.6f%n",
                    "L6", tileTag, pi, variant, chanName[previewChan], br);
        }
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
            // Include both per-plane bayer_*_R and per-permutation sum_abs_R rows.
            if (!f[4].startsWith("bayer_") && !f[4].equals("sum_abs_R")) continue;
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
            pw.println("Round-6 winners — tilewise top 5 per section (by |r| or sum|r|)");
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

    // ============================================================ heatmaps

    private static void exportTilewiseHeatmaps(File f, SubbandExtractor.SubbandRef[] planes,
                                               String tileTag, boolean isTile1, int[] cfaToPreview)
            throws IOException {
        int w = planes[0].planeWidth();
        int h = planes[0].planeHeight();
        int bd = planes[0].bitDepth();
        int midgrey = 1 << (bd - 1);
        byte[] payload = SubbandExtractor.read(f, planes[0]);
        boolean planeIsGreen = (cfaToPreview[0] == 1 || cfaToPreview[0] == 2);

        Object[][] choices = {
                {"J6_signNONE", new Config(SignMode.NONE, KInitPolicy.K_INIT_0, planeIsGreen, isTile1)},
                {"J6_signGLOBAL", new Config(SignMode.GLOBAL_FLIP, KInitPolicy.K_INIT_0, planeIsGreen, isTile1)},
                {"J6_signSYMBOL", new Config(SignMode.SYMBOL_LSB_FLIP, KInitPolicy.K_INIT_0, planeIsGreen, isTile1)},
                {"L6_kInit2", new Config(SignMode.NONE, KInitPolicy.K_INIT_2, planeIsGreen, isTile1)},
                {"L6_kInit7", new Config(SignMode.NONE, KInitPolicy.K_INIT_7, planeIsGreen, isTile1)},
        };
        for (Object[] c : choices) {
            String name = (String) c[0];
            Config cfg = (Config) c[1];
            int[] dec = decode(payload, w, h, bd, midgrey, cfg);
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
     * P_LEFT_BASE row-major decoder with LEAD_ZERO_TILE_FIRST baseline and
     * round-6 knobs (sign mapping + K-init policy). Residuals are always
     * consumed from the bitstream so K-state is consistent across variants.
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

        // Initial K from per-tile policy.
        int k = switch (cfg.kInit) {
            case K_INIT_0 -> 0;
            case K_INIT_1 -> 1;
            case K_INIT_2 -> 2;
            case K_INIT_3 -> 3;
            case K_INIT_4 -> 4;
            case K_INIT_5 -> 5;
            case K_INIT_7 -> 7;
            case K_INIT_FROM_FIRST -> {
                // Two-pass: peek at first symbol with k=0, derive K* = floor(log2(s+1)),
                // then reset reader and re-decode from scratch.
                BitReader peek = new BitReader(payload, 0, payload.length);
                int firstRaw = peek.hasMore() ? decodeRice(peek, 0) : 0;
                int kFromFirst = (firstRaw <= 0)
                        ? 0
                        : Math.min(bd, 31 - Integer.numberOfLeadingZeros(firstRaw + 1));
                yield kFromFirst;
            }
        };
        if (cfg.kInit == KInitPolicy.K_INIT_FROM_FIRST) {
            reader = new BitReader(payload, 0, payload.length);
        }

        boolean planeFlip = (cfg.signMode == SignMode.PER_PLANE_FLIP) && cfg.planeIsGreen;
        boolean tileFlip = (cfg.signMode == SignMode.PER_TILE_FLIP) && cfg.isTile1;
        boolean globalFlip = (cfg.signMode == SignMode.GLOBAL_FLIP)
                || (cfg.signMode == SignMode.RESIDUAL_NEG)
                || planeFlip || tileFlip;
        boolean symbolFlip = (cfg.signMode == SignMode.SYMBOL_LSB_FLIP);

        for (int n = 0; n < w * h; n++) {
            if (!reader.hasMore()) return Arrays.copyOf(result, n);
            int raw = decodeRice(reader, k);
            int residualForK = raw;
            int symbol = symbolFlip ? (raw ^ 1) : raw;
            int signed = mapSignLsb(symbol);
            if (globalFlip) signed = -signed;

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
            k = updateK(k, residualForK, bd);
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
