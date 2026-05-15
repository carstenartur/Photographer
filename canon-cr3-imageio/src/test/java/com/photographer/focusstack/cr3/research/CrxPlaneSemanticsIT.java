package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.raw.codec.BitReader;
import com.photographer.focusstack.cr3.raw.codec.InverseWaveletTransform;
import com.photographer.focusstack.cr3.raw.codec.InverseWaveletTransform.FilterType;
import com.photographer.focusstack.cr3.raw.codec.SubbandExtractor;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Diagnostic plane-semantics analysis for the CRX entropy codec — A through E
 * of the new requirement (2026-04-30, after Step 3's negative IDWT result).
 *
 * <p>This IT lives entirely in test scope. It produces only artefacts and
 * stdout reports — <em>no production code is modified, no hypothesis is
 * promoted, no flag is added</em>. Each of the four diagnostic groups (A–D)
 * is evaluated in isolation and its results are dumped under
 * {@code canon-cr3-imageio/target/crx-plane-semantics/} for the next session.</p>
 *
 * <h3>Outline</h3>
 * <ul>
 *   <li><b>A. Plane semantics</b> — per-plane min/max/mean/variance/zero-fraction
 *       /sign-distribution/8-bit histogram + 4-neighbour correlation, both on
 *       raw payload bytes (the entropy-coded view) and on the patent-decoded
 *       outputs under each of the four roles (LL/HL/LH/HH).</li>
 *   <li><b>B. Dequantisation hypotheses</b> — identity, ×q, ×2^q, dead-zone-(q),
 *       applied to the patent-decoded planes <em>before</em> the existing
 *       Le Gall 5/3 IDWT; each variant scored vs. the PRVW reference under the
 *       most plausible permutation from Update (d).</li>
 *   <li><b>C. Multi-level wavelet hypothesis</b> — checks the CMP1 box's
 *       {@code waveletLevels} field and documents whether multi-level
 *       reconstruction is even structurally possible for the fixture. (For
 *       sample.cr3 the answer is decisive: see report.)</li>
 *   <li><b>D. Alternative plane interpretations</b> — treats the four planes
 *       directly as RGGB Bayer channels (no IDWT). Per-plane Pearson r vs
 *       the matching preview channel, plus a composed Bayer mosaic scored
 *       against the preview's luminance.</li>
 *   <li><b>E. Debug artefacts</b> — heatmaps as 8-bit PGM, histograms as CSV,
 *       full metric table as TSV, raw decoded planes as binary {@code .i16}
 *       dumps. Output dir: {@code target/crx-plane-semantics/}.</li>
 * </ul>
 *
 * <p>The test always succeeds — the goal is artefact production, not a
 * threshold check. The acceptance criterion ("clear winning interpretation")
 * is for the human reviewing the artefacts.</p>
 */
class CrxPlaneSemanticsIT {

    private static final String SAMPLE = "src/test/resources/samples/sample.cr3";
    private static final Path OUT_DIR = Path.of("target", "crx-plane-semantics");

    private enum Role { LL, HL, LH, HH }

    @Test
    void diagnoseTile0Planes() throws Exception {
        File f = new File(SAMPLE);
        assertThat(f).exists();
        Files.createDirectories(OUT_DIR);

        // ---- container metadata (drives section C) ----
        Cmp1Box cmp1 = readCmp1(f);
        assertThat(cmp1).as("CMP1 box of " + SAMPLE).isNotNull();

        List<SubbandExtractor.SubbandRef> subs = SubbandExtractor.list(f);
        assertThat(subs).hasSizeGreaterThanOrEqualTo(4);

        SubbandExtractor.SubbandRef[] p = {subs.get(0), subs.get(1), subs.get(2), subs.get(3)};
        int w = p[0].planeWidth();
        int h = p[0].planeHeight();
        int bd = p[0].bitDepth();
        int q  = p[0].quantValue();
        for (int i = 1; i < 4; i++) {
            assertThat(p[i].planeWidth()).isEqualTo(w);
            assertThat(p[i].planeHeight()).isEqualTo(h);
        }

        byte[][] payload = new byte[4][];
        for (int i = 0; i < 4; i++) payload[i] = SubbandExtractor.read(f, p[i]);

        // ---- decode each plane under each of the four roles (16 decodings) ----
        int[][][] decoded = new int[4][Role.values().length][];
        for (int pi = 0; pi < 4; pi++) {
            for (Role role : Role.values()) {
                decoded[pi][role.ordinal()] =
                        decodePatent(payload[pi], w, h, bd, q, role);
            }
        }

        // metric table goes to TSV
        try (PrintWriter tsv = new PrintWriter(OUT_DIR.resolve("metrics.tsv").toFile())) {
            tsv.println("section\tlabel\tmetric\tvalue");

            // ============================================================
            // A. Per-plane statistics (raw bytes + decoded under each role)
            // ============================================================
            System.out.printf("%n=== A. Plane statistics — sample.cr3 tile=0 (%dx%d, %d-bit, q=%d) ===%n",
                    w, h, bd, q);
            System.out.printf("%-22s %10s %10s %10s %12s %10s %10s %10s %10s%n",
                    "label", "min", "max", "mean", "variance",
                    "zeroFrac", "negFrac", "neighCorr", "entropy8");

            // raw bytes (the entropy-coded view) – treat as unsigned 8-bit
            for (int pi = 0; pi < 4; pi++) {
                int[] asInts = bytesToUnsigned(payload[pi]);
                Stats s = Stats.compute(asInts, 256);
                double nc = neighbourhoodCorrelation8(payload[pi]);
                System.out.printf("raw-p%-19d %10d %10d %10.2f %12.2f %10.4f %10s %10.4f %10.3f%n",
                        pi, s.min, s.max, s.mean, s.variance, s.zeroFrac,
                        "n/a (uByte)", nc, s.entropyBits);
                emitTsv(tsv, "A.raw", "p" + pi, s, nc);
                writeHistogramCsv(OUT_DIR.resolve("A_hist_raw_p" + pi + ".csv"),
                        s.histogram, "byteValue");
            }

            // decoded under each role
            for (int pi = 0; pi < 4; pi++) {
                for (Role role : Role.values()) {
                    int[] arr = decoded[pi][role.ordinal()];
                    if (arr.length == 0) continue;
                    int hist = Math.min(1024, 1 << bd);
                    Stats s = Stats.compute(arr, hist);
                    double nc = neighbourhoodCorrelation2D(arr, w, h);
                    String label = "dec-p" + pi + "-" + role;
                    System.out.printf("%-22s %10d %10d %10.2f %12.2f %10.4f %10.4f %10.4f %10.3f%n",
                            label, s.min, s.max, s.mean, s.variance, s.zeroFrac,
                            s.negFrac, nc, s.entropyBits);
                    emitTsv(tsv, "A.dec", label, s, nc);
                }
            }

            // diagonal: each plane decoded as its same-index role
            // (i.e. p0→LL, p1→HL, p2→LH, p3→HH) — write heatmaps
            Role[] diag = {Role.LL, Role.HL, Role.LH, Role.HH};
            for (int pi = 0; pi < 4; pi++) {
                int[] arr = decoded[pi][diag[pi].ordinal()];
                writeHeatmapPgm(OUT_DIR.resolve(
                        String.format("A_heat_p%d_%s.pgm", pi, diag[pi])),
                        arr, w, h);
            }

            // ============================================================
            // B. Dequantisation hypotheses (applied before IDWT)
            // ============================================================
            // Use the best permutation from Update (d): (0,3,2,1).
            // For each dequant variant, transform the decoded subbands and
            // run IDWT, then score vs preview R.
            System.out.printf("%n=== B. Dequantisation hypotheses (perm 0,3,2,1; LeGall 5/3 IDWT) ===%n");
            System.out.printf("%-20s %12s %12s %10s%n",
                    "variant", "pearsonR", "meanAbsErr", "outLen");
            int[] perm = {0, 3, 2, 1};
            int[] llSrc = decoded[perm[0]][Role.LL.ordinal()];
            int[] hlSrc = decoded[perm[1]][Role.HL.ordinal()];
            int[] lhSrc = decoded[perm[2]][Role.LH.ordinal()];
            int[] hhSrc = decoded[perm[3]][Role.HH.ordinal()];
            int outW = 2 * w;
            int outH = 2 * h;
            PreviewReferenceExtractor.Reference refR =
                    PreviewReferenceExtractor.extract(f, 0, outW, outH);

            DequantVariant[] variants = {
                    new DequantVariant("identity",     v -> v),
                    new DequantVariant("scale_q",      v -> v * q),
                    new DequantVariant("scale_2pq",    v -> v * (1 << Math.min(15, q))),
                    new DequantVariant("deadzone_q",   v -> deadZone(v, q)),
                    new DequantVariant("deadzone_2q",  v -> deadZone(v, 2 * q)),
            };
            for (DequantVariant dv : variants) {
                int[] ll = dv.apply(llSrc);
                int[] hl = dv.apply(hlSrc);
                int[] lh = dv.apply(lhSrc);
                int[] hh = dv.apply(hhSrc);
                int[] recon;
                try {
                    recon = InverseWaveletTransform.inverseSynthesis2D(
                            ll, lh, hl, hh, w, h, outW, outH, FilterType.LEGALL_5_3);
                } catch (RuntimeException e) {
                    System.out.printf("%-20s %12s %12s %10s%n", dv.name,
                            e.getClass().getSimpleName(), "n/a", "0");
                    continue;
                }
                ScoredStats st = score(recon, refR.pixels());
                System.out.printf("%-20s %12.4f %12.2f %10d%n",
                        dv.name, st.r, st.mae, recon.length);
                tsv.printf("B%s%s%spearsonR%s%.6f%n", "\t", dv.name, "\t", "\t", st.r);
                tsv.printf("B%s%s%smeanAbsErr%s%.4f%n", "\t", dv.name, "\t", "\t", st.mae);

                // dump the dequantised LL only (others would inflate the dir)
                if (dv.name.equals("identity") || dv.name.equals("scale_q")) {
                    writeI16(OUT_DIR.resolve("B_dequant_LL_" + dv.name + ".i16"), ll);
                }
                writeHeatmapPgm(OUT_DIR.resolve("B_recon_" + dv.name + ".pgm"),
                        recon, outW, outH);
            }

            // ============================================================
            // C. Multi-level wavelet hypothesis (driven by CMP1)
            // ============================================================
            System.out.printf("%n=== C. Multi-level wavelet hypothesis ===%n");
            System.out.printf("CMP1.waveletLevels = %d, CMP1.numPlanes = %d, CMP1.cfaLayout = %d (%s)%n",
                    cmp1.waveletLevels(), cmp1.numPlanes(), cmp1.cfaLayout(), cmp1.cfaLayoutName());
            System.out.printf("CMP1.encodingType  = %d, CMP1.bitsPerSample = %d, isLossless=%s%n",
                    cmp1.encodingType(), cmp1.bitsPerSample(), cmp1.isLossless());
            tsv.printf("C\tcmp1\twaveletLevels\t%d%n", cmp1.waveletLevels());
            tsv.printf("C\tcmp1\tnumPlanes\t%d%n", cmp1.numPlanes());
            tsv.printf("C\tcmp1\tcfaLayout\t%d%n", cmp1.cfaLayout());
            tsv.printf("C\tcmp1\tencodingType\t%d%n", cmp1.encodingType());

            if (cmp1.waveletLevels() == 0) {
                System.out.println("→ CMP1 declares 0 wavelet levels (lossless mode). Multi-level");
                System.out.println("  IDWT is structurally inapplicable to this fixture: there is");
                System.out.println("  no wavelet decomposition at all. The 4 planes are NOT");
                System.out.println("  LL/HL/LH/HH of any level — they are the 4 Bayer channels");
                System.out.println("  per the CFA layout. This decisively rules out hypothesis (i)");
                System.out.println("  in Update (d) and elevates hypothesis (D / interpretation 2).");
                tsv.println("C\tdecision\tmulti_level_applicable\tFALSE");
            } else {
                System.out.printf("→ CMP1 declares %d wavelet levels. Recursive IDWT could apply,%n",
                        cmp1.waveletLevels());
                System.out.println("  but this fixture has only 1 subband per plane, so the");
                System.out.println("  recursive subbands are NOT in the bytestream. A multi-level");
                System.out.println("  fixture would need ≥4 subbands per plane.");
                tsv.println("C\tdecision\tmulti_level_applicable\tNEEDS_DEEPER_FIXTURE");
            }

            // ============================================================
            // D. Alternative plane interpretations (4 planes = 4 Bayer channels)
            // ============================================================
            // Per CMP1: numPlanes=4, cfaLayout. Each "plane" decoded as LL
            // (MED predictor) is the natural candidate for that Bayer channel.
            // Score each plane vs the preview's matching channel at plane size.
            System.out.printf("%n=== D. Plane = Bayer channel hypothesis (no IDWT) ===%n");
            System.out.printf("%-22s %12s %12s %10s%n",
                    "label", "pearsonR", "meanAbsErr", "outLen");
            int[] cfaPlaneToPreviewIdx = cfaLayoutMap(cmp1.cfaLayout());
            String[] chanName = {"R", "Gr", "Gb", "B"};
            for (int pi = 0; pi < 4; pi++) {
                int previewChan = cfaPlaneToPreviewIdx[pi];
                int[] dec = decoded[pi][Role.LL.ordinal()];
                if (dec.length < w * h) continue;
                PreviewReferenceExtractor.Reference ref =
                        PreviewReferenceExtractor.extract(f, previewChan, w, h);
                ScoredStats st = score(dec, ref.pixels());
                String label = "p" + pi + "→" + chanName[previewChan];
                System.out.printf("%-22s %12.4f %12.2f %10d%n",
                        label, st.r, st.mae, dec.length);
                tsv.printf("D.bayer\t%s\tpearsonR\t%.6f%n", label, st.r);
                tsv.printf("D.bayer\t%s\tmeanAbsErr\t%.4f%n", label, st.mae);
            }

            // also try the swapped MED-predictor table for each plane
            // (in case the predictor table differs per Bayer channel)
            System.out.println("(decoded with patent LL/MED predictor — left-only and above-only");
            System.out.println(" predictors give similar bit-budget but stronger directional bias;");
            System.out.println(" see Update (c) sweep table for those scores.)");

            // composed Bayer mosaic from p0..p3 under cfaLayout, scored vs
            // preview's full-resolution luminance at outW × outH.
            int[] mosaic = composeBayerMosaic(decoded, cmp1.cfaLayout(), w, h);
            // luminance reference = 0.299R + 0.587G + 0.114B at full size
            int[] lumaRef = lumaReference(f, outW, outH);
            ScoredStats stMos = score(mosaic, lumaRef);
            System.out.printf("%-22s %12.4f %12.2f %10d%n",
                    "bayer_mosaic_vs_luma", stMos.r, stMos.mae, mosaic.length);
            tsv.printf("D.mosaic\tbayer_mosaic\tpearsonR\t%.6f%n", stMos.r);
            tsv.printf("D.mosaic\tbayer_mosaic\tmeanAbsErr\t%.4f%n", stMos.mae);
            writeHeatmapPgm(OUT_DIR.resolve("D_bayer_mosaic.pgm"),
                    mosaic, outW, outH);
        }

        // The test asserts only that artefacts were produced.
        assertThat(OUT_DIR.resolve("metrics.tsv")).exists();
        assertThat(OUT_DIR.resolve("D_bayer_mosaic.pgm")).exists();
        assertThat(OUT_DIR.resolve("A_heat_p0_LL.pgm")).exists();
    }

    // ===================== helpers =====================

    private static int[] cfaLayoutMap(int cfaLayout) {
        // Each entry tells which preview channel (0=R, 1=Gr, 2=Gb, 3=B) the
        // CRX plane at that index represents under the given CFA layout.
        // PreviewReferenceExtractor uses 1==2==G internally; either choice
        // is fine for scoring purposes.
        return switch (cfaLayout) {
            case 0 -> new int[]{0, 1, 2, 3};   // RGGB
            case 1 -> new int[]{1, 0, 3, 2};   // GRBG
            case 2 -> new int[]{2, 3, 0, 1};   // GBRG
            case 3 -> new int[]{3, 2, 1, 0};   // BGGR
            default -> new int[]{0, 1, 2, 3};
        };
    }

    private static int[] composeBayerMosaic(int[][][] decoded, int cfaLayout,
                                            int w, int h) {
        // Each plane is one of the 4 Bayer positions, at half-resolution per axis.
        // The mosaic is 2w × 2h, filled at the corresponding (offX, offY) in 2×2 cells.
        // RGGB layout: R=(0,0), Gr=(1,0), Gb=(0,1), B=(1,1) — convention used by the
        // existing BayerDemosaicer.
        int[] mosaic = new int[2 * w * 2 * h];
        int[][] off = switch (cfaLayout) {
            case 0 -> new int[][]{{0,0},{1,0},{0,1},{1,1}};   // RGGB
            case 1 -> new int[][]{{1,0},{0,0},{1,1},{0,1}};   // GRBG
            case 2 -> new int[][]{{0,1},{1,1},{0,0},{1,0}};   // GBRG
            case 3 -> new int[][]{{1,1},{0,1},{1,0},{0,0}};   // BGGR
            default -> new int[][]{{0,0},{1,0},{0,1},{1,1}};
        };
        for (int pi = 0; pi < 4; pi++) {
            int[] plane = decoded[pi][Role.LL.ordinal()];
            int ox = off[pi][0];
            int oy = off[pi][1];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int v = plane[y * w + x];
                    mosaic[(2 * y + oy) * (2 * w) + (2 * x + ox)] = v;
                }
            }
        }
        return mosaic;
    }

    /** 0.299R + 0.587G + 0.114B from preview, downsampled to outW×outH. */
    private static int[] lumaReference(File cr3, int outW, int outH) throws IOException {
        PreviewReferenceExtractor.Reference rR =
                PreviewReferenceExtractor.extract(cr3, 0, outW, outH);
        PreviewReferenceExtractor.Reference rG =
                PreviewReferenceExtractor.extract(cr3, 1, outW, outH);
        PreviewReferenceExtractor.Reference rB =
                PreviewReferenceExtractor.extract(cr3, 3, outW, outH);
        int n = outW * outH;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            int v = (int) Math.round(0.299 * rR.pixels()[i]
                                   + 0.587 * rG.pixels()[i]
                                   + 0.114 * rB.pixels()[i]);
            out[i] = Math.max(0, Math.min(255, v));
        }
        return out;
    }

    private static int deadZone(int v, int t) {
        if (Math.abs(v) <= t) return 0;
        return v;
    }

    private record DequantVariant(String name, java.util.function.IntUnaryOperator op) {
        int[] apply(int[] src) {
            int[] out = new int[src.length];
            for (int i = 0; i < src.length; i++) out[i] = op.applyAsInt(src[i]);
            return out;
        }
    }

    private static int[] bytesToUnsigned(byte[] b) {
        int[] r = new int[b.length];
        for (int i = 0; i < b.length; i++) r[i] = b[i] & 0xFF;
        return r;
    }

    private record Stats(int min, int max, double mean, double variance,
                         double zeroFrac, double negFrac, double entropyBits,
                         long[] histogram) {
        static Stats compute(int[] arr, int histBuckets) {
            if (arr.length == 0) {
                return new Stats(0, 0, 0, 0, 0, 0, 0, new long[0]);
            }
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            double sum = 0;
            long zeros = 0;
            long negs = 0;
            for (int v : arr) {
                if (v < min) min = v;
                if (v > max) max = v;
                sum += v;
                if (v == 0) zeros++;
                if (v < 0)  negs++;
            }
            double mean = sum / arr.length;
            double var = 0;
            for (int v : arr) {
                double d = v - mean;
                var += d * d;
            }
            var /= arr.length;

            int range = Math.max(1, max - min);
            int buckets = Math.max(1, Math.min(histBuckets, range + 1));
            long[] hist = new long[buckets];
            for (int v : arr) {
                int idx = (int) ((long)(v - min) * (buckets - 1L) / range);
                hist[idx]++;
            }
            double entropy = 0;
            for (long c : hist) {
                if (c == 0) continue;
                double pp = c / (double) arr.length;
                entropy -= pp * (Math.log(pp) / Math.log(2));
            }
            return new Stats(min, max, mean, var,
                    zeros / (double) arr.length,
                    negs  / (double) arr.length,
                    entropy, hist);
        }
    }

    private static double neighbourhoodCorrelation8(byte[] payload) {
        // simple Pearson r between consecutive bytes
        int n = payload.length - 1;
        if (n <= 1) return 0;
        double sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0;
        for (int i = 0; i < n; i++) {
            double a = payload[i] & 0xFF;
            double b = payload[i + 1] & 0xFF;
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

    private static double neighbourhoodCorrelation2D(int[] arr, int w, int h) {
        // Pearson r between a pixel and its left neighbour (skipping x==0)
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

    private record ScoredStats(double r, double mae) {}

    private static ScoredStats score(int[] cand, int[] ref) {
        int n = Math.min(cand.length, ref.length);
        if (n < 2) return new ScoredStats(Double.NaN, Double.NaN);
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            int v = cand[i];
            if (v < min) min = v;
            if (v > max) max = v;
        }
        int range = Math.max(1, max - min);
        double sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0;
        long sum = 0;
        for (int i = 0; i < n; i++) {
            double a = (cand[i] - min) * 255.0 / range;
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
        return new ScoredStats(denom < 1e-9 ? 0 : cov / denom,
                (double) sum / n);
    }

    private static void emitTsv(PrintWriter out, String section, String label,
                                Stats s, double neigh) {
        out.printf("%s\t%s\tmin\t%d%n", section, label, s.min);
        out.printf("%s\t%s\tmax\t%d%n", section, label, s.max);
        out.printf("%s\t%s\tmean\t%.4f%n", section, label, s.mean);
        out.printf("%s\t%s\tvariance\t%.4f%n", section, label, s.variance);
        out.printf("%s\t%s\tzeroFrac\t%.6f%n", section, label, s.zeroFrac);
        out.printf("%s\t%s\tnegFrac\t%.6f%n", section, label, s.negFrac);
        out.printf("%s\t%s\tneighCorr\t%.6f%n", section, label, neigh);
        out.printf("%s\t%s\tentropyBits\t%.4f%n", section, label, s.entropyBits);
    }

    private static void writeHistogramCsv(Path path, long[] hist, String header)
            throws IOException {
        try (PrintWriter w = new PrintWriter(path.toFile())) {
            w.println(header + ",count");
            for (int i = 0; i < hist.length; i++) {
                w.printf("%d,%d%n", i, hist[i]);
            }
        }
    }

    private static void writeHeatmapPgm(Path path, int[] arr, int w, int h)
            throws IOException {
        // Downsample by stride if larger than 1024 wide, to keep file sizes sane.
        int stride = Math.max(1, Math.max(w, h) / 1024);
        int dw = w / stride;
        int dh = h / stride;
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int y = 0; y < dh; y++) {
            for (int x = 0; x < dw; x++) {
                int v = arr[Math.min(arr.length - 1, (y * stride) * w + x * stride)];
                if (v < min) min = v;
                if (v > max) max = v;
            }
        }
        int range = Math.max(1, max - min);
        try (var os = new java.io.BufferedOutputStream(
                Files.newOutputStream(path))) {
            String header = "P5\n" + dw + " " + dh + "\n255\n";
            os.write(header.getBytes());
            for (int y = 0; y < dh; y++) {
                for (int x = 0; x < dw; x++) {
                    int v = arr[Math.min(arr.length - 1, (y * stride) * w + x * stride)];
                    int q = (int) ((long)(v - min) * 255L / range);
                    os.write(Math.max(0, Math.min(255, q)));
                }
            }
        }
    }

    private static void writeI16(Path path, int[] arr) throws IOException {
        try (var dos = new java.io.DataOutputStream(
                new java.io.BufferedOutputStream(Files.newOutputStream(path)))) {
            for (int v : arr) {
                dos.writeShort((short) Math.max(Short.MIN_VALUE,
                        Math.min(Short.MAX_VALUE, v)));
            }
        }
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

    // ===================== patent decoder (mirror of CrxPatentIdwtValidationIT) =====================

    private static int[] decodePatent(byte[] payload, int w, int h, int bd, int q, Role role) {
        try { return decodePatentImpl(payload, w, h, bd, q, role); }
        catch (RuntimeException | IOException e) { return new int[0]; }
    }

    private static int[] decodePatentImpl(byte[] payload, int w, int h, int bd, int q, Role role)
            throws IOException {
        int[] result = new int[w * h];
        int maxVal = (1 << bd) - 1;
        BitReader reader = new BitReader(payload, 0, payload.length);
        int k = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!reader.hasMore()) return Arrays.copyOf(result, y * w + x);
                int residual = decodeRice(reader, k);
                int signed = ((residual & 1) == 0) ? (residual >> 1) : (-(residual >> 1) - 1);
                int predicted = predictForRole(result, w, x, y, bd, role);
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

    private static int predictForRole(int[] r, int w, int x, int y, int bd, Role ro) {
        if (ro == Role.HH) return 0;
        if (x == 0 && y == 0) return 1 << (bd - 1);
        if (y == 0) return r[x - 1];
        if (x == 0) return r[(y - 1) * w];
        int a = r[y * w + x - 1];
        int b = r[(y - 1) * w + x];
        int c = r[(y - 1) * w + x - 1];
        return switch (ro) {
            case LL -> {
                if (c >= Math.max(a, b)) yield Math.min(a, b);
                else if (c <= Math.min(a, b)) yield Math.max(a, b);
                else yield a + b - c;
            }
            case HL -> b;
            case LH -> a;
            case HH -> 0;
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

    @SuppressWarnings("unused")
    private static BufferedImage unused; // silence ImageIO import warning if any
}
