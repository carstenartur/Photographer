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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round‑12 mega‑mega‑sweep — packs <b>all</b> currently‑plausible decoder
 * hypotheses into a single IT, exercised across <b>all five</b> research
 * fixtures (2 lossless + 3 C‑RAW). Sized to amortise CR3 parsing and
 * preview extraction so we can evaluate ~50 000 candidate configurations
 * in one wall‑clock window instead of dripping a few hundred per round.
 *
 * <p>This IT extends the Round‑11 axes with:</p>
 * <ul>
 *   <li><b>Bit order</b>: MSB‑first (Canon publicly documented) vs
 *       LSB‑first (some bitstream conventions reverse the per‑byte order
 *       — fold tested by reversing each byte before feeding the
 *       BitReader).</li>
 *   <li><b>Payload offset</b>: {0, 1, 2, 4, 8} bytes — covers the
 *       hypothesis that each subband payload starts with a small
 *       per‑subband header (dRunC / "rounded bits" alignment).</li>
 *   <li><b>Sign convention</b>: LSB‑sign‑magnitude, negated LSB,
 *       MSB‑sign (sign bit is high bit of the codeword), and zigzag
 *       (k → (k &gt;&gt; 1) ^ (-(k &amp; 1))).</li>
 *   <li><b>Predictors</b>: {NONE, HORIZ_DELTA, HORIZ_ROW_RESET,
 *       VERT_DELTA, MED} — adds vertical and median (Paeth‑lite)
 *       predictors not in Round‑11.</li>
 *   <li><b>Expanded K_INIT</b>: {0,1,2,3,4,5,7,9} for lossless,
 *       {0,1,2,3,5} for the staged C‑RAW path.</li>
 *   <li><b>Lossless sweep</b> for the {@code asis} and {@code dark}
 *       fixtures — Round‑11 only touched the C‑RAW {@code detail}
 *       fixture.</li>
 * </ul>
 *
 * <h3>Strict test scope</h3>
 * <p>No production code is modified, no flag is added, no hypothesis is
 * promoted. Output dir
 * {@code canon-cr3-imageio/target/crx-followup-round12/}. Always passes
 * — produces:
 * <ul>
 *   <li>{@code metrics.tsv}</li>
 *   <li>{@code winners.txt}</li>
 *   <li>{@code top10.txt} — the ten best hypotheses globally, with full
 *       configuration printed.</li>
 *   <li>{@code summary.txt} — a one‑page outcome summary suitable for
 *       pasting into SubbandFixtures.md as Update (p).</li>
 * </ul>
 *
 * <h3>Variant budget (estimate)</h3>
 * <pre>
 *   Lossless (asis + dark):
 *     2 fixtures × 8 (tile,plane) × 8 K × 4 sign × 5 pred × 2 bit × 5 off
 *     = 25 600 variants total
 *
 *   C‑RAW Stage A (LL_3 only, very cheap):
 *     3 fixtures × 8 (tile,plane) × 4 LL × 5 K × 4 dq × 5 pred × 2 bit
 *     = 19 200 variants total
 *
 *   C‑RAW Stage B (full IDWT, pinned LL):
 *     3 fixtures × 8 (tile,plane) × 4 K × 4 sign × 3 dq × 5 pred × 6 perm
 *     = 17 280 variants total
 * </pre>
 * <p>Aggressive caching: each entropy decode (the expensive part) is
 * reused across all post‑processing axes (sign, predictor, dequant,
 * role permutation), so the actual entropy‑decode count is at most
 * {@code (kInit × bit_order × payload_offset)} per subband — a few
 * hundred per fixture instead of tens of thousands.</p>
 */
class CrxPlaneFollowupRound12IT {

    private static final String FIXTURE_DIR = "src/test/resources/samples/research";
    private static final Path OUT_DIR = Path.of("target", "crx-followup-round12");

    private static final String FX_ASIS   = "g5xm2-asis-iso200-ss130-ff2p5-01.cr3";
    private static final String FX_DARK   = "g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02.cr3";
    private static final String FX_FLAT   = "g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02.cr3";
    private static final String FX_DET_02 = "g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-02.cr3";
    private static final String FX_DET_03 = "g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-03.cr3";

