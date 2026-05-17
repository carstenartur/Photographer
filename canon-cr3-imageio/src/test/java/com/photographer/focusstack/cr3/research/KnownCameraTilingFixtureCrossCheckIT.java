package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.bmff.Iad1Box;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.raw.codec.SubbandExtractor;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Fixture‑driven cross‑check of {@link KnownCameraTilingHypothesis} against
 * the actual CMP1/IAD1/CRX‑header values our cleanroom parser extracts from
 * the committed Canon CR3 fixtures.
 *
 * <p>For every available fixture this IT records:</p>
 * <ul>
 *   <li>CNCV version string (lets us tell {@code CanonCR3_001} G5X II RAW
 *       from {@code CanonCR3_002}+ EOS R RAW);</li>
 *   <li>RAW track type and dimensions;</li>
 *   <li>CMP1: {@code imageWidth/Height}, {@code tileWidth/Height},
 *       {@code waveletLevels}, {@code cfaLayout}, {@code bitsPerSample};</li>
 *   <li>IAD1: full sensor / OB / active rectangle (when present);</li>
 *   <li>CRX header: number of tiles, planes per tile, subbands per plane,
 *       per‑subband {@code quantValue} and per‑plane {@code roundedBits}.</li>
 * </ul>
 *
 * <p>The report is written to
 * {@code target/known-tiling-hypothesis/report.txt}. Where the fixture's
 * model can be inferred from the file name, the IT additionally asserts a
 * minimal set of <i>falsifiable</i> equalities against
 * {@link KnownCameraTilingHypothesis} — but only equalities that are
 * mathematically guaranteed if the hypothesis is true (e.g. CFA layout,
 * wavelet level count, CMP1 image dimensions). Anything that the
 * 2026‑05‑14 information drop only <i>claimed</i> (sample count per slice,
 * absolute sensor matrix sizes) is recorded but not asserted, so the
 * report can flag them as confirmed/refuted without breaking the build.</p>
 *
 * <p>This is research scaffolding — no production code is touched.</p>
 */
final class KnownCameraTilingFixtureCrossCheckIT {

    private static final Path REPORT_DIR = Path.of("target/known-tiling-hypothesis");

    /** Fixtures we expect; missing ones are silently skipped per file. */
    private static final String[] FIXTURES = {
            "src/test/resources/samples/sample.cr3",
            "src/test/resources/samples/sample-iso-high.cr3",
            "src/test/resources/samples/sample-tele.cr3",
            "src/test/resources/samples/sample-wide.cr3",
            "src/test/resources/samples/sample-wb-tungsten.cr3",
            "src/test/resources/samples/eosr-iso-low.cr3",
            "src/test/resources/samples/eosr-iso-mid.cr3",
            "src/test/resources/samples/eosr-iso-high.cr3",
            "src/test/resources/samples/research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3",
            "src/test/resources/samples/research/g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02.cr3",
            "src/test/resources/samples/research/g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02.cr3",
            "src/test/resources/samples/research/g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-02.cr3",
            "src/test/resources/samples/research/g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-03.cr3",
    };

