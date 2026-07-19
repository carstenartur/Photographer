package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.bmff.Iad1Box;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.raw.codec.SubbandExtractor;
import com.photographer.focusstack.cr3.reference.ReferenceImage;
import com.photographer.focusstack.cr3.reference.ReferenceImageLoader;
import com.photographer.focusstack.cr3.reference.ReferenceMetrics;
import com.photographer.focusstack.cr3.reference.SpatialReferenceMetrics;
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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * D5 residual sign-mapping discriminator from issue #111.
 *
 * <p>All entropy and predictor settings are held constant. The only changed
 * variable is unsigned-to-signed residual mapping: bit-0 sign separation versus
 * conventional zig-zag mapping. This is a research integration test and never
 * changes the production decoder.</p>
 */
class SignMappingDiscriminatorIT {

    private static final Path SAMPLES_DIR = Path.of(System.getProperty(
            "cr3.samples.dir", "src/test/resources/samples"));
    private static final Path OUT_DIR = Path.of(System.getProperty(
            "cr3.research.output.dir", "target")).resolve("sign-mapping-discriminator");
    private static final int EXPECTED_REGRESSION_PANEL_SIZE = 11;

    private static final Set<String> DISCRIMINATOR_FIXTURES = Set.of(
            "sample-tele.cr3",
            "sample-wide.cr3",
            "research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3"
    );

    private enum SignMapping {
        BIT0_SIGN(true),
        ZIGZAG_SIGN(false);

        private final boolean signBit;

        SignMapping(boolean signBit) {
            this.signBit = signBit;
        }
    }

    @Test
    void isolateResidualSignMappingAgainstReferenceTiffs() throws Exception {
        List<Path> fixtures = discoverFixtures();
        assumeTrue(fixtures.size() >= EXPECTED_REGRESSION_PANEL_SIZE,
                () -> "Sign-mapping verdict requires at least "
                        + EXPECTED_REGRESSION_PANEL_SIZE
                        + " reference-backed lossless fixtures, found " + fixtures.size());

        Set<String> fixtureNames = fixtures.stream()
                .map(SignMappingDiscriminatorIT::fixtureName)
                .collect(Collectors.toSet());
        Set<String> missingDiscriminators = new TreeSet<>(DISCRIMINATOR_FIXTURES);
        missingDiscriminators.removeAll(fixtureNames);
        assumeTrue(missingDiscriminators.isEmpty(),
                () -> "Sign-mapping verdict requires all discriminator fixtures; missing "
                        + missingDiscriminators);

        Files.createDirectories(OUT_DIR);

        List<FixtureResult> results = new ArrayList<>();
        for (Path fixture : fixtures) {
            results.add(analyseFixture(fixture));
        }

        Verdict verdict = decide(results);
        String markdown = buildMarkdown(results, verdict);
        String tsv = buildTsv(results);
        String json = buildJson(results, verdict);

        Files.writeString(OUT_DIR.resolve("results.md"), markdown);
        Files.writeString(OUT_DIR.resolve("results.tsv"), tsv);
        Files.writeString(OUT_DIR.resolve("results.json"), json);

        System.out.println(markdown);

        assertThat(results).isNotEmpty();
        assertThat(OUT_DIR.resolve("results.md")).exists();
        assertThat(OUT_DIR.resolve("results.tsv")).exists();
        assertThat(OUT_DIR.resolve("results.json")).exists();
    }

    private static FixtureResult analyseFixture(Path fixture) throws Exception {
        ReferenceImage reference = ReferenceImageLoader.load(referenceTiffPath(fixture));
        FixtureStructure structure = loadStructure(fixture);

        assertThat(structure.cmp1).as("CMP1 for %s", fixture.getFileName()).isNotNull();
        assertThat(structure.cmp1.isLossless())
                .as("lossless fixture %s", fixture.getFileName()).isTrue();
        assertThat(reference.width()).isEqualTo(structure.width);
        assertThat(reference.height()).isEqualTo(structure.height);

        VariantDecode bit0 = decodeVariant(fixture, reference.width(), reference.height(),
                structure.cmp1.bitsPerSample(), SignMapping.BIT0_SIGN);
        VariantDecode zigzag = decodeVariant(fixture, reference.width(), reference.height(),
                structure.cmp1.bitsPerSample(), SignMapping.ZIGZAG_SIGN);

        VariantMetrics bit0Metrics = metrics(reference.samples(), bit0.bayer,
                reference.width(), reference.height(), structure.cmp1, structure.iad1, bit0);
        VariantMetrics zigzagMetrics = metrics(reference.samples(), zigzag.bayer,
                reference.width(), reference.height(), structure.cmp1, structure.iad1, zigzag);

        return new FixtureResult(fixtureName(fixture), bit0Metrics, zigzagMetrics);
    }