    private static final int MAX_UNARY = 64;

    private enum BitOrder { MSB_FIRST, LSB_FIRST }
    private enum SignConv { S_LSB, S_LSB_NEG, S_MSB, S_ZIGZAG }
    private enum Predictor { P_NONE, P_HORIZ_DELTA, P_HORIZ_ROW_RESET, P_VERT_DELTA, P_MED }
    private enum LlMode { LL_SIGNED, LL_UNSIGNED, LL_UNSIGNED_MID, LL_UNSIGNED_MID_X2 }
    private enum Dequant { D_IDENTITY, D_MUL_Q, D_MUL_Q_HALF, D_SHIFT_Q }

    private static final int[] LOSSLESS_K_INITS = { 0, 1, 2, 3, 4, 5, 7, 9 };
    private static final int[] STAGE_A_K_INITS  = { 0, 1, 2, 3, 5 };
    private static final int[] STAGE_B_K_INITS  = { 0, 1, 2, 3 };
    private static final int[] PAYLOAD_OFFSETS  = { 0, 1, 2, 4, 8 };

    private static final int[][] ROLE_PERMS = {
            {0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}
    };
    private static final String[] ROLE_NAMES = { "HL", "LH", "HH" };

    /** A scored variant — kept compact for top‑N tracking. */
    private record Hit(double r, String fixture, String tilePlane, String stage, String label) {
        double absR() { return Math.abs(r); }
    }

    @Test
    void runRound12MegaMegaSweep() throws Exception {
        Files.createDirectories(OUT_DIR);
        long t0 = System.currentTimeMillis();
        AtomicLong totalEvals = new AtomicLong();
        AtomicLong totalDecodes = new AtomicLong();
        List<Hit> globalTop = new ArrayList<>();

        try (PrintWriter tsv = new PrintWriter(OUT_DIR.resolve("metrics.tsv").toFile());
             PrintWriter win = new PrintWriter(OUT_DIR.resolve("winners.txt").toFile())) {
            tsv.println("section\tfixture\ttile_plane\tvariant\tmetric\tvalue");
            win.println("Round‑12 mega‑mega‑sweep — winners per (fixture, tile, plane)");
            win.println("===============================================================");

            // ---------- Lossless sweep (asis + dark) -----------------------
            for (String fx : new String[]{ FX_ASIS, FX_DARK }) {
                runOneFixture(fx, /*lossless*/ true, tsv, win, globalTop, totalEvals, totalDecodes);
            }
            // ---------- C‑RAW two‑stage sweep (flat + 2× detail) ----------
            for (String fx : new String[]{ FX_FLAT, FX_DET_02, FX_DET_03 }) {
                runOneFixture(fx, /*lossless*/ false, tsv, win, globalTop, totalEvals, totalDecodes);
            }

            long dt = System.currentTimeMillis() - t0;
            win.printf("%n--- Global summary ---%n");
            win.printf("Total decode‑equivalents: %d%n", totalDecodes.get());
            win.printf("Total scored variants:    %d%n", totalEvals.get());
            win.printf("Wall‑clock: %.1f s%n", dt / 1000.0);
        }

        // Top‑N report
        globalTop.sort((a, b) -> Double.compare(b.absR(), a.absR()));
        try (PrintWriter pw = new PrintWriter(OUT_DIR.resolve("top10.txt").toFile())) {
            pw.println("Round‑12 — top 10 hypotheses by |Pearson r|");
            pw.println("==============================================");
            int n = Math.min(10, globalTop.size());
            for (int i = 0; i < n; i++) {
                Hit h = globalTop.get(i);
                pw.printf("#%-2d  |r|=%.4f  r=%+.4f  %s / %s / %s%n     %s%n",
                        i + 1, h.absR(), h.r, h.fixture, h.tilePlane, h.stage, h.label);
            }
            if (globalTop.isEmpty()) pw.println("(no hypotheses scored)");
        }

        // One‑page summary suitable for pasting into SubbandFixtures.md
        try (PrintWriter pw = new PrintWriter(OUT_DIR.resolve("summary.txt").toFile())) {
            double maxAbs = globalTop.isEmpty() ? 0 : globalTop.get(0).absR();
            pw.println("Round‑12 outcome summary");
            pw.println("=========================");
            pw.printf("Fixtures swept:           5 (2 lossless + 3 C‑RAW)%n");
            pw.printf("Total variants evaluated: %d%n", totalEvals.get());
            pw.printf("Decode‑equivalents:       %d%n", totalDecodes.get());
            pw.printf("Wall‑clock:               %.1f s%n", (System.currentTimeMillis() - t0) / 1000.0);
            pw.printf("Best |Pearson r|:         %.4f%n", maxAbs);
            if (maxAbs >= 0.7) {
                pw.println();
                pw.println("RESULT: ≥ 0.70 — promotion threshold cleared. Review top10.txt and");
                pw.println("        cross‑validate the winning configuration on the second");
                pw.println("        detail fixture before any production change.");
            } else if (maxAbs >= 0.4) {
                pw.println();
                pw.println("RESULT: 0.40 ≤ |r| < 0.70 — partial signal recovered. Inspect");
                pw.println("        top10.txt for the winning axis combinations and design a");
                pw.println("        focused Round‑13 around them.");
            } else {
                pw.println();
                pw.println("RESULT: |r| < 0.40 across all 50k+ variants. The remaining missing");
                pw.println("        pieces are almost certainly outside the swept axes:");
                pw.println("          • Per‑subband K_INIT seeded from CMP1.kvalues (not constant)");
                pw.println("          • Per‑pixel context model (not row‑reset horizontal delta)");
                pw.println("          • Lifting sign convention inside InverseWaveletTransform");
                pw.println("          • MDAT subband interleaving differs from the assumed order");
                pw.println("          • Lossy path uses a different entropy stage than lossless");
                pw.println("        Recommendation: pivot to LibRaw‑backed JNI ground truth and");
                pw.println("        keep these fixtures as pixel‑exact regression tests.");
            }
        }

        assertThat(OUT_DIR.resolve("metrics.tsv")).exists();
        assertThat(OUT_DIR.resolve("winners.txt")).exists();
        assertThat(OUT_DIR.resolve("top10.txt")).exists();
        assertThat(OUT_DIR.resolve("summary.txt")).exists();
    }

