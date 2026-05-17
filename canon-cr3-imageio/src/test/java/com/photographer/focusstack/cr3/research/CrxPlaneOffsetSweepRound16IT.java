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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round‑16 — Per‑plane Byte/Bit‑offset Sweep (test scope only).
 *
 * <h3>Hypothesis under test</h3>
 * <p>The Round‑15 plane‑offset diagnostic showed that adding flat byte‑level
 * padding (4 / 8 byte) between planes did <b>not</b> improve cross‑frame
 * Pearson r on the detail fixtures. The Round‑14 reference‑ceiling report and
 * the visual inspection of the current decoder output (vertical streaks, no
 * recognisable scene geometry) strongly suggest that the decoder is
 * <b>entering each plane at the wrong byte <i>or</i> the wrong sub‑byte bit
 * offset</b>. The CRX bitstream is read MSB‑first by {@link BitReader}, but
 * {@code BitReader} can only start on a byte boundary; if the true plane
 * payload begins at a non‑zero bit offset (or one byte off from what
 * {@code resolveDataOffsets()} computes) every Rice symbol after that point is
 * wrong.</p>
 *
 * <h3>What this IT measures</h3>
 * <p>For every (tile, plane) of the detail fixtures and the flat fixture, this
 * IT sweeps a small local grid of starting positions:</p>
 * <ul>
 *   <li>{@code byteOffsetDelta} ∈ {−4, −3, … +8} relative to the offset that
 *       {@link SubbandExtractor} reports.</li>
 *   <li>{@code bitOffsetDelta} ∈ {0, 1, … 7} bits skipped before the first
 *       Rice symbol is decoded.</li>
 * </ul>
 * <p>For each (byteΔ, bitΔ) candidate three independent metrics are computed
 * from a short window of decoded Rice symbols:</p>
 * <ol>
 *   <li><b>Symbol entropy</b> — should be <i>low</i> if the bitstream is
 *       being parsed coherently (otherwise the symbols look uniformly
 *       random).</li>
 *   <li><b>Neighbour correlation</b> — Pearson r between adjacent decoded
 *       symbols. Natural raw image samples are highly correlated; noise from
 *       a misaligned bitstream is not.</li>
 *   <li><b>Cross‑frame Pearson r</b> against the same (tile, plane) of a
 *       second fixture of the <i>same scene</i>. A correctly aligned decoder
 *       should produce sample sequences that correlate strongly across frames
 *       of the same static scene.</li>
 * </ol>
 *
 * <p>For each plane the IT picks the (byteΔ, bitΔ) candidate that maximises a
 * combined score and reports it. A small ASCII heatmap of the first 64
 * decoded symbols at the winning offset is also written so the report can be
 * reviewed by eye.</p>
 *
 * <p>This IT does <b>not</b> mutate any production code. Its output, written
 * to {@code target/plane-offset-sweep-r16/report.txt}, is the diagnostic
 * artefact the developer reads to decide what to patch in
 * {@code CrxHeader.resolveDataOffsets()} or in the per‑plane bit‑stream
 * entry point of the decoder.</p>
 */
class CrxPlaneOffsetSweepRound16IT {

    private static final Path REPORT_DIR = Path.of("target/plane-offset-sweep-r16");

    private static final String FX_DET_02 =
        "src/test/resources/samples/research/g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-02.cr3";
    private static final String FX_DET_03 =
        "src/test/resources/samples/research/g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-03.cr3";
    private static final String FX_FLAT =
        "src/test/resources/samples/research/g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02.cr3";

    /** Local sweep window around the offset reported by SubbandExtractor. */
    private static final int BYTE_DELTA_MIN = -4;
    private static final int BYTE_DELTA_MAX = +8;

    /** Number of Rice symbols to decode per probe. Small enough to stay fast,
     *  large enough that entropy / correlation are stable. */
    private static final int SYMBOLS_PER_PROBE = 512;

    /** Hard cap on bytes consumed by one probe (defends against runaway K). */
    private static final int PROBE_BYTE_BUDGET = 4096;