    private static VariantDecode decodeVariant(Path fixture, int width, int height,
                                               int bitDepth, SignMapping mapping) throws Exception {
        List<SubbandExtractor.SubbandRef> refs = SubbandExtractor.list(fixture.toFile()).stream()
                .filter(ref -> ref.waveletLevels() == 0 && ref.subbandIndex() == 0)
                .sorted(Comparator.comparingInt(SubbandExtractor.SubbandRef::tileSequence)
                        .thenComparingInt(SubbandExtractor.SubbandRef::planeIndex))
                .toList();
        assertThat(refs).as("lossless subbands for %s", fixture.getFileName()).isNotEmpty();

        Map<Integer, List<SubbandExtractor.SubbandRef>> byTile = refs.stream()
                .collect(Collectors.groupingBy(SubbandExtractor.SubbandRef::tileSequence,
                        TreeMap::new, Collectors.toList()));

        int fullPlaneWidth = (width + 1) / 2;
        int fullPlaneHeight = (height + 1) / 2;
        int[][] planes = new int[4][fullPlaneWidth * fullPlaneHeight];

        long symbolsDecoded = 0;
        long symbolCapacity = 0;
        boolean exhausted = false;
        int tileXOffset = 0;
        int tileOrdinal = 0;

        for (List<SubbandExtractor.SubbandRef> tileRefs : byTile.values()) {
            tileRefs.sort(Comparator.comparingInt(SubbandExtractor.SubbandRef::planeIndex));
            assertThat(tileRefs).as("four planes in tile for %s", fixture.getFileName())
                    .hasSize(4);

            int remaining = fullPlaneWidth - tileXOffset;
            int nominalWidth = tileRefs.getFirst().planeWidth();
            int tilePlaneWidth = tileOrdinal == byTile.size() - 1
                    ? remaining
                    : Math.min(nominalWidth, remaining);
            int tilePlaneHeight = Math.min(tileRefs.getFirst().planeHeight(), fullPlaneHeight);
            assertThat(tilePlaneWidth).isPositive();
            assertThat(tilePlaneHeight).isPositive();

            for (SubbandExtractor.SubbandRef ref : tileRefs) {
                int plane = ref.planeIndex();
                assertThat(plane).isBetween(0, 3);

                byte[] payload = SubbandExtractor.read(fixture.toFile(), ref);
                CrxNormativeLosslessCandidate.Options options = commonOptions(bitDepth, mapping);
                CrxNormativeLosslessCandidate.Result decoded =
                        CrxNormativeLosslessCandidate.decodePlane(payload, tilePlaneWidth,
                                tilePlaneHeight, bitDepth, options);

                for (int row = 0; row < tilePlaneHeight; row++) {
                    System.arraycopy(decoded.pixels, row * tilePlaneWidth,
                            planes[plane], row * fullPlaneWidth + tileXOffset,
                            tilePlaneWidth);
                }

                symbolsDecoded += decoded.symbolsDecoded;
                symbolCapacity += (long) tilePlaneWidth * tilePlaneHeight;
                exhausted |= decoded.streamExhausted;
            }

            tileXOffset += tilePlaneWidth;
            tileOrdinal++;
        }

        assertThat(tileXOffset).as("tile coverage for %s", fixture.getFileName())
                .isEqualTo(fullPlaneWidth);

        int[] bayer = interleave(planes, width, height, fullPlaneWidth, fullPlaneHeight);
        double completion = symbolCapacity == 0 ? 0.0
                : (double) symbolsDecoded / symbolCapacity;
        return new VariantDecode(bayer, completion, exhausted);
    }

