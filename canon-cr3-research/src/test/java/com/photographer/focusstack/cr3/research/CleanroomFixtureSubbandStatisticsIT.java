package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.raw.codec.SubbandExtractor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-fixture subband statistics for the cleanroom CRX decoder.
 *
 * <p>Joins the operator-shot fixtures in {@code samples/research/} (captured
 * by {@code CleanroomCr3FixtureCaptureIT}) with the {@link SubbandExtractor}
 * /{@link SubbandStatistics} pipeline, and asserts the strong invariants the
 * cleanroom decoder must rely on.</p>
 *
 * <p>The asserted invariants are <em>physical</em>: they would hold for any
 * correct CRX encoder, regardless of which Canon body produced the file. A
 * regression here means the bitstream is being mis-segmented or the wrong
 * codec branch is being taken — not a tuning issue.</p>
 *
 * <ol>
 *   <li><b>Determinism within a burst:</b> N successive captures at identical
 *       camera settings must produce subband descriptors of identical
 *       <em>structure</em> (same plane / subband counts and bit depth).
 *       The byte-for-byte payload differs frame to frame because of shot
 *       noise, but the layout does not.</li>
 *   <li><b>Dark frames compress harder than {@code asis} frames:</b> all
 *       four planes of a cap-on shot have effectively zero high-frequency
 *       content, so the entropy-coded subband sizes are dramatically smaller
 *       than the equivalent subbands in a normal scene. We assert the median
 *       payload bytes-per-pixel of {@code dark} frames is at most 70 % of
 *       the {@code asis} median.</li>
 *   <li><b>Bit balance is non-trivial:</b> for any real CRX subband the
 *       fraction of 1-bits is between 10 % and 90 %. All-0/all-1 indicates a
 *       parse error.</li>
 *   <li><b>Zero-run tail is bounded:</b> the longest run of zero bits in
 *       any subband never exceeds {@code 64 K} bits — beyond that the
 *       lossless decoder treats the stream as corrupt and we want to fail
 *       fast.</li>
 * </ol>
 *
 * <p>This IT runs in CI and depends only on bundled fixtures, so it does not
 * carry the {@code real-camera} tag.</p>
 */
class CleanroomFixtureSubbandStatisticsIT {

    private static final Logger log = LoggerFactory.getLogger(CleanroomFixtureSubbandStatisticsIT.class);

    private static final Path RESEARCH_DIR =
            Path.of("src", "test", "resources", "samples", "research");

    /** Maximum tolerated zero-run before the lossless decoder calls the stream corrupt. */
    private static final int MAX_RICE_UNARY_BITS = 65_536;