    // ============================================================ per‑fixture entry

    private void runOneFixture(String fx, boolean lossless, PrintWriter tsv, PrintWriter win,
                               List<Hit> top, AtomicLong evals, AtomicLong decodes) throws Exception {
        File f = new File(FIXTURE_DIR + "/" + fx);
        if (!f.exists()) {
            win.printf("%n--- %s --- skipped (fixture not present)%n", fx);
            return;
        }
        Cmp1Box cmp1 = readCmp1(f);
        if (cmp1 == null) {
            win.printf("%n--- %s --- skipped (no CMP1)%n", fx);
            return;
        }
        int waveletLevels = cmp1.waveletLevels();
        int[] cfaToPreview = cfaLayoutMap(cmp1.cfaLayout());
        List<SubbandExtractor.SubbandRef> subs = SubbandExtractor.list(f);
        if (subs.isEmpty()) {
            win.printf("%n--- %s --- skipped (no subbands)%n", fx);
            return;
        }
        SubbandExtractor.SubbandRef any = subs.get(0);
        int planeW = any.planeWidth();
        int planeH = any.planeHeight();
        int bd = any.bitDepth();

        win.printf("%n=== %s ===%n", fx);
        win.printf("    plane %d×%d, bd=%d, waveletLevels=%d, cfaLayout=%s, %d subbands%n",
                planeW, planeH, bd, waveletLevels, cmp1.cfaLayoutName(), subs.size());

        Map<String, List<SubbandExtractor.SubbandRef>> groups = new LinkedHashMap<>();
        for (SubbandExtractor.SubbandRef s : subs) {
            String key = "tile" + s.tileSequence() + "/p" + s.planeIndex();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }

        // Pre‑extract preview per Bayer channel once per fixture.
        PreviewReferenceExtractor.Reference[] refs = new PreviewReferenceExtractor.Reference[4];
        for (int c = 0; c < 4; c++) {
            refs[c] = PreviewReferenceExtractor.extract(f, c, planeW, planeH);
        }

        for (var e : groups.entrySet()) {
            String tag = e.getKey();
            List<SubbandExtractor.SubbandRef> sbs = e.getValue();
            int previewChan = cfaToPreview[sbs.get(0).planeIndex()];
            int[] refFull = refs[previewChan].pixels();

            if (lossless) {
                sweepLossless(f, sbs, planeW, planeH, bd, refFull, fx, tag, tsv, win, top, evals, decodes);
            } else {
                sweepCraw(f, sbs, planeW, planeH, bd, waveletLevels, refFull,
                        fx, tag, tsv, win, top, evals, decodes);
            }
        }
    }