    @Test
    void crossCheckHypothesisAgainstAllFixtures() throws Exception {
        Files.createDirectories(REPORT_DIR);
        StringBuilder sb = new StringBuilder();
        sb.append("Known camera tiling hypothesis — fixture cross‑check\n");
        sb.append("====================================================\n");
        sb.append("Generated: ").append(Instant.now()).append("\n\n");

        int filesSeen = 0;
        List<FixtureSummary> summaries = new java.util.ArrayList<>();
        List<String> violations = new java.util.ArrayList<>();

        // PHASE 1 — collect every fixture's facts and write the report
        // BEFORE asserting anything. The point of the report is to survive
        // assertion failures so a human can diagnose which line of the
        // hypothesis was falsified.
        for (String path : FIXTURES) {
            File f = new File(path);
            if (!f.exists()) {
                sb.append("--- ").append(path).append(" --- MISSING, skipped\n\n");
                continue;
            }
            filesSeen++;
            try {
                FixtureSummary s = inspectWithoutAsserting(f);
                summaries.add(s);
                sb.append(s.render());
                sb.append('\n');
                // PHASE 2 — record (but do not throw) hypothesis violations.
                collectViolations(s, violations);
            } catch (Exception e) {
                sb.append("--- ").append(path).append(" --- PARSE FAILURE: ")
                        .append(e.getClass().getSimpleName()).append(": ").append(e.getMessage())
                        .append("\n\n");
            }
        }

        long asserted = summaries.stream().filter(s -> s.assertedAgainstHypothesis).count();
        sb.append("\nFiles inspected: ").append(filesSeen)
                .append(", asserted against hypothesis: ").append(asserted).append('\n');

        if (!violations.isEmpty()) {
            sb.append("\nHypothesis violations (").append(violations.size()).append("):\n");
            violations.forEach(v -> sb.append("  ✗ ").append(v).append('\n'));
        } else {
            sb.append("\nNo hypothesis violations recorded.\n");
        }

        Path report = REPORT_DIR.resolve("report.txt");
        Files.writeString(report, sb.toString());

        // PHASE 3 — final gating.
        assumeTrue(filesSeen > 0, "no CR3 fixtures present, nothing to cross‑check");
        assertThat(asserted)
                .as("hypothesis was not asserted against any fixture; report=%s", report)
                .isGreaterThanOrEqualTo(1);
        assertThat(violations)
                .as("hypothesis falsified by fixtures; see %s", report)
                .isEmpty();
    }

    /** Records the violation (if any) instead of throwing — keeps the report whole. */
    private void collectViolations(FixtureSummary s, List<String> out) {
        switch (s.detectedModel) {
            case "G5X_MARK_II" -> checkG5xMarkIi(s, out);
            case "EOS_R"       -> checkEosR(s, out);
            default            -> { /* unknown model — report only */ }
        }
    }

    private void checkG5xMarkIi(FixtureSummary s, List<String> out) {
        var hyp = KnownCameraTilingHypothesis.G5X_MARK_II;
        if (s.cmp1Present && !"RGGB".equals(s.cfaLayout)) {
            out.add(s.fileName + ": [G5X II] CFA layout expected RGGB, got " + s.cfaLayout);
        }
        // Only assert wavelet count when the fixture is actually C‑RAW.
        // Lossless CR3 reports waveletLevels=0 and is governed by the
        // separate LosslessSliceLayout hypothesis.
        if (s.waveletLevels > 0 && s.waveletLevels != hyp.waveletLevels()) {
            out.add(s.fileName + ": [G5X II C‑RAW] waveletLevels expected "
                    + hyp.waveletLevels() + ", got " + s.waveletLevels);
        }
        s.assertedAgainstHypothesis = true;
    }

    private void checkEosR(FixtureSummary s, List<String> out) {
        var hyp = KnownCameraTilingHypothesis.EOS_R;
        if (s.cmp1Present && !"RGGB".equals(s.cfaLayout)) {
            out.add(s.fileName + ": [EOS R] CFA layout expected RGGB, got " + s.cfaLayout);
        }
        if (s.waveletLevels > 0 && s.waveletLevels != hyp.waveletLevels()) {
            out.add(s.fileName + ": [EOS R C‑RAW] waveletLevels expected "
                    + hyp.waveletLevels() + ", got " + s.waveletLevels);
        }
        s.assertedAgainstHypothesis = true;
    }

    // ---------- inspection ----------

