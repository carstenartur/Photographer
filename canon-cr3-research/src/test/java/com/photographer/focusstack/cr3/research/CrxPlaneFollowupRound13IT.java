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
 * Round‑13 deep‑refinement sweep — focuses the search around the three
 * hot zones uncovered by Round‑12 (max |r|=0.6573):
 *
 * <ol>
 *   <li><b>Predictor family extension</b>. Round‑12 showed that
 *       {@code P_MED} (LOCO‑I‑style median) dominates Stage A. Round‑13
 *       adds {@code PAETH} (PNG predictor), {@code GRAD}
 *       (Paeth‑lite gradient), {@code AVG} ((a+b)/2),
 *       {@code MED_REV} (predictor applied after a horizontal pre‑pass),
 *       and {@code DC_OFFSET} (subtract per‑row DC mean).</li>
 *   <li><b>Per‑level role permutations</b>. Round‑12 used a single uniform
 *       permutation across all wavelet levels. Round‑13 sweeps all
 *       {@code 6^L} per‑level permutations (216 for L=3) — three
 *       Round‑12 Stage‑B winners had non‑canonical uniform perms, so
 *       per‑level perms are the obvious next step.</li>
 *   <li><b>Detail‑band coefficient negation</b>. The IDWT lifting steps
 *       are sign‑sensitive: flipping HL/LH/HH coefficients before IDWT
 *       is equivalent to a different lifting orientation. Round‑13
 *       sweeps all 2^3 = 8 per‑band negation patterns.</li>
 * </ol>
 *
 * <h3>Greedy two‑pass design</h3>
 *
 * <p>The full cross‑product of axes would be 1.3 M variants per
 * (tile, plane), which is infeasible. Round‑13 uses a two‑pass greedy
 * refinement instead:</p>
 *
 * <pre>
 *   Pass 1 (uniform perm = identity):
 *     2 bit × 4 K × 4 sign × 3 dq × 8 pred × 8 neg  =  6 144 variants
 *   Pass 2 (pin (bit,K,sign,dq,pred,neg) from Pass 1):
 *     6^L per‑level perms                            =    216 variants (L=3)
 *   ─────────────────────────────────────────────────────────────────
 *   Total per (tile, plane) : ~6 360 Stage‑B variants
 *   × 3 C‑RAW fixtures × 8 (tile, plane)           : ≈152 k Stage‑B variants
 * </pre>
 *
 * <p>Plus Stage‑A predictor refinement (~24 k) and a slimmed lossless
 * refinement (~16 k) — total ≈ 192 k variants per run. With Round‑12's
 * caching pattern (entropy decode reused across post‑processing axes)
 * the actual decode count stays in the low thousands and wall‑clock is
 * comparable to Round‑12 (~60 min).</p>
 *
 * <p>Strict test scope: no production code is modified, no flag is
 * added, no hypothesis is promoted. Output dir
 * {@code canon-cr3-imageio/target/crx-followup-round13/}. Always passes
 * — produces:
 * <ul>
 *   <li>{@code metrics.tsv}</li>
 *   <li>{@code winners.txt} — best per (fixture, tile, plane)</li>
 *   <li>{@code top10.txt}</li>
 *   <li>{@code summary.txt}</li>
 *   <li>{@code per_level_perm.txt} — Pass‑2 grid: r vs per‑level perm</li>
 *   <li>{@code per_level_neg.txt} — Pass‑3 grid: r vs per‑level
 *       coefficient‑negation pattern</li>
 *   <li>{@code per_role_predictor.txt} — Pass‑4 grid: r vs
 *       per‑role (HL/LH/HH) predictor</li>
 *   <li>{@code progress.log} — heartbeat / progress trail (also
 *       mirrored to {@code stderr}). Always flushed; survives an
 *       aborted JVM so an external observer can confirm the harness
 *       is alive.</li>
 * </ul>
 *
 * <h3>Crash‑safety / heartbeat contract</h3>
 *
 * <p>All sweep writers are autoflushing and the {@code summary.txt} /
 * {@code top10.txt} block is wrapped in a {@code try/finally} so even
 * an OS‑level abort (Ctrl‑C, sleep, OOM) leaves a usable partial
 * snapshot on disk. Every fixture, every (tile, plane) and every
 * sweep stage emits a timestamped line to {@code stderr} and
 * {@code progress.log}; long inner loops emit a "tick" at most every
 * 30 s so an outside observer can always tell whether the JVM is
 * still doing real work or has wedged.</p>
 */
class CrxPlaneFollowupRound13IT {

    private static final String FIXTURE_DIR = "src/test/resources/samples/research";
    private static final Path OUT_DIR = Path.of("target", "crx-followup-round13");

    private static final String FX_ASIS   = "g5xm2-asis-iso200-ss130-ff2p5-01.cr3";
    private static final String FX_DARK   = "g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02.cr3";
    private static final String FX_FLAT   = "g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02.cr3";
    private static final String FX_DET_02 = "g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-02.cr3";
    private static final String FX_DET_03 = "g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-03.cr3";

    private static final int MAX_UNARY = 64;

    private enum BitOrder { MSB_FIRST, LSB_FIRST }
    private enum SignConv { S_LSB, S_LSB_NEG, S_MSB, S_ZIGZAG }
    private enum LlMode { LL_SIGNED, LL_UNSIGNED, LL_UNSIGNED_MID, LL_UNSIGNED_MID_X2 }
    private enum Dequant { D_IDENTITY, D_MUL_Q, D_MUL_Q_HALF, D_SHIFT_Q }

    /**
     * Predictor family — extended from Round‑12 with PAETH, GRAD, AVG,
     * MED_REV (median applied after a row‑normalisation pre‑pass) and
     * DC_OFFSET (per‑row DC removal).
     */
    private enum Predictor {
        P_NONE,
        P_HORIZ_DELTA,
        P_HORIZ_ROW_RESET,
        P_VERT_DELTA,
        P_MED,
        P_PAETH,
        P_GRAD,
        P_AVG,
        P_MED_REV,
        P_DC_OFFSET
    }

    private static final int[] LOSSLESS_K_INITS = { 0, 1, 2, 3, 5, 7 };
    private static final int[] STAGE_A_K_INITS  = { 0, 1, 2, 3, 5 };
    private static final int[] STAGE_B_K_INITS  = { 0, 1, 2, 3 };

