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
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-9 follow-up to {@link CrxPlaneFollowupRound8IT}, executing the
 * single experiment Update (l) §1 explicitly deferred:
 *
 * <p><b>Hypothesis under test (Update (l) winner):</b>
 * {@code K_INIT = CMP1.quantValue} per subband, vs. the alternative
 * {@code K_INIT = 4} (constant). Round-8 could not separate them
 * because every fixture in the corpus reported {@code q = 4} on every
 * plane — making both rules numerically identical for that input.</p>
 *
 * <p>The Update (m) C-RAW fixture
 * {@code g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-02.cr3}
 * is the first bitstream in the corpus with a non-constant per-subband
 * {@code q-vector} ({@code {4, 16, 22, 26, 32}}). Round-9 measures, for
 * every one of its 80 subbands and every {@code K_INIT ∈ [0, bitDepth]},
 * the average bits-per-symbol consumed by a Rice/Golomb decoder running
 * the patented {@code K_BRACKET_PATENT} K-update rule on a fixed symbol
 * budget. The {@code K_INIT} that minimises bits-per-symbol per subband
 * is the empirically optimal seed for that subband.</p>
 *
 * <h3>Discriminator</h3>
 * <ul>
 *   <li>If {@code K_INIT = q} is structurally correct, the per-subband
 *       optimal {@code K_INIT} should be close to {@code q} for all five
 *       {@code q}-groups, and bits-per-symbol at {@code K_INIT = q}
 *       should match the global per-subband minimum to within a small
 *       slack.</li>
 *   <li>If {@code K_INIT = 4} is structurally correct, the optimal
 *       {@code K_INIT} should be near {@code 4} for every subband
 *       regardless of {@code q}, and bits-per-symbol at {@code K_INIT = 4}
 *       should match the per-subband minimum.</li>
 * </ul>
 *
 * <p><b>Strict test scope.</b> No production code is modified, no flag
 * is added, no hypothesis is promoted. Output:
 * {@code canon-cr3-imageio/target/crx-followup-round9/}.</p>
 *
 * <p>The test always passes — it produces artefacts for SubbandFixtures.md
 * Update (n).</p>
 */
class CrxPlaneFollowupRound9IT {

    private static final String FIXTURE_DIR = "src/test/resources/samples/research";
    /** First fixture in the corpus with a non-constant per-subband q-vector. */
    private static final String CRAW_FIXTURE =
            "g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-02.cr3";
    /** A second C-RAW frame from the same burst (sanity check across frames). */
    private static final String CRAW_FIXTURE_FRAME2 =
            "g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-03.cr3";

    private static final Path OUT_DIR = Path.of("target", "crx-followup-round9");

    /**
     * Per-subband symbol budget. The smallest C-RAW HH3 subbands hold
     * around 1–4 KB; 4096 symbols at ~10 bits/symbol average is well
     * within reach for every subband while keeping the 80 × ≈15 K-values
     * sweep under a few seconds total.
     */
    private static final int SYMBOL_BUDGET = 4096;

    /** Maximum unary prefix length we accept before declaring the K invalid. */
    private static final int MAX_UNARY = 64;

    @Test
    void runRound9Sweep() throws Exception {
        Files.createDirectories(OUT_DIR);

        File primary = new File(FIXTURE_DIR + "/" + CRAW_FIXTURE);
        if (!primary.exists()) {
            // Fixture not committed to the repo — the test is a no-op in that case.
            // Operators capture it via CleanroomCr3FixtureCaptureIT (see SubbandFixtures.md
            // Update (m) for the operator workflow).
            System.out.println("Round-9: fixture not present, skipping (" + primary + ")");
            try (PrintWriter pw = new PrintWriter(OUT_DIR.resolve("metrics.tsv").toFile())) {
                pw.println("section\tfile\ttile\tplane\tsubband\tq\tk_init\tmetric\tvalue");
                pw.printf("R9\t%s\t-\t-\t-\t-\t-\tnote\tfixture-not-present%n", CRAW_FIXTURE);
            }
            try (PrintWriter pw = new PrintWriter(OUT_DIR.resolve("winners.txt").toFile())) {
                pw.println("Round-9: skipped (fixture " + CRAW_FIXTURE + " not present).");
            }
            return;
        }

        try (PrintWriter tsv = new PrintWriter(OUT_DIR.resolve("metrics.tsv").toFile())) {
            tsv.println("section\tfile\ttile\tplane\tsubband\tq\tk_init\tmetric\tvalue");
            List<SubbandResult> primaryResults = sweepFile(primary, CRAW_FIXTURE, tsv);

            File frame2 = new File(FIXTURE_DIR + "/" + CRAW_FIXTURE_FRAME2);
            List<SubbandResult> frame2Results = frame2.exists()
                    ? sweepFile(frame2, CRAW_FIXTURE_FRAME2, tsv)
                    : List.of();

            summarise(primaryResults, frame2Results);
        }

        assertThat(OUT_DIR.resolve("metrics.tsv")).exists();
        assertThat(OUT_DIR.resolve("winners.txt")).exists();
    }

