package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.raw.codec.BitReader;
import com.photographer.focusstack.cr3.raw.codec.SubbandExtractor;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round‑15 — Plane-Offset / Byte-Alignment diagnostic (test scope only).
 *
 * <h3>Background</h3>
 * <p>The Reference Ceiling report (Round‑14 post-mortem) showed:</p>
 * <ul>
 *   <li>Cross-frame Pearson r = 0.34 (should be ≥ 0.95 for a stable decoder)</li>
 *   <li>Flat-field CV = 0.4–5.0 (should be &lt; 0.05)</li>
 * </ul>
 * <p>These two numbers together are the signature of a decoder that starts
 * reading the wrong byte for planes 1–3 — not a predictor or K-update issue.
 * The canonical cause in binary RAW formats is <b>alignment padding</b>: each
 * plane data block is padded to a multiple of N bytes, but our
 * {@code resolveDataOffsets()} accumulates raw {@code dataSize} fields without
 * padding, so the computed start position drifts with each plane.</p>
 *
 * <h3>What this IT measures</h3>
 * <p>Three alignment hypotheses are swept:</p>
 * <ol>
 *   <li><b>align=1</b> — no padding (current behaviour)</li>
 *   <li><b>align=4</b> — 4-byte boundary padding</li>
 *   <li><b>align=8</b> — 8-byte boundary padding</li>
 * </ol>
 *
 * <p>Results are written to {@code target/plane-offset-diagnostic/report.txt}.</p>
 */
class CrxPlaneOffsetDiagnosticIT {

    private static final Path REPORT_DIR = Path.of("target/plane-offset-diagnostic");

    private static final String FX_DET_02 =
        "src/test/resources/samples/research/g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-02.cr3";
    private static final String FX_DET_03 =
        "src/test/resources/samples/research/g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-03.cr3";
    private static final String FX_FLAT =
        "src/test/resources/samples/research/g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02.cr3";