    private static final int[][] ROLE_PERMS = {
            {0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}
    };
    private static final String[] ROLE_NAMES = { "HL", "LH", "HH" };
    /** All 8 per‑detail‑band coefficient‑negation patterns. */
    private static final int[][] NEG_PATTERNS = {
            {1, 1, 1}, {-1, 1, 1}, {1, -1, 1}, {1, 1, -1},
            {-1, -1, 1}, {-1, 1, -1}, {1, -1, -1}, {-1, -1, -1}
    };

    private record Hit(double r, String fixture, String tilePlane, String stage, String label) {
        double absR() { return Math.abs(r); }
    }

    /**
     * Liveness / progress reporter.
     *
     * <p>Every meaningful step (fixture start/end, plane start/end,
     * sweep stage start/end) is emitted to both {@code stderr} and
     * {@code progress.log}. Long inner loops call {@link #tick} which
     * coalesces output to at most one line every {@link #TICK_PERIOD_MS}
     * milliseconds — that way the file never explodes but an outside
     * observer always sees forward progress within ~30 s.</p>
     */
    private static final class Heartbeat {
        private static final long TICK_PERIOD_MS = 30_000;
        private final PrintWriter log;
        private final long t0 = System.currentTimeMillis();
        private long lastTick;

        Heartbeat(PrintWriter log) {
            this.log = log;
            emit("=== Round‑13 sweep started at " + Instant.now() + " ===");
        }

        void event(String msg) { emit(msg); }

        void fixture(int idx, int total, String name, String phase) {
            emit(String.format("Fixture %d/%d %-5s : %s", idx, total, phase, name));
        }

        void plane(String fx, String tag, String phase) {
            emit(String.format("  %s/%s %-5s", fx, tag, phase));
        }

        void stage(String fx, String tag, String stage, String phase, String detail) {
            emit(String.format("    %s/%s [%s] %-5s %s", fx, tag, stage, phase, detail));
        }

