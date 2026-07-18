package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.raw.codec.SubbandExtractor;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase F — Decoder Stage Isolation (Update t).
 *
 * <p>Ablation study: tests each of the five normative spec changes
 * <em>individually</em> to identify which stage is responsible for the
 * entropy‑lock‑loss bug (mode at 16 320, rail fraction ≈ 1.0) that
 * persists in the cleanroom lossless decoder.</p>
 *
 * <h3>Variants under test</h3>
 *
 * <table>
 * <caption>Ablation configurations (one knob on at a time)</caption>
 * <tr><th>ID</th><th>Description</th><th>Changed knob(s)</th></tr>
 * <tr><td>F0</td><td>baseline</td><td>(production semantics, all knobs off)</td></tr>
 * <tr><td>F1</td><td>+unary</td><td>unary direction: 1s→0 only</td></tr>
 * <tr><td>F2</td><td>+kInit</td><td>K_INIT = 1 only</td></tr>
 * <tr><td>F3</td><td>+resetRow</td><td>per-row K reset only</td></tr>
 * <tr><td>F4</td><td>+signBit</td><td>bit-0 sign mapping only</td></tr>
 * <tr><td>F5</td><td>+escape15</td><td>unary-15 escape only</td></tr>
 * <tr><td>Fspec</td><td>all knobs on</td><td>full normative spec (same as Round-17 spec)</td></tr>
 * </table>
 *
 * <h3>Metrics per variant per plane</h3>
 * <ul>
 *   <li>median, mode, std‑dev, ±1024 fraction, saturation fraction</li>
 *   <li>symbolsDecoded / (w × h) — completeness indicator; near 1.0 means no early exhaustion</li>
 *   <li>consumedBytes / payloadBytes — byte‑budget usage</li>
 *   <li>firstExhaustX / firstExhaustY — pixel coordinate of first exhaustion (−1 if clean)</li>
 *   <li>escapeHits, unaryOverflows, streamExhausted</li>
 * </ul>
 *
 * <h3>Decision rules</h3>
 * <ul>
 *   <li>A variant <em>fixes entropy lock</em> if its saturation fraction drops from ≈ 1.0
 *       (baseline) to &lt; 0.05 on the dark/flat research fixtures.</li>
 *   <li>A variant that causes <em>early bitstream exhaustion</em> on lossless fixtures
 *       where baseline does not is a regression — it is mis‑interpreting the bit
 *       alignment for those fixtures.</li>
 * </ul>
 *
 * <p>No production code is modified. Output goes to
 * {@code target/decoder-stage-isolation/report.txt} and
 * {@code target/decoder-stage-isolation/report.tsv}.</p>
 *
 * <p>If no lossless CR3 fixtures are found the test silently passes (fresh checkout).</p>
 */
class DecoderStageIsolationIT {

    private static final Logger log = LoggerFactory.getLogger(DecoderStageIsolationIT.class);

    private static final Path SAMPLES_DIR  = Path.of("src", "test", "resources", "samples");
    private static final Path RESEARCH_DIR = SAMPLES_DIR.resolve("research");
    private static final Path REPORT_DIR   = Path.of("target", "decoder-stage-isolation");

    // ────────────────────────────────────────────────────────────────────────
    // Variant definitions
    // ────────────────────────────────────────────────────────────────────────

    private record Variant(String id, String desc, CrxNormativeLosslessCandidate.Options opts) {}

    private static List<Variant> variants() {
        return List.of(
            variant("F0",    "baseline    ",
                    false, 0, false, false, false),
            variant("F1",    "+unary      ",
                    true,  0, false, false, false),
            variant("F2",    "+kInit      ",
                    false, 1, false, false, false),
            variant("F3",    "+resetRow   ",
                    false, 0, true,  false, false),
            variant("F4",    "+signBit    ",
                    false, 0, false, true,  false),
            variant("F5",    "+escape15   ",
                    false, 0, false, false, true),
            variant("Fspec", "all-spec    ",
                    true,  1, true,  true,  true)
        );
    }