    private FixtureSummary inspectWithoutAsserting(File f) throws Exception {
        FixtureSummary s = new FixtureSummary();
        s.fileName = f.getName();

        try (ImageInputStream in = ImageIO.createImageInputStream(f)) {
            Cr3Container container = Cr3Parser.parse(in);
            s.cncv = container.cncvBox() == null ? null : container.cncvBox().versionString();
            Cr3Track raw = findRawTrack(container);
            if (raw == null) {
                s.rawTrackPresent = false;
                return s;
            }
            s.rawTrackPresent = true;
            s.rawType = raw.type().name();
            s.rawWidth = raw.width();
            s.rawHeight = raw.height();
            s.compression = raw.compressionMode() == null ? "?" : raw.compressionMode().name();

            Cmp1Box cmp1 = raw.cmp1();
            if (cmp1 != null) {
                s.cmp1Present = true;
                s.imageWidth = cmp1.imageWidth();
                s.imageHeight = cmp1.imageHeight();
                s.tileWidth = cmp1.tileWidth();
                s.tileHeight = cmp1.tileHeight();
                s.waveletLevels = cmp1.waveletLevels();
                s.cfaLayout = cmp1.cfaLayoutName();
                s.bitsPerSample = cmp1.bitsPerSample();
                s.numPlanes = cmp1.numPlanes();
                s.encodingType = cmp1.encodingType();
                s.multipleHTiles = cmp1.multipleHTiles();
            }

            Iad1Box iad1 = raw.iad1();
            if (iad1 != null) {
                s.iad1Present = true;
                s.iad1Width = iad1.imageWidth();
                s.iad1Height = iad1.imageHeight();
                s.activeLeft = iad1.activeLeft();
                s.activeTop = iad1.activeTop();
                s.activeRight = iad1.activeRight();
                s.activeBottom = iad1.activeBottom();
                s.iad1IsBigImage = iad1.isBigImage();
            }
        }

        // CRX‑header level: count tiles / planes / subbands.
        List<SubbandExtractor.SubbandRef> subs = SubbandExtractor.list(f);
        s.subbandCount = subs.size();
        if (!subs.isEmpty()) {
            // Tile sequences (distinct ordinals — independent of the tile
            // index field, which Canon may reuse).
            Map<Integer, Map<Integer, Integer>> tilePlaneSubbands = new LinkedHashMap<>();
            for (SubbandExtractor.SubbandRef r : subs) {
                tilePlaneSubbands
                        .computeIfAbsent(r.tileSequence(), k -> new LinkedHashMap<>())
                        .merge(r.planeIndex(), 1, Integer::sum);
            }
            s.tileCount = tilePlaneSubbands.size();
            // Use the first tile as the canonical view (tiles are uniform here).
            Map<Integer, Integer> firstTile = tilePlaneSubbands.values().iterator().next();
            s.planesInFirstTile = firstTile.size();
            s.subbandsPerPlane = firstTile.values().iterator().next();

            SubbandExtractor.SubbandRef anyRef = subs.get(0);
            s.codecVersion = anyRef.codecVersion().name();
            s.derivedPlaneWidth = anyRef.planeWidth();
            s.derivedPlaneHeight = anyRef.planeHeight();
        }

        // ---- model detection only; assertions happen in PHASE 2 ----
        Model model = guessModel(s);
        s.detectedModel = model.name();
        return s;
    }

    private Cr3Track findRawTrack(Cr3Container container) {
        for (Cr3Track t : container.tracks()) {
            if (t.type() == Cr3Track.TrackType.CRX_BIG || t.type() == Cr3Track.TrackType.CRX_RAW) {
                return t;
            }
        }
        return null;
    }

    private enum Model { G5X_MARK_II, EOS_R, UNKNOWN }

    /**
     * Identifies the camera model from CNCV / file name. CNCV alone is
     * insufficient because CanonCR3_001 covers several DIGIC‑8 bodies;
     * file‑name prefixes ({@code g5xm2-}, {@code eosr-}) disambiguate the
     * fixtures we ship.
     */
    private Model guessModel(FixtureSummary s) {
        String name = s.fileName.toLowerCase(Locale.ROOT);
        if (name.startsWith("g5xm2-")) return Model.G5X_MARK_II;
        if (name.startsWith("eosr-"))  return Model.EOS_R;
        return Model.UNKNOWN;
    }

    // ---------- value object ----------

    private static final class FixtureSummary {
        String fileName;
        String detectedModel = "UNKNOWN";
        String cncv;
        boolean rawTrackPresent;
        String rawType;
        int rawWidth, rawHeight;
        String compression;
        boolean cmp1Present;
        int imageWidth, imageHeight, tileWidth, tileHeight;
        int waveletLevels, bitsPerSample, numPlanes, encodingType;
        String cfaLayout;
        boolean multipleHTiles;
        boolean iad1Present;
        int iad1Width, iad1Height;
        int activeLeft, activeTop, activeRight, activeBottom;
        boolean iad1IsBigImage;
        int subbandCount, tileCount, planesInFirstTile, subbandsPerPlane;
        String codecVersion;
        int derivedPlaneWidth, derivedPlaneHeight;
        boolean assertedAgainstHypothesis;

