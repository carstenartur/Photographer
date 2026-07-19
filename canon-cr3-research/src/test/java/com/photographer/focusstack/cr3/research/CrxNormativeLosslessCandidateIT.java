package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.raw.codec.SubbandExtractor;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round‑17 — Normative Lossless‑Entropy Bake‑off (test scope only).
 *
 * <p>The 2026‑05‑14 normative spec hand‑off enumerates six concrete differences
 * between the production CRX lossless decoder and Canon's documented bitstream:
 * unary direction, K_INIT, per‑row reset, sign mapping, unary‑15 escape, and
 * K clamp. {@link CrxNormativeLosslessCandidate} implements all six knobs as a
 * test‑scope candidate. This IT runs that candidate against every lossless
 * fixture's first plane and records, per fixture and per plane:</p>
 *
 * <ul>
 *   <li>median, mode, std‑dev, and {@code ±1024} fraction of the decoded plane;</li>
 *   <li>fraction of pixels saturated at {@code (1 << bd) - 1} (the
 *       entropy‑lock‑loss signature: production wedges at 16320);</li>
 *   <li>how many escape paths and unary overflows fired;</li>
 *   <li>how many symbols decoded before stream exhaustion (production exhausts
 *       early on dark/flat fixtures).</li>
 * </ul>
 *
 * <p>For each fixture the IT runs three configurations:</p>
 * <ol>
 *   <li><b>baseline</b> — production semantics (zigzag, unary 0s→1, no escape,
 *       K_INIT=0, no row reset). This reproduces the current
 *       {@code CrxLosslessRawDecoder} behaviour through the candidate, so the
 *       IT can prove the candidate matches production before the spec knobs
 *       are flipped.</li>
 *   <li><b>spec</b> — full normative spec: unary 1s→0, K_INIT=1, row reset,
 *       sign‑bit mapping, escape at unary‑15.</li>
 *   <li><b>spec − escape</b> — same as spec but with the unary‑15 escape
 *       disabled, to isolate which of the spec changes carries the win.</li>
 * </ol>
 *
 * <p>No production code is touched. Output:
 * {@code target/normative-lossless-bakeoff/report.txt}.</p>
 */
class CrxNormativeLosslessCandidateIT {

    private static final Path REPORT_DIR = Path.of("target/normative-lossless-bakeoff");

    /** Lossless fixtures we expect to be present in the repo. */
    private static final String[] FIXTURES = {
            "src/test/resources/samples/sample.cr3",
            "src/test/resources/samples/sample-iso-high.cr3",
            "src/test/resources/samples/sample-tele.cr3",
            "src/test/resources/samples/sample-wide.cr3",
            "src/test/resources/samples/sample-wb-tungsten.cr3",
            "src/test/resources/samples/eosr-iso-low.cr3",
            "src/test/resources/samples/eosr-iso-mid.cr3",
            "src/test/resources/samples/eosr-iso-high.cr3",
            // dark / flat / asis fixtures (committed under research/) are great
            // diagnostics for entropy lock-loss; if they are missing the IT
            // simply skips them.
            "src/test/resources/samples/research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3",
            "src/test/resources/samples/research/g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02.cr3",
            "src/test/resources/samples/research/g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02.cr3",
    };

    @Test
    void bakeOffNormativeLossless() throws Exception {
        Files.createDirectories(REPORT_DIR);
        StringBuilder sb = new StringBuilder();
        sb.append("CRX Normative Lossless‑Entropy Bake‑off (Round 17)\n");
        sb.append("===================================================\n");
        sb.append("Generated: ").append(Instant.now()).append("\n\n");
        sb.append("Configurations:\n");
        sb.append("  baseline   = production semantics (zigzag, unary 0s→1, no escape, K_INIT=0)\n");
        sb.append("  spec       = normative (unary 1s→0, K_INIT=1, row reset, sign‑bit, escape@15)\n");
        sb.append("  spec−esc   = spec with the unary‑15 escape disabled\n\n");

        var baseline = baselineOptions();
        var spec     = specOptions();
        var specNoEsc = specOptions();
        specNoEsc.escapeAtUnary15 = false;

        for (String path : FIXTURES) {
            File f = new File(path);
            if (!f.exists()) {
                sb.append("--- ").append(f.getName()).append(" --- SKIPPED (missing)\n\n");
                continue;
            }
            List<SubbandExtractor.SubbandRef> subs = SubbandExtractor.list(f);
            if (subs.isEmpty()) {
                sb.append("--- ").append(f.getName()).append(" --- SKIPPED (no subbands)\n\n");
                continue;
            }
            // Lossless = 1 subband per plane. Take the first plane of the first tile.
            SubbandExtractor.SubbandRef ref = subs.stream()
                    .filter(s -> s.subbandIndex() == 0)
                    .findFirst().orElse(subs.get(0));
            if (ref.waveletLevels() != 0) {
                sb.append("--- ").append(f.getName())
                  .append(" --- SKIPPED (waveletLevels=").append(ref.waveletLevels())
                  .append(", not lossless)\n\n");
                continue;
            }
            byte[] payload = SubbandExtractor.read(f, ref);
            int w = ref.planeWidth();
            int h = ref.planeHeight();
            int bd = ref.bitDepth();

            sb.append("--- ").append(f.getName()).append(" ---\n");
            sb.append(String.format("    plane %dx%d, bd=%d, payload=%d bytes%n",
                    w, h, bd, payload.length));

            sb.append(report(payload, w, h, bd, "baseline ", baseline));
            sb.append(report(payload, w, h, bd, "spec     ", spec));
            sb.append(report(payload, w, h, bd, "spec−esc ", specNoEsc));
            sb.append('\n');
        }

        sb.append("\n=== Interpretation guide ===\n");
        sb.append("  • Production wedges at 16320 on dark/flat fixtures (entropy lock‑loss)\n");
        sb.append("    → success criterion #1: spec column's saturated‑fraction ≪ baseline.\n");
        sb.append("  • Production exhausts the bitstream early on lossless planes\n");
        sb.append("    → success criterion #2: spec column's symbolsDecoded / (w*h) ≈ 1.0.\n");
        sb.append("  • Production drifts in std‑dev as K mis‑adapts\n");
        sb.append("    → success criterion #3: spec column's std‑dev within 1.5× of expected\n");
        sb.append("      shot noise (~50 LSB at OB pedestal, ~1500 LSB at mid‑grey).\n\n");
        sb.append("If spec wins on (#1, #2, #3) across multiple fixtures, the next step is\n");
        sb.append("to gate the production change on the matching ITs (DarkFixtureLosslessDecoderIT\n");
        sb.append("and FlatFixtureLosslessDecoderIT), then patch CrxLosslessRawDecoder.\n");

        String report = sb.toString();
        System.out.println(report);
        Files.writeString(REPORT_DIR.resolve("report.txt"), report);
        assertThat(REPORT_DIR.resolve("report.txt")).exists();
    }