    // ============================================================ lossless sweep

    private void sweepLossless(File f, List<SubbandExtractor.SubbandRef> sbs, int planeW, int planeH,
                               int bd, int[] refFull, String fx, String tag,
                               PrintWriter tsv, PrintWriter win, List<Hit> top,
                               AtomicLong evals, AtomicLong decodes) throws IOException {
        if (sbs.isEmpty()) return;
        SubbandExtractor.SubbandRef ref = sbs.get(0); // lossless = 1 subband per (tile,plane)
        byte[] payloadRaw = SubbandExtractor.read(f, ref);
        int n = planeW * planeH;

        Hit best = new Hit(0, fx, tag, "lossless",
                "k=0 sign=LSB pred=NONE bit=MSB off=0");
        for (BitOrder bo : BitOrder.values()) {
            byte[] paLevel0 = (bo == BitOrder.MSB_FIRST) ? payloadRaw : reverseBitsPerByte(payloadRaw);
            for (int off : PAYLOAD_OFFSETS) {
                if (off >= paLevel0.length) continue;
                byte[] payload = (off == 0) ? paLevel0
                        : Arrays.copyOfRange(paLevel0, off, paLevel0.length);
                for (int kInit : LOSSLESS_K_INITS) {
                    int[] decoded = decodeRiceRaw(payload, n, bd, kInit);
                    decodes.incrementAndGet();
                    for (SignConv sc : SignConv.values()) {
                        int[] mapped = applySign(decoded, sc);
                        for (Predictor p : Predictor.values()) {
                            int[] pix = applyPredictor(mapped, planeW, planeH, p);
                            evals.incrementAndGet();
                            double r = pearson(pix, refFull);
                            if (Math.abs(r) > best.absR()) {
                                best = new Hit(r, fx, tag, "lossless",
                                        "k=" + kInit + " sign=" + sc + " pred=" + p
                                                + " bit=" + bo + " off=" + off);
                            }
                        }
                    }
                }
            }
        }
        tsv.printf("R12/lossless\t%s\t%s\t%s\tbest_r\t%.6f%n",
                fx, tag, best.label, best.r);
        win.printf("  [lossless] %s: |r|=%.4f  %s%n", tag, best.absR(), best.label);
        top.add(best);
    }

    // ============================================================ C‑RAW sweep

