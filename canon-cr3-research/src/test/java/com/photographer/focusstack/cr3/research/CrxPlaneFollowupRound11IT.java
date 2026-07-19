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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round‑11 mega‑sweep — packs a large number of decoder hypotheses into a
 * single IT to amortise the cost of CR3 parsing / preview extraction /
 * subband decoding. Per Update (o) §1‑3 (SubbandFixtures.md), this is the
 * first round that exercises:
 *
 * <ol>
 *   <li>An <b>inverse horizontal predictor</b> per subband (Canon C‑RAW is
 *       documented to residualise coefficients horizontally before entropy
 *       coding — the most plausible explanation for the Round‑10 noise floor).
 *   </li>
 *   <li>All <b>6 role permutations of {HL, LH, HH}</b> applied uniformly
 *       across all decomposition levels — the patent fixes the lowpass slot
 *       (LL) but is silent on the canonical order of the three highpass
 *       slots in the Canon bitstream.</li>
 *   <li><b>Cross‑plane subband ordering verification</b>: the
 *       {@code payload_bytes} column produced by Round‑10 is replayed here
 *       and asserted to satisfy {@code LL ≥ HL ≈ LH ≥ HH} per level (a
 *       direct entropy‑theoretic prediction for natural images).</li>
 * </ol>
 *
 * <h3>Sweep layout (two‑stage to stay under ~2 minutes wall‑clock)</h3>
 *
 * <p><b>Stage A — LL_3 only (no IDWT).</b> For each (tile, plane), score
 * the deepest lowpass band against a 1/2^L downscaled preview channel.
 * This isolates entropy/dequant/LL‑sign correctness from the IDWT and
 * predictor, and runs in ~milliseconds per variant.</p>
 *
 * <pre>
 *   LL mode  ∈ {SIGNED, UNSIGNED, UNSIGNED+MID}    (3)
 *   K_INIT   ∈ {0, 1, 2, 5}                         (4)
 *   Dequant  ∈ {IDENTITY, MUL_Q, MUL_Q_HALF}        (3)
 *   Predictor∈ {NONE, HORIZ_DELTA, HORIZ_ROW_RESET} (3)
 *   ───────────────────────────────────────────────
 *   = 108 variants per (tile, plane)  → ~864 evals total
 * </pre>
 *
 * <p><b>Stage B — full IDWT.</b> Pin the Stage‑A winner on LL_3 per plane,
 * then sweep entropy/predictor/role permutation on the detail bands and
 * run all 3 IDWT levels.</p>
 *
 * <pre>
 *   Detail K_INIT ∈ {0, 1, 2}                       (3)
 *   Detail Pred   ∈ {NONE, HORIZ_DELTA, ROW_RESET}  (3)
 *   Detail Sign   ∈ {LSB, LSB_NEG}                  (2)
 *   Detail Dequant∈ {MUL_Q, MUL_Q_HALF}             (2)
 *   Role perm     ∈ all 6 of {HL, LH, HH}           (6)
 *   ───────────────────────────────────────────────
 *   = 216 variants per (tile, plane) → ~1728 IDWTs
 * </pre>
 *
 * <p>Strict test scope: no production code is modified, no flag is added,
 * no hypothesis is promoted. Output dir
 * {@code canon-cr3-imageio/target/crx-followup-round11/}. Always passes —
 * produces {@code metrics.tsv}, {@code winners.txt},
 * {@code subband_order.txt}.</p>
 */
class CrxPlaneFollowupRound11IT {

    private static final String FIXTURE_DIR = "src/test/resources/samples/research";
    private static final String CRAW_FIXTURE =
            "g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-02.cr3";
    private static final Path OUT_DIR = Path.of("target", "crx-followup-round11");

    private static final int MAX_UNARY = 64;

    private enum LlMode { LL_SIGNED, LL_UNSIGNED, LL_UNSIGNED_MID }
    private enum Dequant { D_IDENTITY, D_MUL_Q, D_MUL_Q_HALF }
    private enum Predictor { P_NONE, P_HORIZ_DELTA, P_HORIZ_ROW_RESET }
    private enum SignConv { S_LSB, S_LSB_NEG }

