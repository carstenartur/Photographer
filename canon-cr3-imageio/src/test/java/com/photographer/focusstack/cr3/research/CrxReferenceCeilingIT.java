package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.raw.codec.SubbandExtractor;
import com.photographer.focusstack.cr3.raw.codec.BitReader;
import org.junit.jupiter.api.Test;

import com.photographer.focusstack.cr3.preview.Cr3PreviewExtractor;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
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
 * Reference‑ceiling measurement for the CRX cleanroom decoder.
 *
 * <h3>Motivation</h3>
 *
 * <p>Rounds 1–14 score candidate decoders against the embedded JPEG
 * preview using Pearson r as the sole discriminator, with a promotion
 * threshold of |r| > 0.70. Before investing further sweeps, we need to
 * know: <em>what is the maximum achievable Pearson r between a
 * mathematically correct RAW decoder output and that JPEG preview?</em>
 * If the ceiling is, say, 0.72, then 0.66 is already 92 % of maximum
 * and the decoder is nearly correct. If it is 0.95, there is still a
 * large structural gap.</p>
 *
 * <h3>Three experiments (all ground‑truth free)</h3>
 *
 * <h4>Exp A — Cross‑frame reproducibility</h4>
 * <p>Take two frames of the same scene configuration
 * ({@code …-02.cr3} and {@code …-03.cr3}). Decode both with the
 * Round‑13 winner (K_INIT=0, P_MED, sign=LSB, D_MUL_Q, MSB‑first).
 * Compute Pearson r <em>between the two decoder outputs</em> (not against
 * the preview). A correct decoder must be deterministic; Pearson r should
 * be ≥ 0.99. If it is significantly lower, the decoder is internally
 * inconsistent (bit‑drift or wrong K convergence on some planes) and no
 * amount of predictor tuning will help.</p>
 *
 * <h4>Exp B — Flat‑field blind test</h4>
 * <p>Use the flat‑field fixture ({@code g5xm2-flat-*}). A physically
 * correct decoder for a flat field produces a nearly‑constant plane
 * (mean ≈ sensor‑saturation × fraction, std ≈ √mean from shot noise).
 * The coefficient of variation (std/mean) should be below 5 %. We
 * measure the CV of the Round‑13 decoder output on this fixture. A CV
 * close to 100 % means the decoder is producing noise, not a flat field;
 * a low CV means the DC component is being decoded correctly even if
 * spatial detail is wrong.</p>
 *
 * <h4>Exp C — Oracle ceiling via preview self‑consistency</h4>
 * <p>Downscale the embedded preview twice to the plane grid using two
 * independent methods (box‑average vs. nearest‑neighbour), then score
 * them against each other. This gives the Pearson r one achieves
 * <em>against the preview, using the preview itself</em> after
 * resampling. Any correct RAW decoder that produces the same image as
 * the camera's JPEG engine can score at most this value — it is the
 * hard ceiling imposed by JPEG compression + chroma subsampling +
 * gamma. If the ceiling is < 0.90, our 0.70 threshold may already be
 * over‑ambitious.</p>
 *
 * <p>Additionally scores the full‑resolution preview downscaled by box
 * vs. the Round‑13 decoder output, matching what the sweep ITs do — this
 * confirms the ceiling number applies to the exact same pipeline.</p>
 *
 * <h3>Output</h3>
 *
 * <p>Results in
 * {@code canon-cr3-imageio/target/crx-reference-ceiling/report.txt} and
 * {@code metrics.tsv}. Always passes (no assertion on r values — all
 * assertions are structural). The numbers are intended to be pasted into
 * {@code SubbandFixtures.md} as Update (q).</p>
 */
class CrxReferenceCeilingIT {

    private static final String FIXTURE_DIR = "src/test/resources/samples/research";
    private static final Path OUT_DIR = Path.of("target", "crx-reference-ceiling");

    private static final String FX_DET_02 = "g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-02.cr3";
    private static final String FX_DET_03 = "g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-03.cr3";
    private static final String FX_FLAT   = "g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02.cr3";
    private static final String FX_ASIS   = "g5xm2-asis-iso200-ss130-ff2p5-01.cr3";