    @Test
    void sweepAndReport() throws Exception {
        Files.createDirectories(REPORT_DIR);
        StringBuilder sb = new StringBuilder();
        sb.append("CRX Per-Plane Byte/Bit Offset Sweep (Round 16)\n");
        sb.append("================================================\n");
        sb.append("Generated: ").append(Instant.now()).append('\n');
        sb.append("byteΔ ∈ [").append(BYTE_DELTA_MIN).append(',').append(BYTE_DELTA_MAX)
                .append("], bitΔ ∈ [0,7], symbols/probe = ").append(SYMBOLS_PER_PROBE)
                .append("\n\n");

        File f02 = new File(FX_DET_02);
        File f03 = new File(FX_DET_03);
        File flat = new File(FX_FLAT);
        assertThat(f02).withFailMessage("fixture missing: " + FX_DET_02).exists();
        assertThat(f03).withFailMessage("fixture missing: " + FX_DET_03).exists();

        List<SubbandExtractor.SubbandRef> subs02 = SubbandExtractor.list(f02);
        List<SubbandExtractor.SubbandRef> subs03 = SubbandExtractor.list(f03);
        byte[] bytes02 = Files.readAllBytes(f02.toPath());
        byte[] bytes03 = Files.readAllBytes(f03.toPath());

        // ── Section 1: per-plane sweep on detail-02 vs. detail-03 ───────────
        sb.append("=== Section 1: Detail fixtures — combined byte/bit sweep ===\n\n");
        List<PlaneResult> detailResults = new ArrayList<>();
        for (SubbandExtractor.SubbandRef ref : firstSubbandPerPlane(subs02)) {
            SubbandExtractor.SubbandRef peer = matchingSubband(subs03, ref);
            if (peer == null) {
                sb.append(String.format("tile%d/plane%d: no peer in detail-03 — skipped%n",
                        ref.tileSequence(), ref.planeIndex()));
                continue;
            }
            PlaneResult r = sweepPlane(ref, peer, bytes02, bytes03);
            detailResults.add(r);
            r.appendTo(sb);
        }

        // ── Section 2: best-offset summary table ────────────────────────────
        sb.append("\n=== Section 2: Best (byteΔ, bitΔ) per plane — detail fixtures ===\n\n");
        sb.append(String.format("%-12s %-7s %-7s %-12s %-12s %-12s %-7s%n",
                "plane", "byteΔ", "bitΔ", "entropy", "neighbour-r", "cross-r", "score"));
        sb.append("------------------------------------------------------------------------\n");
        for (PlaneResult r : detailResults) {
            sb.append(String.format("t%d/p%d        %+5d   %5d   %10.3f   %+10.4f   %+10.4f   %+5.3f%n",
                    r.tileSeq, r.planeIdx, r.bestByteDelta, r.bestBitDelta,
                    r.bestEntropy, r.bestNeighbourR, r.bestCrossR, r.bestScore));
        }

        // ── Section 3: ASCII heatmap of decoded symbols at best offset ──────
        sb.append("\n=== Section 3: First 64 decoded symbols at winning offset (heatmap) ===\n\n");
        for (PlaneResult r : detailResults) {
            sb.append(String.format("t%d/p%d  byteΔ=%+d bitΔ=%d:%n",
                    r.tileSeq, r.planeIdx, r.bestByteDelta, r.bestBitDelta));
            int[] preview = decodeRiceSymbols(
                    bytes02,
                    (int) r.basisRef.dataOffsetInFile() + r.bestByteDelta,
                    r.bestBitDelta, PROBE_BYTE_BUDGET, 64);
            sb.append("  raw : ").append(symbolPreview(preview)).append('\n');
            sb.append("  heat: ").append(asciiHeatmap(preview, 64)).append("\n\n");
        }

        // ── Section 4: flat fixture sanity (CV per plane at winning offset) ─
        sb.append("=== Section 4: Flat fixture CV at the detail-fixture winning offsets ===\n");
        sb.append("(A correctly aligned decoder must yield CV ≪ 1 on a flat field.)\n\n");
        if (flat.exists()) {
            List<SubbandExtractor.SubbandRef> subsFlat = SubbandExtractor.list(flat);
            byte[] bytesFlat = Files.readAllBytes(flat.toPath());
            for (PlaneResult r : detailResults) {
                SubbandExtractor.SubbandRef peer = matchingSubband(subsFlat, r.basisRef);
                if (peer == null) continue;
                int[] syms = decodeRiceSymbols(
                        bytesFlat,
                        (int) peer.dataOffsetInFile() + r.bestByteDelta,
                        r.bestBitDelta, PROBE_BYTE_BUDGET, SYMBOLS_PER_PROBE);
                double[] ms = meanAndStd(syms);
                double cv = ms[0] != 0 ? ms[1] / Math.abs(ms[0]) : Double.NaN;
                sb.append(String.format("  t%d/p%d (byteΔ=%+d bitΔ=%d): mean=%.2f std=%.2f CV=%.3f%n",
                        r.tileSeq, r.planeIdx, r.bestByteDelta, r.bestBitDelta, ms[0], ms[1], cv));
            }
        } else {
            sb.append("  flat fixture missing — skipped\n");
        }

        // ── Verdict ────────────────────────────────────────────────────────
        sb.append("\n=== Verdict ===\n");
        sb.append(verdict(detailResults));

        String report = sb.toString();
        System.out.println(report);
        Files.writeString(REPORT_DIR.resolve("report.txt"), report);
        assertThat(REPORT_DIR.resolve("report.txt")).exists();
    }