    /** All 6 permutations of the three detail subband roles {HL, LH, HH}. */
    private static final int[][] ROLE_PERMS = {
            {0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}
    };
    private static final String[] ROLE_NAMES = { "HL", "LH", "HH" };

    private static final int[] STAGE_A_K_INITS = { 0, 1, 2, 5 };
    private static final int[] STAGE_B_K_INITS = { 0, 1, 2 };

    @Test
    void runRound11Sweep() throws Exception {
        Files.createDirectories(OUT_DIR);
        File f = new File(FIXTURE_DIR + "/" + CRAW_FIXTURE);
        if (!f.exists()) { writeSkip("fixture-not-present: " + f); return; }

        Cmp1Box cmp1 = readCmp1(f);
        if (cmp1 == null) { writeSkip("no-CMP1"); return; }
        int waveletLevels = cmp1.waveletLevels();
        int[] cfaToPreview = cfaLayoutMap(cmp1.cfaLayout());

        List<SubbandExtractor.SubbandRef> subs = SubbandExtractor.list(f);
        if (subs.isEmpty()) { writeSkip("no-subbands"); return; }

        Map<String, List<SubbandExtractor.SubbandRef>> groups = new LinkedHashMap<>();
        for (SubbandExtractor.SubbandRef s : subs) {
            String key = "tile" + s.tileSequence() + "/p" + s.planeIndex();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }

        SubbandExtractor.SubbandRef any = subs.get(0);
        int planeW = any.planeWidth();
        int planeH = any.planeHeight();
        int bd = any.bitDepth();

        long t0 = System.currentTimeMillis();
        System.out.printf("%n=== Round‑11 mega‑sweep on %s ===%n", CRAW_FIXTURE);
        System.out.printf("    plane %d×%d, bd=%d, waveletLevels=%d, cfaLayout=%s, %d subbands%n",
                planeW, planeH, bd, waveletLevels, cmp1.cfaLayoutName(), subs.size());

        // ---------------- Subband ordering verification (Update (o) §3) ------
        verifySubbandOrdering(groups, waveletLevels);

        // ---------------- Reference grids ---------------------------------
        PreviewReferenceExtractor.Reference[] refsFull = new PreviewReferenceExtractor.Reference[4];
        for (int c = 0; c < 4; c++) {
            refsFull[c] = PreviewReferenceExtractor.extract(f, c, planeW, planeH);
        }
        // Down‑scaled refs at the LL_L resolution (1/2^L) for Stage A.
        int llW = Math.max(1, planeW >> waveletLevels);
        int llH = Math.max(1, planeH >> waveletLevels);
        int[][] refsLl = new int[4][];
        for (int c = 0; c < 4; c++) {
            refsLl[c] = boxDownscale(refsFull[c].pixels(), planeW, planeH, llW, llH);
        }

        try (PrintWriter tsv = new PrintWriter(OUT_DIR.resolve("metrics.tsv").toFile());
             PrintWriter win = new PrintWriter(OUT_DIR.resolve("winners.txt").toFile())) {
            tsv.println("section\ttile_plane\tvariant\tmetric\tvalue");
            win.println("Round‑11 mega‑sweep — winners per (tile, plane)");
            win.println("=================================================");

            int[][] dimsTpl = subbandDims(planeW, planeH, waveletLevels);
            double globalMaxAbs = 0;
            String globalMaxLabel = "(none)";

            for (var e : groups.entrySet()) {
                String tag = e.getKey();
                List<SubbandExtractor.SubbandRef> sbs = e.getValue();
                if (sbs.size() < dimsTpl.length) {
                    win.printf("%s: skipped (only %d/%d subbands)%n", tag, sbs.size(), dimsTpl.length);
                    continue;
                }
                int previewChan = cfaToPreview[sbs.get(0).planeIndex()];

                // -------- Stage A: LL_3 sweep ---------------------------------
                StageAResult bestA = sweepStageA(f, sbs.get(0), dimsTpl[0], bd,
                        refsLl[previewChan], tsv, tag);

                // -------- Stage B: full IDWT sweep, pin LL config from Stage A ----
                StageBResult bestB = sweepStageB(f, sbs, dimsTpl, bd, waveletLevels,
                        refsFull[previewChan].pixels(), planeW, planeH,
                        bestA, tsv, tag);

                win.printf("%n--- %s (preview chan %s) ---%n",
                        tag, refsFull[previewChan].channel());
                win.printf("  Stage A best LL_3 |r|=%.4f  ll=%s k=%d dq=%s pred=%s%n",
                        Math.abs(bestA.r), bestA.llMode, bestA.kInit, bestA.dequant, bestA.pred);
                win.printf("  Stage B best full |r|=%.4f  k=%d pred=%s sign=%s dq=%s perm=[%s,%s,%s]%n",
                        Math.abs(bestB.r), bestB.kInit, bestB.pred, bestB.sign,
                        bestB.dequant,
                        ROLE_NAMES[bestB.perm[0]], ROLE_NAMES[bestB.perm[1]], ROLE_NAMES[bestB.perm[2]]);

                if (Math.abs(bestB.r) > globalMaxAbs) {
                    globalMaxAbs = Math.abs(bestB.r);
                    globalMaxLabel = tag + " " + bestB.label();
                }
            }

            long dtMs = System.currentTimeMillis() - t0;
            win.printf("%nGLOBAL MAX |r| = %.4f at %s%n", globalMaxAbs, globalMaxLabel);
            win.printf("Sweep wall‑clock: %d s%n", dtMs / 1000);
            if (globalMaxAbs >= 0.7) {
                win.println("INTERPRETATION: max|r| ≥ 0.7 — promotion threshold cleared.");
                win.println("  Investigate the winning configuration in detail before any");
                win.println("  production change. Cross‑validate on the second detail fixture.");
            } else {
                win.println("INTERPRETATION: max|r| < 0.7 — none of the 1296 hypotheses cleared the");
                win.println("  threshold. Likely missing pieces still on the table:");
                win.println("    • Per‑subband K_INIT seeded from CMP1.kvalues (not constant)");
                win.println("    • Per‑pixel context model (not row‑reset horizontal delta)");
                win.println("    • Lifting sign convention in InverseWaveletTransform itself");
                win.println("    • MDAT subband interleaving differs from the assumed");
                win.println("      (LL, HL3, LH3, HH3, HL2, LH2, HH2, HL1, LH1, HH1) order");
                win.println("    • Lossy path uses a different entropy stage than lossless");
                win.println("  Recommendation: pivot to LibRaw‑backed JNI ground truth (see");
                win.println("  copilot conversation 2026‑05‑11) and use these IT fixtures as");
                win.println("  pixel‑exact regression tests against the LibRaw output.");
            }
        }

        assertThat(OUT_DIR.resolve("metrics.tsv")).exists();
        assertThat(OUT_DIR.resolve("winners.txt")).exists();
        assertThat(OUT_DIR.resolve("subband_order.txt")).exists();
    }