    private static CrxNormativeLosslessCandidate.Options baselineOptions() {
        var o = new CrxNormativeLosslessCandidate.Options();
        o.kInit = 0;
        o.resetKPerRow = false;
        o.unaryOnesUntilZero = false;
        o.signBitMapping = false;     // zigzag
        o.escapeAtUnary15 = false;
        o.firstPixelSeed = 0;
        return o;
    }

    private static CrxNormativeLosslessCandidate.Options specOptions() {
        var o = new CrxNormativeLosslessCandidate.Options();
        o.kInit = 1;
        o.resetKPerRow = true;
        o.unaryOnesUntilZero = true;
        o.signBitMapping = true;
        o.escapeAtUnary15 = true;
        o.firstPixelSeed = 0;
        return o;
    }

    private static String report(byte[] payload, int w, int h, int bd,
                                 String label,
                                 CrxNormativeLosslessCandidate.Options opt) {
        long t0 = System.nanoTime();
        var res = CrxNormativeLosslessCandidate.decodePlane(payload, w, h, bd, opt);
        long dtMs = (System.nanoTime() - t0) / 1_000_000;
        Stats s = stats(res.pixels, bd);
        double symFrac = (double) res.symbolsDecoded / (double) (w * h);
        return String.format(
                "    %s [%4d ms] median=%5d mode=%5d std=%7.1f ±1024=%4.2f sat=%.3f"
                + " sym=%.3f esc=%d unaryOf=%d exhausted=%s%n",
                label, dtMs, s.median, s.mode, s.std, s.fracWithin1024,
                s.fracSaturated, symFrac, res.escapeHits, res.unaryOverflows,
                res.streamExhausted);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Stats
    // ──────────────────────────────────────────────────────────────────────

    private record Stats(int median, int mode, double std,
                         double fracWithin1024, double fracSaturated) {}

    private static Stats stats(int[] pixels, int bd) {
        if (pixels.length == 0) return new Stats(0, 0, 0, 0, 0);
        int max = (1 << bd) - 1;
        int[] hist = new int[max + 1];
        long sat = 0;
        long sum = 0;
        for (int v : pixels) {
            if (v < 0) v = 0; else if (v > max) v = max;
            hist[v]++;
            sum += v;
            if (v == max) sat++;
        }
        double mean = (double) sum / pixels.length;
        double var = 0;
        for (int v : pixels) {
            int c = v < 0 ? 0 : (v > max ? max : v);
            double d = c - mean;
            var += d * d;
        }
        double std = Math.sqrt(var / pixels.length);

        // Median + mode from histogram.
        long half = pixels.length / 2L;
        long acc = 0;
        int median = 0;
        int mode = 0, modeCount = -1;
        for (int v = 0; v <= max; v++) {
            int c = hist[v];
            if (c > modeCount) { modeCount = c; mode = v; }
            acc += c;
            if (acc >= half && median == 0) median = v;
        }

        // ±1024 fraction around the mode.
        int lo = Math.max(0, mode - 1024);
        int hi = Math.min(max, mode + 1024);
        long within = 0;
        for (int v = lo; v <= hi; v++) within += hist[v];
        double fw = (double) within / pixels.length;

        return new Stats(median, mode, std, fw, (double) sat / pixels.length);
    }

    // Used as a fail-safe so test compilers do not flag unused imports.
    @SuppressWarnings("unused")
    private static final List<int[]> KEEP = new ArrayList<>(Arrays.asList(new int[0]));
}