    // ───────────────────────────────────────────────────────────────────────
    // Sweep core
    // ───────────────────────────────────────────────────────────────────────

    private PlaneResult sweepPlane(SubbandExtractor.SubbandRef ref,
                                   SubbandExtractor.SubbandRef peer,
                                   byte[] bytesA, byte[] bytesB) {
        PlaneResult result = new PlaneResult(ref.tileSequence(), ref.planeIndex(), ref);

        int baseA = (int) ref.dataOffsetInFile();
        int baseB = (int) peer.dataOffsetInFile();

        for (int byteDelta = BYTE_DELTA_MIN; byteDelta <= BYTE_DELTA_MAX; byteDelta++) {
            for (int bitDelta = 0; bitDelta < 8; bitDelta++) {
                int offA = baseA + byteDelta;
                int offB = baseB + byteDelta;
                if (offA < 0 || offA >= bytesA.length) continue;
                if (offB < 0 || offB >= bytesB.length) continue;

                int[] symsA = decodeRiceSymbols(bytesA, offA, bitDelta,
                        PROBE_BYTE_BUDGET, SYMBOLS_PER_PROBE);
                int[] symsB = decodeRiceSymbols(bytesB, offB, bitDelta,
                        PROBE_BYTE_BUDGET, SYMBOLS_PER_PROBE);
                if (symsA.length < 32 || symsB.length < 32) continue;

                double entropy = entropyBits(symsA);
                double neighR = neighbourCorrelation(symsA);
                double crossR = pearsonR(symsA, symsB);
                double score = combinedScore(entropy, neighR, crossR);

                result.observe(byteDelta, bitDelta, entropy, neighR, crossR, score);
            }
        }
        return result;
    }

    /** Combined heuristic: high cross-frame r dominates, neighbour correlation
     *  reinforces, entropy is a soft penalty (Rice K=0 entropy ≈ 1–2 bits is fine). */
    private static double combinedScore(double entropy, double neighR, double crossR) {
        double e = Double.isFinite(entropy) ? entropy : 8.0;
        double n = Double.isFinite(neighR) ? neighR : 0.0;
        double c = Double.isFinite(crossR) ? crossR : 0.0;
        return 1.0 * c + 0.5 * n - 0.05 * e;
    }

    /**
     * Decision thresholds. A plane is considered "convincingly aligned" only when
     * cross-frame r is comparable to what a healthy lossless decoder produces on
     * the same scene (≥ 0.6 here is already a soft target — the long-term goal in
     * the Reference Ceiling report is ≥ 0.95).
     */
    private static final double STRONG_CROSS_R = 0.60;
    private static final double WEAK_CROSS_R   = 0.30;

