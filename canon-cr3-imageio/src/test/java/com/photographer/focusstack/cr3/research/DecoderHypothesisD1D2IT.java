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
import com.photographer.focusstack.cr3.reference.ReferencePaths;
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
 * <p>Measures whether adding {@code mean(reference) - mean(decoder)} removes
 * more than 80% of the baseline MAE.</p>
 *
 * <h3>D2 — Sign mapping comparison</h3>
 * <p>Compares the production PATENT_BRACKET path with the legacy JPEG_LS
 * path and records fixtures where the Pearson sign changes.</p>
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
        md.append("| Fixture | RefMean | Patent MAE | Patent Pearson | JPEGL MAE | JPEGL Pearson | ")
                .append("DC-corr MAE | DC-bias | D1? | D2-flip? |\n");
        md.append("|---|---:|---:|---:|---:|---:|---:|---:|:---:|:---:|\n");

        List<String> d1Confirmed = new ArrayList<>();
        List<String> d2Confirmed = new ArrayList<>();

        for (Path fixture : fixtures) {
            String name = SAMPLES_DIR.relativize(fixture).toString().replace('\\', '/');
            ReferenceImage reference;
            try {
                reference = ReferenceImageLoader.load(ReferencePaths.rawLinearTiff(fixture));
            } catch (Exception exception) {
                md.append("| `").append(name).append("` | ERROR: ").append(exception.getMessage())
                        .append(" | — | — | — | — | — | — | — | — |\n");
                continue;
            }

            int[] patentDecoded;
            int[] jpegLsDecoded;
            try {
                patentDecoded = decodeFixtureData(
                        fixture,
                        CrxLosslessRawDecoder.KUpdateRule.PATENT_BRACKET,
                        reference.width(),
                        reference.height());
                jpegLsDecoded = decodeFixtureData(
                        fixture,
                        CrxLosslessRawDecoder.KUpdateRule.JPEG_LS,
                        reference.width(),
                        reference.height());
            } catch (Exception exception) {
                md.append("| `").append(name).append("` | — | DECODE-ERROR: ")
                        .append(exception.getMessage())
                        .append(" | — | — | — | — | — | — | — |\n");
                continue;
            }

            if (patentDecoded == null || jpegLsDecoded == null) {
                md.append("| `").append(name)
                        .append("` | — | SKIP (no lossless track) | — | — | — | — | — | — | — |\n");
                continue;
            }

            int[] referenceSamples = reference.samples();
            double referenceMean = ReferenceMetrics.mean(referenceSamples);
            double patentMean = ReferenceMetrics.mean(patentDecoded);
            double dcBias = referenceMean - patentMean;

            ReferenceMetrics.MetricTriple patentMetrics =
                    ReferenceMetrics.compare(referenceSamples, patentDecoded);
            ReferenceMetrics.MetricTriple jpegLsMetrics =
                    ReferenceMetrics.compare(referenceSamples, jpegLsDecoded);

            int[] dcCorrected = ReferenceMetrics.addClampedOffset(
                    patentDecoded,
                    (int) Math.round(dcBias),
                    0,
                    0xFFFF);
            ReferenceMetrics.MetricTriple correctedMetrics =
                    ReferenceMetrics.compare(referenceSamples, dcCorrected);

            double dcImprovementFraction = patentMetrics.mae() > 0
                    ? (patentMetrics.mae() - correctedMetrics.mae()) / patentMetrics.mae()
                    : 0.0;
            boolean d1ConfirmedForFixture = dcImprovementFraction > 0.80;
            boolean d2Flip = patentMetrics.pearson() < 0 && jpegLsMetrics.pearson() > 0;

            if (d1ConfirmedForFixture) {
                d1Confirmed.add(name);
            }
            if (d2Flip) {
                d2Confirmed.add(name);
            }

            md.append("| `").append(name).append("` | ")
                    .append(fmt(referenceMean)).append(" | ")
                    .append(fmt(patentMetrics.mae())).append(" | ")
                    .append(fmt(patentMetrics.pearson())).append(" | ")
                    .append(fmt(jpegLsMetrics.mae())).append(" | ")
                    .append(fmt(jpegLsMetrics.pearson())).append(" | ")
                    .append(fmt(correctedMetrics.mae())).append(" | ")
                    .append(fmt(dcBias)).append(" | ")
                    .append(d1ConfirmedForFixture ? "**YES**" : "no").append(" | ")
                    .append(d2Flip ? "**YES**" : "no").append(" |\n");

            System.out.printf(
                    "%-55s refMean=%7.0f  patent: mae=%7.1f r=%+.4f  jpegls: mae=%7.1f r=%+.4f  "
                            + "dcCorr-mae=%7.1f bias=%+7.0f  D1=%s D2-flip=%s%n",
                    name,
                    referenceMean,
                    patentMetrics.mae(),
                    patentMetrics.pearson(),
                    jpegLsMetrics.mae(),
                    jpegLsMetrics.pearson(),
                    correctedMetrics.mae(),
                    dcBias,
                    d1ConfirmedForFixture ? "YES" : "no",
                    d2Flip ? "YES" : "no");
        }

        appendVerdict(md, d1Confirmed, d2Confirmed);
        String report = md.toString();
        System.out.println("\n=== REPORT ===\n" + report);
        Files.writeString(OUT_DIR.resolve("report.md"), report);
        assertThat(OUT_DIR.resolve("report.md")).exists();
    }

    private static void appendVerdict(StringBuilder md, List<String> d1Confirmed, List<String> d2Confirmed) {
        md.append("\n## Verdict\n\n");
        if (!d1Confirmed.isEmpty()) {
            md.append("### D1 (DC-offset bias) — **CONFIRMED** on ")
                    .append(d1Confirmed.size()).append(" fixture(s)\n\n");
            d1Confirmed.forEach(fixture -> md.append("- `").append(fixture).append("`\n"));
            md.append("\n> DC correction removes >80% of MAE. The decoder's mean output is ")
                    .append("systematically lower than the reference. The first-pixel seed or a ")
                    .append("systematic OB-level offset remains a candidate source.\n");
        } else {
            md.append("### D1 (DC-offset bias) — not confirmed by DC-correction criterion\n\n")
                    .append("> DC correction does not reduce MAE by >80% on any fixture. ")
                    .append("The error is not a simple DC shift.\n");
        }

        md.append('\n');
        if (!d2Confirmed.isEmpty()) {
            md.append("### D2 (sign-mapping flip) — **CONFIRMED** on ")
                    .append(d2Confirmed.size()).append(" fixture(s)\n\n");
            d2Confirmed.forEach(fixture -> md.append("- `").append(fixture).append("`\n"));
            md.append("\n> JPEG_LS changes Pearson from negative to positive on these fixtures. ")
                    .append("Further investigation must determine which convention matches each bitstream.\n");
        } else {
            md.append("### D2 (sign-mapping flip) — not confirmed\n\n")
                    .append("> Switching sign rules does not flip Pearson on the anti-correlated fixtures.\n");
        }
    }

    private static List<Path> discoverFixturesWithReferences() throws IOException {
        if (!Files.isDirectory(SAMPLES_DIR)) {
            return List.of();
        }
        try (var stream = Files.walk(SAMPLES_DIR)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".cr3"))
                    .filter(ReferencePaths::hasRawLinearTiff)
                    .sorted(Comparator.comparing(path -> SAMPLES_DIR.relativize(path).toString()))
                    .toList();
        }
    }

    /** Returns {@code null} if the fixture has no lossless RAW track. */
    private static int[] decodeFixtureData(
            Path fixture,
            CrxLosslessRawDecoder.KUpdateRule rule,
            int expectedWidth,
            int expectedHeight) throws IOException, Cr3Exception {
        try (ImageInputStream input = ImageIO.createImageInputStream(fixture.toFile())) {
            if (input == null) {
                throw new IOException("Cannot open: " + fixture);
            }
            Cr3Container container = Cr3Parser.parse(input);
            Cr3Track rawTrack = container.tracks().stream()
                    .filter(track -> track.type() == Cr3Track.TrackType.CRX_BIG
                            || track.type() == Cr3Track.TrackType.CRX_RAW)
                    .findFirst()
                    .orElse(null);
            if (rawTrack == null || rawTrack.cmp1() == null || !rawTrack.cmp1().isLossless()) {
                return null;
            }

            Cmp1Box cmp1 = rawTrack.cmp1();
            byte[] payload = new byte[(int) rawTrack.dataSize()];
            input.seek(rawTrack.dataOffset());
            input.readFully(payload);

            CrxHeader.CodecVersion codecVersion = container.cncvBox() == null
                    ? CrxHeader.CodecVersion.V1
                    : CrxHeader.detectVersion(container.cncvBox().versionString());
            Cr3RawImage raw = CrxLosslessRawDecoder.decode(
                    payload,
                    cmp1.imageWidth(),
                    cmp1.imageHeight(),
                    cmp1.bitsPerSample(),
                    codecVersion,
                    cmp1,
                    rule);

            if (raw.width() != expectedWidth || raw.height() != expectedHeight) {
                throw new IllegalStateException(
                        "Decoder size " + raw.width() + "x" + raw.height()
                                + " != reference " + expectedWidth + "x" + expectedHeight);
            }
            return raw.data();
        }
    }

    private static String fmt(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.2f", value) : "n/a";
    }
}