    @Test
    void diagnoseAndReport() throws Exception {
        Files.createDirectories(REPORT_DIR);
        var sb = new StringBuilder();
        sb.append("CRX Plane-Offset Alignment Diagnostic\n");
        sb.append("======================================\n");
        sb.append("Generated: ").append(Instant.now()).append("\n\n");

        // ── Section 1: Hex dump at plane starts for each alignment ───────────
        sb.append("=== Section 1: Hex dump at plane data starts ===\n\n");
        for (String path : new String[]{FX_DET_02, FX_DET_03}) {
            File f = new File(path);
            if (!f.exists()) { sb.append("MISSING: ").append(path).append("\n\n"); continue; }
            sb.append("Fixture: ").append(f.getName()).append("\n");

            List<SubbandExtractor.SubbandRef> subs = SubbandExtractor.list(f);
            if (subs.isEmpty()) { sb.append("  [no subbands]\n\n"); continue; }
            byte[] fileBytes = Files.readAllBytes(f.toPath());

            for (int align : new int[]{1, 4, 8}) {
                sb.append(String.format("  align=%d:\n", align));
                long[][] offsets = recomputeOffsets(subs, align);
                for (int t = 0; t < offsets.length; t++) {
                    for (int p = 0; p < offsets[t].length; p++) {
                        int off = (int) offsets[t][p];
                        sb.append(String.format("    tile%d/plane%d @%d: ", t, p, off));
                        sb.append(hexDump(fileBytes, off, 48)).append("\n");
                    }
                }
            }
            sb.append("\n");
        }

        // ── Section 2: Rice entropy probe ────────────────────────────────────
        sb.append("=== Section 2: Rice K=0 symbol entropy (256 syms; lower=better) ===\n\n");
        {
            File f = new File(FX_DET_02);
            if (f.exists()) {
                List<SubbandExtractor.SubbandRef> subs = SubbandExtractor.list(f);
                byte[] fileBytes = Files.readAllBytes(f.toPath());
                sb.append("Fixture: ").append(f.getName()).append("\n");
                for (int align : new int[]{1, 4, 8}) {
                    sb.append(String.format("  align=%d:\n", align));
                    long[][] offsets = recomputeOffsets(subs, align);
                    for (int t = 0; t < offsets.length; t++) {
                        for (int p = 0; p < offsets[t].length; p++) {
                            int off = (int) offsets[t][p];
                            double ent = riceEntropy(fileBytes, off, 256);
                            sb.append(String.format("    tile%d/plane%d: entropy=%.3f bits\n", t, p, ent));
                        }
                    }
                }
                sb.append("\n");
            }
        }

        // ── Section 3: Cross-frame Pearson r ─────────────────────────────────
        sb.append("=== Section 3: Cross-frame Pearson r per alignment ===\n\n");

        File f0 = new File(FX_DET_02);
        File f1 = new File(FX_DET_03);
        assertThat(f0).withFailMessage("fixture missing: " + FX_DET_02).exists();
        assertThat(f1).withFailMessage("fixture missing: " + FX_DET_03).exists();

        List<SubbandExtractor.SubbandRef> subs0 = SubbandExtractor.list(f0);
        List<SubbandExtractor.SubbandRef> subs1 = SubbandExtractor.list(f1);
        byte[] bytes0 = Files.readAllBytes(f0.toPath());
        byte[] bytes1 = Files.readAllBytes(f1.toPath());

        int bestAlign = 1;
        double bestMeanR = -1;

        for (int align : new int[]{1, 4, 8}) {
            sb.append(String.format("alignment=%d:\n", align));
            long[][] off0 = recomputeOffsets(subs0, align);
            long[][] off1 = recomputeOffsets(subs1, align);

            int numTiles = Math.min(off0.length, off1.length);
            double sumR = 0; int count = 0;

            for (int t = 0; t < numTiles; t++) {
                int np = Math.min(off0[t].length, off1[t].length);
                for (int p = 0; p < np; p++) {
                    int o0 = (int) off0[t][p];
                    int o1 = (int) off1[t][p];
                    long sz0 = planeSizeFromSubs(subs0, t, p);
                    long sz1 = planeSizeFromSubs(subs1, t, p);
                    int sampleLen = (int) Math.min(Math.min(sz0, sz1), 4096);
                    if (sampleLen < 64 || o0 < 0 || o0 >= bytes0.length
                            || o1 < 0 || o1 >= bytes1.length) continue;

                    int[] syms0 = decodeRiceSymbols(bytes0, o0, sampleLen, 512);
                    int[] syms1 = decodeRiceSymbols(bytes1, o1, sampleLen, 512);
                    double r = pearsonR(syms0, syms1);
                    sb.append(String.format("  tile%d/plane%d: r = %+.4f\n", t, p, r));
                    sumR += r; count++;
                }
            }
            double meanR = count > 0 ? sumR / count : 0;
            sb.append(String.format("  → mean r = %.4f over %d pairs\n\n", meanR, count));
            if (meanR > bestMeanR) { bestMeanR = meanR; bestAlign = align; }
        }

        // ── Section 4: Flat-field CV ──────────────────────────────────────────
        sb.append("=== Section 4: Flat-field CV per alignment (lower=better) ===\n\n");
        {
            File flat = new File(FX_FLAT);
            if (flat.exists()) {
                List<SubbandExtractor.SubbandRef> subs = SubbandExtractor.list(flat);
                byte[] fileBytes = Files.readAllBytes(flat.toPath());
                for (int align : new int[]{1, 4, 8}) {
                    sb.append(String.format("alignment=%d:\n", align));
                    long[][] offsets = recomputeOffsets(subs, align);
                    for (int t = 0; t < offsets.length; t++) {
                        for (int p = 0; p < offsets[t].length; p++) {
                            int off = (int) offsets[t][p];
                            long sz = planeSizeFromSubs(subs, t, p);
                            int[] syms = decodeRiceSymbols(fileBytes, off, (int) Math.min(sz, 8192), 1024);
                            double[] stats = meanAndStd(syms);
                            double cv = stats[0] != 0 ? stats[1] / Math.abs(stats[0]) : Double.NaN;
                            sb.append(String.format("  tile%d/plane%d: mean=%.1f std=%.1f CV=%.3f\n",
                                t, p, stats[0], stats[1], cv));
                        }
                    }
                    sb.append("\n");
                }
            }
        }

        // ── Summary ───────────────────────────────────────────────────────────
        sb.append("=== Summary ===\n");
        sb.append(String.format("Best alignment: %d-byte  (mean cross-frame r = %.4f)\n\n",
            bestAlign, bestMeanR));
        if (bestAlign == 1) {
            sb.append("VERDICT: No alignment wins.\n");
            sb.append("  The offset bug is NOT simple padding between planes.\n");
            sb.append("  Next: check whether the 00 00 00 00 CRX header terminator is included in\n");
            sb.append("  the first plane's dataOffsetInFile, or whether subband sizes sum correctly.\n");
        } else {
            sb.append(String.format(
                "VERDICT: %d-byte alignment improves cross-frame r.\n", bestAlign));
            sb.append(String.format(
                "ACTION:  Apply align=%d in CrxHeader.resolveDataOffsets().\n", bestAlign));
        }

        String report = sb.toString();
        System.out.println(report);
        Files.writeString(REPORT_DIR.resolve("report.txt"), report);
        assertThat(REPORT_DIR.resolve("report.txt")).exists();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Recomputes per-plane start offsets (as absolute file offsets) for the given alignment.
     *
     * <p>For {@code align=1} the original dataOffsetInFile values from
     * SubbandExtractor are used as-is (they already reflect the header parsing).</p>
     * <p>For {@code align>1} we recompute: start from plane 0's known offset,
     * advance by the total plane size, then align up before the next plane.</p>
     *
     * <p>Returns {@code offsets[tileSeq][planeIdx]}.</p>
     */
    private long[][] recomputeOffsets(List<SubbandExtractor.SubbandRef> subs, int align) {
        int maxTile = subs.stream().mapToInt(SubbandExtractor.SubbandRef::tileSequence).max().orElse(0);
        long[][] result = new long[maxTile + 1][];

        for (int t = 0; t <= maxTile; t++) {
            final int tile = t;
            // First subband (index=0) per plane, sorted by planeIndex
            List<SubbandExtractor.SubbandRef> firstSubs = subs.stream()
                .filter(s -> s.tileSequence() == tile && s.subbandIndex() == 0)
                .sorted((a, b) -> Integer.compare(a.planeIndex(), b.planeIndex()))
                .toList();
            result[t] = new long[firstSubs.size()];

            if (firstSubs.isEmpty()) continue;

            if (align == 1) {
                // Use the offsets already computed by SubbandExtractor
                for (int p = 0; p < firstSubs.size(); p++) {
                    result[t][p] = firstSubs.get(p).dataOffsetInFile();
                }
            } else {
                // Recompute with alignment padding between planes
                long cursor = firstSubs.get(0).dataOffsetInFile();
                for (int p = 0; p < firstSubs.size(); p++) {
                    result[t][p] = cursor;
                    long planeSize = planeSizeFromSubs(subs, tile, firstSubs.get(p).planeIndex());
                    cursor += planeSize;
                    cursor = alignUp(cursor, align);
                }
            }
        }
        return result;
    }

    private long planeSizeFromSubs(List<SubbandExtractor.SubbandRef> subs, int tileSeq, int planeIdx) {
        return subs.stream()
            .filter(s -> s.tileSequence() == tileSeq && s.planeIndex() == planeIdx)
            .mapToLong(SubbandExtractor.SubbandRef::dataSize)
            .sum();
    }

    private static long alignUp(long v, int align) {
        long r = v % align; return r == 0 ? v : v + (align - r);
    }

    private int[] decodeRiceSymbols(byte[] data, int offset, int byteLen, int maxSymbols) {
        if (offset < 0 || offset >= data.length) return new int[0];
        int end = Math.min(offset + byteLen, data.length);
        BitReader reader = new BitReader(data, offset, end - offset);
        List<Integer> symbols = new ArrayList<>();
        int k = 0; long accum = 0; long cnt = 1;

        while (reader.hasMore() && symbols.size() < maxSymbols) {
            try {
                int q = 0;
                while (reader.hasMore() && reader.readBit() == 0) { q++; if (q > 32) break; }
                int r = (k > 0 && reader.hasMore()) ? reader.readBits(k) : 0;
                int sym = (q << k) | r;
                symbols.add(sym);
                int ss = (sym & 1) == 0 ? sym >> 1 : -(sym >> 1) - 1;
                accum += Math.abs(ss); cnt++;
                if (accum > cnt << k && k < 14) k++;
                else if (k > 0 && accum <= cnt << (k - 1)) k--;
                if (cnt >= 1024) { accum >>= 1; cnt >>= 1; if (cnt == 0) cnt = 1; }
            } catch (Exception e) { break; }
        }
        return symbols.stream().mapToInt(Integer::intValue).toArray();
    }

    private double riceEntropy(byte[] data, int offset, int numSymbols) {
        int[] syms = decodeRiceSymbols(data, offset, 4096, numSymbols);
        if (syms.length == 0) return Double.NaN;
        Map<Integer, Integer> freq = new java.util.HashMap<>();
        for (int s : syms) freq.merge(s, 1, Integer::sum);
        double n = syms.length, ent = 0;
        for (int f : freq.values()) { double p = f / n; ent -= p * (Math.log(p) / Math.log(2)); }
        return ent;
    }

    private double pearsonR(int[] a, int[] b) {
        int n = Math.min(a.length, b.length); if (n < 4) return 0;
        double sa = 0, sb = 0;
        for (int i = 0; i < n; i++) { sa += a[i]; sb += b[i]; }
        double ma = sa / n, mb = sb / n, cov = 0, va = 0, vb = 0;
        for (int i = 0; i < n; i++) {
            double da = a[i] - ma, db = b[i] - mb;
            cov += da * db; va += da * da; vb += db * db;
        }
        double d = Math.sqrt(va * vb); return d < 1e-12 ? 0 : cov / d;
    }

    private double[] meanAndStd(int[] arr) {
        if (arr.length == 0) return new double[]{0, 0};
        double sum = 0; for (int v : arr) sum += v;
        double mean = sum / arr.length, var = 0;
        for (int v : arr) { double d = v - mean; var += d * d; }
        return new double[]{mean, Math.sqrt(var / arr.length)};
    }

    private String hexDump(byte[] data, int off, int len) {
        if (off < 0 || off >= data.length) return "[out-of-bounds @" + off + "]";
        int end = Math.min(off + len, data.length);
        var sb = new StringBuilder();
        for (int i = off; i < end; i++) {
            if (i > off) sb.append(' ');
            sb.append(String.format("%02x", data[i] & 0xFF));
        }
        return sb.toString();
    }
}