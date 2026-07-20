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
 * D6 K-adaptation discriminator from issue #175.
 *
 * <p>Every entropy, predictor and assembly choice is held constant. The only
 * changed variable is the K-update rule: the historical hybrid
 * magnitude/accumulator rule versus one-step movement toward the bracket around
 * {@code floor(log2(S + 1))}, where {@code S} is the current unsigned Rice
 * symbol. This integration test never changes the production decoder.</p>
 */
class KAdaptationDiscriminatorIT {

    private static final Path SAMPLES_DIR = Path.of(System.getProperty(
            "cr3.samples.dir", "src/test/resources/samples"));
    private static final Path OUT_DIR = Path.of(System.getProperty(
            "cr3.research.output.dir", "target")).resolve("k-adaptation-discriminator");
    private static final int EXPECTED_REGRESSION_PANEL_SIZE = 11;

    private static final Set<String> DISCRIMINATOR_FIXTURES = Set.of(
            "sample-tele.cr3",
            "sample-wide.cr3",
            "research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3"
    );

    private enum Variant {
        HYBRID(CrxNormativeLosslessCandidate.KAdaptation.HYBRID_ACCUMULATOR),
        SYMBOL_BRACKET(CrxNormativeLosslessCandidate.KAdaptation.SYMBOL_BRACKET);

        private final CrxNormativeLosslessCandidate.KAdaptation adaptation;

        Variant(CrxNormativeLosslessCandidate.KAdaptation adaptation) {
            this.adaptation = adaptation;
        }
    }