    private void sweepCraw(File f, List<SubbandExtractor.SubbandRef> sbs, int planeW, int planeH,
                           int bd, int levels, int[] refFull, String fx, String tag,
                           PrintWriter tsv, PrintWriter win, List<Hit> top,
                           AtomicLong evals, AtomicLong decodes) throws IOException {
        int[][] dims = subbandDims(planeW, planeH, levels);
        if (sbs.size() < dims.length) {
            win.printf("  %s: skipped (%d/%d subbands)%n", tag, sbs.size(), dims.length);
            return;
        }
        // ---- Stage A: LL_L only ------------------------------------------
        SubbandExtractor.SubbandRef llRef = sbs.get(0);
        byte[] llRaw = SubbandExtractor.read(f, llRef);
        int llN = dims[0][0] * dims[0][1];
        int llQ = llRef.quantValue();

        // Down‑scaled preview reference for LL grid.
        int llW = dims[0][0], llH = dims[0][1];
        int[] refLl = boxDownscale(refFull, planeW, planeH, llW, llH);

        Hit bestA = new Hit(0, fx, tag, "stageA",
                "ll=SIGNED k=0 dq=IDENTITY pred=NONE bit=MSB");
        // Cache decode per (bit_order, kInit). LL mode and dequant are post‑processing.
        for (BitOrder bo : BitOrder.values()) {
            byte[] llBitOrdered = (bo == BitOrder.MSB_FIRST) ? llRaw : reverseBitsPerByte(llRaw);
            for (int kInit : STAGE_A_K_INITS) {
                int[] base = decodeRiceRaw(llBitOrdered, llN, bd, kInit);
                decodes.incrementAndGet();
                for (LlMode llm : LlMode.values()) {
                    int[] adj = applyLlMode(base, llm, bd);
                    for (Dequant dq : Dequant.values()) {
                        int[] dq1 = applyDequant(adj, dq, llQ);
                        for (Predictor p : Predictor.values()) {
                            int[] pix = applyPredictor(dq1, llW, llH, p);
                            evals.incrementAndGet();
                            double r = pearson(pix, refLl);
                            if (Math.abs(r) > bestA.absR()) {
                                bestA = new Hit(r, fx, tag, "stageA",
                                        "ll=" + llm + " k=" + kInit + " dq=" + dq
                                                + " pred=" + p + " bit=" + bo);
                            }
                        }
                    }
                }
            }
        }
        tsv.printf("R12/A\t%s\t%s\t%s\tbest_LL_r\t%.6f%n",
                fx, tag, bestA.label, bestA.r);
        win.printf("  [stageA] %s: |r|=%.4f  %s%n", tag, bestA.absR(), bestA.label);
        top.add(bestA);

        // ---- Stage B: full IDWT ------------------------------------------
        // Pin LL config from stageA winner. Sweep entropy on the 3*levels detail bands.
        BitOrder pinnedBo = parseBitOrder(bestA.label);
        LlMode pinnedLl = parseLlMode(bestA.label);
        int pinnedKa = parseKInit(bestA.label);
        Dequant pinnedDqA = parseDequant(bestA.label);
        Predictor pinnedPredA = parsePredictor(bestA.label);

        byte[] llBitOrdered = (pinnedBo == BitOrder.MSB_FIRST) ? llRaw : reverseBitsPerByte(llRaw);
        int[] llBase = decodeRiceRaw(llBitOrdered, llN, bd, pinnedKa);
        decodes.incrementAndGet();
        int[] llCoef = applyLlMode(llBase, pinnedLl, bd);
        llCoef = applyDequant(llCoef, pinnedDqA, llQ);
        llCoef = applyPredictor(llCoef, llW, llH, pinnedPredA);

        int detailCount = 3 * levels;
        byte[][] detailRaw = new byte[detailCount][];
        int[][] detailDims = new int[detailCount][];
        int[] detailQ = new int[detailCount];
        for (int i = 0; i < detailCount; i++) {
            detailRaw[i] = SubbandExtractor.read(f, sbs.get(1 + i));
            detailDims[i] = dims[1 + i];
            detailQ[i] = sbs.get(1 + i).quantValue();
        }

        Hit bestB = new Hit(0, fx, tag, "stageB",
                "k=0 sign=LSB pred=NONE dq=MUL_Q perm=[HL,LH,HH] bit=MSB");
        for (BitOrder bo : BitOrder.values()) {
            byte[][] payloads = new byte[detailCount][];
            for (int i = 0; i < detailCount; i++) {
                payloads[i] = (bo == BitOrder.MSB_FIRST) ? detailRaw[i]
                        : reverseBitsPerByte(detailRaw[i]);
            }
            for (int kInit : STAGE_B_K_INITS) {
                // decode all detail subbands once per (bo, kInit)
                int[][] decoded = new int[detailCount][];
                for (int i = 0; i < detailCount; i++) {
                    int n = detailDims[i][0] * detailDims[i][1];
                    decoded[i] = decodeRiceRaw(payloads[i], n, bd, kInit);
                    decodes.incrementAndGet();
                }
                for (SignConv sc : SignConv.values()) {
                    int[][] signed = new int[detailCount][];
                    for (int i = 0; i < detailCount; i++) signed[i] = applySign(decoded[i], sc);
                    for (Dequant dq : new Dequant[]{ Dequant.D_MUL_Q, Dequant.D_MUL_Q_HALF, Dequant.D_SHIFT_Q }) {
                        int[][] dqArr = new int[detailCount][];
                        for (int i = 0; i < detailCount; i++) dqArr[i] = applyDequant(signed[i], dq, detailQ[i]);
                        for (Predictor pred : Predictor.values()) {
                            int[][] predicted = new int[detailCount][];
                            for (int i = 0; i < detailCount; i++) {
                                predicted[i] = applyPredictor(dqArr[i],
                                        detailDims[i][0], detailDims[i][1], pred);
                            }
                            for (int[] perm : ROLE_PERMS) {
                                int[] recon = idwtAssemble(llCoef, dims[0], predicted, detailDims, levels, perm);
                                evals.incrementAndGet();
                                if (recon == null) continue;
                                int n = Math.min(recon.length, refFull.length);
                                int[] cand = (recon.length == n) ? recon : Arrays.copyOf(recon, n);
                                int[] ref = (refFull.length == n) ? refFull : Arrays.copyOf(refFull, n);
                                double r = pearson(cand, ref);
                                if (Math.abs(r) > bestB.absR()) {
                                    bestB = new Hit(r, fx, tag, "stageB",
                                            "k=" + kInit + " sign=" + sc + " pred=" + pred
                                                    + " dq=" + dq
                                                    + " perm=[" + ROLE_NAMES[perm[0]] + ","
                                                    + ROLE_NAMES[perm[1]] + ","
                                                    + ROLE_NAMES[perm[2]] + "]"
                                                    + " bit=" + bo);
                                }
                            }
                        }
                    }
                }
            }
        }
        tsv.printf("R12/B\t%s\t%s\t%s\tbest_full_r\t%.6f%n",
                fx, tag, bestB.label, bestB.r);
        win.printf("  [stageB] %s: |r|=%.4f  %s%n", tag, bestB.absR(), bestB.label);
        top.add(bestB);
    }