    private static CrxNormativeLosslessCandidate.Options commonOptions(
            int bitDepth, SignMapping mapping) {
        CrxNormativeLosslessCandidate.Options options =
                new CrxNormativeLosslessCandidate.Options();
        options.kInit = 1;
        options.resetKPerRow = true;
        options.unaryOnesUntilZero = true;
        options.signBitMapping = mapping.signBit;
        options.escapeAtUnary15 = false;
        options.firstPixelSeed = 0;
        options.escapeBitWidth = bitDepth;
        return options;
    }

    private static int[] interleave(int[][] planes, int width, int height,
                                    int planeWidth, int planeHeight) {
        int[] output = new int[width * height];
        for (int py = 0; py < planeHeight; py++) {
            int y0 = py * 2;
            int y1 = y0 + 1;
            for (int px = 0; px < planeWidth; px++) {
                int source = py * planeWidth + px;
                int x0 = px * 2;
                int x1 = x0 + 1;
                if (y0 < height && x0 < width) output[y0 * width + x0] = planes[0][source];
                if (y0 < height && x1 < width) output[y0 * width + x1] = planes[1][source];
                if (y1 < height && x0 < width) output[y1 * width + x0] = planes[2][source];
                if (y1 < height && x1 < width) output[y1 * width + x1] = planes[3][source];
            }
        }
        return output;
    }

    private static VariantMetrics metrics(int[] reference, int[] actual,
                                          int width, int height,
                                          Cmp1Box cmp1, Iad1Box iad1,
                                          VariantDecode decode) {
        MetricTriple global = metricTriple(ReferenceMetrics.compare(reference, actual));
        double obMae = opticalBlackMae(reference, actual, width, height, iad1);
        double boundaryError = cmp1 == null || cmp1.tileWidth() <= 0
                || cmp1.tileHeight() <= 0
                ? Double.NaN
                : SpatialReferenceMetrics.tileBoundaryMae(reference, actual, width, height,
                        cmp1.tileWidth(), cmp1.tileHeight());

        List<MetricTriple> planeMetrics = new ArrayList<>(4);
        for (int plane = 0; plane < 4; plane++) {
            planeMetrics.add(planeMetric(reference, actual, width, height, plane));
        }
        return new VariantMetrics(global, obMae, boundaryError, planeMetrics,
                decode.completion, decode.exhausted);
    }

    private static double opticalBlackMae(int[] reference, int[] actual,
                                          int width, int height, Iad1Box iad1) {
        if (iad1 == null) return Double.NaN;
        List<SpatialReferenceMetrics.Rect> regions = List.of(
                        SpatialReferenceMetrics.Rect.clip(iad1.leftObLeft(), iad1.leftObTop(),
                                iad1.leftObRight(), iad1.leftObBottom(), width, height),
                        SpatialReferenceMetrics.Rect.clip(iad1.topObLeft(), iad1.topObTop(),
                                iad1.topObRight(), iad1.topObBottom(), width, height))
                .stream()
                .filter(region -> !region.isEmpty())
                .toList();
        return regions.isEmpty() ? Double.NaN
                : SpatialReferenceMetrics.compareRegions(reference, actual,
                        width, height, regions).mae();
    }

    private static MetricTriple planeMetric(int[] reference, int[] actual,
                                            int width, int height, int plane) {
        int rowOffset = plane / 2;
        int columnOffset = plane % 2;
        int count = ((height - rowOffset + 1) / 2)
                * ((width - columnOffset + 1) / 2);
        int[] referencePlane = new int[count];
        int[] actualPlane = new int[count];
        int index = 0;
        for (int y = rowOffset; y < height; y += 2) {
            int row = y * width;
            for (int x = columnOffset; x < width; x += 2) {
                referencePlane[index] = reference[row + x];
                actualPlane[index] = actual[row + x];
                index++;
            }
        }
        return metricTriple(ReferenceMetrics.compare(referencePlane, actualPlane));
    }

    private static MetricTriple metricTriple(ReferenceMetrics.Metrics metrics) {
        return new MetricTriple(metrics.mae(), metrics.rmse(), metrics.pearson());
    }