    @Test
    void isolateKAdaptationAgainstReferenceTiffs() throws Exception {
        List<Path> fixtures = discoverFixtures();
        assumeTrue(fixtures.size() >= EXPECTED_REGRESSION_PANEL_SIZE,
                () -> "K-adaptation verdict requires at least "
                        + EXPECTED_REGRESSION_PANEL_SIZE
                        + " reference-backed lossless fixtures, found " + fixtures.size());

        Set<String> fixtureNames = fixtures.stream()
                .map(KAdaptationDiscriminatorIT::fixtureName)
                .collect(Collectors.toSet());
        Set<String> missingDiscriminators = new TreeSet<>(DISCRIMINATOR_FIXTURES);
        missingDiscriminators.removeAll(fixtureNames);
        assumeTrue(missingDiscriminators.isEmpty(),
                () -> "K-adaptation verdict requires all discriminator fixtures; missing "
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

        VariantDecode hybrid = decodeVariant(fixture, reference.width(), reference.height(),
                structure.cmp1.bitsPerSample(), Variant.HYBRID);
        VariantDecode bracket = decodeVariant(fixture, reference.width(), reference.height(),
                structure.cmp1.bitsPerSample(), Variant.SYMBOL_BRACKET);

        VariantMetrics hybridMetrics = metrics(reference.samples(), hybrid.bayer,
                reference.width(), reference.height(), structure.cmp1, structure.iad1,
                structure.cmp1.bitsPerSample(), hybrid);
        VariantMetrics bracketMetrics = metrics(reference.samples(), bracket.bayer,
                reference.width(), reference.height(), structure.cmp1, structure.iad1,
                structure.cmp1.bitsPerSample(), bracket);

        return new FixtureResult(fixtureName(fixture), hybridMetrics, bracketMetrics);
    }

    private static VariantDecode decodeVariant(Path fixture, int width, int height,
                                               int bitDepth, Variant variant) throws Exception {
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
                CrxNormativeLosslessCandidate.Options options = commonOptions(bitDepth, variant);
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
            int bitDepth, Variant variant) {
        CrxNormativeLosslessCandidate.Options options =
                new CrxNormativeLosslessCandidate.Options();
        options.kInit = 1;
        options.resetKPerRow = true;
        options.unaryOnesUntilZero = true;
        options.signBitMapping = true;
        options.escapeAtUnary15 = false;
        options.firstPixelSeed = 0;
        options.escapeBitWidth = bitDepth;
        options.kAdaptation = variant.adaptation;
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
                if (y0 < height && x0 < width) {
                    output[y0 * width + x0] = planes[0][source];
                }
                if (y0 < height && x1 < width) {
                    output[y0 * width + x1] = planes[1][source];
                }
                if (y1 < height && x0 < width) {
                    output[y1 * width + x0] = planes[2][source];
                }
                if (y1 < height && x1 < width) {
                    output[y1 * width + x1] = planes[3][source];
                }
            }
        }
        return output;
    }

    private static VariantMetrics metrics(int[] reference, int[] actual,
                                          int width, int height,
                                          Cmp1Box cmp1, Iad1Box iad1,
                                          int bitDepth, VariantDecode decode) {
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
                saturationFraction(actual, bitDepth), decode.completion, decode.exhausted);
    }

    private static double opticalBlackMae(int[] reference, int[] actual,
                                          int width, int height, Iad1Box iad1) {
        if (iad1 == null) {
            return Double.NaN;
        }
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

    private static double saturationFraction(int[] actual, int bitDepth) {
        if (actual.length == 0) {
            return 0.0;
        }
        int max = (1 << bitDepth) - 1;
        long saturated = 0;
        for (int value : actual) {
            if (value == max) {
                saturated++;
            }
        }
        return (double) saturated / actual.length;
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
        boolean noCompletionRegression = true;

        for (FixtureResult result : results) {
            VariantMetrics baseline = result.hybrid;
            VariantMetrics candidate = result.symbolBracket;
            if (DISCRIMINATOR_FIXTURES.contains(result.fixture)
                    && candidate.global.mae < baseline.global.mae
                    && finiteImprovement(candidate.obMae, baseline.obMae)) {
                successfulDiscriminators++;
            }
            if (candidate.completion + 1e-12 < baseline.completion
                    || (candidate.exhausted && !baseline.exhausted)) {
                noCompletionRegression = false;
            }
        }

        double hybridMedianMae = median(results.stream()
                .map(result -> result.hybrid.global.mae).toList());
        double bracketMedianMae = median(results.stream()
                .map(result -> result.symbolBracket.global.mae).toList());
        double hybridMedianBoundary = medianFinite(results.stream()
                .map(result -> result.hybrid.boundaryError).toList());
        double bracketMedianBoundary = medianFinite(results.stream()
                .map(result -> result.symbolBracket.boundaryError).toList());
        double hybridMedianSaturation = median(results.stream()
                .map(result -> result.hybrid.saturationFraction).toList());
        double bracketMedianSaturation = median(results.stream()
                .map(result -> result.symbolBracket.saturationFraction).toList());

        boolean corpusGate = bracketMedianMae <= hybridMedianMae
                && (Double.isNaN(hybridMedianBoundary)
                || bracketMedianBoundary <= hybridMedianBoundary)
                && bracketMedianSaturation <= hybridMedianSaturation;

        String status;
        if (successfulDiscriminators >= 2 && corpusGate && noCompletionRegression) {
            status = "SUPPORTED";
        } else if (successfulDiscriminators >= 1 || bracketMedianMae < hybridMedianMae) {
            status = "PARTIAL";
        } else {
            status = "REJECTED";
        }
        return new Verdict(status, successfulDiscriminators,
                hybridMedianMae, bracketMedianMae,
                hybridMedianBoundary, bracketMedianBoundary,
                hybridMedianSaturation, bracketMedianSaturation,
                noCompletionRegression);
    }

    private static boolean finiteImprovement(double candidate, double baseline) {
        return Double.isFinite(candidate) && Double.isFinite(baseline)
                && candidate < baseline;
    }

    private static double median(List<Double> values) {
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        if (sorted.length == 0) {
            return Double.NaN;
        }
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
        out.append("# D6 K-Adaptation Discriminator\n\n");
        out.append("Generated: ").append(Instant.now()).append("\n\n");
        out.append("Only the K-update rule changes. K initialization, row reset, unary direction, ")
                .append("BIT0 sign mapping, escape behavior, MED predictor, seed, payload extraction ")
                .append("and Bayer assembly are identical.\n\n");
        out.append("Candidate rule: `K*=floor(log2(S+1))`, bracket `K*-1..K*+1`, ")
                .append("one movement step per decoded unsigned symbol.\n\n");
        out.append("## Verdict: **").append(verdict.status).append("**\n\n");
        out.append("- successful discriminator fixtures: ")
                .append(verdict.successfulDiscriminators).append(" / 3\n");
        out.append("- median global MAE HYBRID/SYMBOL_BRACKET: ")
                .append(fmt(verdict.hybridMedianMae)).append(" / ")
                .append(fmt(verdict.bracketMedianMae)).append("\n");
        out.append("- median boundary error HYBRID/SYMBOL_BRACKET: ")
                .append(fmt(verdict.hybridMedianBoundary)).append(" / ")
                .append(fmt(verdict.bracketMedianBoundary)).append("\n");
        out.append("- median saturation HYBRID/SYMBOL_BRACKET: ")
                .append(fmt(verdict.hybridMedianSaturation)).append(" / ")
                .append(fmt(verdict.bracketMedianSaturation)).append("\n");
        out.append("- no completion regression: ")
                .append(verdict.noCompletionRegression).append("\n\n");

        out.append("## Fixture metrics\n\n");
        out.append("| Fixture | K update | MAE | RMSE | Pearson | OB MAE | Boundary | Saturation | Completion | Exhausted |\n");
        out.append("|---|---|---:|---:|---:|---:|---:|---:|---:|:---:|\n");
        for (FixtureResult result : results) {
            appendFixtureRow(out, result.fixture, "HYBRID", result.hybrid);
            appendFixtureRow(out, result.fixture, "SYMBOL_BRACKET", result.symbolBracket);
        }

        out.append("\n## Per-plane metrics\n\n");
        out.append("| Fixture | K update | Plane | MAE | RMSE | Pearson |\n");
        out.append("|---|---|---:|---:|---:|---:|\n");
        for (FixtureResult result : results) {
            appendPlaneRows(out, result.fixture, "HYBRID", result.hybrid.planeMetrics);
            appendPlaneRows(out, result.fixture, "SYMBOL_BRACKET",
                    result.symbolBracket.planeMetrics);
        }
        return out.toString();
    }

    private static void appendFixtureRow(StringBuilder out, String fixture,
                                         String adaptation, VariantMetrics metrics) {
        out.append("| `").append(fixture).append("` | ").append(adaptation).append(" | ")
                .append(fmt(metrics.global.mae)).append(" | ")
                .append(fmt(metrics.global.rmse)).append(" | ")
                .append(fmt(metrics.global.pearson)).append(" | ")
                .append(fmt(metrics.obMae)).append(" | ")
                .append(fmt(metrics.boundaryError)).append(" | ")
                .append(fmt(metrics.saturationFraction)).append(" | ")
                .append(fmt(metrics.completion)).append(" | ")
                .append(metrics.exhausted ? "yes" : "no").append(" |\n");
    }

    private static void appendPlaneRows(StringBuilder out, String fixture,
                                        String adaptation, List<MetricTriple> planes) {
        for (int plane = 0; plane < planes.size(); plane++) {
            MetricTriple metrics = planes.get(plane);
            out.append("| `").append(fixture).append("` | ").append(adaptation).append(" | ")
                    .append(plane).append(" | ").append(fmt(metrics.mae)).append(" | ")
                    .append(fmt(metrics.rmse)).append(" | ")
                    .append(fmt(metrics.pearson)).append(" |\n");
        }
    }

    private static String buildTsv(List<FixtureResult> results) {
        StringBuilder out = new StringBuilder(
                "fixture\tkAdaptation\tmae\trmse\tpearson\tobMae\tboundaryError"
                        + "\tsaturation\tcompletion\texhausted");
        for (int plane = 0; plane < 4; plane++) {
            out.append("\tplane").append(plane).append("Mae")
                    .append("\tplane").append(plane).append("Rmse")
                    .append("\tplane").append(plane).append("Pearson");
        }
        out.append('\n');
        for (FixtureResult result : results) {
            appendTsv(out, result.fixture, "HYBRID", result.hybrid);
            appendTsv(out, result.fixture, "SYMBOL_BRACKET", result.symbolBracket);
        }
        return out.toString();
    }

    private static void appendTsv(StringBuilder out, String fixture,
                                  String adaptation, VariantMetrics metrics) {
        out.append(fixture).append('\t').append(adaptation).append('\t')
                .append(metrics.global.mae).append('\t')
                .append(metrics.global.rmse).append('\t')
                .append(metrics.global.pearson).append('\t')
                .append(metrics.obMae).append('\t')
                .append(metrics.boundaryError).append('\t')
                .append(metrics.saturationFraction).append('\t')
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
        out.append("{\n")
                .append("  \"verdict\": \"").append(verdict.status).append("\",\n")
                .append("  \"successfulDiscriminators\": ")
                .append(verdict.successfulDiscriminators).append(",\n")
                .append("  \"noCompletionRegression\": ")
                .append(verdict.noCompletionRegression).append(",\n")
                .append("  \"results\": [\n");
        boolean first = true;
        for (FixtureResult result : results) {
            first = appendJsonResult(out, first, result.fixture, "HYBRID", result.hybrid);
            first = appendJsonResult(out, first, result.fixture, "SYMBOL_BRACKET",
                    result.symbolBracket);
        }
        out.append("\n  ]\n}\n");
        return out.toString();
    }

    private static boolean appendJsonResult(StringBuilder out, boolean first,
                                            String fixture, String adaptation,
                                            VariantMetrics metrics) {
        if (!first) {
            out.append(",\n");
        }
        out.append("    {\"fixture\":\"").append(jsonEscape(fixture))
                .append("\",\"kAdaptation\":\"").append(adaptation)
                .append("\",\"mae\":").append(jsonNumber(metrics.global.mae))
                .append(",\"rmse\":").append(jsonNumber(metrics.global.rmse))
                .append(",\"pearson\":").append(jsonNumber(metrics.global.pearson))
                .append(",\"obMae\":").append(jsonNumber(metrics.obMae))
                .append(",\"boundaryError\":").append(jsonNumber(metrics.boundaryError))
                .append(",\"saturation\":").append(jsonNumber(metrics.saturationFraction))
                .append(",\"completion\":").append(jsonNumber(metrics.completion))
                .append(",\"exhausted\":").append(metrics.exhausted)
                .append(",\"planes\":[");
        for (int plane = 0; plane < metrics.planeMetrics.size(); plane++) {
            if (plane > 0) {
                out.append(',');
            }
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
        if (!Files.isDirectory(SAMPLES_DIR)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(SAMPLES_DIR)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .toLowerCase(Locale.ROOT).endsWith(".cr3"))
                    .filter(path -> Files.isRegularFile(referenceTiffPath(path)))
                    .filter(KAdaptationDiscriminatorIT::isLossless)
                    .sorted(Comparator.comparing(KAdaptationDiscriminatorIT::fixtureName))
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
            if (input == null) {
                throw new IOException("Cannot open " + fixture);
            }
            Cr3Container container = Cr3Parser.parse(input);
            Cr3Track raw = container.tracks().stream()
                    .filter(track -> track.type() == Cr3Track.TrackType.CRX_BIG
                            || track.type() == Cr3Track.TrackType.CRX_RAW)
                    .findFirst()
                    .orElseThrow(() -> new IOException("No RAW track in " + fixture));
            Cmp1Box cmp1 = raw.cmp1();
            if (cmp1 == null) {
                throw new IOException("No CMP1 in " + fixture);
            }
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

    private record FixtureStructure(int width, int height, Cmp1Box cmp1, Iad1Box iad1) {
    }

    private record VariantDecode(int[] bayer, double completion, boolean exhausted) {
    }

    private record MetricTriple(double mae, double rmse, double pearson) {
    }

    private record VariantMetrics(MetricTriple global, double obMae,
                                  double boundaryError, List<MetricTriple> planeMetrics,
                                  double saturationFraction, double completion,
                                  boolean exhausted) {
    }

    private record FixtureResult(String fixture, VariantMetrics hybrid,
                                 VariantMetrics symbolBracket) {
    }

    private record Verdict(String status, int successfulDiscriminators,
                           double hybridMedianMae, double bracketMedianMae,
                           double hybridMedianBoundary, double bracketMedianBoundary,
                           double hybridMedianSaturation, double bracketMedianSaturation,
                           boolean noCompletionRegression) {
    }
}
