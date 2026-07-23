package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.bmff.Iad1Box;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.decode.Cr3Decoder;
import com.photographer.focusstack.cr3.metadata.Cr3Metadata;
import com.photographer.focusstack.cr3.processing.BlackLevel;
import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import com.photographer.focusstack.cr3.raw.codec.CrxHeader;
import com.photographer.focusstack.cr3.raw.codec.CrxLosslessRawDecoder;
import com.photographer.focusstack.cr3.reference.ReferenceFixtureResolver;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferUShort;
import java.awt.image.Raster;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PedestalHypothesisValidationIT {

    private static final Path SAMPLES_DIR = Path.of("src", "test", "resources", "samples");
    private static final ReferenceFixtureResolver FIXTURES =
            new ReferenceFixtureResolver(SAMPLES_DIR);
    private static final String STABLE_REPORT_NAME = "pedestal-hypothesis-validation.md";
    private static final Path DOCS_REPORT = Path.of("..", "docs", "cr3-results", STABLE_REPORT_NAME);
    private static final Path DOCS_IMAGE_DIR = Path.of("..", "docs", "cr3-results", "images", "pedestal-hypothesis-validation");
    private static final Path TARGET_DIR = Path.of("target", "pedestal-hypothesis-validation");
    private static final Path TARGET_IMAGE_DIR = TARGET_DIR.resolve("images");

    @Test
    void validatePedestalHypothesisAgainstReference() throws Exception {
        List<Path> fixtures = discoverFixtures();
        assumeTrue(!fixtures.isEmpty(), "No reference-backed lossless CR3 fixtures found");

        Files.createDirectories(DOCS_IMAGE_DIR);
        Files.createDirectories(TARGET_IMAGE_DIR);
        Files.createDirectories(TARGET_DIR);

        List<FixtureResult> results = new ArrayList<>();
        for (int i = 0; i < fixtures.size(); i++) {
            results.add(analyseFixture(fixtures.get(i), i < 3));
            System.gc();
        }

        Path metadataVsDeltaPlot = writeScatterPlot(results,
                "Metadata black level vs optimal Δ",
                "Resolved metadata/pedestal level",
                "Optimal Δ",
                FixtureResult::resolvedBlackLevelScalar,
                FixtureResult::optimalDelta,
                "metadata-vs-optimal-delta.png");

        Path maeBeforeAfterPlot = writeScatterPlot(results,
                "MAE before vs after experimental pedestal",
                "Baseline MAE",
                "Experimental MAE",
                r -> r.baselineMetrics().mae(),
                r -> r.experimentalMetrics().mae(),
                "mae-before-after.png");

        String report = buildReport(results, metadataVsDeltaPlot, maeBeforeAfterPlot);
        Files.writeString(DOCS_REPORT, report);
        Files.writeString(TARGET_DIR.resolve(STABLE_REPORT_NAME), report);

        assertThat(results).isNotEmpty();
        assertThat(results).allSatisfy(result -> {
            assertThat(result.baselineMetrics().mae()).isFinite();
            assertThat(result.experimentalMetrics().mae()).isFinite();
            assertThat(result.optimalDelta()).isFinite();
        });
    }

    private static FixtureResult analyseFixture(Path fixture, boolean includeExampleImages) throws Exception {
        ReferenceImage reference = loadReference(
                FIXTURES.resolve(fixture).requiredTiff());
        DecodedFixture decoded = decodeFixture(fixture);
        assertThat(decoded.raw().width()).as("width for %s", fixture.getFileName()).isEqualTo(reference.width());
        assertThat(decoded.raw().height()).as("height for %s", fixture.getFileName()).isEqualTo(reference.height());

        Cr3Decoder.PedestalReport pedestalReport;
        Cr3Metadata metadata;
        try (Cr3Decoder decoder = new Cr3Decoder()) {
            decoder.setInput(fixture);
            pedestalReport = decoder.pedestalReport();
            metadata = decoder.getMetadata();
        }

        int[] actual = decoded.raw().data();
        int[] referenceSamples = reference.samples();
        MetricTriple baselineMetrics = metricTriple(referenceSamples, actual, 0);
        ObMetrics baselineObMetrics = computeOpticalBlackMetrics(referenceSamples, actual,
                decoded.raw().width(), decoded.raw().height(), decoded.iad1(), 0);

        int optimalDelta = optimalDeltaByMedian(referenceSamples, actual);
        MetricTriple experimentalMetrics = metricTriple(referenceSamples, actual, optimalDelta);
        ObMetrics experimentalObMetrics = computeOpticalBlackMetrics(referenceSamples, actual,
                decoded.raw().width(), decoded.raw().height(), decoded.iad1(), optimalDelta);

        double resolvedBlackLevelScalar = resolveBlackLevelScalar(
                pedestalReport.blackLevel(), decoded.raw().bayerPattern(), decoded.raw().width(), decoded.raw().height());

        ExampleImages images = includeExampleImages
                ? writeExampleImages(
                fixture,
                referenceSamples,
                actual,
                decoded.raw().width(),
                decoded.raw().height(),
                decoded.iad1(),
                optimalDelta)
                : new ExampleImages("", "", "");

        return new FixtureResult(
                FIXTURES.samplesRoot().relativize(fixture).toString().replace('\\', '/'),
                metadata != null && metadata.cameraModel() != null ? metadata.cameraModel() : "unknown",
                pedestalReport.source().name(),
                pedestalReport.blackLevel().mode().name(),
                formatResolvedLevels(pedestalReport.blackLevel()),
                resolvedBlackLevelScalar,
                optimalDelta,
                optimalDelta - resolvedBlackLevelScalar,
                baselineMetrics,
                experimentalMetrics,
                baselineObMetrics,
                experimentalObMetrics,
                images.referenceImage(),
                images.baselineDiffImage(),
                images.experimentalDiffImage()
        );
    }

    private static String buildReport(List<FixtureResult> results, Path metadataVsDeltaPlot, Path maeBeforeAfterPlot) {
        StringBuilder md = new StringBuilder(24_000);
        md.append("# Pedestal Hypothesis Validation\n\n");
        md.append("Generated: ").append(Instant.now()).append("\n\n");
        md.append("## Methodik\n\n");
        md.append("1. Für jede lossless-CR3-Fixture wurde `pedestal-report` über `Cr3Decoder.pedestalReport()` erfasst.\n");
        md.append("2. Gegen die vorhandenen `*.reference.tiff` Sidecars wurde für jede Fixture ein konstanter Offset Δ gesucht, der MAE minimiert.\n");
        md.append("3. Baseline- und Experimental-Metriken (Decoder+Δ) wurden auf denselben RAW-Mosaikdaten berechnet.\n");
        md.append("4. Metadata/Pedestal-Level wurden gegen optimale Δ-Werte korreliert.\n\n");

        md.append("## Schritt 1 – Pedestal Baseline\n\n");
        md.append("| Fixture | Kamera | Quelle | Modus | Werte |\n");
        md.append("|---|---|---|---|---|\n");
        for (FixtureResult result : results) {
            md.append("| `").append(result.fixture()).append("` | ")
                    .append(escapePipes(result.cameraModel())).append(" | ")
                    .append(result.pedestalSource()).append(" | ")
                    .append(result.pedestalMode()).append(" | ")
                    .append(escapePipes(result.resolvedLevels())).append(" |\n");
        }

        md.append("\n## Schritt 2 – Referenzbasiertes Optimierungsproblem\n\n");
        md.append("| Fixture | Optimales Δ | MAE vorher | MAE nachher | RMSE nachher | Pearson nachher | OB-MAE nachher |\n");
        md.append("|---|---:|---:|---:|---:|---:|---:|\n");
        for (FixtureResult result : results) {
            md.append("| `").append(result.fixture()).append("` | ")
                    .append(fmt(result.optimalDelta())).append(" | ")
                    .append(fmt(result.baselineMetrics().mae())).append(" | ")
                    .append(fmt(result.experimentalMetrics().mae())).append(" | ")
                    .append(fmt(result.experimentalMetrics().rmse())).append(" | ")
                    .append(fmt(result.experimentalMetrics().pearson())).append(" | ")
                    .append(fmt(result.experimentalObMetrics().mae())).append(" |\n");
        }

        md.append("\n## Schritt 3 – Vergleich mit Pedestal-Report\n\n");
        md.append("| Fixture | Resolved BlackLevel (Skalar) | Optimales Δ | DeltaMetadata = Δ - Resolved |\n");
        md.append("|---|---:|---:|---:|\n");
        for (FixtureResult result : results) {
            md.append("| `").append(result.fixture()).append("` | ")
                    .append(fmt(result.resolvedBlackLevelScalar())).append(" | ")
                    .append(fmt(result.optimalDelta())).append(" | ")
                    .append(fmt(result.deltaMetadata())).append(" |\n");
        }

        md.append("\n## Schritt 4 – Korrelation\n\n");
        md.append("- Scatterplot Metadata-BlackLevel vs Optimales Δ:\n\n");
        md.append("![](images/pedestal-hypothesis-validation/")
                .append(metadataVsDeltaPlot.getFileName())
                .append(")\n\n");

        md.append("## Schritt 5/6 – Experimental Pedestal Regression\n\n");
        md.append("| Fixture | Baseline MAE | Experimental MAE | Baseline RMSE | Experimental RMSE | Baseline Pearson | Experimental Pearson | Baseline OB-StdDev | Experimental OB-StdDev |\n");
        md.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (FixtureResult result : results) {
            md.append("| `").append(result.fixture()).append("` | ")
                    .append(fmt(result.baselineMetrics().mae())).append(" | ")
                    .append(fmt(result.experimentalMetrics().mae())).append(" | ")
                    .append(fmt(result.baselineMetrics().rmse())).append(" | ")
                    .append(fmt(result.experimentalMetrics().rmse())).append(" | ")
                    .append(fmt(result.baselineMetrics().pearson())).append(" | ")
                    .append(fmt(result.experimentalMetrics().pearson())).append(" | ")
                    .append(fmt(result.baselineObMetrics().stddev())).append(" | ")
                    .append(fmt(result.experimentalObMetrics().stddev())).append(" |\n");
        }

        md.append("\n- Scatterplot MAE vorher/nachher:\n\n");
        md.append("![](images/pedestal-hypothesis-validation/")
                .append(maeBeforeAfterPlot.getFileName())
                .append(")\n\n");

        md.append("## Beispielbilder\n\n");
        for (FixtureResult result : results.stream().filter(r -> !r.referenceImage().isEmpty()).toList()) {
            md.append("### `").append(result.fixture()).append("`\n\n");
            md.append("| Reference | Baseline-Diff | Experimental-Diff |\n");
            md.append("|---|---|---|\n");
            md.append("| ![](").append(result.referenceImage()).append(") | ")
                    .append("![](").append(result.baselineDiffImage()).append(") | ")
                    .append("![](").append(result.experimentalDiffImage()).append(") |\n\n");
        }

        md.append("## Schlussfolgerung\n\n");
        HypothesisDecision decision = evaluateHypothesis(results);
        md.append("- Entscheidung: **").append(decision.label()).append("**\n");
        md.append("- Begründung:\n");
        for (String reason : decision.reasons()) {
            md.append("  - ").append(reason).append("\n");
        }
        return md.toString();
    }

    private static HypothesisDecision evaluateHypothesis(List<FixtureResult> results) {
        long improvedMae = results.stream()
                .filter(r -> reductionPercent(r.baselineMetrics().mae(), r.experimentalMetrics().mae()) > 20.0)
                .count();
        long improvedObMae = results.stream()
                .filter(r -> r.experimentalObMetrics().mae() < r.baselineObMetrics().mae())
                .count();
        long worsenedPearson = results.stream()
                .filter(r -> r.experimentalMetrics().pearson() + 1e-9 < r.baselineMetrics().pearson())
                .count();

        Map<String, Long> perCameraSupport = new HashMap<>();
        for (FixtureResult result : results) {
            if (reductionPercent(result.baselineMetrics().mae(), result.experimentalMetrics().mae()) > 20.0) {
                perCameraSupport.merge(result.cameraModel(), 1L, Long::sum);
            }
        }
        long cameraModelsSupported = perCameraSupport.entrySet().stream().filter(e -> e.getValue() > 0).count();

        List<String> reasons = new ArrayList<>();
        reasons.add("MAE >20% verbessert bei " + improvedMae + "/" + results.size() + " Fixtures.");
        reasons.add("OB-MAE verbessert bei " + improvedObMae + "/" + results.size() + " Fixtures.");
        reasons.add("Pearson verschlechtert bei " + worsenedPearson + "/" + results.size() + " Fixtures.");
        reasons.add("Verbesserung über " + cameraModelsSupported + " Kameramodelle reproduzierbar.");

        boolean supported = improvedMae >= Math.max(2, results.size() / 2)
                && improvedObMae >= Math.max(2, results.size() / 2)
                && worsenedPearson <= results.size() / 4
                && cameraModelsSupported >= 2;

        return new HypothesisDecision(supported ? "SUPPORTED" : "REJECTED", reasons);
    }

    private static double reductionPercent(double baseline, double improved) {
        if (!Double.isFinite(baseline) || baseline == 0.0) {
            return 0.0;
        }
        return (baseline - improved) * 100.0 / baseline;
    }

    private static String formatResolvedLevels(BlackLevel blackLevel) {
        return switch (blackLevel.mode()) {
            case GLOBAL -> Integer.toString(blackLevel.globalLevel());
            case PER_CHANNEL -> Arrays.toString(blackLevel.perChannelLevels());
            case PER_ROW -> "minPerRow=" + blackLevel.minPerRowLevel();
        };
    }

    private static double resolveBlackLevelScalar(BlackLevel blackLevel,
                                                  Cr3RawImage.BayerPattern pattern,
                                                  int width,
                                                  int height) {
        if (blackLevel.mode() == BlackLevel.Mode.GLOBAL) {
            return blackLevel.globalLevel();
        }

        long evenCols = (width + 1L) / 2L;
        long oddCols = width / 2L;
        double sum = 0.0;
        long count = 0;
        for (int y = 0; y < height; y++) {
            int evenLevel = blackLevel.levelAt(0, y, pattern);
            int oddLevel = blackLevel.levelAt(1, y, pattern);
            sum += evenLevel * evenCols + oddLevel * oddCols;
            count += width;
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private static int optimalDeltaByMedian(int[] reference, int[] actual) {
        assertThat(actual.length).isEqualTo(reference.length);
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < reference.length; i++) {
            int diff = reference[i] - actual[i];
            if (diff < min) min = diff;
            if (diff > max) max = diff;
        }

        int range = max - min + 1;
        if (range > 200_000) {
            return approximateMedian(reference, actual);
        }

        long[] histogram = new long[range];
        for (int i = 0; i < reference.length; i++) {
            histogram[reference[i] - actual[i] - min]++;
        }

        long half = (reference.length + 1L) / 2L;
        long cumulative = 0;
        for (int i = 0; i < histogram.length; i++) {
            cumulative += histogram[i];
            if (cumulative >= half) {
                return i + min;
            }
        }
        return 0;
    }

    private static int approximateMedian(int[] reference, int[] actual) {
        int sampleSize = Math.min(reference.length, 1_000_000);
        int[] diffs = new int[sampleSize];
        int step = Math.max(1, reference.length / sampleSize);
        int idx = 0;
        for (int i = 0; i < reference.length && idx < sampleSize; i += step) {
            diffs[idx++] = reference[i] - actual[i];
        }
        Arrays.sort(diffs, 0, idx);
        return idx == 0 ? 0 : diffs[idx / 2];
    }

    private static MetricTriple metricTriple(int[] reference, int[] actual, int delta) {
        assertThat(actual.length).isEqualTo(reference.length);
        double absSum = 0;
        double sqSum = 0;
        double sumRef = 0;
        double sumActual = 0;
        double sumRefSq = 0;
        double sumActualSq = 0;
        double sumCross = 0;
        for (int i = 0; i < reference.length; i++) {
            double ref = reference[i];
            double act = actual[i] + (double) delta;
            double diff = act - ref;
            absSum += Math.abs(diff);
            sqSum += diff * diff;
            sumRef += ref;
            sumActual += act;
            sumRefSq += ref * ref;
            sumActualSq += act * act;
            sumCross += ref * act;
        }
        double count = reference.length;
        double meanRef = sumRef / count;
        double meanActual = sumActual / count;
        double covariance = sumCross / count - meanRef * meanActual;
        double varianceRef = sumRefSq / count - meanRef * meanRef;
        double varianceActual = sumActualSq / count - meanActual * meanActual;
        double denom = Math.sqrt(Math.max(varianceRef, 0.0) * Math.max(varianceActual, 0.0));
        double pearson = denom == 0.0 ? 1.0 : covariance / denom;
        return new MetricTriple(absSum / count, Math.sqrt(sqSum / count), pearson);
    }

    private static ObMetrics computeOpticalBlackMetrics(int[] reference, int[] actual, int width, int height,
                                                        Iad1Box iad1, int delta) {
        if (iad1 == null) {
            return new ObMetrics(Double.NaN, Double.NaN);
        }
        List<VisibleRect> rects = new ArrayList<>(2);
        VisibleRect left = toRect(iad1.leftObLeft(), iad1.leftObTop(), iad1.leftObRight(), iad1.leftObBottom(), width, height);
        VisibleRect top = toRect(iad1.topObLeft(), iad1.topObTop(), iad1.topObRight(), iad1.topObBottom(), width, height);
        if (left.width() > 0 && left.height() > 0) rects.add(left);
        if (top.width() > 0 && top.height() > 0) rects.add(top);
        if (rects.isEmpty()) {
            return new ObMetrics(Double.NaN, Double.NaN);
        }

        double absSum = 0;
        double diffSum = 0;
        double diffSqSum = 0;
        long count = 0;
        for (VisibleRect rect : rects) {
            for (int y = rect.y(); y < rect.y() + rect.height(); y++) {
                int rowBase = y * width;
                for (int x = rect.x(); x < rect.x() + rect.width(); x++) {
                    int diff = actual[rowBase + x] + delta - reference[rowBase + x];
                    absSum += Math.abs(diff);
                    diffSum += diff;
                    diffSqSum += (double) diff * diff;
                    count++;
                }
            }
        }
        if (count == 0) {
            return new ObMetrics(Double.NaN, Double.NaN);
        }
        double mean = diffSum / count;
        double variance = Math.max(0.0, diffSqSum / count - mean * mean);
        return new ObMetrics(absSum / count, Math.sqrt(variance));
    }

    private static ExampleImages writeExampleImages(Path fixture,
                                                    int[] reference,
                                                    int[] actual,
                                                    int width,
                                                    int height,
                                                    Iad1Box iad1,
                                                    int optimalDelta) throws IOException {
        VisibleRect visible = resolveVisibleRect(iad1, width, height);
        VisualScale scale = computeScale(reference, width, visible);

        BufferedImage referenceImg = buildRawVisual(reference, width, visible, scale, 1200, 800);
        BufferedImage baselineDiff = buildDifferenceVisual(reference, actual, width, visible, 0, 1200, 800);
        BufferedImage experimentalDiff = buildDifferenceVisual(reference, actual, width, visible, optimalDelta, 1200, 800);

        String stem = fixture.getFileName().toString().replaceAll("\\.cr3$", "");
        Path referencePath = writeImage(stem + "-reference", referenceImg);
        Path baselinePath = writeImage(stem + "-baseline-diff", baselineDiff);
        Path experimentalPath = writeImage(stem + "-experimental-diff", experimentalDiff);

        return new ExampleImages(
                DOCS_REPORT.getParent().relativize(referencePath).toString().replace('\\', '/'),
                DOCS_REPORT.getParent().relativize(baselinePath).toString().replace('\\', '/'),
                DOCS_REPORT.getParent().relativize(experimentalPath).toString().replace('\\', '/')
        );
    }

    private static Path writeScatterPlot(List<FixtureResult> results,
                                         String title,
                                         String xLabel,
                                         String yLabel,
                                         ValueExtractor xExtractor,
                                         ValueExtractor yExtractor,
                                         String fileName) throws IOException {
        int width = 1400;
        int height = 1000;
        int left = 120;
        int right = 80;
        int top = 100;
        int bottom = 120;
        int plotWidth = width - left - right;
        int plotHeight = height - top - bottom;

        double minX = results.stream().mapToDouble(xExtractor::value).min().orElse(0.0);
        double maxX = results.stream().mapToDouble(xExtractor::value).max().orElse(1.0);
        double minY = results.stream().mapToDouble(yExtractor::value).min().orElse(0.0);
        double maxY = results.stream().mapToDouble(yExtractor::value).max().orElse(1.0);

        if (maxX <= minX) maxX = minX + 1.0;
        if (maxY <= minY) maxY = minY + 1.0;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);

            g.setColor(new Color(230, 230, 230));
            g.fillRect(left, top, plotWidth, plotHeight);
            g.setColor(Color.DARK_GRAY);
            g.drawRect(left, top, plotWidth, plotHeight);

            g.setColor(Color.BLACK);
            g.drawString(title, left, 50);
            g.drawString(xLabel, left + plotWidth / 2 - 80, height - 40);
            g.rotate(-Math.PI / 2.0);
            g.drawString(yLabel, -(top + plotHeight / 2 + 40), 35);
            g.rotate(Math.PI / 2.0);

            g.setStroke(new BasicStroke(2f));
            g.setColor(new Color(31, 119, 180));
            for (FixtureResult result : results) {
                double xv = xExtractor.value(result);
                double yv = yExtractor.value(result);
                int x = left + (int) Math.round((xv - minX) / (maxX - minX) * plotWidth);
                int y = top + plotHeight - (int) Math.round((yv - minY) / (maxY - minY) * plotHeight);
                g.fillOval(x - 5, y - 5, 10, 10);
            }

            g.setColor(Color.BLACK);
            g.drawString(fmt(minX), left, top + plotHeight + 20);
            g.drawString(fmt(maxX), left + plotWidth - 40, top + plotHeight + 20);
            g.drawString(fmt(minY), left - 80, top + plotHeight);
            g.drawString(fmt(maxY), left - 80, top + 10);
        } finally {
            g.dispose();
        }

        Path docsPath = DOCS_IMAGE_DIR.resolve(fileName);
        Path targetPath = TARGET_IMAGE_DIR.resolve(fileName);
        ImageIO.write(image, "PNG", docsPath.toFile());
        ImageIO.write(image, "PNG", targetPath.toFile());
        return docsPath;
    }

    private static Path writeImage(String stem, BufferedImage image) throws IOException {
        Path docsPath = DOCS_IMAGE_DIR.resolve(stem + ".png");
        Path targetPath = TARGET_IMAGE_DIR.resolve(stem + ".png");
        ImageIO.write(image, "PNG", docsPath.toFile());
        ImageIO.write(image, "PNG", targetPath.toFile());
        return docsPath;
    }

    private static BufferedImage buildRawVisual(int[] samples, int width, VisibleRect rect, VisualScale scale,
                                                int outWidth, int outHeight) {
        BufferedImage raw = new BufferedImage(rect.width(), rect.height(), BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < rect.height(); y++) {
            int rowBase = (rect.y() + y) * width;
            for (int x = 0; x < rect.width(); x++) {
                int value = samples[rowBase + rect.x() + x];
                int normalized = normalize(value, scale);
                int gray = (normalized << 16) | (normalized << 8) | normalized;
                raw.setRGB(x, y, 0xFF000000 | gray);
            }
        }
        return scaleImage(raw, outWidth, outHeight);
    }

    private static BufferedImage buildDifferenceVisual(int[] reference, int[] actual, int width, VisibleRect rect,
                                                       int delta, int outWidth, int outHeight) {
        BufferedImage diff = new BufferedImage(rect.width(), rect.height(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < rect.height(); y++) {
            int rowBase = (rect.y() + y) * width;
            for (int x = 0; x < rect.width(); x++) {
                int d = Math.abs(actual[rowBase + rect.x() + x] + delta - reference[rowBase + rect.x() + x]);
                int intensity = Math.min(255, d / 8);
                int rgb = (intensity << 16) | (Math.min(255, intensity / 2) << 8);
                diff.setRGB(x, y, 0xFF000000 | rgb);
            }
        }
        return scaleImage(diff, outWidth, outHeight);
    }

    private static BufferedImage scaleImage(BufferedImage source, int width, int height) {
        if (source.getWidth() == width && source.getHeight() == height) {
            return source;
        }
        BufferedImage scaled = new BufferedImage(width, height,
                source.getType() == 0 ? BufferedImage.TYPE_INT_RGB : source.getType());
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    private static int normalize(int value, VisualScale scale) {
        if (value <= scale.low()) return 0;
        if (value >= scale.high()) return 255;
        return (int) Math.round((value - scale.low()) * 255.0 / (scale.high() - scale.low()));
    }

    private static VisualScale computeScale(int[] samples, int width, VisibleRect rect) {
        int total = rect.width() * rect.height();
        int sampleSize = Math.min(total, 50_000);
        int[] values = new int[sampleSize];
        int step = Math.max(1, total / sampleSize);
        int seen = 0;
        int stored = 0;
        for (int y = rect.y(); y < rect.y() + rect.height(); y++) {
            int rowBase = y * width;
            for (int x = rect.x(); x < rect.x() + rect.width(); x++) {
                if (stored >= sampleSize) break;
                if ((seen++ % step) == 0) values[stored++] = samples[rowBase + x];
            }
        }
        values = Arrays.copyOf(values, stored);
        Arrays.sort(values);
        int lo = percentile(values, 0.5);
        int hi = percentile(values, 99.5);
        return new VisualScale(lo, Math.max(lo + 1, hi));
    }

    private static int percentile(int[] values, double percentile) {
        if (values.length == 0) return 0;
        int index = (int) Math.round((percentile / 100.0) * (values.length - 1));
        return values[Math.max(0, Math.min(values.length - 1, index))];
    }

    private static VisibleRect resolveVisibleRect(Iad1Box iad1, int width, int height) {
        if (iad1 == null) {
            return new VisibleRect(0, 0, width, height);
        }
        VisibleRect rect = toRect(iad1.activeLeft(), iad1.activeTop(), iad1.activeRight(), iad1.activeBottom(), width, height);
        return rect.width() > 0 && rect.height() > 0 ? rect : new VisibleRect(0, 0, width, height);
    }

    private static VisibleRect toRect(int left, int top, int right, int bottom, int width, int height) {
        int x0 = Math.max(0, Math.min(width, left));
        int y0 = Math.max(0, Math.min(height, top));
        int x1 = Math.max(x0, Math.min(width, right));
        int y1 = Math.max(y0, Math.min(height, bottom));
        return new VisibleRect(x0, y0, x1 - x0, y1 - y0);
    }

    private static List<Path> discoverFixtures() throws IOException {
        return FIXTURES.discoverComplete().stream()
                .map(ReferenceFixtureResolver.ResolvedFixture::cr3)
                .filter(PedestalHypothesisValidationIT::isLosslessFixture)
                .toList();
    }

    private static boolean isLosslessFixture(Path fixture) {
        try (ImageInputStream in = ImageIO.createImageInputStream(fixture.toFile())) {
            if (in == null) {
                return false;
            }
            Cr3Container container = Cr3Parser.parse(in);
            Cr3Track rawTrack = container.tracks().stream()
                    .filter(track -> track.type() == Cr3Track.TrackType.CRX_BIG
                            || track.type() == Cr3Track.TrackType.CRX_RAW)
                    .findFirst()
                    .orElse(null);
            return rawTrack != null && rawTrack.cmp1() != null && rawTrack.cmp1().isLossless();
        } catch (Exception e) {
            return false;
        }
    }

    private static DecodedFixture decodeFixture(Path fixture) throws IOException, Cr3Exception {
        try (ImageInputStream in = ImageIO.createImageInputStream(fixture.toFile())) {
            if (in == null) {
                throw new IOException("Could not open fixture " + fixture);
            }
            Cr3Container container = Cr3Parser.parse(in);
            Cr3Track rawTrack = container.tracks().stream()
                    .filter(track -> track.type() == Cr3Track.TrackType.CRX_BIG
                            || track.type() == Cr3Track.TrackType.CRX_RAW)
                    .findFirst()
                    .orElseThrow(() -> new IOException("No CRX raw track in " + fixture));
            Cmp1Box cmp1 = rawTrack.cmp1();
            assertThat(cmp1).as("CMP1 for %s", fixture.getFileName()).isNotNull();
            byte[] payload = new byte[(int) rawTrack.dataSize()];
            in.seek(rawTrack.dataOffset());
            in.readFully(payload);
            CrxHeader.CodecVersion codecVersion = container.cncvBox() == null
                    ? CrxHeader.CodecVersion.V1
                    : CrxHeader.detectVersion(container.cncvBox().versionString());
            Cr3RawImage raw = CrxLosslessRawDecoder.decode(payload, cmp1.imageWidth(), cmp1.imageHeight(),
                    cmp1.bitsPerSample(), codecVersion, cmp1);
            return new DecodedFixture(raw, rawTrack.iad1());
        }
    }

    private static ReferenceImage loadReference(Path tiffPath) throws IOException {
        BufferedImage image = ImageIO.read(tiffPath.toFile());
        if (image == null) {
            throw new IOException("Could not decode TIFF reference " + tiffPath);
        }
        Raster raster = image.getRaster();
        int width = image.getWidth();
        int height = image.getHeight();
        int[] samples = readSamples(raster, width, height);
        return new ReferenceImage(width, height, samples);
    }

    private static int[] readSamples(Raster raster, int width, int height) {
        DataBuffer buffer = raster.getDataBuffer();
        if (buffer instanceof DataBufferUShort ushort && ushort.getNumBanks() == 1) {
            short[] bank = ushort.getData();
            int[] out = new int[bank.length];
            for (int i = 0; i < bank.length; i++) {
                out[i] = Short.toUnsignedInt(bank[i]);
            }
            return out;
        }
        return raster.getSamples(0, 0, width, height, 0, (int[]) null);
    }

    private static String escapePipes(String s) {
        return s == null ? "" : s.replace("|", "\\|");
    }

    private static String fmt(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.4f", value) : "n/a";
    }

    private record ReferenceImage(int width, int height, int[] samples) { }

    private record DecodedFixture(Cr3RawImage raw, Iad1Box iad1) { }

    private record MetricTriple(double mae, double rmse, double pearson) { }

    private record ObMetrics(double mae, double stddev) { }

    private record ExampleImages(String referenceImage, String baselineDiffImage, String experimentalDiffImage) { }

    private record FixtureResult(
            String fixture,
            String cameraModel,
            String pedestalSource,
            String pedestalMode,
            String resolvedLevels,
            double resolvedBlackLevelScalar,
            double optimalDelta,
            double deltaMetadata,
            MetricTriple baselineMetrics,
            MetricTriple experimentalMetrics,
            ObMetrics baselineObMetrics,
            ObMetrics experimentalObMetrics,
            String referenceImage,
            String baselineDiffImage,
            String experimentalDiffImage
    ) { }

    private record VisibleRect(int x, int y, int width, int height) { }

    private record VisualScale(int low, int high) { }

    private record HypothesisDecision(String label, List<String> reasons) { }

    @FunctionalInterface
    private interface ValueExtractor {
        double value(FixtureResult result);
    }
}
