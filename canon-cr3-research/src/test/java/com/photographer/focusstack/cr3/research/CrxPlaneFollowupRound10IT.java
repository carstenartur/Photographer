package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.raw.codec.BitReader;
import com.photographer.focusstack.cr3.raw.codec.InverseWaveletTransform;
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
 * Round-10 follow-up to {@link CrxPlaneFollowupRound9IT}. Implements the
 * Update (n) §1 next step: <b>multi-level IDWT + dequantisation on a real
 * C-RAW bitstream</b>.
 *
 * <p>This is the first IT in the corpus that actually exercises the
 * {@link InverseWaveletTransform#inverseSynthesis2D} path on a fixture
 * with {@code waveletLevels &gt; 0}. Every previous round operated on
 * {@code waveletLevels = 0} fixtures (lossless RAW), where the four
 * "planes per tile" are the four Bayer channels rather than four wavelet
 * subbands of one decomposition level.</p>
 *
 * <p>Fixture: {@code g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-02.cr3}.
 * Per Update (m) the C-RAW fixture has
 * {@code waveletLevels = 3, encodingType = lossy, q-vector = {4,16,22,26,32}},
 * and reports 80 subbands ({@code 2 tiles × 4 planes × 10 subbands per
 * plane}). With {@code L = 3} a single plane decomposes into:</p>
 * <pre>
 *   sb0          = LL_3   (lowpass approximation at deepest level)
 *   sb1, 2, 3    = HL_3, LH_3, HH_3   (level-3 detail bands)
 *   sb4, 5, 6    = HL_2, LH_2, HH_2   (level-2 detail bands)
 *   sb7, 8, 9    = HL_1, LH_1, HH_1   (level-1 detail bands)
 * </pre>
 *
 * <h3>Pipeline under test</h3>
 * For each (tile, plane):
 * <ol>
 *   <li>Decode each of the 10 subbands as <b>signed wavelet
 *       coefficients</b> using the Round-9 winning entropy stage
 *       ({@code K_INIT = 0}, patent {@code K_BRACKET} update,
 *       sign-LSB mapping). No spatial predictor is applied: wavelet
 *       coefficients are zero-mean (apart from LL).</li>
 *   <li>Apply a candidate inverse-quantisation rule to the decoded
 *       coefficients. Three rules are tested:
 *       <ul>
 *         <li>{@code D_IDENTITY} — no dequant ({@code coef * 1}).</li>
 *         <li>{@code D_MUL_Q} — straight reconstruction
 *             ({@code coef * q}).</li>
 *         <li>{@code D_MUL_Q_HALF} — JPEG2000-style midpoint reconstruction
 *             ({@code coef * q + sign(coef)*q/2}).</li>
 *       </ul>
 *   </li>
 *   <li>Run three inverse 2D Le Gall 5/3 synthesis passes:
 *       {@code LL_2 = IDWT(LL_3, HL_3, LH_3, HH_3)},
 *       {@code LL_1 = IDWT(LL_2, HL_2, LH_2, HH_2)},
 *       {@code plane = IDWT(LL_1, HL_1, LH_1, HH_1)}.</li>
 *   <li>Score the reconstructed plane against the matching preview
 *       Bayer channel (Pearson r), using the GRBG-derived plane→Bayer
 *       map.</li>
 *   <li>For LL_3 also try a second convention where LL coefficients are
 *       read as <b>unsigned</b> Rice codewords (LL is non-negative for
 *       Le Gall 5/3 of a non-negative input). The non-LL detail bands
 *       always use signed-LSB mapping.</li>
 * </ol>
 *
 * <p><b>Strict test scope.</b> No production code is modified, no flag
 * is added, no hypothesis is promoted. Output dir:
 * {@code canon-cr3-imageio/target/crx-followup-round10/}. The test
 * always passes — it produces artefacts ({@code metrics.tsv},
 * {@code winners.txt}) for SubbandFixtures.md Update (o).</p>
 */
class CrxPlaneFollowupRound10IT {

    private static final String FIXTURE_DIR = "src/test/resources/samples/research";
    private static final String CRAW_FIXTURE =
            "g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-02.cr3";
    private static final Path OUT_DIR = Path.of("target", "crx-followup-round10");

    private static final int MAX_UNARY = 64;

    /** Inverse-quantisation rule applied to decoded coefficients. */
    private enum Dequant { D_IDENTITY, D_MUL_Q, D_MUL_Q_HALF }

    /** LL coefficient sign convention. */
    private enum LlMode { LL_SIGNED, LL_UNSIGNED }

    @Test
    void runRound10Sweep() throws Exception {
        Files.createDirectories(OUT_DIR);
        File f = new File(FIXTURE_DIR + "/" + CRAW_FIXTURE);
        if (!f.exists()) {
            writeSkip("fixture-not-present: " + f);
            return;
        }

        Cmp1Box cmp1 = readCmp1(f);
        if (cmp1 == null) { writeSkip("no-CMP1"); return; }
        int waveletLevels = cmp1.waveletLevels();
        int[] cfaToPreview = cfaLayoutMap(cmp1.cfaLayout());

        List<SubbandExtractor.SubbandRef> subs = SubbandExtractor.list(f);
        if (subs.isEmpty()) { writeSkip("no-subbands"); return; }

        // Group by (tile, plane) preserving subband order.
        Map<String, List<SubbandExtractor.SubbandRef>> groups = new LinkedHashMap<>();
        for (SubbandExtractor.SubbandRef s : subs) {
            String key = "tile" + s.tileSequence() + "/p" + s.planeIndex();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }

        // Sanity: expect 1 + 3*L subbands per (tile, plane).
        int expectedPerPlane = (waveletLevels <= 0) ? 1 : 1 + 3 * waveletLevels;
        for (var e : groups.entrySet()) {
            if (e.getValue().size() != expectedPerPlane) {
                System.out.printf("R10 unexpected subband count on %s: got %d, expected %d (L=%d)%n",
                        e.getKey(), e.getValue().size(), expectedPerPlane, waveletLevels);
            }
        }

        SubbandExtractor.SubbandRef any = subs.get(0);
        int planeW = any.planeWidth();
        int planeH = any.planeHeight();
        int bd = any.bitDepth();
        int midgrey = 1 << (bd - 1);

        System.out.printf("%n=== Round-10 multi-level IDWT on %s ===%n", CRAW_FIXTURE);
        System.out.printf("    plane %d×%d, bd=%d, waveletLevels=%d, cfaLayout=%s, %d subbands total%n",
                planeW, planeH, bd, waveletLevels, cmp1.cfaLayoutName(), subs.size());

        // Reference grids at the un-decomposed plane size.
        PreviewReferenceExtractor.Reference[] refs = new PreviewReferenceExtractor.Reference[4];
        for (int c = 0; c < 4; c++) {
            refs[c] = PreviewReferenceExtractor.extract(f, c, planeW, planeH);
        }

        try (PrintWriter tsv = new PrintWriter(OUT_DIR.resolve("metrics.tsv").toFile())) {
            tsv.println("section\ttile\tplane\tvariant\tmetric\tvalue");

            // Per-subband summary (truncation diagnostics).
            for (var e : groups.entrySet()) {
                List<SubbandExtractor.SubbandRef> sbs = e.getValue();
                int[][] dims = subbandDims(planeW, planeH, waveletLevels);
                if (dims.length != sbs.size()) {
                    // Mismatch — fall back to dims for the subbands we got.
                    dims = Arrays.copyOf(dims, sbs.size());
                }
                for (int i = 0; i < sbs.size(); i++) {
                    SubbandExtractor.SubbandRef s = sbs.get(i);
                    int expected = dims[i][0] * dims[i][1];
                    tsv.printf("R10/dim\t%s\tsb%d\tlevel=%d/role=%s\texpected_coefs\t%d%n",
                            e.getKey(), i, levelOf(i), roleOf(i), expected);
                    tsv.printf("R10/dim\t%s\tsb%d\tq=%d\tpayload_bytes\t%d%n",
                            e.getKey(), i, s.quantValue(), s.dataSize());
                }
            }

            // Main IDWT sweep.
            double[][][] perPlaneScores =
                    new double[groups.size()][Dequant.values().length * LlMode.values().length][];

            int planeIdx = 0;
            for (var e : groups.entrySet()) {
                String tag = e.getKey();
                List<SubbandExtractor.SubbandRef> sbs = e.getValue();
                int tileSeq = sbs.get(0).tileSequence();
                int planeNum = sbs.get(0).planeIndex();
                int previewChan = cfaToPreview[planeNum];

                int[][] dims = subbandDims(planeW, planeH, waveletLevels);
                if (sbs.size() < dims.length) {
                    System.out.printf("R10 %s: only %d/%d subbands present, skipping%n",
                            tag, sbs.size(), dims.length);
                    planeIdx++;
                    continue;
                }

                // Decode every subband once with signed-LSB mapping. LL_3 also gets an
                // unsigned variant.
                int[][] coefSigned = new int[sbs.size()][];
                int[] coefLlUnsigned = null;
                for (int i = 0; i < sbs.size(); i++) {
                    byte[] payload = SubbandExtractor.read(f, sbs.get(i));
                    int n = dims[i][0] * dims[i][1];
                    coefSigned[i] = decodeRiceSubband(payload, n, bd, /*signed*/ true);
                }
                {
                    byte[] payload = SubbandExtractor.read(f, sbs.get(0));
                    int n = dims[0][0] * dims[0][1];
                    coefLlUnsigned = decodeRiceSubband(payload, n, bd, /*signed*/ false);
                }

                int variantIdx = 0;
                for (Dequant dq : Dequant.values()) {
                    for (LlMode llm : LlMode.values()) {
                        int[][] coefs = new int[sbs.size()][];
                        for (int i = 0; i < sbs.size(); i++) {
                            int q = sbs.get(i).quantValue();
                            int[] src = (i == 0 && llm == LlMode.LL_UNSIGNED)
                                    ? coefLlUnsigned : coefSigned[i];
                            coefs[i] = applyDequant(src, dq, q);
                        }

                        int[] recon = idwtMultiLevel(coefs, dims, waveletLevels);
                        // Reconstruction grid may be slightly shorter than the preview grid
                        // when planeH is not divisible by 2^L (e.g. 1855 >> 3 = 231 → 1848,
                        // losing 7 rows of 1408). Pearson r is computed over the row-aligned
                        // overlap.
                        int reconW = planeW;                                  // always 2^L * (planeW>>L) == planeW for our fixture
                        int reconH = (recon == null) ? 0 : recon.length / Math.max(1, reconW);
                        double r = 0.0;
                        if (recon != null && reconH > 0) {
                            int overlapH = Math.min(reconH, planeH);
                            int n = reconW * overlapH;
                            int[] candTrim = (recon.length == n) ? recon : Arrays.copyOf(recon, n);
                            int[] refTrim  = Arrays.copyOf(refs[previewChan].pixels(), n);
                            r = pearson(candTrim, refTrim);
                        }

                        String variant = "R10/dq=" + dq + "/llmode=" + llm;
                        tsv.printf("R10\t%s\t-\t%s\tbayer_%s_R\t%.6f%n",
                                tag, variant, refs[previewChan].channel(), r);
                        if (recon != null) {
                            int[] stats = quickStats(recon);
                            tsv.printf("R10\t%s\t-\t%s\trecon_min\t%d%n", tag, variant, stats[0]);
                            tsv.printf("R10\t%s\t-\t%s\trecon_max\t%d%n", tag, variant, stats[1]);
                            tsv.printf("R10\t%s\t-\t%s\trecon_mean\t%d%n", tag, variant, stats[2]);
                        }

                        perPlaneScores[planeIdx][variantIdx] =
                                new double[]{r, recon == null ? 0 : recon.length};
                        variantIdx++;
                    }
                }
                planeIdx++;
            }

            summariseWinners(groups, perPlaneScores);
        }

        assertThat(OUT_DIR.resolve("metrics.tsv")).exists();
        assertThat(OUT_DIR.resolve("winners.txt")).exists();
    }

    // ============================================================ subband geometry

    /**
     * Returns {@code [width, height]} for each subband index in the canonical
     * Canon CR3 ordering (LL_L, then HL/LH/HH at level L, L-1, ..., 1).
     */
    private static int[][] subbandDims(int planeW, int planeH, int levels) {
        int n = (levels <= 0) ? 1 : 1 + 3 * levels;
        int[][] dims = new int[n][2];
        // sb0 = LL at deepest level
        dims[0][0] = Math.max(1, planeW >> levels);
        dims[0][1] = Math.max(1, planeH >> levels);
        for (int lev = levels; lev >= 1; lev--) {
            int w = Math.max(1, planeW >> lev);
            int h = Math.max(1, planeH >> lev);
            int base = 1 + 3 * (levels - lev);
            // HL_lev, LH_lev, HH_lev
            dims[base    ][0] = w; dims[base    ][1] = h;
            dims[base + 1][0] = w; dims[base + 1][1] = h;
            dims[base + 2][0] = w; dims[base + 2][1] = h;
        }
        return dims;
    }

    private static int levelOf(int sbIdx) {
        if (sbIdx == 0) return -1; // LL marker
        return 1 + ((sbIdx - 1) / 3); // role within level 1..L counted from deepest
    }

    private static String roleOf(int sbIdx) {
        if (sbIdx == 0) return "LL";
        int r = (sbIdx - 1) % 3;
        return switch (r) { case 0 -> "HL"; case 1 -> "LH"; default -> "HH"; };
    }

    // ============================================================ multi-level IDWT

    /**
     * Applies {@code levels} successive 2D inverse Le Gall 5/3 syntheses,
     * starting with {@code coefs[0]} as LL_L and consuming the detail
     * triples in order.
     */
    private static int[] idwtMultiLevel(int[][] coefs, int[][] dims, int levels) {
        if (levels <= 0) return coefs[0];
        int[] ll = coefs[0];
        int llW = dims[0][0];
        int llH = dims[0][1];
        for (int lev = levels; lev >= 1; lev--) {
            int base = 1 + 3 * (levels - lev);
            int[] hl = coefs[base];
            int[] lh = coefs[base + 1];
            int[] hh = coefs[base + 2];
            int outW = llW * 2;
            int outH = llH * 2;
            // Pad coefficient arrays if entropy decode truncated them.
            int need = llW * llH;
            ll = padTo(ll, need);
            hl = padTo(hl, need);
            lh = padTo(lh, need);
            hh = padTo(hh, need);
            try {
                ll = InverseWaveletTransform.inverseSynthesis2D(
                        ll, lh, hl, hh, llW, llH, outW, outH,
                        InverseWaveletTransform.FilterType.LEGALL_5_3);
            } catch (RuntimeException e) {
                return null;
            }
            llW = outW;
            llH = outH;
        }
        return ll;
    }

    private static int[] padTo(int[] src, int n) {
        if (src != null && src.length >= n) return src;
        int[] out = new int[n];
        if (src != null) System.arraycopy(src, 0, out, 0, src.length);
        return out;
    }

    // ============================================================ dequant

    private static int[] applyDequant(int[] coef, Dequant dq, int q) {
        if (coef == null) return new int[0];
        if (dq == Dequant.D_IDENTITY || q <= 1) return coef;
        int[] out = new int[coef.length];
        switch (dq) {
            case D_MUL_Q -> {
                for (int i = 0; i < coef.length; i++) out[i] = coef[i] * q;
            }
            case D_MUL_Q_HALF -> {
                int half = q / 2;
                for (int i = 0; i < coef.length; i++) {
                    int c = coef[i];
                    if (c > 0) out[i] = c * q + half;
                    else if (c < 0) out[i] = c * q - half;
                    else out[i] = 0;
                }
            }
            default -> System.arraycopy(coef, 0, out, 0, coef.length);
        }
        return out;
    }

    // ============================================================ entropy decode

    /**
     * Rice/Golomb decode of one subband payload into exactly {@code n}
     * coefficients (or fewer if the bitstream exhausts). Uses
     * {@code K_INIT = 0} and the patent {@code K_BRACKET} update rule
     * (matches Round-9's empirically validated entropy stage).
     *
     * @param signed if true, output values are zig-zag-decoded with
     *               {@link #mapSignLsb}; if false, raw unsigned magnitudes
     *               are returned (suitable for an LL-band that is
     *               non-negative).
     */
    private static int[] decodeRiceSubband(byte[] payload, int n, int bd, boolean signed) {
        if (payload == null || payload.length == 0 || n <= 0) return new int[0];
        BitReader rd = new BitReader(payload);
        int[] out = new int[n];
        int k = 0;
        int produced = 0;
        try {
            while (produced < n && rd.hasMore()) {
                // unary prefix
                int q = 0;
                while (true) {
                    if (!rd.hasMore()) throw new IOException("eof in unary");
                    if (rd.readBit() != 0) break;
                    q++;
                    if (q > MAX_UNARY) {
                        return Arrays.copyOf(out, produced);
                    }
                }
                // remainder
                int r = 0;
                for (int i = 0; i < k; i++) {
                    if (!rd.hasMore()) throw new IOException("eof in rem");
                    r = (r << 1) | rd.readBit();
                }
                int s = (q << k) | r;
                out[produced++] = signed ? mapSignLsb(s) : s;

                // patent K-bracket update
                int kStar = (s <= 0) ? 0 : 31 - Integer.numberOfLeadingZeros(s + 1);
                int kMin = Math.max(0, kStar - 1);
                int kMax = Math.min(bd, kStar + 1);
                if (k < kMin) k++;
                else if (k > kMax) k--;
            }
        } catch (IOException eof) {
            return Arrays.copyOf(out, produced);
        }
        return out;
    }

    private static int mapSignLsb(int s) {
        return ((s & 1) == 0) ? (s >> 1) : -((s >> 1) + 1);
    }

    // ============================================================ scoring

    private static double pearson(int[] cand, int[] ref) {
        int n = Math.min(cand.length, ref.length);
        if (n < 2) return 0;
        long mn = Long.MAX_VALUE, mx = Long.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            int v = cand[i];
            if (v < mn) mn = v;
            if (v > mx) mx = v;
        }
        long range = Math.max(1, mx - mn);
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

    private static int[] quickStats(int[] xs) {
        if (xs.length == 0) return new int[]{0, 0, 0};
        long mn = Long.MAX_VALUE, mx = Long.MIN_VALUE, sum = 0;
        for (int v : xs) { if (v < mn) mn = v; if (v > mx) mx = v; sum += v; }
        return new int[]{(int) mn, (int) mx, (int) (sum / xs.length)};
    }

    // ============================================================ artefacts

    private static void summariseWinners(Map<String, List<SubbandExtractor.SubbandRef>> groups,
                                         double[][][] scores) throws IOException {
        try (PrintWriter pw = new PrintWriter(OUT_DIR.resolve("winners.txt").toFile())) {
            pw.println("Round-10 — multi-level IDWT + dequant on C-RAW (waveletLevels=3)");
            pw.println("=================================================================");
            pw.println();
            String[] dqs = Arrays.stream(Dequant.values()).map(Enum::name).toArray(String[]::new);
            String[] llms = Arrays.stream(LlMode.values()).map(Enum::name).toArray(String[]::new);

            int planeIdx = 0;
            double globalMaxAbs = 0;
            String globalMaxLabel = "(none)";
            for (var e : groups.entrySet()) {
                String tag = e.getKey();
                pw.printf("--- %s ---%n", tag);
                int variantIdx = 0;
                for (Dequant dq : Dequant.values()) {
                    for (LlMode llm : LlMode.values()) {
                        double[] s = scores[planeIdx][variantIdx];
                        double r = (s == null) ? 0 : s[0];
                        long len = (s == null) ? 0L : (long) s[1];
                        pw.printf("  dq=%-14s ll=%-12s r=%+.4f reconLen=%d%n",
                                dq.name(), llm.name(), r, len);
                        if (Math.abs(r) > globalMaxAbs) {
                            globalMaxAbs = Math.abs(r);
                            globalMaxLabel = tag + " dq=" + dq + " ll=" + llm;
                        }
                        variantIdx++;
                    }
                }
                pw.println();
                planeIdx++;
            }
            pw.printf("GLOBAL MAX |r| = %.4f at %s%n", globalMaxAbs, globalMaxLabel);
            pw.println();
            if (globalMaxAbs >= 0.7) {
                pw.println("INTERPRETATION: max|r| >= 0.7 — promotion threshold cleared on at least");
                pw.println("one (tile, plane). Investigate before any production change.");
            } else {
                pw.println("INTERPRETATION: max|r| < 0.7 — multi-level IDWT + signed-LSB Rice with");
                pw.println("the Round-9 K stage does not by itself reach the promotion threshold.");
                pw.println("Likely missing: per-subband predictor (Canon C-RAW is documented to");
                pw.println("apply a horizontal predictor inside each subband BEFORE entropy");
                pw.println("coding), or a different coefficient ordering / sign convention.");
            }
        }
    }

    private static void writeSkip(String reason) throws IOException {
        try (PrintWriter pw = new PrintWriter(OUT_DIR.resolve("metrics.tsv").toFile())) {
            pw.println("section\ttile\tplane\tvariant\tmetric\tvalue");
            pw.printf("R10\t-\t-\tskipped\tnote\t0%n");
        }
        try (PrintWriter pw = new PrintWriter(OUT_DIR.resolve("winners.txt").toFile())) {
            pw.println("Round-10: skipped (" + reason + ")");
        }
    }

    // ============================================================ helpers

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