    // ============================================================ sweep

    private static List<SubbandResult> sweepFile(File f, String fileName, PrintWriter tsv) throws Exception {
        Cmp1Box cmp1 = readCmp1(f);
        if (cmp1 == null) {
            System.out.printf("R9 %-60s SKIPPED (no CMP1)%n", fileName);
            return List.of();
        }
        List<SubbandExtractor.SubbandRef> subs = SubbandExtractor.list(f);
        if (subs.isEmpty()) {
            System.out.printf("R9 %-60s SKIPPED (no subbands)%n", fileName);
            return List.of();
        }
        int bd = cmp1.bitsPerSample();
        if (bd <= 0) bd = 14;

        System.out.printf("%n=== Round-9 sweep on %s (bd=%d, %d subbands, waveletLevels=%d) ===%n",
                fileName, bd, subs.size(), cmp1.waveletLevels());

        List<SubbandResult> results = new ArrayList<>(subs.size());
        for (SubbandExtractor.SubbandRef s : subs) {
            byte[] payload = SubbandExtractor.read(f, s);
            int q = s.quantValue();

            double[] bitsPerSymbol = new double[bd + 1];
            int[] symbolsDecoded = new int[bd + 1];
            for (int kInit = 0; kInit <= bd; kInit++) {
                Probe p = riceProbe(payload, kInit, bd, SYMBOL_BUDGET);
                bitsPerSymbol[kInit] = (p.symbols == 0) ? Double.POSITIVE_INFINITY
                        : (p.bitsConsumed / (double) p.symbols);
                symbolsDecoded[kInit] = p.symbols;
                tsv.printf("R9\t%s\ttile%d\tp%d\tsb%d\t%d\t%d\tbits_per_symbol\t%.6f%n",
                        fileName, s.tileSequence(), s.planeIndex(), s.subbandIndex(),
                        q, kInit, bitsPerSymbol[kInit]);
                tsv.printf("R9\t%s\ttile%d\tp%d\tsb%d\t%d\t%d\tsymbols_decoded\t%d%n",
                        fileName, s.tileSequence(), s.planeIndex(), s.subbandIndex(),
                        q, kInit, p.symbols);
            }

            int optK = argMinFinite(bitsPerSymbol);
            double bpsAtOpt = bitsPerSymbol[optK];
            double bpsAtQ   = (q >= 0 && q <= bd) ? bitsPerSymbol[q] : Double.POSITIVE_INFINITY;
            double bpsAt4   = (4 <= bd) ? bitsPerSymbol[4] : Double.POSITIVE_INFINITY;

            tsv.printf("R9\t%s\ttile%d\tp%d\tsb%d\t%d\t-\toptimal_k_init\t%d%n",
                    fileName, s.tileSequence(), s.planeIndex(), s.subbandIndex(), q, optK);
            tsv.printf("R9\t%s\ttile%d\tp%d\tsb%d\t%d\t-\tbps_at_optimal\t%.6f%n",
                    fileName, s.tileSequence(), s.planeIndex(), s.subbandIndex(), q, bpsAtOpt);
            tsv.printf("R9\t%s\ttile%d\tp%d\tsb%d\t%d\t-\tbps_at_kInit_eq_q\t%.6f%n",
                    fileName, s.tileSequence(), s.planeIndex(), s.subbandIndex(), q, bpsAtQ);
            tsv.printf("R9\t%s\ttile%d\tp%d\tsb%d\t%d\t-\tbps_at_kInit_eq_4\t%.6f%n",
                    fileName, s.tileSequence(), s.planeIndex(), s.subbandIndex(), q, bpsAt4);

            results.add(new SubbandResult(fileName, s.tileSequence(), s.planeIndex(),
                    s.subbandIndex(), q, optK, bpsAtOpt, bpsAtQ, bpsAt4));
        }

        // Per-q-group summary on stdout.
        Map<Integer, List<SubbandResult>> byQ = new TreeMap<>();
        for (SubbandResult r : results) byQ.computeIfAbsent(r.q, k -> new ArrayList<>()).add(r);
        System.out.printf("%-6s %-6s %-12s %-14s %-14s %-14s%n",
                "q", "n", "med(optK)", "med(bps@opt)", "med(bps@q)", "med(bps@4)");
        for (var e : byQ.entrySet()) {
            int q = e.getKey();
            List<SubbandResult> rs = e.getValue();
            double medOptK = medianInt(rs.stream().mapToInt(r -> r.optK).toArray());
            double medBpsOpt = median(rs.stream().mapToDouble(r -> r.bpsAtOpt).toArray());
            double medBpsQ   = median(rs.stream().mapToDouble(r -> r.bpsAtQ).toArray());
            double medBps4   = median(rs.stream().mapToDouble(r -> r.bpsAt4).toArray());
            System.out.printf("%-6d %-6d %-12.2f %-14.4f %-14.4f %-14.4f%n",
                    q, rs.size(), medOptK, medBpsOpt, medBpsQ, medBps4);
        }
        return results;
    }