    private static String verdict(List<PlaneResult> results) {
        if (results.isEmpty()) return "  no planes evaluated.\n";

        long planesNeedingShift = results.stream()
                .filter(r -> r.bestByteDelta != 0 || r.bestBitDelta != 0)
                .count();
        long strong = results.stream().filter(r -> r.bestCrossR >= STRONG_CROSS_R).count();
        long weak   = results.stream()
                .filter(r -> r.bestCrossR >= WEAK_CROSS_R && r.bestCrossR < STRONG_CROSS_R)
                .count();
        double meanBestCrossR = results.stream()
                .mapToDouble(r -> r.bestCrossR).average().orElse(0);

        StringBuilder v = new StringBuilder();
        v.append(String.format("  Planes evaluated:        %d%n", results.size()));
        v.append(String.format("  Planes wanting a shift:  %d%n", planesNeedingShift));
        v.append(String.format("  Mean best cross-frame r: %+.3f%n", meanBestCrossR));
        v.append(String.format("  Planes with cross-r ≥ %.2f (strong): %d%n", STRONG_CROSS_R, strong));
        v.append(String.format("  Planes with cross-r ≥ %.2f (weak):   %d%n", WEAK_CROSS_R, weak));
        v.append('\n');

        if (strong == results.size()) {
            v.append("  → All planes locked in to a strong cross-frame correlation under some\n");
            v.append("    (byteΔ, bitΔ) shift. This is the signature of a pure plane-offset\n");
            v.append("    bug. Recommended action:\n");
            for (PlaneResult r : results) {
                v.append(String.format(
                        "      • t%d/p%d: shift by (%+d byte, %d bit)  [cross-r=%+.3f]%n",
                        r.tileSeq, r.planeIdx, r.bestByteDelta, r.bestBitDelta, r.bestCrossR));
            }
            v.append("    Patch CrxHeader.resolveDataOffsets() (or the per-plane bit-stream\n");
            v.append("    entry point) to apply these corrections, then re-run\n");
            v.append("    CrxReferenceCeilingIT and expect cross-frame r ≥ 0.95.\n");
        } else if (strong + weak >= results.size() * 0.5) {
            v.append("  → Some planes line up partially but no shift recovers strong\n");
            v.append("    cross-frame correlation everywhere. The most likely interpretation\n");
            v.append("    is a HYBRID failure: plane offsets / bit alignment are wrong AND\n");
            v.append("    something downstream of the Rice loop is also wrong (K initialiser,\n");
            v.append("    sign mapping, golomb prefix length, or subband assembly).\n");
            v.append("  → Suggested next probes:\n");
            v.append("      • CrxHeader.resolveDataOffsets() — verify each plane's true entry\n");
            v.append("        offset against the candidates listed in Section 2.\n");
            v.append("      • Sweep K-initialiser ∈ {0..7} per plane in a follow-up IT.\n");
            v.append("      • Check whether the 4-byte 0x00000000 separator is being\n");
            v.append("        consumed twice (once by the header, once by the first plane).\n");
        } else {
            v.append("  → No (byteΔ, bitΔ) candidate brings any plane near a healthy\n");
            v.append("    cross-frame correlation. The bug is NOT a simple per-plane offset.\n");
            v.append("  → Higher-priority hypotheses (work the next IT against these):\n");
            v.append("      • Wrong K-initialiser per plane (Rice never adapts cleanly).\n");
            v.append("      • Wrong residual/sign mapping after the unary prefix.\n");
            v.append("      • Wrong subband (LL/HL/LH/HH) assembly order.\n");
            v.append("      • Wrong tile-data ordering (tile0/plane0..3 then tile1/plane0..3\n");
            v.append("        vs. interleaved). Section 1 hex-dumps in Round-15 are the start.\n");
            v.append("  → Reading the per-plane top-5 table in Section 1 is mandatory:\n");
            v.append("    if the best score is barely above the second-best, the apparent\n");
            v.append("    'winning' shift is just noise.\n");
        }
        return v.toString();
    }

