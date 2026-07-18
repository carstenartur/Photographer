package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import com.photographer.focusstack.cr3.raw.codec.CrxHeader;
import com.photographer.focusstack.cr3.raw.codec.CrxLosslessRawDecoder;
import com.photographer.focusstack.cr3.reference.ReferenceImage;
import com.photographer.focusstack.cr3.reference.ReferenceImageLoader;
import com.photographer.focusstack.cr3.reference.ReferenceMetrics;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Decoder Hypothesis D1 / D2 — targeted reference-TIFF probe.
 *
 * <h3>D1 — DC-offset bias</h3>
 * <p>Hypothesis: the production decoder (PATENT_BRACKET) outputs pixel values
 * that are systematically too low, i.e. mean(decoder) ≪ mean(reference).
 * The expected symptom is that applying a post-hoc DC offset correction
 * (adding mean(reference) − mean(decoder) to every pixel) dramatically
 * reduces MAE on flat / uniformly-lit fixtures.</p>
 *
 * <h3>D2 — Sign mapping comparison</h3>
 * <p>Hypothesis: the negative Pearson correlations (e.g. sample-tele Pearson=−0.42)
 * are caused by a residual sign mapping error.  The production decoder uses
 * PATENT_BRACKET (bit-0 sign-bit) which matches the normative spec; the legacy
 * JPEG_LS path uses zig-zag.  We measure Pearson(reference, decoder) for both
 * rules on every fixture and observe whether either sign convention produces
 * positive correlations where the other does not.</p>
 *
 * <h3>Decision rule</h3>
 * <ul>
 *   <li>D1 confirmed if DC-corrected MAE &lt; 20% of raw MAE on &ge;1 fixture</li>
 *   <li>D2 confirmed if flipping the sign rule changes the Pearson sign for
 *       the negatively-correlated fixtures (sample-tele, sample-wide,
 *       g5xm2-asis)</li>
 * </ul>
 *
 * <p>No production code is changed. Output:
 * {@code canon-cr3-imageio/target/decoder-hypothesis-d1-d2/report.md}</p>
 */
class DecoderHypothesisD1D2IT {

    private static final Path SAMPLES_DIR = Path.of("src/test/resources/samples");
    private static final Path OUT_DIR = Path.of("target/decoder-hypothesis-d1-d2");

