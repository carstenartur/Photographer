package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.raw.codec.BitReader;
import com.photographer.focusstack.cr3.raw.codec.InverseWaveletTransform;
import com.photographer.focusstack.cr3.raw.codec.SubbandExtractor;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round‑14 — JPEG‑LS‑style run‑mode hypothesis (test scope only).
 *
 * <p>Update (p) of {@code SubbandFixtures.md} narrowed the open hypothesis
 * space to a single mechanism that no prior Round IT has exercised: a
 * <b>run mode</b> escape inside the entropy stage. Rounds 11–13 swept
 * predictor, sign convention, K‑init, K‑update, dequant, role permutation
 * and per‑band negation exhaustively; the per‑channel max |r| plateaued at
 * <b>0.66 on C‑RAW</b> and <b>0.56 on lossless</b>. The structural
 * signature of the plateau (per‑plane spread, K‑bracket "over‑climbing"
 * in flat regions) is consistent with a missing per‑pixel run‑mode
 * escape — the mechanism JPEG‑LS uses to close exactly the same
 * efficiency gap on natural images.</p>
 *
 * <h3>Hypothesis under test</h3>
 *
 * <p>At every pixel boundary, when the local context satisfies a "flat"
 * predicate (one of {@code a==b}, {@code a==b==c}, {@code |a−b|≤1}), the
 * decoder reads a unary‑coded zero‑run length from a separate K‑bracket
 * accumulator {@code K_run} and emits that many copies of the previous
 * left‑neighbour {@code a} before resuming per‑pixel residual decoding.</p>
 *
 * <h3>Sweep design (kept small to amortise CR3 parsing)</h3>
 *
 * <p>The Round‑13 winner is pinned for every other axis. Only three
 * variables are swept:</p>
 *
 * <pre>
 *   entry condition  ∈ { EQ_AB, EQ_ABC, NEAR_AB }       (3)
 *   K_run init       ∈ { 0, 1, 2 }                       (3)
 *   scope            ∈ { LL_ONLY, DETAIL_ONLY, ALL }     (3)
 *   ─────────────────────────────────────────────────
 *   = 27 variants × 8 (tile, plane) × 5 fixtures
 * </pre>
 *
 * <p>Plus a no‑run baseline ({@code OFF}) per (tile, plane) to confirm
 * the harness reproduces the Round‑13 score before any new mechanism is
 * in scope.</p>
 *
 * <h3>Strict test scope</h3>
 *
 * <p>No production code is modified. No flag is added. No hypothesis is
 * promoted. Output dir {@code canon-cr3-imageio/target/crx-followup-round14/}.
 * Always passes — produces:
 * <ul>
 *   <li>{@code metrics.tsv}</li>
 *   <li>{@code winners.txt} — best per (fixture, tile, plane)</li>
 *   <li>{@code top10.txt}</li>
 *   <li>{@code summary.txt} — outcome summary suitable for pasting into
 *       {@code SubbandFixtures.md} as Update (q).</li>
 *   <li>{@code progress.log} — autoflushing heartbeat trail.</li>
 * </ul>
 *
 * <h3>Promotion criterion</h3>
 *
 * <p>If any (entry × K_run × scope) variant lifts the per‑channel max
 * |r| above <b>0.70</b> on at least one fixture's dominant plane, the
 * run‑mode mechanism is structurally justified and the next round is
 * production‑pipeline integration in {@code CrxLosslessRawDecoder}. If
 * not, the next axis to try (in order) is context‑modelled K (separate K
 * accumulator per {@code |a−b|} quantiser bin) and a fixed‑width
 * remainder escape (H3 proper).</p>
 */
class CrxPlaneFollowupRound14IT {

    private static final String FIXTURE_DIR = "src/test/resources/samples/research";
    private static final Path OUT_DIR = Path.of("target", "crx-followup-round14");

    private static final String FX_ASIS   = "g5xm2-asis-iso200-ss130-ff2p5-01.cr3";
    private static final String FX_DARK   = "g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02.cr3";
    private static final String FX_FLAT   = "g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02.cr3";
    private static final String FX_DET_02 = "g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-02.cr3";
    private static final String FX_DET_03 = "g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-03.cr3";