    private static Verdict decide(List<FixtureResult> results) {
        int successfulDiscriminators = 0;
        int pearsonFlips = 0;
        boolean noCompletionRegression = true;

        for (FixtureResult result : results) {
            VariantMetrics bit0 = result.bit0;
            VariantMetrics zigzag = result.zigzag;
            if (DISCRIMINATOR_FIXTURES.contains(result.fixture)) {
                boolean flip = bit0.global.pearson < 0 && zigzag.global.pearson > 0;
                if (flip) pearsonFlips++;
                if (flip && zigzag.global.mae < bit0.global.mae
                        && finiteImprovement(zigzag.obMae, bit0.obMae)) {
                    successfulDiscriminators++;
                }
            }
            if (zigzag.completion + 1e-12 < bit0.completion
                    || (zigzag.exhausted && !bit0.exhausted)) {
                noCompletionRegression = false;
            }
        }

        double bit0MedianMae = median(results.stream()
                .map(result -> result.bit0.global.mae).toList());
        double zigzagMedianMae = median(results.stream()
                .map(result -> result.zigzag.global.mae).toList());
        double bit0MedianBoundary = medianFinite(results.stream()
                .map(result -> result.bit0.boundaryError).toList());
        double zigzagMedianBoundary = medianFinite(results.stream()
                .map(result -> result.zigzag.boundaryError).toList());
        boolean corpusGate = zigzagMedianMae <= bit0MedianMae
                && (Double.isNaN(bit0MedianBoundary)
                || zigzagMedianBoundary <= bit0MedianBoundary);

        String status;
        if (successfulDiscriminators >= 2 && corpusGate && noCompletionRegression) {
            status = "SUPPORTED";
        } else if (pearsonFlips >= 2 || successfulDiscriminators >= 1) {
            status = "PARTIAL";
        } else {
            status = "REJECTED";
        }
        return new Verdict(status, successfulDiscriminators, pearsonFlips,
                bit0MedianMae, zigzagMedianMae,
                bit0MedianBoundary, zigzagMedianBoundary,
                noCompletionRegression);
    }

    private static boolean finiteImprovement(double candidate, double baseline) {
        return Double.isFinite(candidate) && Double.isFinite(baseline)
                && candidate < baseline;
    }

    private static double median(List<Double> values) {
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        if (sorted.length == 0) return Double.NaN;
        int middle = sorted.length / 2;
        return sorted.length % 2 == 0
                ? (sorted[middle - 1] + sorted[middle]) / 2.0
                : sorted[middle];
    }

    private static double medianFinite(List<Double> values) {
        return median(values.stream().filter(Double::isFinite).toList());
    }

    private static String buildMarkdown(List<FixtureResult> results, Verdict verdict) {
        StringBuilder out = new StringBuilder();
        out.append("# D5 Sign-Mapping Discriminator\n\n");
        out.append("Generated: ").append(Instant.now()).append("\n\n");
        out.append("Only the unsigned-to-signed residual mapping changes. Unary direction, ")
                .append("K initialization, row reset, K adaptation, escape behavior, MED ")
                .append("predictor, seed and assembly are identical.\n\n");
        out.append("## Verdict: **").append(verdict.status).append("**\n\n");
        out.append("- successful discriminator fixtures: ")
                .append(verdict.successfulDiscriminators).append(" / 3\n");
        out.append("- Pearson sign flips: ").append(verdict.pearsonFlips).append(" / 3\n");
        out.append("- median global MAE BIT0/ZIGZAG: ")
                .append(fmt(verdict.bit0MedianMae)).append(" / ")
                .append(fmt(verdict.zigzagMedianMae)).append("\n");
        out.append("- median boundary error BIT0/ZIGZAG: ")
                .append(fmt(verdict.bit0MedianBoundary)).append(" / ")
                .append(fmt(verdict.zigzagMedianBoundary)).append("\n");
        out.append("- no completion regression: ")
                .append(verdict.noCompletionRegression).append("\n\n");

        out.append("## Fixture metrics\n\n");
        out.append("| Fixture | Mapping | MAE | RMSE | Pearson | OB MAE | Boundary | Completion | Exhausted |\n");
        out.append("|---|---|---:|---:|---:|---:|---:|---:|:---:|\n");
        for (FixtureResult result : results) {
            appendFixtureRow(out, result.fixture, "BIT0_SIGN", result.bit0);
            appendFixtureRow(out, result.fixture, "ZIGZAG_SIGN", result.zigzag);
        }

        out.append("\n## Per-plane metrics\n\n");
        out.append("| Fixture | Mapping | Plane | MAE | RMSE | Pearson |\n");
        out.append("|---|---|---:|---:|---:|---:|\n");
        for (FixtureResult result : results) {
            appendPlaneRows(out, result.fixture, "BIT0_SIGN", result.bit0.planeMetrics);
            appendPlaneRows(out, result.fixture, "ZIGZAG_SIGN", result.zigzag.planeMetrics);
        }
        return out.toString();
    }

