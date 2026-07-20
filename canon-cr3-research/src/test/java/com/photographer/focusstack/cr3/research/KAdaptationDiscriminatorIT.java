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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Fixed 11-fixture D6 K-adaptation discriminator from issue #175. */
class KAdaptationDiscriminatorIT {

    private static final Path SAMPLES = Path.of(System.getProperty(
            "cr3.samples.dir", "src/test/resources/samples"));
    private static final Path OUTPUT = Path.of(System.getProperty(
            "cr3.research.output.dir", "target")).resolve("k-adaptation-discriminator");

    private static final List<String> PANEL = List.of(
            "eosr-iso-high.cr3",
            "eosr-iso-low.cr3",
            "eosr-iso-mid.cr3",
            "research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3",
            "research/g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02.cr3",
            "research/g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02.cr3",
            "sample-iso-high.cr3",
            "sample-tele.cr3",
            "sample-wb-tungsten.cr3",
            "sample-wide.cr3",
            "sample.cr3");

    private static final Set<String> DISCRIMINATORS = Set.of(
            "sample-tele.cr3",
            "sample-wide.cr3",
            "research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3");

    private enum Variant {
        HYBRID(CrxNormativeLosslessCandidate.KAdaptation.HYBRID_ACCUMULATOR),
        BRACKET(CrxNormativeLosslessCandidate.KAdaptation.SYMBOL_BRACKET);

        final CrxNormativeLosslessCandidate.KAdaptation adaptation;
        Variant(CrxNormativeLosslessCandidate.KAdaptation adaptation) {
            this.adaptation = adaptation;
        }
    }

    @Test
    void compareExactlyThePredeclaredPanel() throws Exception {
        List<Path> fixtures = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String name : PANEL) {
            Path fixture = SAMPLES.resolve(name);
            if (!Files.isRegularFile(fixture) || !Files.isRegularFile(referenceTiff(fixture))) {
                missing.add(name);
            } else {
                fixtures.add(fixture);
            }
        }
        assumeTrue(missing.isEmpty(), () -> "Missing fixed D6 fixtures/references: " + missing);
        assertThat(fixtures).hasSize(PANEL.size());

        Files.createDirectories(OUTPUT);
        List<FixtureResult> results = new ArrayList<>();
        for (Path fixture : fixtures) results.add(analyse(fixture));
        Verdict verdict = verdict(results);

        Files.writeString(OUTPUT.resolve("results.md"), markdown(results, verdict));
        Files.writeString(OUTPUT.resolve("results.tsv"), tsv(results));
        Files.writeString(OUTPUT.resolve("results.json"), json(results, verdict));