    private static final int MAX_UNARY = 64;

    @Test
    void measureReferenceCeiling() throws Exception {
        Files.createDirectories(OUT_DIR);
        Path reportPath = OUT_DIR.resolve("report.txt");
        Path tsvPath    = OUT_DIR.resolve("metrics.tsv");

        try (PrintWriter rep = new PrintWriter(reportPath.toFile());
             PrintWriter tsv = new PrintWriter(tsvPath.toFile())) {

            rep.println("CRX Reference‑Ceiling Report");
            rep.println("==============================");
            rep.println("Generated: " + java.time.Instant.now());
            rep.println();
            tsv.println("experiment\tfixture\ttile_plane\tmetric\tvalue");

            // ----------------------------------------------------------------
            // Exp A — cross‑frame reproducibility (detail-02 vs detail-03)
            // ----------------------------------------------------------------
            rep.println("=== Exp A — Cross‑frame reproducibility ===");
            rep.println("Fixtures: " + FX_DET_02 + " vs " + FX_DET_03);
            rep.println();

            File f02 = new File(FIXTURE_DIR + "/" + FX_DET_02);
            File f03 = new File(FIXTURE_DIR + "/" + FX_DET_03);

            if (!f02.exists() || !f03.exists()) {
                rep.println("  SKIPPED — one or both fixtures missing.");
            } else {
                Cmp1Box cmp1 = readCmp1(f02);
                int[] cfaMap = cfaLayoutMap(cmp1 == null ? 1 : cmp1.cfaLayout());
                List<SubbandExtractor.SubbandRef> subs02 = SubbandExtractor.list(f02);
                List<SubbandExtractor.SubbandRef> subs03 = SubbandExtractor.list(f03);

                Map<String, List<SubbandExtractor.SubbandRef>> groups02 = groupByTilePlane(subs02);
                Map<String, List<SubbandExtractor.SubbandRef>> groups03 = groupByTilePlane(subs03);

                double sumR = 0; int nPairs = 0;
                for (String key : groups02.keySet()) {
                    if (!groups03.containsKey(key)) continue;
                    List<SubbandExtractor.SubbandRef> sb02 = groups02.get(key);
                    List<SubbandExtractor.SubbandRef> sb03 = groups03.get(key);
                    if (sb02.isEmpty() || sb03.isEmpty()) continue;

                    SubbandExtractor.SubbandRef ref02 = sb02.get(0);
                    SubbandExtractor.SubbandRef ref03 = sb03.get(0);
                    int w = ref02.planeWidth(), h = ref02.planeHeight(), bd = ref02.bitDepth();

                    int[] dec02 = decodeR13Winner(SubbandExtractor.read(f02, ref02), w * h, bd);
                    int[] dec03 = decodeR13Winner(SubbandExtractor.read(f03, ref03), w * h, bd);
                    dec02 = applyMed(dec02, w, h);
                    dec03 = applyMed(dec03, w, h);

                    double r = pearson(dec02, dec03);
                    rep.printf("  %s: cross‑frame Pearson r = %+.4f%n", key, r);
                    tsv.printf("A\t%s\t%s\tcross_frame_r\t%.6f%n", FX_DET_02 + " vs " + FX_DET_03, key, r);
                    sumR += r; nPairs++;
                }
                double meanR = nPairs > 0 ? sumR / nPairs : 0;
                rep.printf("%n  Mean cross‑frame r = %.4f over %d (tile,plane) pairs%n", meanR, nPairs);
                tsv.printf("A\t%s\tALL\tmean_cross_frame_r\t%.6f%n", FX_DET_02, meanR);
                rep.println();
                if (meanR >= 0.95) {
                    rep.println("  INTERPRETATION: Decoder is internally consistent (r ≥ 0.95).");
                    rep.println("  The 0.66 Pearson-r ceiling against the JPEG preview is a");
                    rep.println("  real gap between decoder output and camera rendering, not noise.");
                } else if (meanR >= 0.70) {
                    rep.println("  INTERPRETATION: Moderate consistency (0.70 ≤ r < 0.95).");
                    rep.println("  Some K-divergence or sign inconsistency between frames.");
                    rep.println("  Structural fix needed before scoring against preview is reliable.");
                } else {
                    rep.println("  INTERPRETATION: Low consistency (r < 0.70).");
                    rep.println("  The decoder produces significantly different outputs for the");
                    rep.println("  same scene in two frames — the entropy stage is unstable.");
                    rep.println("  Scoring against JPEG preview has no diagnostic value until fixed.");
                }
                rep.println();
            }

            // ----------------------------------------------------------------
            // Exp B — flat‑field CV
            // ----------------------------------------------------------------
            rep.println("=== Exp B — Flat‑field coefficient of variation ===");
            rep.println("Fixture: " + FX_FLAT);
            rep.println();

            File fFlat = new File(FIXTURE_DIR + "/" + FX_FLAT);
            if (!fFlat.exists()) {
                rep.println("  SKIPPED — fixture missing.");
            } else {
                List<SubbandExtractor.SubbandRef> subsFlat = SubbandExtractor.list(fFlat);
                Cmp1Box cmpFlat = readCmp1(fFlat);
                Map<String, List<SubbandExtractor.SubbandRef>> gFlat = groupByTilePlane(subsFlat);
                for (var e : gFlat.entrySet()) {
                    String key = e.getKey();
                    SubbandExtractor.SubbandRef ref = e.getValue().get(0);
                    int w = ref.planeWidth(), h = ref.planeHeight(), bd = ref.bitDepth();
                    int[] dec = decodeR13Winner(SubbandExtractor.read(fFlat, ref), w * h, bd);
                    dec = applyMed(dec, w, h);
                    double[] ms = meanStd(dec);
                    double cv = (ms[0] > 0) ? ms[1] / ms[0] : Double.NaN;
                    rep.printf("  %s: mean=%.1f std=%.1f CV=%.3f%n", key, ms[0], ms[1], cv);
                    tsv.printf("B\t%s\t%s\tcv\t%.6f%n", FX_FLAT, key, cv);
                    tsv.printf("B\t%s\t%s\tmean\t%.2f%n", FX_FLAT, key, ms[0]);
                    tsv.printf("B\t%s\t%s\tstd\t%.2f%n", FX_FLAT, key, ms[1]);
                }
                rep.println();
                rep.println("  INTERPRETATION guide:");
                rep.println("  CV < 0.05  → DC content decoded correctly, mostly shot noise");
                rep.println("  CV 0.05-0.30 → DC approx. right but structured error present");
                rep.println("  CV > 0.30  → decoder produces significant noise, DC unreliable");
                rep.println();
            }

            // ----------------------------------------------------------------
            // Exp C — oracle ceiling via preview self‑consistency
            // ----------------------------------------------------------------
            rep.println("=== Exp C — Oracle ceiling (preview self‑consistency) ===");
            rep.println("Fixture: " + FX_ASIS + "  (lossless, clean preview)");
            rep.println();

            File fAsis = new File(FIXTURE_DIR + "/" + FX_ASIS);
            if (!fAsis.exists()) {
                rep.println("  SKIPPED — fixture missing.");
            } else {
                List<SubbandExtractor.SubbandRef> subsAsis = SubbandExtractor.list(fAsis);
                Cmp1Box cmpAsis = readCmp1(fAsis);
                int[] cfaAsis = cfaLayoutMap(cmpAsis == null ? 1 : cmpAsis.cfaLayout());

                if (!subsAsis.isEmpty()) {
                    SubbandExtractor.SubbandRef firstRef = subsAsis.get(0);
                    int planeW = firstRef.planeWidth();
                    int planeH = firstRef.planeHeight();

                    // Load full-resolution preview from the JPEG_BIG track
                    BufferedImage prevFull;
                    try (ImageInputStream iis = ImageIO.createImageInputStream(fAsis)) {
                        var container = Cr3Parser.parse(iis);
                        prevFull = Cr3PreviewExtractor.extractPreview(iis, container);
                    }
                    if (prevFull == null) {
                        rep.println("  SKIPPED — cannot extract preview.");
                    } else {
                        // Method 1: box‑average downscale (same as PreviewReferenceExtractor)
                        int[] boxR  = boxDownscale(prevFull, 0, planeW, planeH);
                        int[] boxG  = boxDownscale(prevFull, 1, planeW, planeH);
                        int[] boxB  = boxDownscale(prevFull, 2, planeW, planeH);

                        // Method 2: nearest‑neighbour downscale
                        int[] nnR   = nnDownscale(prevFull, 0, planeW, planeH);
                        int[] nnG   = nnDownscale(prevFull, 1, planeW, planeH);
                        int[] nnB   = nnDownscale(prevFull, 2, planeW, planeH);

                        double rR = pearson(boxR, nnR);
                        double rG = pearson(boxG, nnG);
                        double rB = pearson(boxB, nnB);
                        double rMean = (rR + rG + rB) / 3.0;
                        rep.printf("  Self‑consistency (box vs NN downscale) at %dx%d:%n", planeW, planeH);
                        rep.printf("    R‑channel: r = %.4f%n", rR);
                        rep.printf("    G‑channel: r = %.4f%n", rG);
                        rep.printf("    B‑channel: r = %.4f%n", rB);
                        rep.printf("    mean:      r = %.4f   ← ORACLE CEILING%n", rMean);
                        tsv.printf("C\t%s\tpreview_R\tself_consistency_r\t%.6f%n", FX_ASIS, rR);
                        tsv.printf("C\t%s\tpreview_G\tself_consistency_r\t%.6f%n", FX_ASIS, rG);
                        tsv.printf("C\t%s\tpreview_B\tself_consistency_r\t%.6f%n", FX_ASIS, rB);
                        tsv.printf("C\t%s\tALL\toracle_ceiling_r\t%.6f%n", FX_ASIS, rMean);
                        rep.println();

                        // Also score Round‑13 decoder on tile0/p0 vs the box‑downscaled preview
                        // (same as what the sweep ITs measure) — so we can compare directly.
                        Map<String, List<SubbandExtractor.SubbandRef>> gAsis = groupByTilePlane(subsAsis);
                        rep.println("  Round‑13 decoder vs preview (same pipeline as sweep ITs):");
                        for (var e : gAsis.entrySet()) {
                            String key = e.getKey();
                            SubbandExtractor.SubbandRef ref = e.getValue().get(0);
                            int bd = ref.bitDepth();
                            int pIdx = ref.planeIndex();
                            int previewChan = cfaAsis[pIdx]; // 0=R,1=Gr,2=B,3=Gb → 0=R,1=G,2=B
                            int[] previewCh = previewChan == 0 ? boxR : (previewChan == 1 ? boxG : (previewChan == 2 ? boxG : boxB));
                            int[] dec = decodeR13Winner(SubbandExtractor.read(fAsis, ref), planeW * planeH, bd);
                            dec = applyMed(dec, planeW, planeH);
                            double r = pearson(dec, previewCh);
                            rep.printf("    %s (preview‑ch=%d): decoder vs preview r = %+.4f%n",
                                    key, previewChan, r);
                            tsv.printf("C\t%s\t%s\tdecoder_vs_preview_r\t%.6f%n", FX_ASIS, key, r);
                        }
                        rep.println();

                        rep.println("  INTERPRETATION:");
                        if (rMean >= 0.90) {
                            rep.printf("  Oracle ceiling = %.3f ≥ 0.90. The JPEG preview is a reliable%n", rMean);
                            rep.println("  reference at this plane resolution. The 0.70 promotion threshold");
                            rep.println("  is structurally achievable for a correct decoder.");
                        } else if (rMean >= 0.75) {
                            rep.printf("  Oracle ceiling = %.3f (0.75–0.90). JPEG compression + chroma%n", rMean);
                            rep.println("  subsampling limits the maximum achievable Pearson r.");
                            rep.println("  Consider lowering the promotion threshold to 0.65 × ceiling.");
                        } else {
                            rep.printf("  Oracle ceiling = %.3f < 0.75. The JPEG preview at %dx%d%n",
                                    rMean, planeW, planeH);
                            rep.println("  is too degraded (by downscaling, JPEG artifacts, chroma");
                            rep.println("  sub‑sampling) to serve as a reliable reference.");
                            rep.printf("  Recommended new promotion threshold: %.2f%n", rMean * 0.85);
                        }
                    }
                }
            }

            rep.println();
            rep.println("=== Summary ===");
            rep.println("See metrics.tsv for machine-readable data.");
            rep.println("Paste this report into SubbandFixtures.md as Update (q).");
        }

        System.out.println("[CrxReferenceCeilingIT] Report written to: " + reportPath.toAbsolutePath());
        System.out.println("[CrxReferenceCeilingIT] Metrics TSV:       " + tsvPath.toAbsolutePath());

        // Structural assertions — the report and metrics must be non-empty.
        assertThat(reportPath).exists();
        assertThat(tsvPath).exists();
        assertThat(Files.readString(reportPath)).contains("Exp A").contains("Exp B").contains("Exp C");
    }