    // ============================================================ Stage A

    private record StageAResult(double r, LlMode llMode, int kInit, Dequant dequant, Predictor pred) {}

    private StageAResult sweepStageA(File f, SubbandExtractor.SubbandRef llRef, int[] llDims,
                                     int bd, int[] refLl, PrintWriter tsv, String tag) throws IOException {
        byte[] payload = SubbandExtractor.read(f, llRef);
        int n = llDims[0] * llDims[1];
        int q = llRef.quantValue();

        StageAResult best = new StageAResult(0, LlMode.LL_SIGNED, 0, Dequant.D_IDENTITY, Predictor.P_NONE);
        double bestAbs = -1;
        AtomicInteger evalCount = new AtomicInteger();

        for (LlMode llm : LlMode.values()) {
            boolean signed = (llm == LlMode.LL_SIGNED);
            for (int kInit : STAGE_A_K_INITS) {
                int[] base = decodeRiceSubband(payload, n, bd, signed, kInit);
                if (llm == LlMode.LL_UNSIGNED_MID && !signed) {
                    int mid = 1 << (bd - 1);
                    for (int i = 0; i < base.length; i++) base[i] -= mid;
                }
                for (Dequant dq : Dequant.values()) {
                    int[] dq1 = applyDequant(base, dq, q);
                    for (Predictor p : Predictor.values()) {
                        int[] r1 = applyHorizPredictor(dq1, llDims[0], llDims[1], p);
                        double r = pearson(r1, refLl);
                        evalCount.incrementAndGet();
                        if (Math.abs(r) > bestAbs) {
                            bestAbs = Math.abs(r);
                            best = new StageAResult(r, llm, kInit, dq, p);
                        }
                    }
                }
            }
        }
        tsv.printf("R11/A\t%s\tll=%s/k=%d/dq=%s/pred=%s\tbest_LL_r\t%.6f%n",
                tag, best.llMode, best.kInit, best.dequant, best.pred, best.r);
        tsv.printf("R11/A\t%s\t-\tevaluations\t%d%n", tag, evalCount.get());
        return best;
    }