    private static void appendFixtureRow(StringBuilder out, String fixture,
                                         String mapping, VariantMetrics metrics) {
        out.append("| `").append(fixture).append("` | ").append(mapping).append(" | ")
                .append(fmt(metrics.global.mae)).append(" | ")
                .append(fmt(metrics.global.rmse)).append(" | ")
                .append(fmt(metrics.global.pearson)).append(" | ")
                .append(fmt(metrics.obMae)).append(" | ")
                .append(fmt(metrics.boundaryError)).append(" | ")
                .append(fmt(metrics.completion)).append(" | ")
                .append(metrics.exhausted ? "yes" : "no").append(" |\n");
    }

    private static void appendPlaneRows(StringBuilder out, String fixture,
                                        String mapping, List<MetricTriple> planes) {
        for (int plane = 0; plane < planes.size(); plane++) {
            MetricTriple metrics = planes.get(plane);
            out.append("| `").append(fixture).append("` | ").append(mapping).append(" | ")
                    .append(plane).append(" | ").append(fmt(metrics.mae)).append(" | ")
                    .append(fmt(metrics.rmse)).append(" | ")
                    .append(fmt(metrics.pearson)).append(" |\n");
        }
    }

    private static String buildTsv(List<FixtureResult> results) {
        StringBuilder out = new StringBuilder(
                "fixture\tmapping\tmae\trmse\tpearson\tobMae\tboundaryError\tcompletion\texhausted");
        for (int plane = 0; plane < 4; plane++) {
            out.append("\tplane").append(plane).append("Mae")
                    .append("\tplane").append(plane).append("Rmse")
                    .append("\tplane").append(plane).append("Pearson");
        }
        out.append('\n');
        for (FixtureResult result : results) {
            appendTsv(out, result.fixture, "BIT0_SIGN", result.bit0);
            appendTsv(out, result.fixture, "ZIGZAG_SIGN", result.zigzag);
        }
        return out.toString();
    }

    private static void appendTsv(StringBuilder out, String fixture,
                                  String mapping, VariantMetrics metrics) {
        out.append(fixture).append('\t').append(mapping).append('\t')
                .append(metrics.global.mae).append('\t')
                .append(metrics.global.rmse).append('\t')
                .append(metrics.global.pearson).append('\t')
                .append(metrics.obMae).append('\t')
                .append(metrics.boundaryError).append('\t')
                .append(metrics.completion).append('\t')
                .append(metrics.exhausted);
        for (MetricTriple plane : metrics.planeMetrics) {
            out.append('\t').append(plane.mae)
                    .append('\t').append(plane.rmse)
                    .append('\t').append(plane.pearson);
        }
        out.append('\n');
    }

    private static String buildJson(List<FixtureResult> results, Verdict verdict) {
        StringBuilder out = new StringBuilder();
        out.append("{\n  \"verdict\": \"").append(verdict.status)
                .append("\",\n  \"results\": [\n");
        boolean first = true;
        for (FixtureResult result : results) {
            first = appendJsonResult(out, first, result.fixture, "BIT0_SIGN", result.bit0);
            first = appendJsonResult(out, first, result.fixture, "ZIGZAG_SIGN", result.zigzag);
        }
        out.append("\n  ]\n}\n");
        return out.toString();
    }