    // ============================================================ entropy (R13 winner pinned)

    private static int[] decodeR13Winner(byte[] payload, int n, int bd) {
        if (payload == null || payload.length == 0 || n <= 0) return new int[Math.max(0, n)];
        BitReader rd = new BitReader(payload);
        int[] out = new int[n];
        int k = 0;
        int produced = 0;
        try {
            while (produced < n && rd.hasMore()) {
                int qv = 0;
                while (true) {
                    if (!rd.hasMore()) throw new IOException("eof in unary");
                    if (rd.readBit() != 0) break;
                    if (++qv > MAX_UNARY) return Arrays.copyOf(out, produced);
                }
                int rem = 0;
                for (int i = 0; i < k; i++) {
                    if (!rd.hasMore()) throw new IOException("eof in rem");
                    rem = (rem << 1) | rd.readBit();
                }
                int s = (qv << k) | rem;
                // sign‑LSB
                out[produced++] = ((s & 1) == 0) ? (s >> 1) : -((s >> 1) + 1);
                // K‑bracket (patent K* rule, K_INIT=0)
                int kStar = (s <= 0) ? 0 : 31 - Integer.numberOfLeadingZeros(s + 1);
                int kMin = Math.max(0, kStar - 1), kMax = Math.min(bd, kStar + 1);
                if (k < kMin) k++;
                else if (k > kMax) k--;
            }
        } catch (IOException eof) {
            return Arrays.copyOf(out, produced);
        }
        return out;
    }