    /** Hard cap on unary length so a malformed bitstream cannot wedge the harness. */
    private static final int MAX_UNARY = 64;
    /** Hard cap on run length per single run‑mode entry. */
    private static final int MAX_RUN_LEN = 1 << 20;

    /** Run‑mode entry predicate. */
    private enum Entry { ENTRY_EQ_AB, ENTRY_EQ_ABC, ENTRY_NEAR_AB, ENTRY_OFF }

    /** Subbands the run‑mode is applied to. */
    private enum Scope { ALL, LL_ONLY, DETAIL_ONLY }

    private static final int[] K_RUN_INITS = { 0, 1, 2 };

    private record Hit(double r, String fixture, String tilePlane, String label) {
        double absR() { return Math.abs(r); }
    }

    /** Pinned Round‑13 winner config, applied to every Round‑14 variant. */
    private record Pinned(int kInit, int llBd) { }

    @Test
    @Disabled("Long-running deep sweep (~10–15 min wall clock, "
            + "~1100 variants over 5 fixtures × 8 (tile,plane) × 27 + 1). "
            + "Strict test scope; no production code change. Opt in for a "
            + "fresh run with: "
            + "mvn -B verify -pl canon-cr3-imageio -am -Dit.test=CrxPlaneFollowupRound14IT "
            + "-Djava.awt.headless=true "
            + "-Djunit.jupiter.conditions.deactivate=org.junit.jupiter.api.condition.DisabledCondition")
    void runRound14RunModeSweep() throws Exception {
        Files.createDirectories(OUT_DIR);
        long t0 = System.currentTimeMillis();
        AtomicLong totalEvals = new AtomicLong();
        AtomicLong totalDecodes = new AtomicLong();
        List<Hit> globalTop = new ArrayList<>();

        PrintWriter tsv = null, win = null, progress = null;
        Heartbeat hb = null;
        try {
            progress = autoflushWriter(OUT_DIR.resolve("progress.log"));
            hb = new Heartbeat(progress);
            tsv = autoflushWriter(OUT_DIR.resolve("metrics.tsv"));
            win = autoflushWriter(OUT_DIR.resolve("winners.txt"));

            tsv.println("section\tfixture\ttile_plane\tvariant\tmetric\tvalue");
            win.println("Round‑14 — JPEG‑LS‑style run‑mode sweep — winners per (fixture, tile, plane)");
            win.println("==========================================================================");

            String[] all = { FX_ASIS, FX_DARK, FX_FLAT, FX_DET_02, FX_DET_03 };
            int idx = 0;
            for (String fx : all) {
                idx++;
                long fxT0 = System.currentTimeMillis();
                hb.fixture(idx, all.length, fx, "START");
                runOneFixture(fx, tsv, win, globalTop, totalEvals, totalDecodes, hb);
                hb.fixture(idx, all.length, fx,
                        String.format("END[%.1fs]", (System.currentTimeMillis() - fxT0) / 1000.0));
            }

            long dt = System.currentTimeMillis() - t0;
            win.printf("%n--- Global summary ---%n");
            win.printf("Total decode‑equivalents: %d%n", totalDecodes.get());
            win.printf("Total scored variants:    %d%n", totalEvals.get());
            win.printf("Wall‑clock: %.1f s%n", dt / 1000.0);
        } finally {
            try {
                writeFinalReports(globalTop, totalEvals.get(), totalDecodes.get(), t0);
            } catch (Exception ex) {
                System.err.println("[R14] WARN: writing final reports failed: " + ex);
            }
            if (hb != null) {
                double best = globalTop.stream().mapToDouble(Hit::absR).max().orElse(0);
                hb.summary(totalEvals.get(), totalDecodes.get(), best);
            }
            closeQuiet(tsv);
            closeQuiet(win);
            closeQuiet(progress);
        }

        assertThat(OUT_DIR.resolve("metrics.tsv")).exists();
        assertThat(OUT_DIR.resolve("winners.txt")).exists();
        assertThat(OUT_DIR.resolve("top10.txt")).exists();
        assertThat(OUT_DIR.resolve("summary.txt")).exists();
        assertThat(OUT_DIR.resolve("progress.log")).exists();
    }

    // ============================================================ per‑fixture