    private static Variant variant(String id, String desc,
            boolean unaryOnes, int kInit, boolean resetRow,
            boolean signBit, boolean escape) {
        var o = new CrxNormativeLosslessCandidate.Options();
        o.unaryOnesUntilZero = unaryOnes;
        o.kInit              = kInit;
        o.resetKPerRow       = resetRow;
        o.signBitMapping     = signBit;
        o.escapeAtUnary15    = escape;
        o.firstPixelSeed     = 0;
        return new Variant(id, desc, o);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test entry point
    // ────────────────────────────────────────────────────────────────────────

    @Test
    void isolateDecoderStages() throws Exception {
        List<File> fixtures = collectFixtures();
        if (fixtures.isEmpty()) {
            log.info("No CR3 fixtures found — skipping decoder stage isolation");
            return;
        }

        Files.createDirectories(REPORT_DIR);

        List<Variant> vs = variants();
        StringBuilder txt = new StringBuilder();
        txt.append("Decoder Stage Isolation Report (Phase F / Update t)\n");
        txt.append("=====================================================\n");
        txt.append("Generated: ").append(Instant.now()).append("\n\n");
        txt.append("Variants:\n");
        for (Variant v : vs) {
            txt.append(String.format("  %-6s = %s  (%s)%n",
                    v.id(), v.desc(), v.opts()));
        }
        txt.append("\nDecision rule: a variant FIXES lock if satFraction drops below 0.05 on dark/flat fixtures.\n\n");

        // TSV header
        StringBuilder tsv = new StringBuilder();
        tsv.append("fixture\tscene\tvariant\tplane\tpayloadB\tmedian\tmode\tstd\t"
                + "pm1024frac\tsatFrac\tsymFrac\tconsumedB\texhaustX\texhaustY\t"
                + "escHits\tunaryOF\texhausted\n");

        for (File f : fixtures) {
            List<SubbandExtractor.SubbandRef> subs;
            try {
                subs = SubbandExtractor.list(f);
            } catch (Exception e) {
                log.warn("  ↷ {} unreadable: {}", f.getName(), e.getMessage());
                continue;
            }
            // Only lossless planes (waveletLevels == 0, subbandIndex == 0)
            List<SubbandExtractor.SubbandRef> losslessPlanes = subs.stream()
                    .filter(s -> s.waveletLevels() == 0 && s.subbandIndex() == 0)
                    .toList();
            if (losslessPlanes.isEmpty()) {
                log.info("  ↷ {} has no lossless subbands (C-RAW or empty)", f.getName());
                continue;
            }

            String scene = sceneTag(f.getName());
            txt.append("─── ").append(f.getName())
               .append(" (").append(scene).append(") ───\n");
            txt.append(String.format("    %d lossless plane(s)%n", losslessPlanes.size()));

            for (SubbandExtractor.SubbandRef ref : losslessPlanes) {
                byte[] payload;
                try {
                    payload = SubbandExtractor.read(f, ref);
                } catch (IOException e) {
                    log.warn("    ↷ plane {}/{} read error: {}", ref.tileIndex(), ref.planeIndex(), e);
                    continue;
                }

                int w  = ref.planeWidth();
                int h  = ref.planeHeight();
                int bd = ref.bitDepth();
                int total = w * h;

                txt.append(String.format("    plane tile=%d p=%d  %dx%d bd=%d  payload=%d B%n",
                        ref.tileIndex(), ref.planeIndex(), w, h, bd, payload.length));

                txt.append(String.format("    %-6s  %-12s  %6s %6s %8s %6s %6s %6s %8s %6s  exhaust(%5s,%5s)%n",
                        "var", "description", "median", "mode", "std", "±1024", "sat", "sym",
                        "consumed", "esc", "x", "y"));

                for (Variant v : vs) {
                    long t0 = System.nanoTime();
                    CrxNormativeLosslessCandidate.Result res =
                            CrxNormativeLosslessCandidate.decodePlane(payload, w, h, bd, v.opts());
                    long dtMs = (System.nanoTime() - t0) / 1_000_000;

                    Stats s = stats(res.pixels, bd);
                    double symFrac     = (double) res.symbolsDecoded / total;
                    double consumedFrac = payload.length > 0 ? (double) res.consumedBytes / payload.length : 0.0;
                    String exhaustStr  = res.streamExhausted ? "Y" : "N";

                    txt.append(String.format(
                            "    %-6s  %-12s  %6d %6d %8.1f %6.3f %6.3f %6.3f  %4d/%4dB%6s  "
                                    + "(%5s,%5s)  esc=%d uOF=%d ex=%s  [%d ms]%n",
                            v.id(), v.desc(), s.median, s.mode, s.std,
                            s.fracWithin1024, s.fracSaturated, symFrac,
                            res.consumedBytes, payload.length,
                            String.format("(%.2f)", consumedFrac),
                            res.firstExhaustX >= 0 ? String.valueOf(res.firstExhaustX) : "-",
                            res.firstExhaustY >= 0 ? String.valueOf(res.firstExhaustY) : "-",
                            res.escapeHits, res.unaryOverflows, exhaustStr, dtMs));

                    tsv.append(String.format(
                            "%s\t%s\t%s\t%d\t%d\t%d\t%d\t%.1f\t%.4f\t%.4f\t%.4f\t%d\t%d\t%d\t%d\t%d\t%s%n",
                            f.getName(), scene, v.id(), ref.planeIndex(), payload.length,
                            s.median, s.mode, s.std,
                            s.fracWithin1024, s.fracSaturated, symFrac,
                            res.consumedBytes,
                            res.firstExhaustX, res.firstExhaustY,
                            res.escapeHits, res.unaryOverflows, exhaustStr));
                }
                txt.append('\n');
            }
            txt.append('\n');
        }

        appendInterpretationGuide(txt);

        String reportTxt = txt.toString();
        System.out.println(reportTxt);
        Files.writeString(REPORT_DIR.resolve("report.txt"), reportTxt);
        Files.writeString(REPORT_DIR.resolve("report.tsv"), tsv.toString());

        // Structural invariants only — research is in flight.
        assertThat(REPORT_DIR.resolve("report.txt")).exists();
        assertThat(REPORT_DIR.resolve("report.tsv")).exists();

        log.info("Decoder stage isolation report written to {}",
                REPORT_DIR.toAbsolutePath());
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    private static List<File> collectFixtures() {
        List<File> out = new ArrayList<>();
        // Standard committed samples
        String[] standard = {
            "src/test/resources/samples/sample.cr3",
            "src/test/resources/samples/sample-iso-high.cr3",
            "src/test/resources/samples/sample-tele.cr3",
            "src/test/resources/samples/sample-wide.cr3",
            "src/test/resources/samples/sample-wb-tungsten.cr3",
            "src/test/resources/samples/eosr-iso-low.cr3",
            "src/test/resources/samples/eosr-iso-mid.cr3",
            "src/test/resources/samples/eosr-iso-high.cr3",
        };
        for (String p : standard) {
            File f = new File(p);
            if (f.exists()) out.add(f);
        }
        // Operator-captured research fixtures
        if (Files.isDirectory(RESEARCH_DIR)) {
            try (var stream = Files.list(RESEARCH_DIR)) {
                stream
                        .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".cr3"))
                        .sorted()
                        .map(Path::toFile)
                        .forEach(out::add);
            } catch (IOException e) {
                log.warn("Could not list research fixtures: {}", e.getMessage());
            }
        }
        return out;
    }