    // ============================================================ winner aggregation

    private static void summarise(List<SubbandResult> primary, List<SubbandResult> frame2) throws IOException {
        try (PrintWriter pw = new PrintWriter(OUT_DIR.resolve("winners.txt").toFile())) {
            pw.println("Round-9 — discrimination of K_INIT = q vs K_INIT = 4");
            pw.println("=====================================================");
            pw.println();
            writeFileSummary(pw, "frame 1 (primary)", primary);
            if (!frame2.isEmpty()) {
                pw.println();
                writeFileSummary(pw, "frame 2 (replicate)", frame2);
            }
            pw.println();
            writeVerdict(pw, primary, frame2);
        }
    }

    private static void writeFileSummary(PrintWriter pw, String label, List<SubbandResult> rs) {
        pw.println(label + " — " + rs.size() + " subbands");
        Map<Integer, List<SubbandResult>> byQ = new TreeMap<>();
        for (SubbandResult r : rs) byQ.computeIfAbsent(r.q, k -> new ArrayList<>()).add(r);
        pw.printf("  %-6s %-4s %-10s %-12s %-12s %-12s %-12s%n",
                "q", "n", "med(optK)", "med(bps@opt)", "med(bps@q)", "slack(q)", "slack(4)");
        for (var e : byQ.entrySet()) {
            int q = e.getKey();
            List<SubbandResult> rsq = e.getValue();
            double medOpt   = medianInt(rsq.stream().mapToInt(r -> r.optK).toArray());
            double medBpsO  = median(rsq.stream().mapToDouble(r -> r.bpsAtOpt).toArray());
            double medBpsQ  = median(rsq.stream().mapToDouble(r -> r.bpsAtQ).toArray());
            double medBps4  = median(rsq.stream().mapToDouble(r -> r.bpsAt4).toArray());
            double slackQ   = medBpsQ - medBpsO;
            double slack4   = medBps4 - medBpsO;
            pw.printf("  %-6d %-4d %-10.2f %-12.4f %-12.4f %-12.4f %-12.4f%n",
                    q, rsq.size(), medOpt, medBpsO, medBpsQ, slackQ, slack4);
        }
    }