    @Test
    void testD1DcOffsetAndD2SignMapping() throws Exception {
        List<Path> fixtures = discoverFixturesWithReferences();
        assumeTrue(!fixtures.isEmpty(), "No lossless CR3 fixtures with reference TIFFs found");

        Files.createDirectories(OUT_DIR);

        StringBuilder md = new StringBuilder();
        md.append("# Decoder Hypothesis D1/D2 — Reference-TIFF Probe\n\n");
        md.append("Generated: ").append(Instant.now()).append("\n\n");
        md.append("## D1: DC-Offset Bias\n\n");
        md.append("Test: does adding `mean(reference) − mean(decoder)` to every decoded pixel ");
        md.append("reduce MAE significantly?\n\n");
        md.append("## D2: Sign-Mapping Comparison\n\n");
        md.append("Test: does JPEG_LS (zig-zag) produce different Pearson sign than ");
        md.append("PATENT_BRACKET (bit-0) on anti-correlated fixtures?\n\n");
        md.append("---\n\n");

        md.append("| Fixture | RefMean | Patent MAE | Patent Pearson | JPEGL MAE | JPEGL Pearson | "
                + "DC-corr MAE | DC-bias | D1? | D2-flip? |\n");
        md.append("|---|---:|---:|---:|---:|---:|---:|---:|:---:|:---:|\n");

        List<String> d1Confirmed = new ArrayList<>();
        List<String> d2Confirmed = new ArrayList<>();

        for (Path fixture : fixtures) {
            String name = SAMPLES_DIR.relativize(fixture).toString().replace('\\', '/');
            Path refTiff = referenceTiffPath(fixture);
            if (!Files.exists(refTiff)) {
                continue;
            }

            int[] reference;
            int refWidth;
            int refHeight;
            try {
                ReferenceImage ref = ReferenceImageLoader.load(refTiff);
                reference = ref.samples();
                refWidth = ref.width();
                refHeight = ref.height();
            } catch (Exception e) {
                md.append("| `").append(name).append("` | ERROR: ").append(e.getMessage())
                        .append(" | — | — | — | — | — | — | — | — |\n");
                continue;
            }

            int[] patentDecoded;
            int[] jpegLsDecoded;
            try {
                patentDecoded = decodeFixtureData(fixture, CrxLosslessRawDecoder.KUpdateRule.PATENT_BRACKET,
                        refWidth, refHeight);
                jpegLsDecoded = decodeFixtureData(fixture, CrxLosslessRawDecoder.KUpdateRule.JPEG_LS,
                        refWidth, refHeight);
            } catch (Exception e) {
                md.append("| `").append(name).append("` | — | DECODE-ERROR: ").append(e.getMessage())
                        .append(" | — | — | — | — | — | — | — |\n");
                continue;
            }

            if (patentDecoded == null || jpegLsDecoded == null) {
                md.append("| `").append(name)
                        .append("` | — | SKIP (no lossless track) | — | — | — | — | — | — | — |\n");
                continue;
            }

            double refMean = ReferenceMetrics.mean(reference);
            double patentMean = ReferenceMetrics.mean(patentDecoded);
            double dcBias = refMean - patentMean;

            ReferenceMetrics.Metrics patentMetrics = ReferenceMetrics.compare(reference, patentDecoded);
            double patentMae = patentMetrics.mae();
            double patentPearson = patentMetrics.pearson();

            ReferenceMetrics.Metrics jpegLsMetrics = ReferenceMetrics.compare(reference, jpegLsDecoded);
            double jpegLsMae = jpegLsMetrics.mae();
            double jpegLsPearson = jpegLsMetrics.pearson();

            // D1: apply the same uint16-clamped DC correction used by the original probe.
            int maxVal = 0xFFFF;
            int[] dcCorrected = ReferenceMetrics.addClamped(
                    patentDecoded, (int) Math.round(dcBias), 0, maxVal);
            double dcCorrMae = ReferenceMetrics.compare(reference, dcCorrected).mae();

            double dcImprovementFraction = (patentMae > 0)
                    ? (patentMae - dcCorrMae) / patentMae
                    : 0;
            boolean d1confirmed = dcImprovementFraction > 0.80;
            boolean d2flip = (patentPearson < 0) && (jpegLsPearson > 0);

            if (d1confirmed) d1Confirmed.add(name);
            if (d2flip) d2Confirmed.add(name);

            md.append("| `").append(name).append("` | ")
                    .append(fmt(refMean)).append(" | ")
                    .append(fmt(patentMae)).append(" | ")
                    .append(fmt(patentPearson)).append(" | ")
                    .append(fmt(jpegLsMae)).append(" | ")
                    .append(fmt(jpegLsPearson)).append(" | ")
                    .append(fmt(dcCorrMae)).append(" | ")
                    .append(fmt(dcBias)).append(" | ")
                    .append(d1confirmed ? "**YES**" : "no").append(" | ")
                    .append(d2flip ? "**YES**" : "no").append(" |\n");

            System.out.printf("%-55s refMean=%7.0f  patent: mae=%7.1f r=%+.4f  jpegls: mae=%7.1f r=%+.4f  "
                            + "dcCorr-mae=%7.1f bias=%+7.0f  D1=%s D2-flip=%s%n",
                    name, refMean, patentMae, patentPearson, jpegLsMae, jpegLsPearson,
                    dcCorrMae, dcBias, d1confirmed ? "YES" : "no", d2flip ? "YES" : "no");
        }

        md.append("\n## Verdict\n\n");
        if (!d1Confirmed.isEmpty()) {
            md.append("### D1 (DC-offset bias) — **CONFIRMED** on ")
                    .append(d1Confirmed.size()).append(" fixture(s)\n\n");
            for (String f : d1Confirmed) md.append("- `").append(f).append("`\n");
            md.append("\n> DC correction removes >80% of MAE. The decoder's mean output is ");
            md.append("systematically lower than the reference. The first-pixel seed (currently 0) ");
            md.append("or a systematic OB-level offset is the dominant error source.\n");
        } else {
            md.append("### D1 (DC-offset bias) — not confirmed by DC-correction criterion\n\n");
            md.append("> DC correction does not reduce MAE by >80% on any fixture. ");
            md.append("The error is not a simple DC shift.\n");
        }
        md.append('\n');
        if (!d2Confirmed.isEmpty()) {
            md.append("### D2 (sign-mapping flip) — **CONFIRMED** on ")
                    .append(d2Confirmed.size()).append(" fixture(s)\n\n");
            for (String f : d2Confirmed) md.append("- `").append(f).append("`\n");
            md.append("\n> JPEG_LS (zig-zag) sign rule flips Pearson from negative to positive ");
            md.append("on these fixtures. PATENT_BRACKET and JPEG_LS disagree on sign for these ");
            md.append("fixtures; further investigation is needed to identify which convention ");
            md.append("matches the actual bitstream for each camera model.\n");
        } else {
            md.append("### D2 (sign-mapping flip) — not confirmed\n\n");
            md.append("> Switching from bit-0 to zig-zag does not flip the Pearson sign on ");
            md.append("the anti-correlated fixtures. Sign mapping is not the dominant cause.\n");
        }

        String report = md.toString();
        System.out.println("\n=== REPORT ===\n" + report);
        Files.writeString(OUT_DIR.resolve("report.md"), report);

        assertThat(OUT_DIR.resolve("report.md")).exists();
        // The test always passes — it is diagnostic, not a gating assertion.
    }