    // ============================================================ Stage B

    private record StageBResult(double r, int kInit, Predictor pred, SignConv sign,
                                Dequant dequant, int[] perm) {
        String label() {
            return "k=" + kInit + " pred=" + pred + " sign=" + sign + " dq=" + dequant
                    + " perm=[" + ROLE_NAMES[perm[0]] + "," + ROLE_NAMES[perm[1]]
                    + "," + ROLE_NAMES[perm[2]] + "]";
        }
    }

    private StageBResult sweepStageB(File f, List<SubbandExtractor.SubbandRef> sbs, int[][] dims,
                                     int bd, int levels, int[] refFull, int planeW, int planeH,
                                     StageAResult bestA, PrintWriter tsv, String tag) throws IOException {
        // Pre‑decode LL once with Stage‑A winner.
        byte[] llPayload = SubbandExtractor.read(f, sbs.get(0));
        int llN = dims[0][0] * dims[0][1];
        boolean llSigned = (bestA.llMode == LlMode.LL_SIGNED);
        int[] llCoefBase = decodeRiceSubband(llPayload, llN, bd, llSigned, bestA.kInit);
        if (bestA.llMode == LlMode.LL_UNSIGNED_MID) {
            int mid = 1 << (bd - 1);
            for (int i = 0; i < llCoefBase.length; i++) llCoefBase[i] -= mid;
        }
        int[] llCoef = applyDequant(llCoefBase, bestA.dequant, sbs.get(0).quantValue());
        llCoef = applyHorizPredictor(llCoef, dims[0][0], dims[0][1], bestA.pred);

        // Pre‑load detail payloads (3*levels of them).
        int detailCount = 3 * levels;
        byte[][] detailPayloads = new byte[detailCount][];
        int[][] detailDims = new int[detailCount][];
        int[] detailQ = new int[detailCount];
        for (int i = 0; i < detailCount; i++) {
            SubbandExtractor.SubbandRef s = sbs.get(1 + i);
            detailPayloads[i] = SubbandExtractor.read(f, s);
            detailDims[i] = dims[1 + i];
            detailQ[i] = s.quantValue();
        }

        StageBResult best = new StageBResult(0, 0, Predictor.P_NONE, SignConv.S_LSB,
                Dequant.D_MUL_Q, ROLE_PERMS[0]);
        double bestAbs = -1;
        AtomicInteger evalCount = new AtomicInteger();

        for (int kInit : STAGE_B_K_INITS) {
            // Decode all detail subbands once per (kInit, sign) combo.
            for (SignConv sc : SignConv.values()) {
                boolean signed = true; // detail bands always signed; sign convention applies post‑map
                int[][] decoded = new int[detailCount][];
                for (int i = 0; i < detailCount; i++) {
                    int n = detailDims[i][0] * detailDims[i][1];
                    int[] d = decodeRiceSubband(detailPayloads[i], n, bd, signed, kInit);
                    if (sc == SignConv.S_LSB_NEG) {
                        for (int j = 0; j < d.length; j++) d[j] = -d[j];
                    }
                    decoded[i] = d;
                }
                for (Dequant dq : new Dequant[]{ Dequant.D_MUL_Q, Dequant.D_MUL_Q_HALF }) {
                    int[][] deq = new int[detailCount][];
                    for (int i = 0; i < detailCount; i++) {
                        deq[i] = applyDequant(decoded[i], dq, detailQ[i]);
                    }
                    for (Predictor pred : Predictor.values()) {
                        int[][] predicted = new int[detailCount][];
                        for (int i = 0; i < detailCount; i++) {
                            predicted[i] = applyHorizPredictor(deq[i],
                                    detailDims[i][0], detailDims[i][1], pred);
                        }
                        for (int[] perm : ROLE_PERMS) {
                            // Build a (dims, coefs) array for idwtMultiLevel: position 0 = LL,
                            // then (HL, LH, HH) for level L..1. Apply permutation per level.
                            int[][] coefs = new int[1 + detailCount][];
                            int[][] cdims = new int[1 + detailCount][];
                            coefs[0] = llCoef;
                            cdims[0] = dims[0];
                            for (int lev = 0; lev < levels; lev++) {
                                int coefBase = 1 + 3 * lev;     // LL occupies coefs[0]
                                int detailBase = 3 * lev;        // predicted[]/detailDims[] are 0‑based, no LL slot
                                // perm tells us which logical role (HL=0, LH=1, HH=2) is supplied
                                // at slot 0, 1, 2 of the bitstream order. To rebuild the canonical
                                // (HL, LH, HH) feed we need the inverse permutation. invPerm()
                                // returns 1‑based slot indices, so subtract 1 to index into the
                                // 0‑based detail arrays.
                                int[] inv = invPerm(perm);
                                for (int role = 0; role < 3; role++) {
                                    int detailIdx = detailBase + inv[role] - 1;
                                    coefs[coefBase + role] = predicted[detailIdx];
                                    cdims[coefBase + role] = detailDims[detailIdx];
                                }
                            }
                            int[] recon = idwtMultiLevel(coefs, cdims, levels);
                            evalCount.incrementAndGet();
                            if (recon == null) continue;
                            int reconW = planeW;
                            int reconH = recon.length / Math.max(1, reconW);
                            int overlapH = Math.min(reconH, planeH);
                            int n = reconW * overlapH;
                            int[] candTrim = (recon.length == n) ? recon : Arrays.copyOf(recon, n);
                            int[] refTrim = Arrays.copyOf(refFull, n);
                            double r = pearson(candTrim, refTrim);
                            if (Math.abs(r) > bestAbs) {
                                bestAbs = Math.abs(r);
                                best = new StageBResult(r, kInit, pred, sc, dq, perm);
                            }
                        }
                    }
                }
            }
        }
        tsv.printf("R11/B\t%s\t%s\tbest_full_r\t%.6f%n", tag, best.label(), best.r);
        tsv.printf("R11/B\t%s\t-\tevaluations\t%d%n", tag, evalCount.get());
        return best;
    }