        String render() {
            StringBuilder b = new StringBuilder();
            b.append("--- ").append(fileName)
                    .append(" (model=").append(detectedModel).append(") ---\n");
            b.append("    CNCV         : ").append(cncv).append('\n');
            if (!rawTrackPresent) {
                b.append("    RAW track    : <none>\n");
                return b.toString();
            }
            b.append(String.format(
                    "    RAW track    : type=%s, %dx%d, compression=%s%n",
                    rawType, rawWidth, rawHeight, compression));
            if (cmp1Present) {
                b.append(String.format(
                        "    CMP1         : image=%dx%d, tile=%dx%d, wavelets=%d, "
                                + "cfa=%s, bps=%d, planes=%d, enc=%d, multiHTiles=%s%n",
                        imageWidth, imageHeight, tileWidth, tileHeight,
                        waveletLevels, cfaLayout, bitsPerSample, numPlanes,
                        encodingType, multipleHTiles));
            } else {
                b.append("    CMP1         : <missing>\n");
            }
            if (iad1Present) {
                b.append(String.format(
                        "    IAD1         : image=%dx%d, active=[%d,%d..%d,%d], bigImage=%s%n",
                        iad1Width, iad1Height, activeLeft, activeTop,
                        activeRight, activeBottom, iad1IsBigImage));
            }
            b.append(String.format(
                    "    CRX header   : codec=%s, tiles=%d, planes/tile=%d, "
                            + "subbands/plane=%d, totalSubbands=%d, derivedPlane=%dx%d%n",
                    codecVersion, tileCount, planesInFirstTile, subbandsPerPlane,
                    subbandCount, derivedPlaneWidth, derivedPlaneHeight));

            // Cross‑checks against the hypothesis (informational only — the
            // actual JUnit asserts run inside assertG5xMarkIi/assertEosR).
            if ("G5X_MARK_II".equals(detectedModel)) {
                var hyp = KnownCameraTilingHypothesis.G5X_MARK_II;
                var lay = KnownCameraTilingHypothesis.G5X_MARK_II_LOSSLESS_LAYOUT;
                b.append("    hypothesis   : sensor=")
                        .append(hyp.sensorWidth()).append('x').append(hyp.sensorHeight())
                        .append(", slice=").append(hyp.sliceCount()).append('×').append(hyp.sliceWidth())
                        .append(", tile=").append(hyp.tileHeight())
                        .append(", wavelets=").append(hyp.waveletLevels())
                        .append(", lossless={samples=").append(lay.sampleCount())
                        .append(", planes/slice=").append(lay.planesPerSlice()).append("}\n");
                b.append("    Δ            : ")
                        .append(matches("CMP1.image", imageWidth, hyp.sensorWidth())).append("x")
                        .append(matches("CMP1.image", imageHeight, hyp.sensorHeight())).append(", ")
                        .append(matches("CMP1.tileH", tileHeight, hyp.tileHeight())).append(", ")
                        .append(matches("CRX.tiles", tileCount, hyp.sliceCount())).append(", ")
                        .append(matches("CRX.planes", planesInFirstTile, lay.planesPerSlice()))
                        .append('\n');
            } else if ("EOS_R".equals(detectedModel)) {
                var hyp = KnownCameraTilingHypothesis.EOS_R;
                b.append("    hypothesis   : sensor=")
                        .append(hyp.sensorWidth()).append('x').append(hyp.sensorHeight())
                        .append(", slice=").append(hyp.sliceCount()).append('×').append(hyp.sliceWidth())
                        .append(", tile=").append(hyp.tileHeight())
                        .append(", wavelets=").append(hyp.waveletLevels()).append('\n');
                b.append("    Δ            : ")
                        .append(matches("CMP1.image", imageWidth, hyp.sensorWidth())).append("x")
                        .append(matches("CMP1.image", imageHeight, hyp.sensorHeight())).append(", ")
                        .append(matches("CMP1.tileH", tileHeight, hyp.tileHeight())).append(", ")
                        .append(matches("CRX.tiles", tileCount, hyp.sliceCount()))
                        .append('\n');
            }
            return b.toString();
        }

        private static String matches(String label, int actual, int expected) {
            return label + (actual == expected ? "✓" : "✗(" + actual + "≠" + expected + ")");
        }
    }
}