    private void runOneFixture(String fx, PrintWriter tsv, PrintWriter win,
                               List<Hit> globalTop, AtomicLong evals, AtomicLong decodes,
                               Heartbeat hb) throws Exception {
        File f = new File(FIXTURE_DIR + "/" + fx);
        if (!f.exists()) {
            win.printf("%n--- %s --- skipped (fixture not present)%n", fx);
            hb.event("  SKIP " + fx + " (missing)");
            return;
        }
        Cmp1Box cmp1 = readCmp1(f);
        if (cmp1 == null) {
            win.printf("%n--- %s --- skipped (no CMP1)%n", fx);
            hb.event("  SKIP " + fx + " (no CMP1)");
            return;
        }
        int waveletLevels = cmp1.waveletLevels();
        int[] cfaToPreview = cfaLayoutMap(cmp1.cfaLayout());
        List<SubbandExtractor.SubbandRef> subs = SubbandExtractor.list(f);
        if (subs.isEmpty()) {
            win.printf("%n--- %s --- skipped (no subbands)%n", fx);
            hb.event("  SKIP " + fx + " (no subbands)");
            return;
        }
        SubbandExtractor.SubbandRef any = subs.get(0);
        int planeW = any.planeWidth();
        int planeH = any.planeHeight();
        int bd = any.bitDepth();

        win.printf("%n=== %s ===%n", fx);
        win.printf("    plane %d×%d, bd=%d, L=%d, cfaLayout=%s, %d subbands%n",
                planeW, planeH, bd, waveletLevels, cmp1.cfaLayoutName(), subs.size());
        win.flush();

        Map<String, List<SubbandExtractor.SubbandRef>> groups = new LinkedHashMap<>();
        for (SubbandExtractor.SubbandRef s : subs) {
            String key = "tile" + s.tileSequence() + "/p" + s.planeIndex();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }
        PreviewReferenceExtractor.Reference[] refs = new PreviewReferenceExtractor.Reference[4];
        for (int c = 0; c < 4; c++) {
            refs[c] = PreviewReferenceExtractor.extract(f, c, planeW, planeH);
        }

        int planeIdx = 0;
        int planeTotal = groups.size();
        for (var e : groups.entrySet()) {
            planeIdx++;
            String tag = e.getKey();
            List<SubbandExtractor.SubbandRef> sbs = e.getValue();
            int previewChan = cfaToPreview[sbs.get(0).planeIndex()];
            int[] refFull = refs[previewChan].pixels();
            long planeT0 = System.currentTimeMillis();
            hb.plane(fx, tag, String.format("START (%d/%d)", planeIdx, planeTotal));
            sweepPlane(f, sbs, planeW, planeH, bd, waveletLevels, refFull,
                    fx, tag, tsv, win, globalTop, evals, decodes, hb);
            hb.plane(fx, tag,
                    String.format("END[%.1fs]", (System.currentTimeMillis() - planeT0) / 1000.0));
            tsv.flush();
            win.flush();
        }
    }

    // ============================================================ per‑plane sweep