    // ============================================================ helpers — bit / payload

    private static byte[] reverseBitsPerByte(byte[] in) {
        byte[] out = new byte[in.length];
        for (int i = 0; i < in.length; i++) {
            int b = in[i] & 0xFF;
            b = ((b & 0xF0) >>> 4) | ((b & 0x0F) << 4);
            b = ((b & 0xCC) >>> 2) | ((b & 0x33) << 2);
            b = ((b & 0xAA) >>> 1) | ((b & 0x55) << 1);
            out[i] = (byte) b;
        }
        return out;
    }

    // ============================================================ helpers — entropy

    /**
     * Decodes an unsigned Rice‑Golomb stream of length {@code n} from the given
     * payload. Sign‑mapping and predictor are applied separately so that the
     * (expensive) entropy decode can be cached across the (sign × predictor ×
     * dequant × role‑perm) post‑processing axes.
     */
    private static int[] decodeRiceRaw(byte[] payload, int n, int bd, int kInit) {
        if (payload == null || payload.length == 0 || n <= 0) return new int[Math.max(0, n)];
        BitReader rd = new BitReader(payload);
        int[] out = new int[n];
        int k = Math.max(0, kInit);
        int produced = 0;
        try {
            while (produced < n && rd.hasMore()) {
                int qv = 0;
                while (true) {
                    if (!rd.hasMore()) throw new IOException("eof in unary");
                    if (rd.readBit() != 0) break;
                    qv++;
                    if (qv > MAX_UNARY) return Arrays.copyOf(out, produced);
                }
                int r = 0;
                for (int i = 0; i < k; i++) {
                    if (!rd.hasMore()) throw new IOException("eof in rem");
                    r = (r << 1) | rd.readBit();
                }
                int s = (qv << k) | r;
                out[produced++] = s; // raw, unsigned
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

    private static int[] applySign(int[] coef, SignConv sc) {
        int[] out = new int[coef.length];
        switch (sc) {
            case S_LSB -> {
                for (int i = 0; i < coef.length; i++) {
                    int s = coef[i];
                    out[i] = ((s & 1) == 0) ? (s >> 1) : -((s >> 1) + 1);
                }
            }
            case S_LSB_NEG -> {
                for (int i = 0; i < coef.length; i++) {
                    int s = coef[i];
                    int m = ((s & 1) == 0) ? (s >> 1) : -((s >> 1) + 1);
                    out[i] = -m;
                }
            }
            case S_MSB -> {
                for (int i = 0; i < coef.length; i++) {
                    int s = coef[i];
                    int hb = Integer.highestOneBit(s);
                    if (hb <= 0) out[i] = s;
                    else out[i] = ((s & hb) != 0) ? -(s ^ hb) : s;
                }
            }
            case S_ZIGZAG -> {
                for (int i = 0; i < coef.length; i++) {
                    int s = coef[i];
                    out[i] = (s >> 1) ^ -(s & 1);
                }
            }
            default -> System.arraycopy(coef, 0, out, 0, coef.length);
        }
        return out;
    }

    // ============================================================ helpers — dequant

    private static int[] applyDequant(int[] coef, Dequant dq, int q) {
        if (coef == null) return new int[0];
        if (dq == Dequant.D_IDENTITY || q <= 1) return coef;
        int[] out = new int[coef.length];
        switch (dq) {
            case D_MUL_Q -> { for (int i = 0; i < coef.length; i++) out[i] = coef[i] * q; }
            case D_MUL_Q_HALF -> {
                int half = q / 2;
                for (int i = 0; i < coef.length; i++) {
                    int c = coef[i];
                    if (c > 0) out[i] = c * q + half;
                    else if (c < 0) out[i] = c * q - half;
                    else out[i] = 0;
                }
            }
            case D_SHIFT_Q -> {
                int shift = 31 - Integer.numberOfLeadingZeros(Math.max(1, q));
                for (int i = 0; i < coef.length; i++) out[i] = coef[i] << shift;
            }
            default -> System.arraycopy(coef, 0, out, 0, coef.length);
        }
        return out;
    }

    // ============================================================ helpers — LL mode

    private static int[] applyLlMode(int[] base, LlMode llm, int bd) {
        int[] out = new int[base.length];
        switch (llm) {
            case LL_SIGNED -> {
                for (int i = 0; i < base.length; i++) {
                    int s = base[i];
                    out[i] = ((s & 1) == 0) ? (s >> 1) : -((s >> 1) + 1);
                }
            }
            case LL_UNSIGNED -> System.arraycopy(base, 0, out, 0, base.length);
            case LL_UNSIGNED_MID -> {
                int mid = 1 << (bd - 1);
                for (int i = 0; i < base.length; i++) out[i] = base[i] - mid;
            }
            case LL_UNSIGNED_MID_X2 -> {
                int mid = 1 << bd;
                for (int i = 0; i < base.length; i++) out[i] = base[i] - mid;
            }
        }
        return out;
    }

    // ============================================================ helpers — predictor

    private static int[] applyPredictor(int[] coef, int w, int h, Predictor p) {
        if (p == Predictor.P_NONE || coef == null || coef.length == 0 || w <= 1) return coef;
        int rows = Math.min(h, coef.length / Math.max(1, w));
        int[] out = new int[coef.length];
        switch (p) {
            case P_HORIZ_DELTA, P_HORIZ_ROW_RESET -> {
                for (int y = 0; y < rows; y++) {
                    int rs = y * w;
                    int acc = 0;
                    for (int x = 0; x < w && rs + x < coef.length; x++) {
                        acc += coef[rs + x];
                        out[rs + x] = acc;
                    }
                }
            }
            case P_VERT_DELTA -> {
                for (int x = 0; x < w; x++) {
                    int acc = 0;
                    for (int y = 0; y < rows; y++) {
                        int idx = y * w + x;
                        if (idx >= coef.length) break;
                        acc += coef[idx];
                        out[idx] = acc;
                    }
                }
            }
            case P_MED -> {
                // Median (LOCO‑I‑style) predictor: predict v from
                //   a = left, b = above, c = above‑left
                //   pred = a + b - c (if c is min/max we clip)
                // Then v = residual + pred.
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
            }
            default -> System.arraycopy(coef, 0, out, 0, coef.length);
        }
        for (int i = rows * w; i < coef.length; i++) out[i] = coef[i];
        return out;
    }

    // ============================================================ helpers — IDWT

    private static int[] subbandDims1Level(int planeW, int planeH, int levels, int level) {
        return new int[]{ Math.max(1, planeW >> level), Math.max(1, planeH >> level) };
    }

    private static int[][] subbandDims(int planeW, int planeH, int levels) {
        int n = (levels <= 0) ? 1 : 1 + 3 * levels;
        int[][] dims = new int[n][2];
        int[] ll = subbandDims1Level(planeW, planeH, levels, levels);
        dims[0] = ll;
        for (int lev = levels; lev >= 1; lev--) {
            int[] d = subbandDims1Level(planeW, planeH, levels, lev);
            int base = 1 + 3 * (levels - lev);
            dims[base] = d; dims[base + 1] = d; dims[base + 2] = d;
        }
        return dims;
    }

    /** Assembles an IDWT pyramid using the supplied role permutation (per level). */
    private static int[] idwtAssemble(int[] llCoef, int[] llDims, int[][] detail, int[][] dDims,
                                      int levels, int[] perm) {
        int[] ll = llCoef;
        int llW = llDims[0], llH = llDims[1];
        int[] inv = new int[3];
        for (int slot = 0; slot < 3; slot++) inv[perm[slot]] = slot;
        for (int lev = levels; lev >= 1; lev--) {
            int base = 3 * (levels - lev); // index into detail[]
            int[] hl = padTo(detail[base + inv[0]], llW * llH);
            int[] lh = padTo(detail[base + inv[1]], llW * llH);
            int[] hh = padTo(detail[base + inv[2]], llW * llH);
            ll = padTo(ll, llW * llH);
            int outW = llW * 2, outH = llH * 2;
            try {
                ll = InverseWaveletTransform.inverseSynthesis2D(
                        ll, lh, hl, hh, llW, llH, outW, outH,
                        InverseWaveletTransform.FilterType.LEGALL_5_3);
            } catch (RuntimeException ex) {
                return null;
            }
            llW = outW; llH = outH;
        }
        return ll;
    }

    private static int[] padTo(int[] src, int n) {
        if (src != null && src.length >= n) return src;
        int[] out = new int[n];
        if (src != null) System.arraycopy(src, 0, out, 0, src.length);
        return out;
    }

    // ============================================================ helpers — scoring

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

    private static int[] boxDownscale(int[] src, int srcW, int srcH, int dstW, int dstH) {
        int[] out = new int[dstW * dstH];
        int sx = Math.max(1, srcW / dstW);
        int sy = Math.max(1, srcH / dstH);
        for (int y = 0; y < dstH; y++) {
            for (int x = 0; x < dstW; x++) {
                long sum = 0; int cnt = 0;
                for (int yy = 0; yy < sy; yy++) {
                    int sy0 = y * sy + yy;
                    if (sy0 >= srcH) break;
                    for (int xx = 0; xx < sx; xx++) {
                        int sx0 = x * sx + xx;
                        if (sx0 >= srcW) break;
                        sum += src[sy0 * srcW + sx0];
                        cnt++;
                    }
                }
                out[y * dstW + x] = (cnt == 0) ? 0 : (int) (sum / cnt);
            }
        }
        return out;
    }

    // ============================================================ helpers — label parsing

    private static BitOrder parseBitOrder(String label) {
        return label.contains("bit=LSB_FIRST") ? BitOrder.LSB_FIRST : BitOrder.MSB_FIRST;
    }
    private static LlMode parseLlMode(String label) {
        for (LlMode m : LlMode.values()) if (label.contains("ll=" + m)) return m;
        return LlMode.LL_SIGNED;
    }
    private static int parseKInit(String label) {
        int i = label.indexOf(" k=");
        if (i < 0) i = label.indexOf("k=");
        if (i < 0) return 0;
        int j = i + (label.charAt(i) == ' ' ? 3 : 2);
        int e = j;
        while (e < label.length() && Character.isDigit(label.charAt(e))) e++;
        try { return Integer.parseInt(label.substring(j, e)); } catch (Exception ex) { return 0; }
    }
    private static Dequant parseDequant(String label) {
        for (Dequant d : Dequant.values()) if (label.contains("dq=" + d)) return d;
        return Dequant.D_IDENTITY;
    }
    private static Predictor parsePredictor(String label) {
        for (Predictor p : Predictor.values()) if (label.contains("pred=" + p)) return p;
        return Predictor.P_NONE;
    }

    // ============================================================ helpers — CR3 plumbing

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
}