    /**
     * Inverts a permutation expressed as {@code perm[bitstreamSlot] = role}
     * into {@code inv[role] = bitstreamSlot}. The arrays returned here use
     * 1‑based slot indices (offsets within the 3‑slot detail group).
     */
    private static int[] invPerm(int[] perm) {
        int[] inv = new int[3];
        for (int slot = 0; slot < 3; slot++) inv[perm[slot]] = slot + 1;
        return inv;
    }

    // ============================================================ predictor

    /**
     * Inverse of a horizontal residualisation. Given a row of residuals
     * {@code r[0..w-1]}, the recovered values are
     * {@code v[0] = r[0]; v[x] = r[x] + v[x-1]} (HORIZ_DELTA), or
     * the same but with {@code v[0] = r[0]} reset at every row
     * (HORIZ_ROW_RESET — identical here since we always reset at row start).
     * P_NONE leaves the input unchanged.
     */
    private static int[] applyHorizPredictor(int[] coef, int w, int h, Predictor p) {
        if (p == Predictor.P_NONE || coef == null || coef.length == 0 || w <= 1) return coef;
        int[] out = new int[coef.length];
        int rows = Math.min(h, coef.length / Math.max(1, w));
        for (int y = 0; y < rows; y++) {
            int rowStart = y * w;
            int acc = 0;
            for (int x = 0; x < w && rowStart + x < coef.length; x++) {
                if (p == Predictor.P_HORIZ_ROW_RESET && x == 0) acc = 0;
                acc += coef[rowStart + x];
                out[rowStart + x] = acc;
            }
        }
        // copy any tail past row*w boundary unchanged
        for (int i = rows * w; i < coef.length; i++) out[i] = coef[i];
        return out;
    }