    private void sweepPlane(File f, List<SubbandExtractor.SubbandRef> sbs,
                            int planeW, int planeH, int bd, int waveletLevels, int[] refFull,
                            String fx, String tag,
                            PrintWriter tsv, PrintWriter win,
                            List<Hit> globalTop, AtomicLong evals, AtomicLong decodes,
                            Heartbeat hb) {
        if (sbs.isEmpty()) return;

        int subbandCount = (waveletLevels <= 0) ? 1 : 1 + 3 * waveletLevels;
        if (sbs.size() < subbandCount) {
            win.printf("  %s: skipped (have %d/%d subbands)%n", tag, sbs.size(), subbandCount);
            return;
        }
        int[][] dims = subbandDims(planeW, planeH, waveletLevels);

        // ---- Read all payloads up front (one I/O sweep per plane) ----
        byte[][] payloads = new byte[subbandCount][];
        int[] qs = new int[subbandCount];
        try {
            for (int i = 0; i < subbandCount; i++) {
                payloads[i] = SubbandExtractor.read(f, sbs.get(i));
                qs[i] = sbs.get(i).quantValue();
            }
        } catch (IOException ioe) {
            win.printf("  %s: skipped (I/O error: %s)%n", tag, ioe.getMessage());
            return;
        }

        // Pinned Round‑13 config: K_INIT=0, sign=S_LSB, dq=MUL_Q, pred=P_MED, MSB‑first,
        // LL_UNSIGNED_MID, identity per‑level perm, neg=[1,1,1].
        Pinned pin = new Pinned(0, bd);

        // ---- Baseline (run‑mode OFF) -- reproduces the Round‑13 score for this plane ----
        Hit best = decodeAndScore(payloads, dims, qs, waveletLevels, refFull,
                pin, Entry.ENTRY_OFF, /*kRun*/ 0, Scope.ALL,
                fx, tag, "OFF", evals, decodes);
        tsv.printf("R14/baseline\t%s\t%s\t%s\tbest_r\t%.6f%n", fx, tag, "OFF", best.r);

        // ---- 27‑variant sweep ----
        int variantsTotal = (Entry.values().length - 1) * K_RUN_INITS.length * Scope.values().length;
        int variantsDone = 0;
        for (Entry entry : Entry.values()) {
            if (entry == Entry.ENTRY_OFF) continue;
            for (int kRun : K_RUN_INITS) {
                for (Scope sc : Scope.values()) {
                    String label = entry + " kRun=" + kRun + " scope=" + sc;
                    Hit h = decodeAndScore(payloads, dims, qs, waveletLevels, refFull,
                            pin, entry, kRun, sc, fx, tag, label, evals, decodes);
                    tsv.printf("R14/run\t%s\t%s\t%s\tr\t%.6f%n", fx, tag, label, h.r);
                    if (h.absR() > best.absR()) best = h;
                    variantsDone++;
                    hb.tick(tag, variantsDone, variantsTotal);
                }
            }
        }
        win.printf("  %s: |r|=%.4f  %s%n", tag, best.absR(), best.label);
        globalTop.add(best);
    }

    // ============================================================ decode + assemble + score

    private Hit decodeAndScore(byte[][] payloads, int[][] dims, int[] qs,
                               int waveletLevels, int[] refFull,
                               Pinned pin, Entry entry, int kRun, Scope scope,
                               String fx, String tag, String label,
                               AtomicLong evals, AtomicLong decodes) {
        int subbandCount = payloads.length;
        int[][] decoded = new int[subbandCount][];
        for (int i = 0; i < subbandCount; i++) {
            int n = dims[i][0] * dims[i][1];
            boolean isLl = (i == 0);
            boolean apply = switch (scope) {
                case ALL -> true;
                case LL_ONLY -> isLl;
                case DETAIL_ONLY -> !isLl;
            };
            Entry use = apply ? entry : Entry.ENTRY_OFF;
            decoded[i] = decodeRicePlaneWithRunMode(payloads[i], dims[i][0], dims[i][1], n,
                    pin.llBd(), pin.kInit(), use, kRun);
            decodes.incrementAndGet();
        }

        int[] llCoef;
        int llW, llH;
        if (waveletLevels <= 0) {
            // Lossless: the single "subband" IS the Bayer plane. Apply LL sign + predictor.
            llCoef = applyMedPredictor(applyLlMode(decoded[0], pin.llBd()),
                    dims[0][0], dims[0][1]);
            llW = dims[0][0];
            llH = dims[0][1];
            evals.incrementAndGet();
            double r = pearson(llCoef, refFull);
            return new Hit(r, fx, tag, label);
        }

        // C‑RAW: LL_UNSIGNED_MID + P_MED on LL; per‑subband sign‑LSB + MUL_Q + P_MED on details.
        llCoef = applyMedPredictor(applyLlMode(decoded[0], pin.llBd()),
                dims[0][0], dims[0][1]);
        llW = dims[0][0];
        llH = dims[0][1];
        int[][] detail = new int[3 * waveletLevels][];
        for (int i = 0; i < detail.length; i++) {
            int sb = 1 + i;
            int[] s = applySignLsb(decoded[sb]);
            s = applyDequantMulQ(s, qs[sb]);
            s = applyMedPredictor(s, dims[sb][0], dims[sb][1]);
            detail[i] = s;
        }

        // Identity per‑level perm; canonical role order = [HL, LH, HH].
        int[] cur = llCoef;
        int curW = llW, curH = llH;
        for (int lev = waveletLevels; lev >= 1; lev--) {
            int base = 3 * (waveletLevels - lev);
            int n = curW * curH;
            int[] hl = padTo(detail[base], n);
            int[] lh = padTo(detail[base + 1], n);
            int[] hh = padTo(detail[base + 2], n);
            cur = padTo(cur, n);
            int outW = curW * 2, outH = curH * 2;
            try {
                cur = InverseWaveletTransform.inverseSynthesis2D(
                        cur, lh, hl, hh, curW, curH, outW, outH,
                        InverseWaveletTransform.FilterType.LEGALL_5_3);
            } catch (RuntimeException ex) {
                return new Hit(0, fx, tag, label + " [IDWT-err]");
            }
            curW = outW;
            curH = outH;
        }
        evals.incrementAndGet();
        int n = Math.min(cur.length, refFull.length);
        int[] cand = (cur.length == n) ? cur : Arrays.copyOf(cur, n);
        int[] ref = (refFull.length == n) ? refFull : Arrays.copyOf(refFull, n);
        double r = pearson(cand, ref);
        return new Hit(r, fx, tag, label);
    }