    private static List<Path> discoverFixturesWithReferences() throws IOException {
        if (!Files.isDirectory(SAMPLES_DIR)) {
            return List.of();
        }
        List<Path> found = new ArrayList<>();
        try (var stream = Files.walk(SAMPLES_DIR)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".cr3"))
                    .filter(p -> Files.exists(referenceTiffPath(p)))
                    .sorted(Comparator.comparing(p -> SAMPLES_DIR.relativize(p).toString()))
                    .forEach(found::add);
        }
        return found;
    }

    private static Path referenceTiffPath(Path fixture) {
        String name = fixture.getFileName().toString().replaceAll("(?i)\\.cr3$", ".reference.tiff");
        return fixture.resolveSibling(name);
    }

    /**
     * Decode the CR3 fixture and return the full-sensor Bayer array.
     * Returns {@code null} if the fixture has no lossless RAW track.
     */
    private static int[] decodeFixtureData(Path fixture, CrxLosslessRawDecoder.KUpdateRule rule,
                                           int expectedWidth, int expectedHeight)
            throws IOException, Cr3Exception {
        try (ImageInputStream in = ImageIO.createImageInputStream(fixture.toFile())) {
            if (in == null) throw new IOException("Cannot open: " + fixture);
            Cr3Container container = Cr3Parser.parse(in);
            Cr3Track rawTrack = container.tracks().stream()
                    .filter(t -> t.type() == Cr3Track.TrackType.CRX_BIG
                            || t.type() == Cr3Track.TrackType.CRX_RAW)
                    .findFirst().orElse(null);
            if (rawTrack == null) return null;
            Cmp1Box cmp1 = rawTrack.cmp1();
            if (cmp1 == null || !cmp1.isLossless()) return null;

            byte[] payload = new byte[(int) rawTrack.dataSize()];
            in.seek(rawTrack.dataOffset());
            in.readFully(payload);

            CrxHeader.CodecVersion codecVersion = container.cncvBox() == null
                    ? CrxHeader.CodecVersion.V1
                    : CrxHeader.detectVersion(container.cncvBox().versionString());

            Cr3RawImage raw = CrxLosslessRawDecoder.decode(payload,
                    cmp1.imageWidth(), cmp1.imageHeight(),
                    cmp1.bitsPerSample(), codecVersion, cmp1, rule);

            if (raw.width() != expectedWidth || raw.height() != expectedHeight) {
                throw new IllegalStateException(
                        "Decoder size " + raw.width() + "x" + raw.height()
                                + " != reference " + expectedWidth + "x" + expectedHeight);
            }
            return raw.data();
        }
    }

    private static String fmt(double v) {
        return Double.isFinite(v) ? String.format(Locale.ROOT, "%.2f", v) : "n/a";
    }
}