    // ============================================================ subband geometry

    private static int[][] subbandDims(int planeW, int planeH, int levels) {
        int n = (levels <= 0) ? 1 : 1 + 3 * levels;
        int[][] dims = new int[n][2];
        dims[0][0] = Math.max(1, planeW >> levels);
        dims[0][1] = Math.max(1, planeH >> levels);
        for (int lev = levels; lev >= 1; lev--) {
            int w = Math.max(1, planeW >> lev);
            int h = Math.max(1, planeH >> lev);
            int base = 1 + 3 * (levels - lev);
            dims[base    ][0] = w; dims[base    ][1] = h;
            dims[base + 1][0] = w; dims[base + 1][1] = h;
            dims[base + 2][0] = w; dims[base + 2][1] = h;
        }
        return dims;
    }

    // ============================================================ multi‑level IDWT

    private static int[] idwtMultiLevel(int[][] coefs, int[][] dims, int levels) {
        if (levels <= 0) return coefs[0];
        int[] ll = coefs[0];
        int llW = dims[0][0];
        int llH = dims[0][1];
        for (int lev = levels; lev >= 1; lev--) {
            int base = 1 + 3 * (levels - lev);
            int[] hl = padTo(coefs[base    ], llW * llH);
            int[] lh = padTo(coefs[base + 1], llW * llH);
            int[] hh = padTo(coefs[base + 2], llW * llH);
            ll = padTo(ll, llW * llH);
            int outW = llW * 2;
            int outH = llH * 2;
            try {
                ll = InverseWaveletTransform.inverseSynthesis2D(
                        ll, lh, hl, hh, llW, llH, outW, outH,
                        InverseWaveletTransform.FilterType.LEGALL_5_3);
            } catch (RuntimeException ex) {
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
            default -> System.arraycopy(coef, 0, out, 0, coef.length);
        }
        return out;
    }

    // ============================================================ entropy decode

    private static int[] decodeRiceSubband(byte[] payload, int n, int bd, boolean signed, int kInit) {
        if (payload == null || payload.length == 0 || n <= 0) return new int[0];
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
                out[produced++] = signed ? mapSignLsb(s) : s;
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

    private static int[] boxDownscale(int[] src, int srcW, int srcH, int dstW, int dstH) {
        int[] out = new int[dstW * dstH];
        int sx = srcW / dstW, sy = srcH / dstH;
        if (sx <= 0) sx = 1;
        if (sy <= 0) sy = 1;
        for (int y = 0; y < dstH; y++) {
            for (int x = 0; x < dstW; x++) {
                long sum = 0;
                int cnt = 0;
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

    // ============================================================ subband ordering

    /**
     * Update (o) §3 — compares per‑subband payload sizes to the entropy‑theoretic
     * expectation {@code LL ≥ HL ≈ LH ≥ HH} per level. Writes a per‑plane
     * compliance report; never fails the test.
     */
    private static void verifySubbandOrdering(Map<String, List<SubbandExtractor.SubbandRef>> groups,
                                              int levels) throws IOException {
        try (PrintWriter pw = new PrintWriter(OUT_DIR.resolve("subband_order.txt").toFile())) {
            pw.println("Round‑11 §3 — subband ordering verification via payload_bytes");
            pw.println("================================================================");
            pw.println("Hypothesis: bitstream slot order is (LL, HL3,LH3,HH3, HL2,LH2,HH2, HL1,LH1,HH1)");
            pw.println("Prediction: per level, byte counts satisfy HL ≈ LH ≥ HH; LL is the largest overall.");
            pw.println();
            int compliantPlanes = 0;
            int totalPlanes = 0;
            for (var e : groups.entrySet()) {
                List<SubbandExtractor.SubbandRef> sbs = e.getValue();
                pw.printf("--- %s ---%n", e.getKey());
                long[] sizes = sbs.stream().mapToLong(SubbandExtractor.SubbandRef::dataSize).toArray();
                pw.printf("  sizes: %s%n", Arrays.toString(sizes));
                if (sizes.length != 1 + 3 * levels) {
                    pw.println("  (skipped, unexpected subband count)");
                    continue;
                }
                totalPlanes++;
                boolean ok = true;
                if (sizes[0] < Arrays.stream(sizes).skip(1).max().orElse(0)) {
                    pw.println("  WARN: LL is not the largest payload");
                    ok = false;
                }
                for (int lev = 0; lev < levels; lev++) {
                    int b = 1 + 3 * lev;
                    long hl = sizes[b], lh = sizes[b + 1], hh = sizes[b + 2];
                    String lvlName = "L" + (levels - lev);
                    pw.printf("  %s: HL=%d LH=%d HH=%d  → HH≤max(HL,LH)=%s%n",
                            lvlName, hl, lh, hh, (hh <= Math.max(hl, lh)));
                    if (hh > Math.max(hl, lh)) ok = false;
                }
                if (ok) {
                    compliantPlanes++;
                    pw.println("  OK: ordering hypothesis consistent with payload sizes.");
                } else {
                    pw.println("  MISMATCH: ordering hypothesis NOT consistent. Try permuting roles");
                    pw.println("           and/or the LL slot, or expect a different bitstream layout.");
                }
                pw.println();
            }
            pw.printf("Summary: %d / %d (tile, plane) groups compliant.%n", compliantPlanes, totalPlanes);
        }
    }

    // ============================================================ skip / helpers

    private static void writeSkip(String reason) throws IOException {
        try (PrintWriter pw = new PrintWriter(OUT_DIR.resolve("metrics.tsv").toFile())) {
            pw.println("section\ttile_plane\tvariant\tmetric\tvalue");
            pw.printf("R11\t-\tskipped\tnote\t0%n");
        }
        try (PrintWriter pw = new PrintWriter(OUT_DIR.resolve("winners.txt").toFile())) {
            pw.println("Round‑11: skipped (" + reason + ")");
        }
        try (PrintWriter pw = new PrintWriter(OUT_DIR.resolve("subband_order.txt").toFile())) {
            pw.println("Round‑11: skipped (" + reason + ")");
        }
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

    private static Cmp1Box readCmp1(File cr3) throws Exception {
        try (ImageInputStream in = ImageIO.createImageInputStream(cr3)) {
            Cr3Container c = Cr3Parser.parse(in);
            for (Cr3Track t : c.tracks()) {
                if (t.cmp1() != null) return t.cmp1();
            }
        }
        return null;
    }

    @SuppressWarnings("unused")
    private static Comparator<SubbandExtractor.SubbandRef> bySubbandIndex() {
        return Comparator.comparingInt(SubbandExtractor.SubbandRef::subbandIndex);
    }
}