    // ============================================================ entropy with run mode

    /**
     * Decodes one subband as a 2D grid {@code w × h}, applying the run‑mode
     * escape at every per‑pixel boundary whose context satisfies the entry
     * predicate. Returns the unsigned Rice symbols (sign mapping is applied
     * later by {@link #applySignLsb} for detail bands or
     * {@link #applyLlMode} for LL).
     *
     * <p>Run mode emits {@code r} copies of the previous left‑neighbour
     * {@code a} as <i>raw symbol value</i>. Sign mapping is order‑preserving
     * for symbol = 0, so emitting raw value {@code a} directly lets the
     * downstream sign mapper / predictor handle them uniformly. (For
     * non‑zero {@code a} the run is logically "copy the previous decoded
     * pixel value" which under {@code residual = 0} also yields the
     * predictor's prediction — but the simpler "raw symbol = a" semantics
     * is what this round tests.)</p>
     */
    private static int[] decodeRicePlaneWithRunMode(byte[] payload, int w, int h, int n,
                                                    int bd, int kInit, Entry entry, int kRunInit) {
        if (payload == null || payload.length == 0 || n <= 0) return new int[Math.max(0, n)];
        BitReader rd = new BitReader(payload);
        int[] out = new int[n];
        int k = Math.max(0, kInit);
        int kRun = Math.max(0, kRunInit);
        int produced = 0;
        try {
            outer:
            while (produced < n && rd.hasMore()) {
                int x = produced % w;
                int y = produced / w;

                // ---- Run‑mode entry test -----------------------------------------
                if (entry != Entry.ENTRY_OFF && x > 0 && y > 0 && produced + 1 < n) {
                    int a = out[produced - 1];
                    int b = out[produced - w];
                    int c = out[produced - w - 1];
                    boolean flat = switch (entry) {
                        case ENTRY_EQ_AB   -> a == b;
                        case ENTRY_EQ_ABC  -> a == b && b == c;
                        case ENTRY_NEAR_AB -> Math.abs(a - b) <= 1;
                        case ENTRY_OFF     -> false;
                    };
                    if (flat) {
                        // Read run length r with K_run bracket.
                        int qv = 0;
                        while (true) {
                            if (!rd.hasMore()) throw new IOException("eof in run unary");
                            if (rd.readBit() != 0) break;
                            qv++;
                            if (qv > MAX_UNARY) return Arrays.copyOf(out, produced);
                        }
                        int rem = 0;
                        for (int i = 0; i < kRun; i++) {
                            if (!rd.hasMore()) throw new IOException("eof in run rem");
                            rem = (rem << 1) | rd.readBit();
                        }
                        int runLen = (qv << kRun) | rem;
                        if (runLen <= 0) {
                            // Length 0 = empty run, fall through to per‑pixel decode.
                        } else {
                            if (runLen > MAX_RUN_LEN) runLen = MAX_RUN_LEN;
                            // Don't overrun the row boundary — confine the run to the
                            // current row, mirroring JPEG‑LS scope.
                            int rowRemaining = w - x;
                            int emit = Math.min(runLen, Math.min(rowRemaining, n - produced));
                            for (int i = 0; i < emit; i++) {
                                out[produced++] = a;
                            }
                            // Adapt K_run by the same bracket rule, on the run length.
                            int kStarRun = (runLen <= 0) ? 0
                                    : 31 - Integer.numberOfLeadingZeros(runLen + 1);
                            int kMin = Math.max(0, kStarRun - 1);
                            int kMax = Math.min(bd, kStarRun + 1);
                            if (kRun < kMin) kRun++;
                            else if (kRun > kMax) kRun--;
                            continue outer;
                        }
                    }
                }

                // ---- Per‑pixel residual decode (Round‑6 patent K‑bracket) -------
                int qv = 0;
                while (true) {
                    if (!rd.hasMore()) throw new IOException("eof in unary");
                    if (rd.readBit() != 0) break;
                    qv++;
                    if (qv > MAX_UNARY) return Arrays.copyOf(out, produced);
                }
                int rem = 0;
                for (int i = 0; i < k; i++) {
                    if (!rd.hasMore()) throw new IOException("eof in rem");
                    rem = (rem << 1) | rd.readBit();
                }
                int s = (qv << k) | rem;
                out[produced++] = s;
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

    // ============================================================ post‑decode pipeline (pinned)

    /** Sign‑LSB mapping (Round‑13 winner). */
    private static int[] applySignLsb(int[] coef) {
        int[] out = new int[coef.length];
        for (int i = 0; i < coef.length; i++) {
            int s = coef[i];
            out[i] = ((s & 1) == 0) ? (s >> 1) : -((s >> 1) + 1);
        }
        return out;
    }

    /** LL_UNSIGNED_MID mode (Round‑13 winner): raw unsigned symbols, biased toward 0. */
    private static int[] applyLlMode(int[] base, int bd) {
        int mid = 1 << (bd - 1);
        int[] out = new int[base.length];
        for (int i = 0; i < base.length; i++) out[i] = base[i] - mid;
        return out;
    }

    private static int[] applyDequantMulQ(int[] coef, int q) {
        if (coef == null) return new int[0];
        if (q <= 1) return coef;
        int[] out = new int[coef.length];
        for (int i = 0; i < coef.length; i++) out[i] = coef[i] * q;
        return out;
    }

    /** P_MED — JPEG‑LS / LOCO‑I MED predictor (Round‑13 dominant). */
    private static int[] applyMedPredictor(int[] coef, int w, int h) {
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

    // ============================================================ helpers

    private static int[] padTo(int[] src, int n) {
        if (src != null && src.length >= n) return src;
        int[] out = new int[n];
        if (src != null) System.arraycopy(src, 0, out, 0, src.length);
        return out;
    }

    private static int[][] subbandDims(int planeW, int planeH, int levels) {
        int n = (levels <= 0) ? 1 : 1 + 3 * levels;
        int[][] dims = new int[n][2];
        if (levels <= 0) {
            dims[0][0] = planeW;
            dims[0][1] = planeH;
            return dims;
        }
        dims[0][0] = Math.max(1, planeW >> levels);
        dims[0][1] = Math.max(1, planeH >> levels);
        for (int lev = levels; lev >= 1; lev--) {
            int w = Math.max(1, planeW >> lev), h = Math.max(1, planeH >> lev);
            int base = 1 + 3 * (levels - lev);
            dims[base][0] = w; dims[base][1] = h;
            dims[base + 1][0] = w; dims[base + 1][1] = h;
            dims[base + 2][0] = w; dims[base + 2][1] = h;
        }
        return dims;
    }

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

    // ============================================================ writers

    private static PrintWriter autoflushWriter(Path p) throws IOException {
        return new PrintWriter(new FileWriter(p.toFile()), true);
    }

    private static void closeQuiet(PrintWriter pw) {
        if (pw != null) try { pw.flush(); pw.close(); } catch (Exception ignored) { }
    }

    private void writeFinalReports(List<Hit> globalTop, long evals, long decodes, long t0)
            throws IOException {
        globalTop.sort((a, b) -> Double.compare(b.absR(), a.absR()));
        try (PrintWriter pw = autoflushWriter(OUT_DIR.resolve("top10.txt"))) {
            pw.println("Round‑14 — top 10 hypotheses by |Pearson r|");
            pw.println("==============================================");
            int n = Math.min(10, globalTop.size());
            for (int i = 0; i < n; i++) {
                Hit h = globalTop.get(i);
                pw.printf("#%-2d  |r|=%.4f  r=%+.4f  %s / %s%n     %s%n",
                        i + 1, h.absR(), h.r, h.fixture, h.tilePlane, h.label);
            }
            if (globalTop.isEmpty()) pw.println("(no hypotheses scored)");
        }

        try (PrintWriter pw = autoflushWriter(OUT_DIR.resolve("summary.txt"))) {
            double maxAbs = globalTop.isEmpty() ? 0 : globalTop.get(0).absR();
            pw.println("Round‑14 outcome summary — JPEG‑LS‑style run mode");
            pw.println("===================================================");
            pw.printf("Total variants evaluated: %d%n", evals);
            pw.printf("Decode‑equivalents:       %d%n", decodes);
            pw.printf("Wall‑clock:               %.1f s%n", (System.currentTimeMillis() - t0) / 1000.0);
            pw.printf("Best |Pearson r|:         %.4f%n", maxAbs);
            pw.printf("Round‑13 best (reference): 0.6573%n");
            pw.println();
            if (maxAbs >= 0.7) {
                pw.println("RESULT: ≥ 0.70 — promotion threshold cleared. The run‑mode escape");
                pw.println("        is structurally justified. Next round = production‑pipeline");
                pw.println("        integration in CrxLosslessRawDecoder.decodePlane.");
            } else if (maxAbs >= 0.6573 + 0.02) {
                pw.printf( "RESULT: improved over Round‑13 by Δ=+%.4f. Run‑mode helps but does%n",
                        maxAbs - 0.6573);
                pw.println("        not yet clear the promotion bar. Inspect top10.txt to find");
                pw.println("        the dominant (entry, K_run, scope) configuration; design");
                pw.println("        Round‑15 around context‑modelled K (separate K accumulator");
                pw.println("        per |a−b| quantiser bin, à la JPEG‑LS contexts Q1..Q5).");
            } else {
                pw.println("RESULT: no material improvement over Round‑13. Run‑mode alone is");
                pw.println("        not the missing mechanism. Recommended pivot:");
                pw.println("          1. context‑modelled K (per |a−b| quantiser bin)");
                pw.println("          2. fixed‑width remainder escape (H3 proper)");
                pw.println("          3. LibRaw‑backed JNI ground truth for pixel‑exact tests");
            }
        }
    }

    // ============================================================ heartbeat

    private static final class Heartbeat {
        private static final long TICK_PERIOD_MS = 30_000;
        private final PrintWriter log;
        private final long t0 = System.currentTimeMillis();
        private long lastTick;

        Heartbeat(PrintWriter log) {
            this.log = log;
            emit("=== Round‑14 sweep started at " + Instant.now() + " ===");
        }

        void event(String msg) { emit(msg); }

        void fixture(int idx, int total, String name, String phase) {
            emit(String.format("Fixture %d/%d %-5s : %s", idx, total, phase, name));
        }

        void plane(String fx, String tag, String phase) {
            emit(String.format("  %s/%s %-5s", fx, tag, phase));
        }

        void tick(String label, long current, long total) {
            long now = System.currentTimeMillis();
            if (now - lastTick < TICK_PERIOD_MS) return;
            lastTick = now;
            double pct = (total <= 0) ? 0 : (100.0 * current / total);
            emit(String.format("      tick %s : %d/%d (%.1f%%)", label, current, total, pct));
        }

        void summary(long evals, long decodes, double bestAbsR) {
            emit(String.format("=== summary: variants=%d decodes=%d bestAbsR=%.4f wall=%.1fs ===",
                    evals, decodes, bestAbsR, (System.currentTimeMillis() - t0) / 1000.0));
        }

        private void emit(String msg) {
            String stamp = String.format("[R14 %6.1fs] ", (System.currentTimeMillis() - t0) / 1000.0);
            String line = stamp + msg;
            System.err.println(line);
            System.err.flush();
            if (log != null) {
                log.println(line);
                log.flush();
            }
        }
    }
}