    private static boolean appendJsonResult(StringBuilder out, boolean first,
                                            String fixture, String mapping,
                                            VariantMetrics metrics) {
        if (!first) out.append(",\n");
        out.append("    {\"fixture\":\"").append(jsonEscape(fixture))
                .append("\",\"mapping\":\"").append(mapping)
                .append("\",\"mae\":").append(jsonNumber(metrics.global.mae))
                .append(",\"rmse\":").append(jsonNumber(metrics.global.rmse))
                .append(",\"pearson\":").append(jsonNumber(metrics.global.pearson))
                .append(",\"obMae\":").append(jsonNumber(metrics.obMae))
                .append(",\"boundaryError\":").append(jsonNumber(metrics.boundaryError))
                .append(",\"completion\":").append(jsonNumber(metrics.completion))
                .append(",\"exhausted\":").append(metrics.exhausted)
                .append(",\"planes\":[");
        for (int plane = 0; plane < metrics.planeMetrics.size(); plane++) {
            if (plane > 0) out.append(',');
            MetricTriple values = metrics.planeMetrics.get(plane);
            out.append("{\"plane\":").append(plane)
                    .append(",\"mae\":").append(jsonNumber(values.mae))
                    .append(",\"rmse\":").append(jsonNumber(values.rmse))
                    .append(",\"pearson\":").append(jsonNumber(values.pearson))
                    .append('}');
        }
        out.append("]}");
        return false;
    }

    private static String jsonNumber(double value) {
        return Double.isFinite(value) ? Double.toString(value) : "null";
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String fmt(double value) {
        return Double.isFinite(value)
                ? String.format(Locale.ROOT, "%.4f", value)
                : "n/a";
    }

    private static List<Path> discoverFixtures() throws IOException {
        if (!Files.isDirectory(SAMPLES_DIR)) return List.of();
        try (Stream<Path> stream = Files.walk(SAMPLES_DIR)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .toLowerCase(Locale.ROOT).endsWith(".cr3"))
                    .filter(path -> Files.isRegularFile(referenceTiffPath(path)))
                    .filter(SignMappingDiscriminatorIT::isLossless)
                    .sorted(Comparator.comparing(SignMappingDiscriminatorIT::fixtureName))
                    .toList();
        }
    }

    private static boolean isLossless(Path fixture) {
        try {
            return loadStructure(fixture).cmp1.isLossless();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static FixtureStructure loadStructure(Path fixture) throws Exception {
        try (ImageInputStream input = ImageIO.createImageInputStream(fixture.toFile())) {
            if (input == null) throw new IOException("Cannot open " + fixture);
            Cr3Container container = Cr3Parser.parse(input);
            Cr3Track raw = container.tracks().stream()
                    .filter(track -> track.type() == Cr3Track.TrackType.CRX_BIG
                            || track.type() == Cr3Track.TrackType.CRX_RAW)
                    .findFirst()
                    .orElseThrow(() -> new IOException("No RAW track in " + fixture));
            Cmp1Box cmp1 = raw.cmp1();
            if (cmp1 == null) throw new IOException("No CMP1 in " + fixture);
            return new FixtureStructure(cmp1.imageWidth(), cmp1.imageHeight(),
                    cmp1, raw.iad1());
        }
    }

    private static Path referenceTiffPath(Path fixture) {
        String name = fixture.getFileName().toString()
                .replaceAll("(?i)\\.cr3$", ".reference.tiff");
        return fixture.resolveSibling(name);
    }

    private static String fixtureName(Path fixture) {
        return SAMPLES_DIR.relativize(fixture).toString().replace('\\', '/');
    }

    private record FixtureStructure(int width, int height, Cmp1Box cmp1, Iad1Box iad1) {}
    private record VariantDecode(int[] bayer, double completion, boolean exhausted) {}
    private record MetricTriple(double mae, double rmse, double pearson) {}
    private record VariantMetrics(MetricTriple global, double obMae,
                                  double boundaryError, List<MetricTriple> planeMetrics,
                                  double completion, boolean exhausted) {}
    private record FixtureResult(String fixture, VariantMetrics bit0, VariantMetrics zigzag) {}
    private record Verdict(String status, int successfulDiscriminators, int pearsonFlips,
                           double bit0MedianMae, double zigzagMedianMae,
                           double bit0MedianBoundary, double zigzagMedianBoundary,
                           boolean noCompletionRegression) {}
}