    // ───────────────────────────────────────────────────────────────────────
    // Subband helpers
    // ───────────────────────────────────────────────────────────────────────

    /** Returns one SubbandRef per (tile, plane) — the subband with index 0,
     *  i.e. the entry point of that plane's payload. */
    private static List<SubbandExtractor.SubbandRef> firstSubbandPerPlane(
            List<SubbandExtractor.SubbandRef> all) {
        return all.stream()
                .filter(s -> s.subbandIndex() == 0)
                .sorted((a, b) -> {
                    int c = Integer.compare(a.tileSequence(), b.tileSequence());
                    return c != 0 ? c : Integer.compare(a.planeIndex(), b.planeIndex());
                })
                .toList();
    }

    private static SubbandExtractor.SubbandRef matchingSubband(
            List<SubbandExtractor.SubbandRef> all, SubbandExtractor.SubbandRef ref) {
        return all.stream()
                .filter(s -> s.tileSequence() == ref.tileSequence()
                        && s.planeIndex() == ref.planeIndex()
                        && s.subbandIndex() == ref.subbandIndex())
                .findFirst().orElse(null);
    }

    // ───────────────────────────────────────────────────────────────────────
    // Bit-aware Rice decoder + statistics
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Decodes up to {@code maxSymbols} adaptive-Rice symbols from
     * {@code data[offset..]}, after first skipping {@code bitSkip} bits.
     */
    private static int[] decodeRiceSymbols(byte[] data, int offset, int bitSkip,
                                           int byteBudget, int maxSymbols) {
        if (offset < 0 || offset >= data.length) return new int[0];
        int end = Math.min(offset + byteBudget, data.length);
        BitReader reader = new BitReader(data, offset, end - offset);
        try {
            for (int i = 0; i < bitSkip && reader.hasMore(); i++) {
                reader.readBit();
            }
        } catch (Exception e) {
            return new int[0];
        }

        List<Integer> symbols = new ArrayList<>(maxSymbols);
        int k = 0;
        long accum = 0;
        long cnt = 1;

        while (reader.hasMore() && symbols.size() < maxSymbols) {
            try {
                int q = 0;
                while (reader.hasMore() && reader.readBit() == 0) {
                    q++;
                    if (q > 32) break;
                }
                int r = (k > 0 && reader.hasMore()) ? reader.readBits(k) : 0;
                int sym = (q << k) | r;
                symbols.add(sym);
                int signed = (sym & 1) == 0 ? sym >> 1 : -(sym >> 1) - 1;
                accum += Math.abs(signed);
                cnt++;
                if (accum > cnt << k && k < 14) k++;
                else if (k > 0 && accum <= cnt << (k - 1)) k--;
                if (cnt >= 1024) {
                    accum >>= 1;
                    cnt >>= 1;
                    if (cnt == 0) cnt = 1;
                }
            } catch (Exception e) {
                break;
            }
        }
        int[] out = new int[symbols.size()];
        for (int i = 0; i < out.length; i++) out[i] = symbols.get(i);
        return out;
    }

    private static double entropyBits(int[] syms) {
        if (syms.length == 0) return Double.NaN;
        java.util.Map<Integer, Integer> freq = new java.util.HashMap<>();
        for (int s : syms) freq.merge(s, 1, Integer::sum);
        double n = syms.length, ent = 0;
        for (int f : freq.values()) {
            double p = f / n;
            ent -= p * (Math.log(p) / Math.log(2));
        }
        return ent;
    }