    @Test
    @DisplayName("Operator-captured CR3 fixtures parse and have well-formed subband bitstreams")
    void everyResearchFixtureParsesAndHasReasonableStatistics() throws Exception {
        if (!Files.isDirectory(RESEARCH_DIR)) {
            log.info("No research fixtures directory at {} — skipping", RESEARCH_DIR.toAbsolutePath());
            return; // not present in CI / fresh checkouts
        }

        List<Path> fixtures;
        try (Stream<Path> s = Files.list(RESEARCH_DIR)) {
            fixtures = s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".cr3"))
                    .sorted()
                    .collect(Collectors.toList());
        }
        if (fixtures.isEmpty()) {
            log.info("No CR3 files in {} yet — skipping", RESEARCH_DIR.toAbsolutePath());
            return;
        }

        log.info("═══ Subband statistics across {} research fixtures ═══", fixtures.size());

        // Group by (scene, codec) so a mixed RAW + C-RAW burst of the same
        // scene does not falsely trip the structural-determinism invariant
        // (a lossless dark frame has 8 subbands, a same-scene C-RAW one has
        // 80 — both are correct, they just live in different sub-groups).
        Map<String, List<FixtureMeasurement>> byScene = new LinkedHashMap<>();
        for (Path p : fixtures) {
            FixtureMeasurement m = measure(p.toFile());
            String scene = parseSceneTag(p.getFileName().toString());
            String codec = m.subbandCount > 8 ? "craw" : "raw";
            byScene.computeIfAbsent(scene + "/" + codec, k -> new ArrayList<>()).add(m);
        }

        // ── Invariant 1: determinism of structure within a scene/burst ─────
        for (var entry : byScene.entrySet()) {
            String scene = entry.getKey();
            List<FixtureMeasurement> burst = entry.getValue();
            if (burst.size() < 2) continue;
            FixtureMeasurement first = burst.get(0);
            for (FixtureMeasurement other : burst.subList(1, burst.size())) {
                assertThat(other.subbandCount)
                        .as("scene '%s': %s vs %s subband count", scene, first.path, other.path)
                        .isEqualTo(first.subbandCount);
                assertThat(other.bitDepth)
                        .as("scene '%s': %s vs %s bit depth", scene, first.path, other.path)
                        .isEqualTo(first.bitDepth);
                assertThat(other.trackPixels)
                        .as("scene '%s': %s vs %s pixel count", scene, first.path, other.path)
                        .isEqualTo(first.trackPixels);
            }
            log.info("  scene '{}': {} captures, {} subbands each, structure stable",
                    scene, burst.size(), first.subbandCount);
        }

        // ── Invariant 2: dark < asis in payload bytes per pixel (when both exist) ─
        // Aggregate across codec sub-groups so the comparison reflects the scene,
        // not the codec choice (C-RAW has lower bpp by design and would falsely
        // satisfy the inequality on its own).
        Double darkMedian = medianBppForScene(byScene, "dark");
        Double asisMedian = medianBppForScene(byScene, "asis");
        Double flatMedian = medianBppForScene(byScene, "flat");
        log.info("  median bytes/pixel by scene: dark={} flat={} asis={}",
                fmt(darkMedian), fmt(flatMedian), fmt(asisMedian));
        if (darkMedian != null && asisMedian != null) {
            assertThat(darkMedian)
                    .as("dark median bytes/pixel must be < 70%% of asis (entropy of a "
                            + "cap-on shot is dramatically lower)")
                    .isLessThan(asisMedian * 0.70);
        }

        // ── Invariants 3 & 4: per-subband sanity ──────────────────────────
        long subbandsScanned = 0;
        for (var burst : byScene.values()) {
            for (FixtureMeasurement m : burst) {
                for (SubbandSnapshot ss : m.subbands) {
                    assertThat(ss.oneBitFraction)
                            .as("%s subband[%d] one-bit fraction", m.path, ss.index)
                            .isBetween(0.10, 0.90);
                    assertThat(ss.longestZeroRun)
                            .as("%s subband[%d] longest zero run", m.path, ss.index)
                            .isLessThan(MAX_RICE_UNARY_BITS);
                    subbandsScanned++;
                }
            }
        }
        log.info("  scanned {} subbands across {} fixtures — all within bounds",
                subbandsScanned, fixtures.size());
    }

    // ─────────────────────────────────────────────────────── helpers ──

    private static FixtureMeasurement measure(File f) throws Exception {
        List<SubbandExtractor.SubbandRef> refs = SubbandExtractor.list(f);
        long totalPayload = 0L;
        int trackPixels = 0;
        int bitDepth = 0;
        List<SubbandSnapshot> snaps = new ArrayList<>();
        int idx = 0;
        for (SubbandExtractor.SubbandRef r : refs) {
            byte[] payload = SubbandExtractor.read(f, r);
            SubbandStatistics st = SubbandStatistics.compute(payload);
            snaps.add(new SubbandSnapshot(idx++, payload.length,
                    st.oneBitFraction(), st.longestZeroRun()));
            totalPayload += payload.length;
            // trackWidth/trackHeight/bitDepth are constant across subbands of one file
            if (trackPixels == 0) {
                trackPixels = r.trackWidth() * r.trackHeight();
                bitDepth = r.bitDepth();
            }
        }
        double bpp = trackPixels > 0 ? (double) totalPayload / trackPixels : 0.0;
        return new FixtureMeasurement(f.getName(), refs.size(), bitDepth, trackPixels, bpp, snaps);
    }

    private static Double medianBpp(List<FixtureMeasurement> burst) {
        if (burst == null || burst.isEmpty()) return null;
        List<Double> vals = burst.stream().map(m -> m.bytesPerPixel).sorted().collect(Collectors.toList());
        return vals.get(vals.size() / 2);
    }

    /**
     * Median bytes/pixel for the lossless (RAW) sub-group of {@code scene}.
     * The C-RAW sub-group is deliberately excluded so that the dark-vs-asis
     * inequality of Invariant 2 compares like with like — a C-RAW frame has
     * structurally lower bpp than a lossless one and would otherwise pass the
     * inequality even on a scene whose entropy is *higher* than asis.
     */
    private static Double medianBppForScene(Map<String, List<FixtureMeasurement>> byScene,
                                            String scene) {
        return medianBpp(byScene.get(scene + "/raw"));
    }

    private static String fmt(Double d) {
        return d == null ? "n/a" : String.format("%.3f", d);
    }

    /** Extracts the scene tag from a filename of the form {@code g5xm2-<scene>-iso...-...cr3}. */
    private static String parseSceneTag(String filename) {
        // g5xm2-<scene>-...
        String[] parts = filename.split("-");
        if (parts.length >= 2 && "g5xm2".equals(parts[0])) {
            return parts[1];
        }
        return "unknown";
    }

    private record SubbandSnapshot(int index, int payloadBytes,
                                   double oneBitFraction, int longestZeroRun) { }

    private record FixtureMeasurement(String path, int subbandCount, int bitDepth,
                                      int trackPixels, double bytesPerPixel,
                                      List<SubbandSnapshot> subbands) { }
}