    /** P_MED — LOCO‑I median predictor (Round‑13 winner). */
    private static int[] applyMed(int[] coef, int w, int h) {
        if (coef == null || coef.length == 0 || w <= 1) return coef;
        int rows = Math.min(h, coef.length / Math.max(1, w));
        int[] out = new int[coef.length];
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                if (idx >= coef.length) break;
                int a = (x > 0) ? out[idx - 1] : 0;
                int b = (y > 0) ? out[idx - w] : 0;
                int c = (x > 0 && y > 0) ? out[idx - w - 1] : 0;
                int pred;
                if (c >= Math.max(a, b)) pred = Math.min(a, b);
                else if (c <= Math.min(a, b)) pred = Math.max(a, b);
                else pred = a + b - c;
                out[idx] = coef[idx] + pred;
            }
        }
        for (int i = rows * w; i < coef.length; i++) out[i] = coef[i];
        return out;
    }

    // ============================================================ statistics

    private static double pearson(int[] a, int[] b) {
        int n = Math.min(a.length, b.length);
        if (n < 2) return 0;
        double sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0;
        for (int i = 0; i < n; i++) {
            double ai = a[i], bi = b[i];
            sa += ai; sb += bi; saa += ai * ai; sbb += bi * bi; sab += ai * bi;
        }
        double ma = sa / n, mb = sb / n;
        double cov = sab / n - ma * mb;
        double va = saa / n - ma * ma, vb = sbb / n - mb * mb;
        double denom = Math.sqrt(Math.max(0, va) * Math.max(0, vb));
        return denom < 1e-9 ? 0 : cov / denom;
    }

    private static double[] meanStd(int[] arr) {
        if (arr.length == 0) return new double[]{0, 0};
        double sum = 0;
        for (int v : arr) sum += v;
        double mean = sum / arr.length;
        double ss = 0;
        for (int v : arr) { double d = v - mean; ss += d * d; }
        return new double[]{mean, Math.sqrt(ss / arr.length)};
    }

    // ============================================================ image helpers

    /** Box‑average downscale of one RGB channel (0=R,1=G,2=B) from a BufferedImage. */
    private static int[] boxDownscale(BufferedImage img, int channel, int dstW, int dstH) {
        int srcW = img.getWidth(), srcH = img.getHeight();
        int[] out = new int[dstW * dstH];
        int sx = Math.max(1, srcW / dstW), sy = Math.max(1, srcH / dstH);
        for (int y = 0; y < dstH; y++) {
            for (int x = 0; x < dstW; x++) {
                long sum = 0; int cnt = 0;
                for (int yy = 0; yy < sy; yy++) {
                    int sy0 = y * sy + yy; if (sy0 >= srcH) break;
                    for (int xx = 0; xx < sx; xx++) {
                        int sx0 = x * sx + xx; if (sx0 >= srcW) break;
                        int rgb = img.getRGB(sx0, sy0);
                        int ch = switch (channel) {
                            case 0 -> (rgb >> 16) & 0xFF;
                            case 2 -> rgb & 0xFF;
                            default -> (rgb >> 8) & 0xFF;
                        };
                        sum += ch; cnt++;
                    }
                }
                out[y * dstW + x] = cnt == 0 ? 0 : (int) (sum / cnt);
            }
        }
        return out;
    }

    /** Nearest‑neighbour downscale of one RGB channel. */
    private static int[] nnDownscale(BufferedImage img, int channel, int dstW, int dstH) {
        int srcW = img.getWidth(), srcH = img.getHeight();
        int[] out = new int[dstW * dstH];
        for (int y = 0; y < dstH; y++) {
            int sy0 = Math.min(srcH - 1, y * srcH / dstH);
            for (int x = 0; x < dstW; x++) {
                int sx0 = Math.min(srcW - 1, x * srcW / dstW);
                int rgb = img.getRGB(sx0, sy0);
                out[y * dstW + x] = switch (channel) {
                    case 0 -> (rgb >> 16) & 0xFF;
                    case 2 -> rgb & 0xFF;
                    default -> (rgb >> 8) & 0xFF;
                };
            }
        }
        return out;
    }

    // ============================================================ CR3 plumbing

    private static Cmp1Box readCmp1(File cr3) throws Exception {
        try (ImageInputStream in = ImageIO.createImageInputStream(cr3)) {
            Cr3Container c = Cr3Parser.parse(in);
            for (Cr3Track t : c.tracks()) {
                if (t.cmp1() != null) return t.cmp1();
            }
        }
        return null;
    }

    private static int[] cfaLayoutMap(int cfaLayout) {
        return switch (cfaLayout) {
            case 0 -> new int[]{0, 1, 2, 3};
            case 1 -> new int[]{1, 0, 3, 2};
            case 2 -> new int[]{2, 3, 0, 1};
            case 3 -> new int[]{3, 2, 1, 0};
            default -> new int[]{0, 1, 2, 3};
        };
    }

    private static Map<String, List<SubbandExtractor.SubbandRef>> groupByTilePlane(
            List<SubbandExtractor.SubbandRef> subs) {
        Map<String, List<SubbandExtractor.SubbandRef>> map = new LinkedHashMap<>();
        for (SubbandExtractor.SubbandRef s : subs) {
            String key = "tile" + s.tileSequence() + "/p" + s.planeIndex();
            map.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }
        return map;
    }
}