    private static String sceneTag(String name) {
        String n = name.toLowerCase();
        if (n.contains("-dark-") || n.startsWith("dark-"))  return "dark";
        if (n.contains("-flat-") || n.startsWith("flat-"))  return "flat";
        if (n.contains("-asis-") || n.startsWith("asis-"))  return "asis";
        if (n.contains("-detail-"))                         return "detail";
        if (n.contains("eosr"))                             return "eosr";
        return "other";
    }

    private static void appendInterpretationGuide(StringBuilder sb) {
        sb.append("=== Interpretation guide ===\n");
        sb.append("\n");
        sb.append("Entropy-lock-loss signature: mode ≈ 16320, satFraction ≈ 1.0, symFrac ≪ 1.0\n");
        sb.append("\n");
        sb.append("F0  (baseline) reproduces production JPEG-LS semantics through the candidate;\n");
        sb.append("    it should show the lock-loss signature on dark/flat fixtures.\n");
        sb.append("\n");
        sb.append("A variant FIXES lock-loss if:\n");
        sb.append("  1. satFraction < 0.05 on dark/flat fixtures where baseline ≈ 1.0\n");
        sb.append("  2. symFrac ≈ 1.0 (stream not exhausted early)\n");
        sb.append("  3. mode is near the OB pedestal (512–2048 LSB for dark; mid-grey for flat)\n");
        sb.append("\n");
        sb.append("A variant REGRESSES if:\n");
        sb.append("  4. symFrac < 1.0 on fixtures where F0 gives symFrac ≈ 1.0\n");
        sb.append("     (mis-aligns the bitstream for fixtures that baseline decodes cleanly)\n");
        sb.append("\n");
        sb.append("The variant with the lowest satFraction on dark/flat + highest symFrac\n");
        sb.append("on all fixtures is the best single-change candidate for production promotion.\n");
        sb.append("\n");
        sb.append("If only Fspec (all knobs) wins, further ablation within Fspec is needed\n");
        sb.append("to identify required interaction effects.\n");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Statistics
    // ────────────────────────────────────────────────────────────────────────

    private record Stats(int median, int mode, double std,
                         double fracWithin1024, double fracSaturated) {}

    private static Stats stats(int[] pixels, int bd) {
        if (pixels.length == 0) return new Stats(0, 0, 0.0, 0.0, 0.0);
        int maxVal = (1 << bd) - 1;
        int[] hist = new int[maxVal + 1];
        long sat = 0;
        long sum = 0;
        for (int v : pixels) {
            int c = Math.max(0, Math.min(maxVal, v));
            hist[c]++;
            sum += c;
            if (c == maxVal) sat++;
        }
        double mean = (double) sum / pixels.length;
        double var = 0;
        for (int v : pixels) {
            int c = Math.max(0, Math.min(maxVal, v));
            double d = c - mean;
            var += d * d;
        }
        double std = Math.sqrt(var / pixels.length);

        long target = ((long) pixels.length + 1) / 2;
        long acc = 0;
        int median = 0;
        boolean medianSet = false;
        int mode = 0;
        int modeCount = -1;
        for (int v = 0; v <= maxVal; v++) {
            if (hist[v] > modeCount) { modeCount = hist[v]; mode = v; }
            acc += hist[v];
            if (!medianSet && acc >= target) { median = v; medianSet = true; }
        }

        int lo = Math.max(0, mode - 1024);
        int hi = Math.min(maxVal, mode + 1024);
        long within = 0;
        for (int v = lo; v <= hi; v++) within += hist[v];

        return new Stats(median, mode, std,
                (double) within / pixels.length,
                (double) sat / pixels.length);
    }
}