    private static void writeVerdict(PrintWriter pw, List<SubbandResult> primary, List<SubbandResult> frame2) {
        List<SubbandResult> all = new ArrayList<>(primary);
        all.addAll(frame2);
        if (all.isEmpty()) {
            pw.println("VERDICT: insufficient data.");
            return;
        }
        double sumSlackQ = 0;
        double sumSlack4 = 0;
        int nQEqualsOpt = 0;
        int n4EqualsOpt = 0;
        for (SubbandResult r : all) {
            sumSlackQ += Math.max(0, r.bpsAtQ - r.bpsAtOpt);
            sumSlack4 += Math.max(0, r.bpsAt4 - r.bpsAtOpt);
            if (r.optK == r.q) nQEqualsOpt++;
            if (r.optK == 4) n4EqualsOpt++;
        }
        int n = all.size();
        pw.printf("VERDICT (%d subbands across %d frames):%n",
                n, frame2.isEmpty() ? 1 : 2);
        pw.printf("  mean slack vs optimum, K_INIT = q  : %+.4f bits/symbol%n", sumSlackQ / n);
        pw.printf("  mean slack vs optimum, K_INIT = 4  : %+.4f bits/symbol%n", sumSlack4 / n);
        pw.printf("  subbands where optK == q           : %d / %d (%.1f%%)%n",
                nQEqualsOpt, n, 100.0 * nQEqualsOpt / n);
        pw.printf("  subbands where optK == 4           : %d / %d (%.1f%%)%n",
                n4EqualsOpt, n, 100.0 * n4EqualsOpt / n);
        pw.println();
        if (sumSlackQ < sumSlack4 * 0.5) {
            pw.println("INTERPRETATION: K_INIT = quantValue is structurally favoured.");
        } else if (sumSlack4 < sumSlackQ * 0.5) {
            pw.println("INTERPRETATION: K_INIT = 4 (constant) is structurally favoured.");
        } else {
            pw.println("INTERPRETATION: neither rule clearly wins on bits-per-symbol alone.");
        }
        pw.println("(Bits-per-symbol minimisation is a necessary but not sufficient condition;");
        pw.println(" final promotion still requires Pearson(decode, preview) > 0.7 per CrxLosslessRawDecoder.)");
    }

    // ============================================================ Rice probe

    /** Fixed-budget Rice/Golomb decoder probe with the patent K bracket. */
    private static Probe riceProbe(byte[] payload, int kInit, int bd, int budget) {
        if (payload == null || payload.length == 0) return new Probe(0, 0);
        BitReader rd = new BitReader(payload);
        int k = clamp(kInit, 0, bd);
        int symbols = 0;
        try {
            while (symbols < budget && rd.hasMore()) {
                // unary prefix
                int q = 0;
                while (true) {
                    if (!rd.hasMore()) throw new IOException("eof in unary");
                    if (rd.readBit() != 0) break;
                    q++;
                    if (q > MAX_UNARY) {
                        // pathological: stream is being read with the wrong K
                        return new Probe(symbols, rd.bytePosition() * 8);
                    }
                }
                // remainder
                int r = 0;
                for (int i = 0; i < k; i++) {
                    if (!rd.hasMore()) throw new IOException("eof in rem");
                    r = (r << 1) | rd.readBit();
                }
                int s = (q << k) | r;
                // patent K bracket
                int kStar = (s <= 0) ? 0 : 31 - Integer.numberOfLeadingZeros(s + 1);
                int kMin = Math.max(0, kStar - 1);
                int kMax = Math.min(bd, kStar + 1);
                if (k < kMin) k++;
                else if (k > kMax) k--;
                symbols++;
            }
        } catch (IOException eof) {
            // Stream exhausted before budget reached — return what we got.
        }
        return new Probe(symbols, rd.bytePosition() * 8);
    }

    private record Probe(int symbols, int bitsConsumed) { }
    private record SubbandResult(String fileName, int tileSeq, int planeIndex, int subbandIndex,
                                  int q, int optK, double bpsAtOpt, double bpsAtQ, double bpsAt4) { }

    // ============================================================ utilities

    private static int argMinFinite(double[] xs) {
        int best = 0;
        double bestV = Double.POSITIVE_INFINITY;
        for (int i = 0; i < xs.length; i++) {
            if (Double.isFinite(xs[i]) && xs[i] < bestV) {
                bestV = xs[i];
                best = i;
            }
        }
        return best;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double median(double[] xs) {
        if (xs.length == 0) return Double.NaN;
        double[] copy = xs.clone();
        Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    private static double medianInt(int[] xs) {
        if (xs.length == 0) return Double.NaN;
        int[] copy = xs.clone();
        Arrays.sort(copy);
        return copy[copy.length / 2];
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