        assertThat(results).hasSize(PANEL.size());
        assertThat(OUTPUT.resolve("results.md")).exists();
        assertThat(OUTPUT.resolve("results.tsv")).exists();
        assertThat(OUTPUT.resolve("results.json")).exists();
    }

    private static FixtureResult analyse(Path fixture) throws Exception {
        ReferenceImage reference = ReferenceImageLoader.load(referenceTiff(fixture));
        Structure structure = structure(fixture);
        assertThat(structure.cmp1().isLossless()).isTrue();
        assertThat(reference.width()).isEqualTo(structure.width());
        assertThat(reference.height()).isEqualTo(structure.height());

        Decode hybrid = decode(fixture, structure, Variant.HYBRID);
        Decode bracket = decode(fixture, structure, Variant.BRACKET);
        return new FixtureResult(name(fixture),
                metrics(reference, structure, hybrid),
                metrics(reference, structure, bracket));
    }

    private static Decode decode(Path fixture, Structure structure, Variant variant)
            throws Exception {
        List<SubbandExtractor.SubbandRef> refs = SubbandExtractor.list(fixture.toFile()).stream()
                .filter(r -> r.waveletLevels() == 0 && r.subbandIndex() == 0)
                .sorted(Comparator.comparingInt(SubbandExtractor.SubbandRef::tileSequence)
                        .thenComparingInt(SubbandExtractor.SubbandRef::planeIndex))
                .toList();
        assertThat(refs).isNotEmpty();

        Map<Integer, List<SubbandExtractor.SubbandRef>> tiles = refs.stream()
                .collect(Collectors.groupingBy(SubbandExtractor.SubbandRef::tileSequence,
                        TreeMap::new, Collectors.toList()));
        int planeWidth = (structure.width() + 1) / 2;
        int planeHeight = (structure.height() + 1) / 2;
        int[][] planes = new int[4][planeWidth * planeHeight];
        int xOffset = 0;
        long decodedSymbols = 0;
        long capacity = 0;
        boolean exhausted = false;
        int tileOrdinal = 0;

        for (List<SubbandExtractor.SubbandRef> tile : tiles.values()) {
            tile.sort(Comparator.comparingInt(SubbandExtractor.SubbandRef::planeIndex));
            assertThat(tile).hasSize(4);
            int remaining = planeWidth - xOffset;
            int width = tileOrdinal == tiles.size() - 1
                    ? remaining : Math.min(tile.getFirst().planeWidth(), remaining);
            int height = Math.min(tile.getFirst().planeHeight(), planeHeight);
            for (SubbandExtractor.SubbandRef ref : tile) {
                CrxNormativeLosslessCandidate.Options options = options(variant);
                CrxNormativeLosslessCandidate.Result result =
                        CrxNormativeLosslessCandidate.decodePlane(
                                SubbandExtractor.read(fixture.toFile(), ref),
                                width, height, structure.cmp1().bitsPerSample(), options);
                int plane = ref.planeIndex();
                for (int y = 0; y < height; y++) {
                    System.arraycopy(result.pixels, y * width,
                            planes[plane], y * planeWidth + xOffset, width);
                }
                decodedSymbols += result.symbolsDecoded;
                capacity += (long) width * height;
                exhausted |= result.streamExhausted;
            }
            xOffset += width;
            tileOrdinal++;
        }
        assertThat(xOffset).isEqualTo(planeWidth);
        return new Decode(interleave(planes, structure.width(), structure.height(),
                        planeWidth, planeHeight),
                capacity == 0 ? 0.0 : (double) decodedSymbols / capacity,
                exhausted);
    }

    private static CrxNormativeLosslessCandidate.Options options(Variant variant) {
        CrxNormativeLosslessCandidate.Options o = new CrxNormativeLosslessCandidate.Options();
        o.kInit = 1;
        o.resetKPerRow = true;
        o.unaryOnesUntilZero = true;
        o.signBitMapping = true;
        o.escapeAtUnary15 = false;
        o.firstPixelSeed = 0;
        o.kAdaptation = variant.adaptation;
        return o;
    }

    private static int[] interleave(int[][] planes, int width, int height,
                                    int planeWidth, int planeHeight) {
        int[] out = new int[width * height];
        for (int py = 0; py < planeHeight; py++) {
            for (int px = 0; px < planeWidth; px++) {
                int i = py * planeWidth + px;
                int x = px * 2;
                int y = py * 2;
                if (y < height && x < width) out[y * width + x] = planes[0][i];
                if (y < height && x + 1 < width) out[y * width + x + 1] = planes[1][i];
                if (y + 1 < height && x < width) out[(y + 1) * width + x] = planes[2][i];
                if (y + 1 < height && x + 1 < width) out[(y + 1) * width + x + 1] = planes[3][i];
            }
        }
        return out;
    }

    private static VariantMetrics metrics(ReferenceImage reference, Structure structure,
                                          Decode decode) {
        Metric global = metric(ReferenceMetrics.compare(reference.samples(), decode.pixels()));
        List<Metric> planes = new ArrayList<>();
        for (int plane = 0; plane < 4; plane++) {
            planes.add(planeMetric(reference.samples(), decode.pixels(),
                    reference.width(), reference.height(), plane));
        }
        double ob = opticalBlack(reference.samples(), decode.pixels(),
                reference.width(), reference.height(), structure.iad1());
        double boundary = structure.cmp1().tileWidth() <= 0 || structure.cmp1().tileHeight() <= 0
                ? Double.NaN
                : SpatialReferenceMetrics.tileBoundaryMae(
                        reference.samples(), decode.pixels(), reference.width(), reference.height(),
                        structure.cmp1().tileWidth(), structure.cmp1().tileHeight());
        return new VariantMetrics(global, ob, boundary, planes,
                saturation(decode.pixels(), structure.cmp1().bitsPerSample()),
                decode.completion(), decode.exhausted());
    }

    private static double opticalBlack(int[] expected, int[] actual, int width, int height,
                                       Iad1Box iad1) {
        if (iad1 == null) return Double.NaN;
        List<SpatialReferenceMetrics.Rect> regions = List.of(
                        SpatialReferenceMetrics.Rect.clip(iad1.leftObLeft(), iad1.leftObTop(),
                                iad1.leftObRight(), iad1.leftObBottom(), width, height),
                        SpatialReferenceMetrics.Rect.clip(iad1.topObLeft(), iad1.topObTop(),
                                iad1.topObRight(), iad1.topObBottom(), width, height))
                .stream().filter(r -> !r.isEmpty()).toList();
        return regions.isEmpty() ? Double.NaN
                : SpatialReferenceMetrics.compareRegions(expected, actual, width, height, regions).mae();
    }

    private static Metric planeMetric(int[] expected, int[] actual,
                                      int width, int height, int plane) {
        int row = plane / 2;
        int col = plane % 2;
        int count = ((height - row + 1) / 2) * ((width - col + 1) / 2);
        int[] e = new int[count];
        int[] a = new int[count];
        int i = 0;
        for (int y = row; y < height; y += 2) {
            for (int x = col; x < width; x += 2) {
                e[i] = expected[y * width + x];
                a[i++] = actual[y * width + x];
            }
        }
        return metric(ReferenceMetrics.compare(e, a));
    }

    private static Metric metric(ReferenceMetrics.Metrics m) {
        return new Metric(m.mae(), m.rmse(), m.pearson());
    }

    private static double saturation(int[] pixels, int bitDepth) {
        int max = (1 << bitDepth) - 1;
        long count = 0;
        for (int pixel : pixels) if (pixel == max) count++;
        return pixels.length == 0 ? 0.0 : (double) count / pixels.length;
    }

    private static Verdict verdict(List<FixtureResult> results) {
        int successes = 0;
        boolean completionOkay = true;
        for (FixtureResult r : results) {
            if (DISCRIMINATORS.contains(r.fixture())
                    && r.bracket().global().mae() < r.hybrid().global().mae()
                    && finiteImprovement(r.bracket().obMae(), r.hybrid().obMae())) successes++;
            if (r.bracket().completion() + 1e-12 < r.hybrid().completion()
                    || (r.bracket().exhausted() && !r.hybrid().exhausted())) completionOkay = false;
        }
        double hMae = median(results.stream().map(r -> r.hybrid().global().mae()).toList());
        double bMae = median(results.stream().map(r -> r.bracket().global().mae()).toList());
        double hBoundary = medianFinite(results.stream().map(r -> r.hybrid().boundary()).toList());
        double bBoundary = medianFinite(results.stream().map(r -> r.bracket().boundary()).toList());
        double hSat = median(results.stream().map(r -> r.hybrid().saturation()).toList());
        double bSat = median(results.stream().map(r -> r.bracket().saturation()).toList());
        boolean corpus = bMae <= hMae && (Double.isNaN(hBoundary) || bBoundary <= hBoundary)
                && bSat <= hSat;
        String state = successes >= 2 && corpus && completionOkay ? "SUPPORTED"
                : successes >= 1 || bMae < hMae ? "PARTIAL" : "REJECTED";
        return new Verdict(state, successes, hMae, bMae, hBoundary, bBoundary,
                hSat, bSat, completionOkay);
    }

    private static boolean finiteImprovement(double candidate, double baseline) {
        return Double.isFinite(candidate) && Double.isFinite(baseline) && candidate < baseline;
    }

    private static double median(List<Double> values) {
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int n = sorted.length;
        if (n == 0) return Double.NaN;
        return n % 2 == 0 ? (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0 : sorted[n / 2];
    }

    private static double medianFinite(List<Double> values) {
        return median(values.stream().filter(Double::isFinite).toList());
    }

    private static String markdown(List<FixtureResult> results, Verdict v) {
        StringBuilder out = new StringBuilder("# D6 K-Adaptation Discriminator\n\n");
        out.append("Fixed panel: exactly 11 reference-backed lossless fixtures.\n\n")
                .append("## Verdict: **").append(v.state()).append("**\n\n")
                .append("- successful discriminator fixtures: ").append(v.successes()).append(" / 3\n")
                .append("- median global MAE HYBRID/SYMBOL_BRACKET: ")
                .append(fmt(v.hybridMae())).append(" / ").append(fmt(v.bracketMae())).append('\n')
                .append("- median boundary HYBRID/SYMBOL_BRACKET: ")
                .append(fmt(v.hybridBoundary())).append(" / ").append(fmt(v.bracketBoundary())).append('\n')
                .append("- median saturation HYBRID/SYMBOL_BRACKET: ")
                .append(fmt(v.hybridSaturation())).append(" / ")
                .append(fmt(v.bracketSaturation())).append('\n')
                .append("- no completion regression: ").append(v.completionOkay()).append("\n\n")
                .append("| Fixture | K update | MAE | RMSE | Pearson | OB MAE | Boundary | Saturation | Completion | Exhausted |\n")
                .append("|---|---|---:|---:|---:|---:|---:|---:|---:|:---:|\n");
        for (FixtureResult r : results) {
            row(out, r.fixture(), "HYBRID", r.hybrid());
            row(out, r.fixture(), "SYMBOL_BRACKET", r.bracket());
        }
        return out.toString();
    }

    private static void row(StringBuilder out, String fixture, String variant, VariantMetrics m) {
        out.append("| `").append(fixture).append("` | ").append(variant)
                .append(" | ").append(fmt(m.global().mae()))
                .append(" | ").append(fmt(m.global().rmse()))
                .append(" | ").append(fmt(m.global().pearson()))
                .append(" | ").append(fmt(m.obMae()))
                .append(" | ").append(fmt(m.boundary()))
                .append(" | ").append(fmt(m.saturation()))
                .append(" | ").append(fmt(m.completion()))
                .append(" | ").append(m.exhausted() ? "yes" : "no").append(" |\n");
    }

    private static String tsv(List<FixtureResult> results) {
        StringBuilder out = new StringBuilder("fixture\tkAdaptation\tmae\trmse\tpearson\tobMae\tboundary\tsaturation\tcompletion\texhausted");
        for (int p = 0; p < 4; p++) out.append("\tp").append(p).append("Mae\tp").append(p).append("Rmse\tp").append(p).append("Pearson");
        out.append('\n');
        for (FixtureResult r : results) {
            tsvRow(out, r.fixture(), "HYBRID", r.hybrid());
            tsvRow(out, r.fixture(), "SYMBOL_BRACKET", r.bracket());
        }
        return out.toString();
    }

    private static void tsvRow(StringBuilder out, String fixture, String variant, VariantMetrics m) {
        out.append(fixture).append('\t').append(variant).append('\t')
                .append(m.global().mae()).append('\t').append(m.global().rmse()).append('\t')
                .append(m.global().pearson()).append('\t').append(m.obMae()).append('\t')
                .append(m.boundary()).append('\t').append(m.saturation()).append('\t')
                .append(m.completion()).append('\t').append(m.exhausted());
        for (Metric p : m.planes()) out.append('\t').append(p.mae()).append('\t').append(p.rmse()).append('\t').append(p.pearson());
        out.append('\n');
    }

    private static String json(List<FixtureResult> results, Verdict v) {
        StringBuilder out = new StringBuilder("{\n  \"verdict\": \"").append(v.state())
                .append("\",\n  \"successfulDiscriminators\": ").append(v.successes())
                .append(",\n  \"noCompletionRegression\": ").append(v.completionOkay())
                .append(",\n  \"results\": [\n");
        boolean first = true;
        for (FixtureResult r : results) {
            first = jsonRow(out, first, r.fixture(), "HYBRID", r.hybrid());
            first = jsonRow(out, first, r.fixture(), "SYMBOL_BRACKET", r.bracket());
        }
        return out.append("\n  ]\n}\n").toString();
    }

    private static boolean jsonRow(StringBuilder out, boolean first, String fixture,
                                   String variant, VariantMetrics m) {
        if (!first) out.append(",\n");
        out.append("    {\"fixture\":\"").append(escape(fixture))
                .append("\",\"kAdaptation\":\"").append(variant)
                .append("\",\"mae\":").append(number(m.global().mae()))
                .append(",\"rmse\":").append(number(m.global().rmse()))
                .append(",\"pearson\":").append(number(m.global().pearson()))
                .append(",\"obMae\":").append(number(m.obMae()))
                .append(",\"boundary\":").append(number(m.boundary()))
                .append(",\"saturation\":").append(number(m.saturation()))
                .append(",\"completion\":").append(number(m.completion()))
                .append(",\"exhausted\":").append(m.exhausted()).append(",\"planes\":[");
        for (int i = 0; i < m.planes().size(); i++) {
            if (i > 0) out.append(',');
            Metric p = m.planes().get(i);
            out.append("{\"plane\":").append(i).append(",\"mae\":").append(number(p.mae()))
                    .append(",\"rmse\":").append(number(p.rmse()))
                    .append(",\"pearson\":").append(number(p.pearson())).append('}');
        }
        out.append("]}");
        return false;
    }

    private static String fmt(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.4f", value) : "n/a";
    }

    private static String number(double value) {
        return Double.isFinite(value) ? Double.toString(value) : "null";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static Structure structure(Path fixture) throws Exception {
        try (ImageInputStream input = ImageIO.createImageInputStream(fixture.toFile())) {
            if (input == null) throw new IOException("Cannot open " + fixture);
            Cr3Container container = Cr3Parser.parse(input);
            Cr3Track raw = container.tracks().stream()
                    .filter(t -> t.type() == Cr3Track.TrackType.CRX_BIG
                            || t.type() == Cr3Track.TrackType.CRX_RAW)
                    .findFirst().orElseThrow(() -> new IOException("No RAW track in " + fixture));
            Cmp1Box cmp1 = raw.cmp1();
            if (cmp1 == null) throw new IOException("No CMP1 in " + fixture);
            return new Structure(cmp1.imageWidth(), cmp1.imageHeight(), cmp1, raw.iad1());
        }
    }

    private static Path referenceTiff(Path fixture) {
        return fixture.resolveSibling(fixture.getFileName().toString()
                .replaceAll("(?i)\\.cr3$", ".reference.tiff"));
    }

    private static String name(Path fixture) {
        return SAMPLES.relativize(fixture).toString().replace('\\', '/');
    }

    private record Structure(int width, int height, Cmp1Box cmp1, Iad1Box iad1) {}
    private record Decode(int[] pixels, double completion, boolean exhausted) {}
    private record Metric(double mae, double rmse, double pearson) {}
    private record VariantMetrics(Metric global, double obMae, double boundary,
                                  List<Metric> planes, double saturation,
                                  double completion, boolean exhausted) {}
    private record FixtureResult(String fixture, VariantMetrics hybrid,
                                 VariantMetrics bracket) {}
    private record Verdict(String state, int successes, double hybridMae,
                           double bracketMae, double hybridBoundary,
                           double bracketBoundary, double hybridSaturation,
                           double bracketSaturation, boolean completionOkay) {}
}