        /** Emits a periodic tick — coalesced to {@link #TICK_PERIOD_MS}. */
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
            String stamp = String.format("[R13 %6.1fs] ", (System.currentTimeMillis() - t0) / 1000.0);
            String line = stamp + msg;
            System.err.println(line);
            System.err.flush();
            if (log != null) {
                log.println(line);
                log.flush();
            }
        }
    }

    @Test
    @Disabled("Long-running deep refinement sweep (~2:22 h wall clock, "
            + "266 064 variants). Round-13 has already executed once and "
            + "produced its definitive result (best |r|=0.6573, identical "
            + "to Round-12 — research dead-end documented in samples/research/"
            + "README.md Update (o)). Re-running gains no new information; "
            + "kept disabled in CI to avoid 2 h on every PR. Opt in for a "
            + "fresh run with: "
            + "mvn -B verify -pl canon-cr3-imageio -am -Dit.test=CrxPlaneFollowupRound13IT "
            + "-Dsurefire.failIfNoSpecifiedTests=false -Dtest=NoSuchTestZZZ "
            + "-Djunit.jupiter.conditions.deactivate=org.junit.jupiter.api.condition.DisabledCondition")
    void runRound13DeepSweep() throws Exception {
        Files.createDirectories(OUT_DIR);
        long t0 = System.currentTimeMillis();
        AtomicLong totalEvals = new AtomicLong();
        AtomicLong totalDecodes = new AtomicLong();
        List<Hit> globalTop = new ArrayList<>();

        // Autoflushing writers — survive an aborted JVM with a usable
        // partial snapshot. PrintWriter(FileWriter, autoFlush=true)
        // flushes on every println().
        PrintWriter tsv = null, win = null, perm = null, neg = null, predRole = null, progress = null;
        Heartbeat hb = null;
        try {
            progress = autoflushWriter(OUT_DIR.resolve("progress.log"));
            hb = new Heartbeat(progress);
            tsv      = autoflushWriter(OUT_DIR.resolve("metrics.tsv"));
            win      = autoflushWriter(OUT_DIR.resolve("winners.txt"));
            perm     = autoflushWriter(OUT_DIR.resolve("per_level_perm.txt"));
            neg      = autoflushWriter(OUT_DIR.resolve("per_level_neg.txt"));
            predRole = autoflushWriter(OUT_DIR.resolve("per_role_predictor.txt"));

            tsv.println("section\tfixture\ttile_plane\tvariant\tmetric\tvalue");
            win.println("Round‑13 deep‑refinement sweep — winners per (fixture, tile, plane)");
            win.println("======================================================================");
            perm.println("Round‑13 Pass‑2: per‑level role permutation grid (top‑3 per plane)");
            perm.println("======================================================================");
            neg.println("Round‑13 Pass‑3: per‑level coefficient‑negation grid (top‑3 per plane)");
            neg.println("======================================================================");
            predRole.println("Round‑13 Pass‑4: per‑role (HL/LH/HH) predictor grid (top‑3 per plane)");
            predRole.println("======================================================================");

            String[] losslessFx = { FX_ASIS, FX_DARK };
            String[] crawFx     = { FX_FLAT, FX_DET_02, FX_DET_03 };
            int total = losslessFx.length + crawFx.length;
            int idx = 0;

            for (String fx : losslessFx) {
                idx++;
                long fxT0 = System.currentTimeMillis();
                hb.fixture(idx, total, fx, "START");
                runOneFixture(fx, /*lossless*/ true, tsv, win, perm, neg, predRole,
                        globalTop, totalEvals, totalDecodes, hb);
                hb.fixture(idx, total, fx,
                        String.format("END[%.1fs]", (System.currentTimeMillis() - fxT0) / 1000.0));
            }
            for (String fx : crawFx) {
                idx++;
                long fxT0 = System.currentTimeMillis();
                hb.fixture(idx, total, fx, "START");
                runOneFixture(fx, /*lossless*/ false, tsv, win, perm, neg, predRole,
                        globalTop, totalEvals, totalDecodes, hb);
                hb.fixture(idx, total, fx,
                        String.format("END[%.1fs]", (System.currentTimeMillis() - fxT0) / 1000.0));
            }

            long dt = System.currentTimeMillis() - t0;
            win.printf("%n--- Global summary ---%n");
            win.printf("Total decode‑equivalents: %d%n", totalDecodes.get());
            win.printf("Total scored variants:    %d%n", totalEvals.get());
            win.printf("Wall‑clock: %.1f s%n", dt / 1000.0);
        } finally {
            // Always emit summary + top10, even if the sweep above
            // aborted. This is what makes a partial run useful instead
            // of opaque.
            try {
                writeFinalReports(globalTop, totalEvals.get(), totalDecodes.get(), t0);
            } catch (Exception ex) {
                System.err.println("[R13] WARN: writing final reports failed: " + ex);
            }
            if (hb != null) {
                double best = globalTop.stream().mapToDouble(Hit::absR).max().orElse(0);
                hb.summary(totalEvals.get(), totalDecodes.get(), best);
            }
            closeQuiet(tsv); closeQuiet(win); closeQuiet(perm);
            closeQuiet(neg); closeQuiet(predRole); closeQuiet(progress);
        }

        assertThat(OUT_DIR.resolve("metrics.tsv")).exists();
        assertThat(OUT_DIR.resolve("winners.txt")).exists();
        assertThat(OUT_DIR.resolve("top10.txt")).exists();
        assertThat(OUT_DIR.resolve("summary.txt")).exists();
        assertThat(OUT_DIR.resolve("per_level_perm.txt")).exists();
        assertThat(OUT_DIR.resolve("per_level_neg.txt")).exists();
        assertThat(OUT_DIR.resolve("per_role_predictor.txt")).exists();
        assertThat(OUT_DIR.resolve("progress.log")).exists();
    }

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
            pw.println("Round‑13 — top 10 hypotheses by |Pearson r|");
            pw.println("==============================================");
            int n = Math.min(10, globalTop.size());
            for (int i = 0; i < n; i++) {
                Hit h = globalTop.get(i);
                pw.printf("#%-2d  |r|=%.4f  r=%+.4f  %s / %s / %s%n     %s%n",
                        i + 1, h.absR(), h.r, h.fixture, h.tilePlane, h.stage, h.label);
            }
            if (globalTop.isEmpty()) pw.println("(no hypotheses scored)");
        }

        try (PrintWriter pw = autoflushWriter(OUT_DIR.resolve("summary.txt"))) {
            double maxAbs = globalTop.isEmpty() ? 0 : globalTop.get(0).absR();
            pw.println("Round‑13 outcome summary");
            pw.println("=========================");
            pw.printf("Total variants evaluated: %d%n", evals);
            pw.printf("Decode‑equivalents:       %d%n", decodes);
            pw.printf("Wall‑clock:               %.1f s%n", (System.currentTimeMillis() - t0) / 1000.0);
            pw.printf("Best |Pearson r|:         %.4f%n", maxAbs);
            pw.printf("Round‑12 best (reference): 0.6573%n");
            if (maxAbs >= 0.7) {
                pw.println();
                pw.println("RESULT: ≥ 0.70 — promotion threshold cleared. Cross‑validate the");
                pw.println("        winning configuration on the second detail fixture before");
                pw.println("        promoting any hypothesis to production.");
            } else if (maxAbs >= 0.6573) {
                pw.println();
                pw.printf( "RESULT: improved over Round‑12 by Δ=+%.4f. Inspect top10.txt and%n",
                        maxAbs - 0.6573);
                pw.println("        per_level_perm.txt / per_level_neg.txt / per_role_predictor.txt");
                pw.println("        to identify which axis contributed the improvement, then");
                pw.println("        design Round‑14 around it.");
            } else {
                pw.println();
                pw.println("RESULT: no improvement over Round‑12. The remaining missing axes");
                pw.println("        are very likely outside what the harness can express without");
                pw.println("        a real ground‑truth decoder. Recommend pivot to LibRaw‑backed");
                pw.println("        JNI ground truth + use these fixtures as pixel‑exact tests.");
            }
        }
    }

    // ============================================================ per‑fixture

    private void runOneFixture(String fx, boolean lossless, PrintWriter tsv, PrintWriter win,
                               PrintWriter perm, PrintWriter negPw, PrintWriter predRolePw,
                               List<Hit> top, AtomicLong evals, AtomicLong decodes,
                               Heartbeat hb) throws Exception {
        File f = new File(FIXTURE_DIR + "/" + fx);
        if (!f.exists()) {
            win.printf("%n--- %s --- skipped (fixture not present)%n", fx);
            hb.event("  SKIP " + fx + " (fixture missing)");
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
            if (lossless) {
                sweepLossless(f, sbs, planeW, planeH, bd, refFull, fx, tag,
                        tsv, win, top, evals, decodes, hb);
            } else {
                sweepCrawDeep(f, sbs, planeW, planeH, bd, waveletLevels, refFull,
                        fx, tag, tsv, win, perm, negPw, predRolePw, top, evals, decodes, hb);
            }
            hb.plane(fx, tag,
                    String.format("END[%.1fs]", (System.currentTimeMillis() - planeT0) / 1000.0));
            // Per‑plane flush so a Ctrl‑C between planes never loses
            // the result of the plane that just finished.
            tsv.flush(); win.flush(); perm.flush(); negPw.flush(); predRolePw.flush();
        }
    }

    // ============================================================ lossless (slim)

    private void sweepLossless(File f, List<SubbandExtractor.SubbandRef> sbs, int planeW, int planeH,
                               int bd, int[] refFull, String fx, String tag,
                               PrintWriter tsv, PrintWriter win, List<Hit> top,
                               AtomicLong evals, AtomicLong decodes, Heartbeat hb) throws IOException {
        if (sbs.isEmpty()) return;
        long st = System.currentTimeMillis();
        hb.stage(fx, tag, "lossless", "START", "");
        SubbandExtractor.SubbandRef ref = sbs.get(0);
        byte[] payloadRaw = SubbandExtractor.read(f, ref);
        int n = planeW * planeH;

        Hit best = new Hit(0, fx, tag, "lossless", "k=0 sign=LSB pred=NONE bit=MSB");
        int variantsTotal = BitOrder.values().length * LOSSLESS_K_INITS.length
                * SignConv.values().length * Predictor.values().length;
        int variantsDone = 0;
        for (BitOrder bo : BitOrder.values()) {
            byte[] payload = (bo == BitOrder.MSB_FIRST) ? payloadRaw : reverseBitsPerByte(payloadRaw);
            for (int kInit : LOSSLESS_K_INITS) {
                int[] decoded = decodeRiceRaw(payload, n, bd, kInit);
                decodes.incrementAndGet();
                for (SignConv sc : SignConv.values()) {
                    int[] mapped = applySign(decoded, sc);
                    for (Predictor p : Predictor.values()) {
                        int[] pix = applyPredictor(mapped, planeW, planeH, p);
                        evals.incrementAndGet();
                        variantsDone++;
                        double r = pearson(pix, refFull);
                        if (Math.abs(r) > best.absR()) {
                            best = new Hit(r, fx, tag, "lossless",
                                    "k=" + kInit + " sign=" + sc + " pred=" + p + " bit=" + bo);
                        }
                        hb.tick("lossless " + tag, variantsDone, variantsTotal);
                    }
                }
            }
        }
        tsv.printf("R13/lossless\t%s\t%s\t%s\tbest_r\t%.6f%n", fx, tag, best.label, best.r);
        win.printf("  [lossless] %s: |r|=%.4f  %s%n", tag, best.absR(), best.label);
        top.add(best);
        hb.stage(fx, tag, "lossless", "END",
                String.format("|r|=%.4f in %.1fs", best.absR(),
                        (System.currentTimeMillis() - st) / 1000.0));
    }

    // ============================================================ C‑RAW deep

    private void sweepCrawDeep(File f, List<SubbandExtractor.SubbandRef> sbs, int planeW, int planeH,
                               int bd, int levels, int[] refFull, String fx, String tag,
                               PrintWriter tsv, PrintWriter win, PrintWriter permPw,
                               PrintWriter negPw, PrintWriter predRolePw,
                               List<Hit> top, AtomicLong evals, AtomicLong decodes,
                               Heartbeat hb) throws IOException {
        int[][] dims = subbandDims(planeW, planeH, levels);
        if (sbs.size() < dims.length) {
            win.printf("  %s: skipped (%d/%d subbands)%n", tag, sbs.size(), dims.length);
            hb.event("    " + fx + "/" + tag + " SKIP (subband count mismatch)");
            return;
        }
        // ---- Stage A (LL only, refined predictor family) --------------
        long stA = System.currentTimeMillis();
        hb.stage(fx, tag, "stageA", "START", "");
        SubbandExtractor.SubbandRef llRef = sbs.get(0);
        byte[] llRaw = SubbandExtractor.read(f, llRef);
        int llW = dims[0][0], llH = dims[0][1];
        int llN = llW * llH;
        int llQ = llRef.quantValue();
        int[] refLl = boxDownscale(refFull, planeW, planeH, llW, llH);

        Hit bestA = new Hit(0, fx, tag, "stageA", "ll=SIGNED k=0 dq=IDENTITY pred=NONE bit=MSB");
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
        tsv.printf("R13/A\t%s\t%s\t%s\tbest_LL_r\t%.6f%n", fx, tag, bestA.label, bestA.r);
        win.printf("  [stageA] %s: |r|=%.4f  %s%n", tag, bestA.absR(), bestA.label);
        top.add(bestA);
        hb.stage(fx, tag, "stageA", "END",
                String.format("|r|=%.4f in %.1fs", bestA.absR(),
                        (System.currentTimeMillis() - stA) / 1000.0));

        // ---- Stage B Pass 1 — uniform identity perm, sweep (axes + neg) -----
        long stB1 = System.currentTimeMillis();
        hb.stage(fx, tag, "stageB1", "START", "");
        BitOrder pinnedBoA = parseBitOrder(bestA.label);
        LlMode pinnedLl = parseLlMode(bestA.label);
        int pinnedKa = parseKInit(bestA.label);
        Dequant pinnedDqA = parseDequant(bestA.label);
        Predictor pinnedPredA = parsePredictor(bestA.label);

        byte[] llBitOrdered = (pinnedBoA == BitOrder.MSB_FIRST) ? llRaw : reverseBitsPerByte(llRaw);
        int[] llBaseDecode = decodeRiceRaw(llBitOrdered, llN, bd, pinnedKa);
        decodes.incrementAndGet();
        int[] llCoef0 = applyLlMode(llBaseDecode, pinnedLl, bd);
        llCoef0 = applyDequant(llCoef0, pinnedDqA, llQ);
        final int[] llCoef = applyPredictor(llCoef0, llW, llH, pinnedPredA);

        int detailCount = 3 * levels;
        byte[][] detailRaw = new byte[detailCount][];
        int[][] detailDims = new int[detailCount][];
        int[] detailQ = new int[detailCount];
        for (int i = 0; i < detailCount; i++) {
            detailRaw[i] = SubbandExtractor.read(f, sbs.get(1 + i));
            detailDims[i] = dims[1 + i];
            detailQ[i] = sbs.get(1 + i).quantValue();
        }

        // Pass 1: identity per‑level perm, full axis sweep including 8 negation patterns.
        Hit bestB1 = new Hit(0, fx, tag, "stageB1",
                "k=0 sign=LSB pred=NONE dq=MUL_Q neg=[1,1,1] bit=MSB");
        int b1Total = BitOrder.values().length * STAGE_B_K_INITS.length
                * SignConv.values().length * 3 /*dq*/ * Predictor.values().length
                * NEG_PATTERNS.length;
        int b1Done = 0;
        for (BitOrder bo : BitOrder.values()) {
            byte[][] payloads = new byte[detailCount][];
            for (int i = 0; i < detailCount; i++) {
                payloads[i] = (bo == BitOrder.MSB_FIRST) ? detailRaw[i] : reverseBitsPerByte(detailRaw[i]);
            }
            for (int kInit : STAGE_B_K_INITS) {
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
                            // Apply per‑band negation pattern (uniform across levels in Pass 1).
                            for (int[] negPat : NEG_PATTERNS) {
                                int[][] neg = new int[detailCount][];
                                for (int lev = 0; lev < levels; lev++) {
                                    int base = 3 * lev;
                                    for (int role = 0; role < 3; role++) {
                                        neg[base + role] = (negPat[role] == -1)
                                                ? negate(predicted[base + role]) : predicted[base + role];
                                    }
                                }
                                int[] recon = idwtAssemblePerLevel(llCoef, dims[0], neg, detailDims, levels,
                                        identityPerLevelPerm(levels));
                                evals.incrementAndGet();
                                if (recon == null) continue;
                                int n = Math.min(recon.length, refFull.length);
                                int[] cand = (recon.length == n) ? recon : Arrays.copyOf(recon, n);
                                int[] ref = (refFull.length == n) ? refFull : Arrays.copyOf(refFull, n);
                                double r = pearson(cand, ref);
                                if (Math.abs(r) > bestB1.absR()) {
                                    bestB1 = new Hit(r, fx, tag, "stageB1",
                                            "k=" + kInit + " sign=" + sc + " pred=" + pred
                                                    + " dq=" + dq
                                                    + " neg=[" + negPat[0] + "," + negPat[1] + "," + negPat[2] + "]"
                                                    + " bit=" + bo);
                                }
                                b1Done++;
                                hb.tick("stageB1 " + tag, b1Done, b1Total);
                            }
                        }
                    }
                }
            }
        }
        tsv.printf("R13/B1\t%s\t%s\t%s\tbest_pass1_r\t%.6f%n", fx, tag, bestB1.label, bestB1.r);
        win.printf("  [stageB1] %s: |r|=%.4f  %s%n", tag, bestB1.absR(), bestB1.label);
        top.add(bestB1);
        hb.stage(fx, tag, "stageB1", "END",
                String.format("|r|=%.4f in %.1fs", bestB1.absR(),
                        (System.currentTimeMillis() - stB1) / 1000.0));

        // ---- Stage B Pass 2 — pin Pass 1 winners, sweep all 6^L per‑level perms ----
        long stB2 = System.currentTimeMillis();
        hb.stage(fx, tag, "stageB2", "START",
                String.format("perms=%d", (int) Math.pow(6, levels)));
        BitOrder pinnedBo  = parseBitOrder(bestB1.label);
        int pinnedK        = parseKInit(bestB1.label);
        SignConv pinnedSc  = parseSign(bestB1.label);
        Dequant pinnedDq   = parseDequant(bestB1.label);
        Predictor pinnedPr = parsePredictor(bestB1.label);
        int[] pinnedNeg    = parseNeg(bestB1.label);

        byte[][] payloads = new byte[detailCount][];
        for (int i = 0; i < detailCount; i++) {
            payloads[i] = (pinnedBo == BitOrder.MSB_FIRST) ? detailRaw[i] : reverseBitsPerByte(detailRaw[i]);
        }
        int[][] processed = new int[detailCount][];
        for (int i = 0; i < detailCount; i++) {
            int n = detailDims[i][0] * detailDims[i][1];
            int[] d = decodeRiceRaw(payloads[i], n, bd, pinnedK);
            decodes.incrementAndGet();
            d = applySign(d, pinnedSc);
            d = applyDequant(d, pinnedDq, detailQ[i]);
            d = applyPredictor(d, detailDims[i][0], detailDims[i][1], pinnedPr);
            int role = i % 3;
            if (pinnedNeg[role] == -1) d = negate(d);
            processed[i] = d;
        }

        Hit bestB2 = bestB1;
        // Track the top‑3 per‑level perms for the per_level_perm.txt diagnostic.
        List<Hit> permsThisPlane = new ArrayList<>();
        int totalPermCount = (int) Math.pow(6, levels);
        for (int permIdx = 0; permIdx < totalPermCount; permIdx++) {
            int[][] perLevelPerm = decodePerLevelPerm(permIdx, levels);
            int[] recon = idwtAssemblePerLevel(llCoef, dims[0], processed, detailDims, levels, perLevelPerm);
            evals.incrementAndGet();
            if (recon == null) continue;
            int n = Math.min(recon.length, refFull.length);
            int[] cand = (recon.length == n) ? recon : Arrays.copyOf(recon, n);
            int[] ref = (refFull.length == n) ? refFull : Arrays.copyOf(refFull, n);
            double r = pearson(cand, ref);
            String label = bestB1.label + " perms=" + permLabel(perLevelPerm);
            permsThisPlane.add(new Hit(r, fx, tag, "stageB2", label));
            if (Math.abs(r) > bestB2.absR()) {
                bestB2 = new Hit(r, fx, tag, "stageB2", label);
            }
            hb.tick("stageB2 " + tag, permIdx + 1, totalPermCount);
        }
        permsThisPlane.sort((a, b) -> Double.compare(b.absR(), a.absR()));
        tsv.printf("R13/B2\t%s\t%s\t%s\tbest_pass2_r\t%.6f%n", fx, tag, bestB2.label, bestB2.r);
        win.printf("  [stageB2] %s: |r|=%.4f  %s%n", tag, bestB2.absR(), bestB2.label);
        top.add(bestB2);

        permPw.printf("%n--- %s / %s (best Pass2 |r|=%.4f) ---%n", fx, tag, bestB2.absR());
        for (int i = 0; i < Math.min(3, permsThisPlane.size()); i++) {
            Hit h = permsThisPlane.get(i);
            permPw.printf("  #%d  |r|=%.4f  %s%n", i + 1, h.absR(), h.label);
        }
        hb.stage(fx, tag, "stageB2", "END",
                String.format("|r|=%.4f in %.1fs", bestB2.absR(),
                        (System.currentTimeMillis() - stB2) / 1000.0));

        // ---- Stage B Pass 3 — pin Pass 2 winner, sweep 8^L per‑level neg patterns ----
        long stB3 = System.currentTimeMillis();
        int totalNegCount = (int) Math.pow(8, levels);
        hb.stage(fx, tag, "stageB3", "START", String.format("negs=%d", totalNegCount));

        int[][] pinnedPerm = parsePerLevelPerm(bestB2.label, levels);
        // Recompute processed[] WITHOUT any neg (Pass 3 sweeps neg per level).
        int[][] processedNoNeg = new int[detailCount][];
        for (int i = 0; i < detailCount; i++) {
            int nn = detailDims[i][0] * detailDims[i][1];
            int[] d = decodeRiceRaw(payloads[i], nn, bd, pinnedK);
            decodes.incrementAndGet();
            d = applySign(d, pinnedSc);
            d = applyDequant(d, pinnedDq, detailQ[i]);
            d = applyPredictor(d, detailDims[i][0], detailDims[i][1], pinnedPr);
            processedNoNeg[i] = d;
        }

        Hit bestB3 = bestB2;
        List<Hit> negsThisPlane = new ArrayList<>();
        for (int negIdx = 0; negIdx < totalNegCount; negIdx++) {
            int[][] perLevelNeg = decodePerLevelNeg(negIdx, levels);
            int[][] negApplied = new int[detailCount][];
            for (int lev = 0; lev < levels; lev++) {
                int base = 3 * lev;
                for (int s = 0; s < 3; s++) {
                    negApplied[base + s] = (perLevelNeg[lev][s] == -1)
                            ? negate(processedNoNeg[base + s]) : processedNoNeg[base + s];
                }
            }
            int[] recon = idwtAssemblePerLevel(llCoef, dims[0], negApplied, detailDims, levels, pinnedPerm);
            evals.incrementAndGet();
            hb.tick("stageB3 " + tag, negIdx + 1, totalNegCount);
            if (recon == null) continue;
            int nn = Math.min(recon.length, refFull.length);
            int[] cand = (recon.length == nn) ? recon : Arrays.copyOf(recon, nn);
            int[] ref = (refFull.length == nn) ? refFull : Arrays.copyOf(refFull, nn);
            double r = pearson(cand, ref);
            String label = bestB2.label + " plNeg=" + perLevelNegLabel(perLevelNeg);
            negsThisPlane.add(new Hit(r, fx, tag, "stageB3", label));
            if (Math.abs(r) > bestB3.absR()) {
                bestB3 = new Hit(r, fx, tag, "stageB3", label);
            }
        }
        negsThisPlane.sort((a, b) -> Double.compare(b.absR(), a.absR()));
        tsv.printf("R13/B3\t%s\t%s\t%s\tbest_pass3_r\t%.6f%n", fx, tag, bestB3.label, bestB3.r);
        win.printf("  [stageB3] %s: |r|=%.4f  %s%n", tag, bestB3.absR(), bestB3.label);
        top.add(bestB3);
        negPw.printf("%n--- %s / %s (best Pass3 |r|=%.4f) ---%n", fx, tag, bestB3.absR());
        for (int i = 0; i < Math.min(3, negsThisPlane.size()); i++) {
            Hit h = negsThisPlane.get(i);
            negPw.printf("  #%d  |r|=%.4f  %s%n", i + 1, h.absR(), h.label);
        }
        hb.stage(fx, tag, "stageB3", "END",
                String.format("|r|=%.4f in %.1fs", bestB3.absR(),
                        (System.currentTimeMillis() - stB3) / 1000.0));

        // ---- Stage B Pass 4 — pin Pass 3 winner, sweep 10^3 per‑role predictors ----
        long stB4 = System.currentTimeMillis();
        Predictor[] preds = Predictor.values();
        int totalPredCount = preds.length * preds.length * preds.length;
        hb.stage(fx, tag, "stageB4", "START", String.format("variants=%d", totalPredCount));

        int[][] pinnedNegPerLevel = parsePerLevelNeg(bestB3.label, levels);
        // For each detail band: pre‑compute predicted+sign‑flipped coefficients
        // for every predictor option. Predictor selection in the inner loop is
        // then a single array lookup, not a recompute.
        int[][][] predictedByBandPred = new int[detailCount][preds.length][];
        for (int i = 0; i < detailCount; i++) {
            int nn = detailDims[i][0] * detailDims[i][1];
            int[] d = decodeRiceRaw(payloads[i], nn, bd, pinnedK);
            decodes.incrementAndGet();
            d = applySign(d, pinnedSc);
            d = applyDequant(d, pinnedDq, detailQ[i]);
            int lev = i / 3;
            int slot = i % 3;
            boolean flipSign = pinnedNegPerLevel[lev][slot] == -1;
            for (int p = 0; p < preds.length; p++) {
                int[] pp = applyPredictor(d, detailDims[i][0], detailDims[i][1], preds[p]);
                predictedByBandPred[i][p] = flipSign ? negate(pp) : pp;
            }
        }

        Hit bestB4 = bestB3;
        List<Hit> predsThisPlane = new ArrayList<>();
        int b4Done = 0;
        for (int hl = 0; hl < preds.length; hl++) {
            for (int lh = 0; lh < preds.length; lh++) {
                for (int hh = 0; hh < preds.length; hh++) {
                    int[] predIdxByRole = { hl, lh, hh };
                    int[][] bands = new int[detailCount][];
                    for (int lev = 0; lev < levels; lev++) {
                        int base = 3 * lev;
                        for (int s = 0; s < 3; s++) {
                            int role = pinnedPerm[lev][s];
                            bands[base + s] = predictedByBandPred[base + s][predIdxByRole[role]];
                        }
                    }
                    int[] recon = idwtAssemblePerLevel(llCoef, dims[0], bands, detailDims, levels, pinnedPerm);
                    evals.incrementAndGet();
                    b4Done++;
                    hb.tick("stageB4 " + tag, b4Done, totalPredCount);
                    if (recon == null) continue;
                    int nn = Math.min(recon.length, refFull.length);
                    int[] cand = (recon.length == nn) ? recon : Arrays.copyOf(recon, nn);
                    int[] ref = (refFull.length == nn) ? refFull : Arrays.copyOf(refFull, nn);
                    double r = pearson(cand, ref);
                    if (Math.abs(r) > bestB4.absR()) {
                        String label = bestB3.label + " roleP=[HL:" + preds[hl]
                                + ",LH:" + preds[lh] + ",HH:" + preds[hh] + "]";
                        bestB4 = new Hit(r, fx, tag, "stageB4", label);
                        predsThisPlane.add(bestB4);
                    }
                }
            }
        }
        predsThisPlane.sort((a, b) -> Double.compare(b.absR(), a.absR()));
        tsv.printf("R13/B4\t%s\t%s\t%s\tbest_pass4_r\t%.6f%n", fx, tag, bestB4.label, bestB4.r);
        win.printf("  [stageB4] %s: |r|=%.4f  %s%n", tag, bestB4.absR(), bestB4.label);
        top.add(bestB4);
        predRolePw.printf("%n--- %s / %s (best Pass4 |r|=%.4f) ---%n", fx, tag, bestB4.absR());
        for (int i = 0; i < Math.min(3, predsThisPlane.size()); i++) {
            Hit h = predsThisPlane.get(i);
            predRolePw.printf("  #%d  |r|=%.4f  %s%n", i + 1, h.absR(), h.label);
        }
        hb.stage(fx, tag, "stageB4", "END",
                String.format("|r|=%.4f in %.1fs", bestB4.absR(),
                        (System.currentTimeMillis() - stB4) / 1000.0));
    }

    // ============================================================ helpers

    private static int[] negate(int[] a) {
        int[] out = new int[a.length];
        for (int i = 0; i < a.length; i++) out[i] = -a[i];
        return out;
    }

    private static int[][] identityPerLevelPerm(int levels) {
        int[][] out = new int[levels][];
        for (int i = 0; i < levels; i++) out[i] = ROLE_PERMS[0];
        return out;
    }

    private static String permLabel(int[][] perLevelPerm) {
        StringBuilder sb = new StringBuilder("[");
        for (int lev = 0; lev < perLevelPerm.length; lev++) {
            int[] p = perLevelPerm[lev];
            sb.append("L").append(lev + 1).append(":")
                    .append(ROLE_NAMES[p[0]]).append(ROLE_NAMES[p[1]]).append(ROLE_NAMES[p[2]]);
            if (lev + 1 < perLevelPerm.length) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    /** Decodes a per‑level permutation index in [0, 6^levels) into level‑indexed perms. */
    private static int[][] decodePerLevelPerm(int idx, int levels) {
        int[][] out = new int[levels][];
        for (int lev = 0; lev < levels; lev++) {
            int p = idx % 6;
            out[lev] = ROLE_PERMS[p];
            idx /= 6;
        }
        return out;
    }

    /** Decodes a per‑level neg index in [0, 8^levels) into per‑level (3‑slot ±1) patterns. */
    private static int[][] decodePerLevelNeg(int idx, int levels) {
        int[][] out = new int[levels][];
        for (int lev = 0; lev < levels; lev++) {
            int p = idx % 8;
            out[lev] = NEG_PATTERNS[p].clone();
            idx /= 8;
        }
        return out;
    }

    /** Encodes per‑level neg as e.g. {@code [L1:1,-1,1;L2:-1,-1,1;L3:1,1,-1]}. */
    private static String perLevelNegLabel(int[][] perLevelNeg) {
        StringBuilder sb = new StringBuilder("[");
        for (int lev = 0; lev < perLevelNeg.length; lev++) {
            int[] n = perLevelNeg[lev];
            sb.append("L").append(lev + 1).append(":")
                    .append(n[0]).append(",").append(n[1]).append(",").append(n[2]);
            if (lev + 1 < perLevelNeg.length) sb.append(";");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Parses {@code perms=[L1:HHHLLH,L2:LHHLHH,L3:LHHLHH]} from a label.
     * Falls back to the identity perm for any missing level.
     */
    private static int[][] parsePerLevelPerm(String label, int levels) {
        int[][] out = new int[levels][];
        for (int lev = 0; lev < levels; lev++) out[lev] = ROLE_PERMS[0];
        int i = label.indexOf("perms=[");
        if (i < 0) return out;
        int e = label.indexOf("]", i);
        if (e < 0) return out;
        String body = label.substring(i + "perms=[".length(), e);
        String[] parts = body.split(",");
        for (String part : parts) {
            int colon = part.indexOf(':');
            if (colon < 0 || colon < 2) continue;
            int lev;
            try { lev = Integer.parseInt(part.substring(1, colon)) - 1; }
            catch (Exception ex) { continue; }
            if (lev < 0 || lev >= levels) continue;
            String roleStr = part.substring(colon + 1).trim();
            if (roleStr.length() < 6) continue;
            int[] perm = new int[3];
            boolean ok = true;
            for (int s = 0; s < 3; s++) {
                String rn = roleStr.substring(s * 2, s * 2 + 2);
                int r = -1;
                for (int k = 0; k < ROLE_NAMES.length; k++) {
                    if (ROLE_NAMES[k].equals(rn)) { r = k; break; }
                }
                if (r < 0) { ok = false; break; }
                perm[s] = r;
            }
            if (ok) out[lev] = perm;
        }
        return out;
    }

    /**
     * Parses {@code plNeg=[L1:1,-1,1;L2:-1,-1,1;L3:1,1,-1]} from a label.
     * Falls back to all‑+1 for any missing level.
     */
    private static int[][] parsePerLevelNeg(String label, int levels) {
        int[][] out = new int[levels][];
        for (int lev = 0; lev < levels; lev++) out[lev] = new int[]{ 1, 1, 1 };
        int i = label.indexOf("plNeg=[");
        if (i < 0) return out;
        int e = label.indexOf("]", i);
        if (e < 0) return out;
        String body = label.substring(i + "plNeg=[".length(), e);
        String[] parts = body.split(";");
        for (String part : parts) {
            int colon = part.indexOf(':');
            if (colon < 0 || colon < 2) continue;
            int lev;
            try { lev = Integer.parseInt(part.substring(1, colon)) - 1; }
            catch (Exception ex) { continue; }
            if (lev < 0 || lev >= levels) continue;
            String[] vals = part.substring(colon + 1).split(",");
            if (vals.length < 3) continue;
            int[] neg = new int[3];
            boolean ok = true;
            for (int s = 0; s < 3; s++) {
                try { neg[s] = Integer.parseInt(vals[s].trim()); }
                catch (Exception ex) { ok = false; break; }
            }
            if (ok) out[lev] = neg;
        }
        return out;
    }

    /** IDWT assembly with a separate role permutation per level. */
    private static int[] idwtAssemblePerLevel(int[] llCoef, int[] llDims, int[][] detail, int[][] dDims,
                                              int levels, int[][] perLevelPerm) {
        int[] ll = llCoef;
        int llW = llDims[0], llH = llDims[1];
        for (int lev = levels; lev >= 1; lev--) {
            int detailBase = 3 * (levels - lev);
            int[] perm = perLevelPerm[levels - lev];
            int[] inv = new int[3];
            for (int slot = 0; slot < 3; slot++) inv[perm[slot]] = slot;
            int[] hl = padTo(detail[detailBase + inv[0]], llW * llH);
            int[] lh = padTo(detail[detailBase + inv[1]], llW * llH);
            int[] hh = padTo(detail[detailBase + inv[2]], llW * llH);
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

    private static int[][] subbandDims(int planeW, int planeH, int levels) {
        int n = (levels <= 0) ? 1 : 1 + 3 * levels;
        int[][] dims = new int[n][2];
        dims[0][0] = Math.max(1, planeW >> levels); dims[0][1] = Math.max(1, planeH >> levels);
        for (int lev = levels; lev >= 1; lev--) {
            int w = Math.max(1, planeW >> lev), h = Math.max(1, planeH >> lev);
            int base = 1 + 3 * (levels - lev);
            dims[base][0] = w; dims[base][1] = h;
            dims[base + 1][0] = w; dims[base + 1][1] = h;
            dims[base + 2][0] = w; dims[base + 2][1] = h;
        }
        return dims;
    }

    // ============================================================ entropy / sign / dequant

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
        }
        return out;
    }

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

    // ============================================================ predictor family (extended)

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
            case P_PAETH -> {
                // PNG Paeth predictor: closest of {a,b,c} to (a+b-c).
                for (int y = 0; y < rows; y++) {
                    for (int x = 0; x < w; x++) {
                        int idx = y * w + x;
                        if (idx >= coef.length) break;
                        int a = (x > 0) ? out[idx - 1] : 0;
                        int b = (y > 0) ? out[idx - w] : 0;
                        int c = (x > 0 && y > 0) ? out[idx - w - 1] : 0;
                        int p0 = a + b - c;
                        int da = Math.abs(p0 - a);
                        int db = Math.abs(p0 - b);
                        int dc = Math.abs(p0 - c);
                        int pred = (da <= db && da <= dc) ? a : (db <= dc ? b : c);
                        out[idx] = coef[idx] + pred;
                    }
                }
            }
            case P_GRAD -> {
                // Simple gradient: pred = a + b - c (clipped to int range).
                for (int y = 0; y < rows; y++) {
                    for (int x = 0; x < w; x++) {
                        int idx = y * w + x;
                        if (idx >= coef.length) break;
                        int a = (x > 0) ? out[idx - 1] : 0;
                        int b = (y > 0) ? out[idx - w] : 0;
                        int c = (x > 0 && y > 0) ? out[idx - w - 1] : 0;
                        out[idx] = coef[idx] + (a + b - c);
                    }
                }
            }
            case P_AVG -> {
                // pred = (a + b) / 2.
                for (int y = 0; y < rows; y++) {
                    for (int x = 0; x < w; x++) {
                        int idx = y * w + x;
                        if (idx >= coef.length) break;
                        int a = (x > 0) ? out[idx - 1] : 0;
                        int b = (y > 0) ? out[idx - w] : 0;
                        out[idx] = coef[idx] + ((a + b) >> 1);
                    }
                }
            }
            case P_MED_REV -> {
                // Median predictor applied to a horizontally pre‑accumulated signal,
                // then de‑accumulated. Models a two‑pass residual scheme.
                int[] tmp = new int[coef.length];
                for (int y = 0; y < rows; y++) {
                    int rs = y * w;
                    int acc = 0;
                    for (int x = 0; x < w && rs + x < coef.length; x++) {
                        acc += coef[rs + x];
                        tmp[rs + x] = acc;
                    }
                }
                for (int y = 0; y < rows; y++) {
                    for (int x = 0; x < w; x++) {
                        int idx = y * w + x;
                        if (idx >= tmp.length) break;
                        int a = (x > 0) ? out[idx - 1] : 0;
                        int b = (y > 0) ? out[idx - w] : 0;
                        int c = (x > 0 && y > 0) ? out[idx - w - 1] : 0;
                        int pred;
                        if (c >= Math.max(a, b)) pred = Math.min(a, b);
                        else if (c <= Math.min(a, b)) pred = Math.max(a, b);
                        else pred = a + b - c;
                        out[idx] = tmp[idx] + pred;
                    }
                }
            }
            case P_DC_OFFSET -> {
                // Subtract per‑row DC mean (high‑pass filter).
                for (int y = 0; y < rows; y++) {
                    int rs = y * w;
                    long sum = 0;
                    int cnt = 0;
                    for (int x = 0; x < w && rs + x < coef.length; x++) {
                        sum += coef[rs + x]; cnt++;
                    }
                    int dc = (cnt == 0) ? 0 : (int) (sum / cnt);
                    for (int x = 0; x < w && rs + x < coef.length; x++) {
                        out[rs + x] = coef[rs + x] - dc;
                    }
                }
            }
            default -> System.arraycopy(coef, 0, out, 0, coef.length);
        }
        for (int i = rows * w; i < coef.length; i++) out[i] = coef[i];
        return out;
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
                        sum += src[sy0 * srcW + sx0]; cnt++;
                    }
                }
                out[y * dstW + x] = (cnt == 0) ? 0 : (int) (sum / cnt);
            }
        }
        return out;
    }

    // ============================================================ label parsing

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
    private static SignConv parseSign(String label) {
        for (SignConv s : SignConv.values()) if (label.contains("sign=" + s)) return s;
        return SignConv.S_LSB;
    }
    /** Parses the {@code neg=[a,b,c]} field. Returns {1,1,1} if absent. */
    private static int[] parseNeg(String label) {
        int i = label.indexOf("neg=[");
        if (i < 0) return new int[]{1, 1, 1};
        int e = label.indexOf("]", i);
        if (e < 0) return new int[]{1, 1, 1};
        String body = label.substring(i + 5, e);
        String[] parts = body.split(",");
        int[] out = { 1, 1, 1 };
        for (int k = 0; k < Math.min(3, parts.length); k++) {
            try { out[k] = Integer.parseInt(parts[k].trim()); } catch (Exception ex) { /* keep 1 */ }
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
}