    private static double neighbourCorrelation(int[] syms) {
        int n = syms.length - 1;
        if (n < 4) return 0;
        int[] a = new int[n], b = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = syms[i];
            b[i] = syms[i + 1];
        }
        return pearsonR(a, b);
    }

    private static double pearsonR(int[] a, int[] b) {
        int n = Math.min(a.length, b.length);
        if (n < 4) return 0;
        double sa = 0, sb = 0;
        for (int i = 0; i < n; i++) { sa += a[i]; sb += b[i]; }
        double ma = sa / n, mb = sb / n, cov = 0, va = 0, vb = 0;
        for (int i = 0; i < n; i++) {
            double da = a[i] - ma, db = b[i] - mb;
            cov += da * db;
            va += da * da;
            vb += db * db;
        }
        double d = Math.sqrt(va * vb);
        return d < 1e-12 ? 0 : cov / d;
    }

    private static double[] meanAndStd(int[] arr) {
        if (arr.length == 0) return new double[]{0, 0};
        double sum = 0;
        for (int v : arr) sum += v;
        double mean = sum / arr.length, var = 0;
        for (int v : arr) {
            double d = v - mean;
            var += d * d;
        }
        return new double[]{mean, Math.sqrt(var / arr.length)};
    }

    private static String symbolPreview(int[] syms) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < syms.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(syms[i]);
        }
        return sb.toString();
    }

    /** Tiny ASCII heatmap: maps each symbol into one of 10 buckets by quantile-ish bin. */
    private static String asciiHeatmap(int[] syms, int len) {
        if (syms.length == 0) return "[empty]";
        int n = Math.min(len, syms.length);
        int max = 0;
        for (int i = 0; i < n; i++) max = Math.max(max, syms[i]);
        char[] palette = " .:-=+*#%@".toCharArray();
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            int idx = max == 0 ? 0 : (int) ((long) syms[i] * (palette.length - 1) / max);
            if (idx < 0) idx = 0;
            if (idx >= palette.length) idx = palette.length - 1;
            sb.append(palette[idx]);
        }
        return sb.toString();
    }

    // ───────────────────────────────────────────────────────────────────────
    // Per-plane result holder
    // ───────────────────────────────────────────────────────────────────────

    private static final class PlaneResult {
        final int tileSeq;
        final int planeIdx;
        final SubbandExtractor.SubbandRef basisRef;

        int bestByteDelta = 0;
        int bestBitDelta = 0;
        double bestEntropy = Double.NaN;
        double bestNeighbourR = -1;
        double bestCrossR = -1;
        double bestScore = -Double.MAX_VALUE;

        // Keep top-N rows of the sweep table for the report.
        record Row(int byteDelta, int bitDelta, double entropy, double neighR, double crossR, double score) {}
        final List<Row> rows = new ArrayList<>();

        PlaneResult(int tileSeq, int planeIdx, SubbandExtractor.SubbandRef basisRef) {
            this.tileSeq = tileSeq;
            this.planeIdx = planeIdx;
            this.basisRef = basisRef;
        }

        void observe(int byteDelta, int bitDelta, double entropy, double neighR,
                     double crossR, double score) {
            rows.add(new Row(byteDelta, bitDelta, entropy, neighR, crossR, score));
            if (score > bestScore) {
                bestScore = score;
                bestByteDelta = byteDelta;
                bestBitDelta = bitDelta;
                bestEntropy = entropy;
                bestNeighbourR = neighR;
                bestCrossR = crossR;
            }
        }

        void appendTo(StringBuilder sb) {
            sb.append(String.format("--- tile%d / plane%d  (subband 0 @file offset %d, dataSize=%d) ---%n",
                    tileSeq, planeIdx, basisRef.dataOffsetInFile(), basisRef.dataSize()));
            // Show the top 5 candidates by score for context.
            rows.sort((x, y) -> Double.compare(y.score, x.score));
            int take = Math.min(5, rows.size());
            sb.append(String.format("  %-7s %-5s %-10s %-12s %-12s %-7s%n",
                    "byteΔ", "bitΔ", "entropy", "neighbour-r", "cross-r", "score"));
            for (int i = 0; i < take; i++) {
                Row r = rows.get(i);
                sb.append(String.format("  %+5d   %3d    %8.3f   %+10.4f   %+10.4f   %+5.3f%n",
                        r.byteDelta, r.bitDelta, r.entropy, r.neighR, r.crossR, r.score));
            }
            sb.append('\n');
        }
    }
}
