package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.bmff.Iad1Box;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import com.photographer.focusstack.cr3.raw.codec.CrxHeader;
import com.photographer.focusstack.cr3.raw.codec.CrxLosslessRawDecoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Phase-C predictor evidence matrix.
 *
 * <p>Compares predictor candidates against the already decoded production signal
 * without changing the decoder. For each lossless CR3 fixture, the production
 * decoder output is treated as the observed signal and residuals are computed
 * for multiple predictor families across IAD1 optical-black regions and the
 * visible image area.</p>
 */
class PredictorEvidenceMatrixIT {

    private static final Logger log = LoggerFactory.getLogger(PredictorEvidenceMatrixIT.class);

    private static final Path SAMPLES_DIR = Path.of("src", "test", "resources", "samples");
    private static final Path RESEARCH_DIR = SAMPLES_DIR.resolve("research");
    private static final Path REPORT_DIR = Path.of("target", "predictor-evidence");
    private static final Path IMAGE_DIR = REPORT_DIR.resolve("images");

    private static final int DISPLAY_RANGE = 2048;
    private static final int HISTOGRAM_BUCKET_WIDTH = 64;
    private static final List<AnalysisArea> AREA_ORDER = List.of(
            AnalysisArea.LEFT_OB, AnalysisArea.TOP_OB, AnalysisArea.IMAGE_AREA
    );

    @Test
    @DisplayName("Phase-C predictor evidence matrix against decoded CR3 signal")
    void runPredictorEvidenceMatrix() throws Exception {
        if (!Files.isDirectory(SAMPLES_DIR)) {
            log.info("No samples dir at {} — skipping", SAMPLES_DIR.toAbsolutePath());
            return;
        }

        List<Path> fixtures = new ArrayList<>();
        collectCr3(SAMPLES_DIR, fixtures);
        if (Files.isDirectory(RESEARCH_DIR)) {
            collectCr3(RESEARCH_DIR, fixtures);
        }
        fixtures.sort(Comparator.comparing(Path::toString));
        if (fixtures.isEmpty()) {
            log.info("No CR3 fixtures — skipping");
            return;
        }

        Files.createDirectories(REPORT_DIR);
        Files.createDirectories(IMAGE_DIR);

        List<ResidualResult> results = new ArrayList<>();
        List<FixtureReport> fixtureReports = new ArrayList<>();

        for (Path fixture : fixtures) {
            try {
                analyseFixture(fixture, results, fixtureReports);
            } catch (Exception ex) {
                log.warn("Skipped {}: {}", fixture.getFileName(), ex.toString());
            }
        }

        writeTsv(results, REPORT_DIR.resolve("results.tsv"));
        writeJson(results, REPORT_DIR.resolve("results.json"));
        writeMarkdown(results, fixtureReports, REPORT_DIR.resolve("results.md"));

        log.info("Predictor evidence matrix complete. {} result rows written to {}",
                results.size(), REPORT_DIR.toAbsolutePath());
    }

    private static void analyseFixture(Path file,
                                       List<ResidualResult> allResults,
                                       List<FixtureReport> fixtureReports) throws Exception {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            Cr3Container container = Cr3Parser.parse(in);
            Cr3Track rawTrack = container.tracks().stream()
                    .filter(track -> track.type() == Cr3Track.TrackType.CRX_BIG
                            || track.type() == Cr3Track.TrackType.CRX_RAW)
                    .findFirst()
                    .orElse(null);
            if (rawTrack == null) {
                log.debug("{}: no CRX_BIG/CRX_RAW track — skipping", file.getFileName());
                return;
            }

            Cmp1Box cmp1 = rawTrack.cmp1();
            if (cmp1 != null && cmp1.waveletLevels() > 0) {
                log.debug("{}: C-RAW (waveletLevels={}) — skipping lossless analysis",
                        file.getFileName(), cmp1.waveletLevels());
                return;
            }

            Iad1Box iad1 = rawTrack.iad1();
            if (iad1 == null) {
                log.debug("{}: no IAD1 box — skipping", file.getFileName());
                return;
            }

            int imgW = cmp1 != null && cmp1.imageWidth() > 0 ? cmp1.imageWidth() : rawTrack.width();
            int imgH = cmp1 != null && cmp1.imageHeight() > 0 ? cmp1.imageHeight() : rawTrack.height();
            int bitDepth = cmp1 != null && cmp1.bitsPerSample() > 0 ? cmp1.bitsPerSample() : 14;
            CrxHeader.CodecVersion version = container.cncvBox() == null
                    ? CrxHeader.CodecVersion.V1
                    : CrxHeader.detectVersion(container.cncvBox().versionString());

            byte[] crxBytes = new byte[(int) rawTrack.dataSize()];
            in.seek(rawTrack.dataOffset());
            in.readFully(crxBytes);

            Cr3RawImage baseline;
            try {
                baseline = CrxLosslessRawDecoder.decode(crxBytes, imgW, imgH, bitDepth, version, cmp1);
            } catch (Exception ex) {
                log.info("{}: production decoder failed — {}", file.getFileName(), ex.toString());
                return;
            }

            int[] leftOb = clampRect(iad1.leftObLeft(), iad1.leftObTop(),
                    iad1.leftObRight(), iad1.leftObBottom(), imgW, imgH);
            int[] topOb = clampRect(iad1.topObLeft(), iad1.topObTop(),
                    iad1.topObRight(), iad1.topObBottom(), imgW, imgH);
            int[] imageArea = resolveImageArea(iad1, leftOb, topOb, imgW, imgH);

            if ((leftOb[2] <= 0 || leftOb[3] <= 0) && (topOb[2] <= 0 || topOb[3] <= 0)) {
                log.debug("{}: no usable optical-black geometry — skipping", file.getFileName());
                return;
            }

            String fixtureName = file.getFileName().toString().replace(".cr3", "");
            String scene = sceneTag(fixtureName);
            log.info("Analysing evidence matrix for {} ({}×{}, {}bit, scene={})",
                    file.getFileName(), imgW, imgH, bitDepth, scene);

            int planeW = (imgW + 1) / 2;
            int planeH = (imgH + 1) / 2;
            int[] bayer = baseline.data().clone();
            Map<String, String> generatedPlots = new LinkedHashMap<>();

            for (int plane = 0; plane < 4; plane++) {
                int[] planeData = extractPlane(bayer, imgW, planeW, planeH, plane);
                List<ResidualResult> fixturePlaneResults = analysePlane(
                        fixtureName, scene, plane, planeData, planeW, planeH, bitDepth,
                        leftOb, topOb, imageArea
                );
                allResults.addAll(fixturePlaneResults);

                for (AnalysisArea area : AREA_ORDER) {
                    List<ResidualResult> areaResults = fixturePlaneResults.stream()
                            .filter(result -> result.area() == area)
                            .toList();
                    if (areaResults.isEmpty()) {
                        continue;
                    }
                    Path histogramPath = IMAGE_DIR.resolve(
                            fixtureName + "-" + area.fileLabel + "-plane" + plane + "-histogram.png");
                    renderHistogramComparison(fixtureName, plane, area, areaResults, histogramPath);
                    generatedPlots.put(area.fileLabel + "-plane" + plane + "-histogram",
                            "images/" + histogramPath.getFileName());
                }
            }

            List<ResidualResult> fixtureResults = allResults.stream()
                    .filter(result -> result.fixture().equals(fixtureName))
                    .toList();
            for (AnalysisArea area : AREA_ORDER) {
                List<ResidualResult> areaResults = fixtureResults.stream()
                        .filter(result -> result.area() == area)
                        .toList();
                if (areaResults.isEmpty()) {
                    continue;
                }
                Path plotPath = IMAGE_DIR.resolve(fixtureName + "-" + area.fileLabel + "-per-plane-stddev.png");
                renderPerPlaneStdDevPlot(fixtureName, area, areaResults, plotPath);
                generatedPlots.put(area.fileLabel + "-per-plane-stddev", "images/" + plotPath.getFileName());
            }

            fixtureReports.add(new FixtureReport(fixtureName, scene, generatedPlots));
        }
    }

    private static List<ResidualResult> analysePlane(String fixture,
                                                     String scene,
                                                     int plane,
                                                     int[] planeData,
                                                     int planeW,
                                                     int planeH,
                                                     int bitDepth,
                                                     int[] leftOb,
                                                     int[] topOb,
                                                     int[] imageArea) {
        int maxResidual = (1 << bitDepth) - 1;
        EnumMap<PredictorCandidate, EnumMap<AnalysisArea, ResidualAccumulator>> accumulators =
                new EnumMap<>(PredictorCandidate.class);
        for (PredictorCandidate candidate : PredictorCandidate.values()) {
            EnumMap<AnalysisArea, ResidualAccumulator> perArea = new EnumMap<>(AnalysisArea.class);
            for (AnalysisArea area : AREA_ORDER) {
                perArea.put(area, new ResidualAccumulator(maxResidual));
            }
            accumulators.put(candidate, perArea);
        }

        int rowOff = (plane >> 1) & 1;
        int colOff = plane & 1;

        for (int y = 0; y < planeH; y++) {
            int bayerY = y * 2 + rowOff;
            for (int x = 0; x < planeW; x++) {
                int bayerX = x * 2 + colOff;
                boolean inLeftOb = contains(leftOb, bayerX, bayerY);
                boolean inTopOb = contains(topOb, bayerX, bayerY);
                boolean inImageArea = contains(imageArea, bayerX, bayerY);
                if (!inLeftOb && !inTopOb && !inImageArea) {
                    continue;
                }

                int actual = planeData[y * planeW + x];
                Integer north = y > 0 ? planeData[(y - 1) * planeW + x] : null;
                Integer west = x > 0 ? planeData[y * planeW + (x - 1)] : null;
                Integer northWest = (x > 0 && y > 0) ? planeData[(y - 1) * planeW + (x - 1)] : null;

                for (PredictorCandidate candidate : PredictorCandidate.values()) {
                    Integer prediction = candidate.predict(north, west, northWest);
                    if (prediction == null) {
                        continue;
                    }
                    int residual = actual - prediction;
                    EnumMap<AnalysisArea, ResidualAccumulator> perArea = accumulators.get(candidate);
                    if (inLeftOb) {
                        perArea.get(AnalysisArea.LEFT_OB).add(residual);
                    }
                    if (inTopOb) {
                        perArea.get(AnalysisArea.TOP_OB).add(residual);
                    }
                    if (inImageArea) {
                        perArea.get(AnalysisArea.IMAGE_AREA).add(residual);
                    }
                }
            }
        }

        List<ResidualResult> results = new ArrayList<>();
        for (PredictorCandidate candidate : PredictorCandidate.values()) {
            EnumMap<AnalysisArea, ResidualAccumulator> perArea = accumulators.get(candidate);
            for (AnalysisArea area : AREA_ORDER) {
                ResidualStats stats = perArea.get(area).finish();
                if (stats.count() == 0) {
                    continue;
                }
                results.add(new ResidualResult(fixture, scene, plane, area, candidate, stats));
            }
        }
        return results;
    }

    private static int[] resolveImageArea(Iad1Box iad1,
                                          int[] leftOb,
                                          int[] topOb,
                                          int imgW,
                                          int imgH) {
        int[] active = clampRect(iad1.activeLeft(), iad1.activeTop(),
                iad1.activeRight(), iad1.activeBottom(), imgW, imgH);
        if (active[2] > 0 && active[3] > 0) {
            return active;
        }

        int[] crop = clampRect(iad1.cropLeft(), iad1.cropTop(),
                iad1.cropRight(), iad1.cropBottom(), imgW, imgH);
        if (crop[2] > 0 && crop[3] > 0) {
            return crop;
        }

        int left = Math.max(leftOb[0] + leftOb[2], 0);
        int top = Math.max(topOb[1] + topOb[3], 0);
        return clampRect(left, top, imgW, imgH, imgW, imgH);
    }

    private static void writeTsv(List<ResidualResult> results, Path path) throws IOException {
        try (var writer = Files.newBufferedWriter(path)) {
            writer.write("fixture\tscene\tplane\tarea\tcandidate\tfamily\tcount"
                    + "\tmeanResidual\tmedianResidual\tstdDevResidual\tmadResidual"
                    + "\tskewness\tkurtosis\thistogram\n");
            for (ResidualResult result : results) {
                ResidualStats stats = result.stats();
                writer.write(String.format(Locale.ROOT,
                        "%s\t%s\t%d\t%s\t%s\t%s\t%d\t%.6f\t%d\t%.6f\t%d\t%.6f\t%.6f\t%s%n",
                        result.fixture(),
                        result.scene(),
                        result.plane(),
                        result.area().id,
                        result.candidate().id,
                        result.candidate().family.id,
                        stats.count(),
                        stats.meanResidual(),
                        stats.medianResidual(),
                        stats.stdDevResidual(),
                        stats.madResidual(),
                        stats.skewness(),
                        stats.kurtosis(),
                        histogramString(stats.histogram())));
            }
        }
    }

    private static void writeJson(List<ResidualResult> results, Path path) throws IOException {
        try (var writer = Files.newBufferedWriter(path)) {
            writer.write("[\n");
            for (int i = 0; i < results.size(); i++) {
                ResidualResult result = results.get(i);
                ResidualStats stats = result.stats();
                writer.write("  {\n");
                writer.write(String.format(Locale.ROOT, "    \"fixture\": \"%s\",%n", result.fixture()));
                writer.write(String.format(Locale.ROOT, "    \"scene\": \"%s\",%n", result.scene()));
                writer.write(String.format(Locale.ROOT, "    \"plane\": %d,%n", result.plane()));
                writer.write(String.format(Locale.ROOT, "    \"area\": \"%s\",%n", result.area().id));
                writer.write(String.format(Locale.ROOT, "    \"candidate\": \"%s\",%n", result.candidate().id));
                writer.write(String.format(Locale.ROOT, "    \"family\": \"%s\",%n", result.candidate().family.id));
                writer.write(String.format(Locale.ROOT, "    \"count\": %d,%n", stats.count()));
                writer.write(String.format(Locale.ROOT, "    \"meanResidual\": %.6f,%n", stats.meanResidual()));
                writer.write(String.format(Locale.ROOT, "    \"medianResidual\": %d,%n", stats.medianResidual()));
                writer.write(String.format(Locale.ROOT, "    \"stdDevResidual\": %.6f,%n", stats.stdDevResidual()));
                writer.write(String.format(Locale.ROOT, "    \"madResidual\": %d,%n", stats.madResidual()));
                writer.write(String.format(Locale.ROOT, "    \"skewness\": %.6f,%n", stats.skewness()));
                writer.write(String.format(Locale.ROOT, "    \"kurtosis\": %.6f,%n", stats.kurtosis()));
                writer.write(String.format(Locale.ROOT, "    \"histogram\": \"%s\"%n",
                        histogramString(stats.histogram())));
                writer.write(i + 1 < results.size() ? "  },\n" : "  }\n");
            }
            writer.write("]\n");
        }
    }

    private static void writeMarkdown(List<ResidualResult> results,
                                      List<FixtureReport> fixtureReports,
                                      Path path) throws IOException {
        try (var writer = Files.newBufferedWriter(path)) {
            writer.write("# Phase-C Predictor Evidence Matrix — Results\n\n");
            writer.write("Generated: " + Instant.now() + "\n\n");
            writer.write("This experiment compares predictor candidates against the **already decoded** "
                    + "production signal. Lower residual stddev and MAD indicate better statistical support.\n\n");

            writer.write("## Predictor candidates\n\n");
            for (PredictorCandidate candidate : PredictorCandidate.values()) {
                writer.write(String.format("- **%s** (`%s` family): %s%n",
                        candidate.id, candidate.family.id, candidate.description));
            }
            writer.write("\n## Area definitions\n\n");
            writer.write("- `left-ob`: IAD1 left optical-black rectangle\n");
            writer.write("- `top-ob`: IAD1 top optical-black rectangle\n");
            writer.write("- `image-area`: IAD1 active area, or crop/fallback if absent\n\n");

            writer.write("## Family win counts (lowest residual stddev)\n\n");
            writer.write("| Area | Family | Wins |\n");
            writer.write("|---|---|---|\n");
            for (AnalysisArea area : AREA_ORDER) {
                Map<PredictorFamily, Long> wins = computeFamilyWins(results, area);
                for (Map.Entry<PredictorFamily, Long> entry : wins.entrySet()) {
                    writer.write(String.format("| %s | %s | %d |%n",
                            area.id, entry.getKey().id, entry.getValue()));
                }
            }

            writer.write("\n## Best candidate per fixture / plane / area\n\n");
            writer.write("| Fixture | Plane | Area | Best candidate | Family | StdDev | MAD | Mean |\n");
            writer.write("|---|---|---|---|---|---|---|---|\n");
            for (ResidualResult best : bestResults(results)) {
                writer.write(String.format(Locale.ROOT,
                        "| %s | %d | %s | %s | %s | %.3f | %d | %.3f |%n",
                        best.fixture(),
                        best.plane(),
                        best.area().id,
                        best.candidate().id,
                        best.candidate().family.id,
                        best.stats().stdDevResidual(),
                        best.stats().madResidual(),
                        best.stats().meanResidual()));
            }

            writer.write("\n## Best family per area (aggregate mean rank)\n\n");
            writer.write("| Area | Family | Mean stddev rank |\n");
            writer.write("|---|---|---|\n");
            for (AnalysisArea area : AREA_ORDER) {
                for (FamilyRank rank : computeFamilyRanks(results, area)) {
                    writer.write(String.format(Locale.ROOT, "| %s | %s | %.3f |%n",
                            area.id, rank.family().id, rank.rank()));
                }
            }

            writer.write("\n## Diagnostic plots\n\n");
            for (FixtureReport report : fixtureReports) {
                writer.write("### " + report.fixture() + " (" + report.scene() + ")\n\n");
                for (Map.Entry<String, String> entry : report.generatedPlots().entrySet()) {
                    writer.write(String.format("- `%s`: ![](%s)%n", entry.getKey(), entry.getValue()));
                }
                writer.write("\n");
            }

            writer.write("## Residual distribution notes\n\n");
            writer.write("- Histograms are bucketed into 64-value bins over a ±2048 display range.\n");
            writer.write("- `NorthOnly` is an alias of the `N` family; `WestOnly` is an alias of the `W` family.\n");
            writer.write("- Skewness and kurtosis are computed on the exact residual stream for each row.\n");
        }
    }

    private static Map<PredictorFamily, Long> computeFamilyWins(List<ResidualResult> results, AnalysisArea area) {
        Map<PredictorFamily, Long> counts = new LinkedHashMap<>();
        for (PredictorFamily family : PredictorFamily.values()) {
            counts.put(family, 0L);
        }

        Map<String, List<ResidualResult>> groups = results.stream()
                .filter(result -> result.area() == area)
                .collect(Collectors.groupingBy(result -> result.fixture() + "#" + result.plane(),
                        LinkedHashMap::new, Collectors.toList()));

        for (List<ResidualResult> group : groups.values()) {
            Map<PredictorFamily, ResidualResult> bestPerFamily = new EnumMap<>(PredictorFamily.class);
            for (ResidualResult result : group) {
                ResidualResult existing = bestPerFamily.get(result.candidate().family);
                if (existing == null || compareResults(result, existing) < 0) {
                    bestPerFamily.put(result.candidate().family, result);
                }
            }
            ResidualResult winner = bestPerFamily.values().stream()
                    .min(PredictorEvidenceMatrixIT::compareResults)
                    .orElse(null);
            if (winner != null) {
                counts.computeIfPresent(winner.candidate().family, (ignored, current) -> current + 1);
            }
        }

        return counts.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<PredictorFamily, Long>comparingByValue().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private static List<ResidualResult> bestResults(List<ResidualResult> results) {
        Map<String, ResidualResult> best = new LinkedHashMap<>();
        for (ResidualResult result : results) {
            String key = result.fixture() + "#" + result.plane() + "#" + result.area().id;
            ResidualResult existing = best.get(key);
            if (existing == null || compareResults(result, existing) < 0) {
                best.put(key, result);
            }
        }
        return new ArrayList<>(best.values());
    }

    private static List<FamilyRank> computeFamilyRanks(List<ResidualResult> results, AnalysisArea area) {
        Map<PredictorFamily, DoubleSummaryStatistics> stats = new EnumMap<>(PredictorFamily.class);
        for (PredictorFamily family : PredictorFamily.values()) {
            stats.put(family, new DoubleSummaryStatistics());
        }

        Map<String, List<ResidualResult>> groups = results.stream()
                .filter(result -> result.area() == area)
                .collect(Collectors.groupingBy(result -> result.fixture() + "#" + result.plane(),
                        LinkedHashMap::new, Collectors.toList()));

        for (List<ResidualResult> group : groups.values()) {
            List<ResidualResult> ranked = new ArrayList<>();
            for (PredictorFamily family : PredictorFamily.values()) {
                group.stream()
                        .filter(result -> result.candidate().family == family)
                        .min(PredictorEvidenceMatrixIT::compareResults)
                        .ifPresent(ranked::add);
            }
            ranked.sort(PredictorEvidenceMatrixIT::compareResults);
            for (int i = 0; i < ranked.size(); i++) {
                stats.get(ranked.get(i).candidate().family).accept(i + 1);
            }
        }

        return stats.entrySet().stream()
                .filter(entry -> entry.getValue().getCount() > 0)
                .map(entry -> new FamilyRank(entry.getKey(), entry.getValue().getAverage()))
                .sorted(Comparator.comparingDouble(FamilyRank::rank))
                .toList();
    }

    private static int compareResults(ResidualResult left, ResidualResult right) {
        int stddevOrder = Double.compare(left.stats().stdDevResidual(), right.stats().stdDevResidual());
        if (stddevOrder != 0) {
            return stddevOrder;
        }
        int madOrder = Integer.compare(left.stats().madResidual(), right.stats().madResidual());
        if (madOrder != 0) {
            return madOrder;
        }
        return Double.compare(Math.abs(left.stats().meanResidual()), Math.abs(right.stats().meanResidual()));
    }

    private static void renderHistogramComparison(String fixture,
                                                  int plane,
                                                  AnalysisArea area,
                                                  List<ResidualResult> results,
                                                  Path path) throws IOException {
        int width = 1400;
        int height = 900;
        int leftMargin = 80;
        int rightMargin = 30;
        int topMargin = 50;
        int bottomMargin = 80;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.drawString(fixture + " — plane " + plane + " — " + area.id + " residual histogram", 30, 30);

        int plotWidth = width - leftMargin - rightMargin;
        int plotHeight = height - topMargin - bottomMargin;
        int plotX = leftMargin;
        int plotY = topMargin;
        g.drawRect(plotX, plotY, plotWidth, plotHeight);

        double maxDensity = 0;
        Map<PredictorCandidate, long[]> displayHistograms = new LinkedHashMap<>();
        for (ResidualResult result : results) {
            long[] buckets = toDisplayHistogram(result.stats().histogram());
            displayHistograms.put(result.candidate(), buckets);
            long total = Arrays.stream(buckets).sum();
            if (total == 0) {
                continue;
            }
            for (long bucket : buckets) {
                maxDensity = Math.max(maxDensity, (double) bucket / total);
            }
        }
        maxDensity = Math.max(maxDensity, 1e-9);

        int bucketCount = toDisplayHistogram(new long[(DISPLAY_RANGE * 2) + 1]).length;
        for (int i = 0; i < bucketCount; i++) {
            int x = plotX + i * plotWidth / Math.max(1, bucketCount - 1);
            if (i < bucketCount - 1) {
                g.setColor(new Color(240, 240, 240));
                g.drawLine(x, plotY, x, plotY + plotHeight);
            }
        }

        int legendY = plotY + 20;
        for (ResidualResult result : results) {
            PredictorCandidate candidate = result.candidate();
            long[] buckets = displayHistograms.get(candidate);
            long total = Arrays.stream(buckets).sum();
            if (total == 0) {
                continue;
            }
            g.setColor(candidate.color);
            int lastX = plotX;
            int lastY = plotY + plotHeight;
            for (int i = 0; i < buckets.length; i++) {
                double density = (double) buckets[i] / total;
                int x = plotX + (int) Math.round((double) i * plotWidth / Math.max(1, buckets.length - 1));
                int y = plotY + plotHeight - (int) Math.round(density / maxDensity * plotHeight);
                if (i > 0) {
                    g.drawLine(lastX, lastY, x, y);
                }
                lastX = x;
                lastY = y;
            }
            g.fillRect(width - 250, legendY - 10, 12, 12);
            g.setColor(Color.BLACK);
            g.drawString(candidate.id, width - 230, legendY);
            legendY += 18;
        }

        g.drawString("-2048", plotX - 10, plotY + plotHeight + 25);
        g.drawString("0", plotX + plotWidth / 2, plotY + plotHeight + 25);
        g.drawString("+2048", plotX + plotWidth - 30, plotY + plotHeight + 25);
        g.drawString("normalised density", 10, plotY + 20);
        g.dispose();
        ImageIO.write(image, "PNG", path.toFile());
    }

    private static void renderPerPlaneStdDevPlot(String fixture,
                                                 AnalysisArea area,
                                                 List<ResidualResult> results,
                                                 Path path) throws IOException {
        int width = 1400;
        int height = 900;
        int leftMargin = 90;
        int rightMargin = 30;
        int topMargin = 50;
        int bottomMargin = 90;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.drawString(fixture + " — " + area.id + " residual stddev by plane", 30, 30);

        double maxStdDev = results.stream()
                .mapToDouble(result -> result.stats().stdDevResidual())
                .max()
                .orElse(1.0);
        maxStdDev = Math.max(1.0, maxStdDev);

        int plotWidth = width - leftMargin - rightMargin;
        int plotHeight = height - topMargin - bottomMargin;
        int plotX = leftMargin;
        int plotY = topMargin;
        g.drawRect(plotX, plotY, plotWidth, plotHeight);

        int groups = 4;
        int barsPerGroup = PredictorCandidate.values().length;
        int groupWidth = plotWidth / groups;
        int barWidth = Math.max(6, groupWidth / (barsPerGroup + 2));

        for (int plane = 0; plane < groups; plane++) {
            int planeIndex = plane;
            int groupStart = plotX + planeIndex * groupWidth;
            g.setColor(Color.LIGHT_GRAY);
            g.drawLine(groupStart, plotY, groupStart, plotY + plotHeight);
            g.setColor(Color.BLACK);
            g.drawString("plane " + planeIndex, groupStart + groupWidth / 3, plotY + plotHeight + 25);

            List<ResidualResult> planeResults = results.stream()
                    .filter(result -> result.plane() == planeIndex)
                    .sorted(Comparator.comparing(result -> result.candidate().ordinal()))
                    .toList();

            for (int i = 0; i < planeResults.size(); i++) {
                ResidualResult result = planeResults.get(i);
                int x = groupStart + barWidth + i * barWidth;
                int barHeight = (int) Math.round(result.stats().stdDevResidual() / maxStdDev * plotHeight);
                int y = plotY + plotHeight - barHeight;
                g.setColor(result.candidate().color);
                g.fillRect(x, y, barWidth - 1, Math.max(1, barHeight));
            }
        }

        int legendY = plotY + 20;
        for (PredictorCandidate candidate : PredictorCandidate.values()) {
            g.setColor(candidate.color);
            g.fillRect(width - 250, legendY - 10, 12, 12);
            g.setColor(Color.BLACK);
            g.drawString(candidate.id, width - 230, legendY);
            legendY += 18;
        }
        g.drawString("0", plotX - 20, plotY + plotHeight + 5);
        g.drawString(String.format(Locale.ROOT, "%.1f", maxStdDev), 10, plotY + 10);
        g.dispose();
        ImageIO.write(image, "PNG", path.toFile());
    }

    private static long[] toDisplayHistogram(long[] exactHistogram) {
        int bucketCount = (DISPLAY_RANGE * 2) / HISTOGRAM_BUCKET_WIDTH + 3;
        long[] buckets = new long[bucketCount];
        int offset = (exactHistogram.length - 1) / 2;
        for (int i = 0; i < exactHistogram.length; i++) {
            long count = exactHistogram[i];
            if (count == 0) {
                continue;
            }
            int residual = i - offset;
            int bucket;
            if (residual < -DISPLAY_RANGE) {
                bucket = 0;
            } else if (residual > DISPLAY_RANGE) {
                bucket = bucketCount - 1;
            } else {
                bucket = 1 + (residual + DISPLAY_RANGE) / HISTOGRAM_BUCKET_WIDTH;
            }
            buckets[bucket] += count;
        }
        return buckets;
    }

    private static String histogramString(long[] histogram) {
        return Arrays.stream(toDisplayHistogram(histogram))
                .mapToObj(Long::toString)
                .collect(Collectors.joining(","));
    }

    private static int[] extractPlane(int[] bayer, int imgW, int planeW, int planeH, int plane) {
        int rowOff = (plane >> 1) & 1;
        int colOff = plane & 1;
        int[] out = new int[planeW * planeH];
        for (int py = 0; py < planeH; py++) {
            int bayerY = py * 2 + rowOff;
            for (int px = 0; px < planeW; px++) {
                int bayerX = px * 2 + colOff;
                int index = bayerY * imgW + bayerX;
                if (index < bayer.length) {
                    out[py * planeW + px] = bayer[index];
                }
            }
        }
        return out;
    }

    private static boolean contains(int[] rect, int x, int y) {
        return rect[2] > 0
                && rect[3] > 0
                && x >= rect[0]
                && x < rect[0] + rect[2]
                && y >= rect[1]
                && y < rect[1] + rect[3];
    }

    private static void collectCr3(Path dir, List<Path> out) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".cr3"))
                    .forEach(out::add);
        }
    }

    private static int[] clampRect(int x0, int y0, int x1, int y1, int width, int height) {
        x0 = Math.max(0, Math.min(width, x0));
        y0 = Math.max(0, Math.min(height, y0));
        x1 = Math.max(x0, Math.min(width, x1));
        y1 = Math.max(y0, Math.min(height, y1));
        return new int[]{x0, y0, x1 - x0, y1 - y0};
    }

    private static String sceneTag(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("-dark-") || n.startsWith("dark")) return "dark";
        if (n.contains("-flat-") || n.startsWith("flat")) return "flat";
        if (n.contains("-detail-")) return "detail";
        if (n.contains("-asis-")) return "asis";
        if (n.contains("eosr")) return "detail";
        if (n.contains("sample")) return "sample";
        return "?";
    }

    enum AnalysisArea {
        LEFT_OB("left-ob", "left-ob"),
        TOP_OB("top-ob", "top-ob"),
        IMAGE_AREA("image-area", "image-area");

        final String id;
        final String fileLabel;

        AnalysisArea(String id, String fileLabel) {
            this.id = id;
            this.fileLabel = fileLabel;
        }
    }

    enum PredictorFamily {
        N("N"),
        W("W"),
        NW("NW"),
        MED("MED"),
        PAETH("Paeth"),
        N_PLUS_W_MINUS_NW("N+W-NW");

        final String id;

        PredictorFamily(String id) {
            this.id = id;
        }
    }

    enum PredictorCandidate {
        N("N", PredictorFamily.N, "North neighbour only", new Color(31, 119, 180)) {
            @Override
            Integer predict(Integer north, Integer west, Integer northWest) {
                return north;
            }
        },
        W("W", PredictorFamily.W, "West neighbour only", new Color(255, 127, 14)) {
            @Override
            Integer predict(Integer north, Integer west, Integer northWest) {
                return west;
            }
        },
        NW("NW", PredictorFamily.NW, "North-west neighbour only", new Color(44, 160, 44)) {
            @Override
            Integer predict(Integer north, Integer west, Integer northWest) {
                return northWest;
            }
        },
        MED("MED", PredictorFamily.MED, "JPEG-LS median edge detector", new Color(214, 39, 40)) {
            @Override
            Integer predict(Integer north, Integer west, Integer northWest) {
                if (north == null || west == null || northWest == null) {
                    return null;
                }
                return medPredict(west, north, northWest);
            }
        },
        PAETH("Paeth", PredictorFamily.PAETH, "PNG-style Paeth predictor", new Color(148, 103, 189)) {
            @Override
            Integer predict(Integer north, Integer west, Integer northWest) {
                if (north == null || west == null || northWest == null) {
                    return null;
                }
                return paethPredict(west, north, northWest);
            }
        },
        N_PLUS_W_MINUS_NW("N+W-NW", PredictorFamily.N_PLUS_W_MINUS_NW,
                "Linear N + W - NW predictor", new Color(140, 86, 75)) {
            @Override
            Integer predict(Integer north, Integer west, Integer northWest) {
                if (north == null || west == null || northWest == null) {
                    return null;
                }
                return west + north - northWest;
            }
        },
        NORTH_ONLY("NorthOnly", PredictorFamily.N, "Alias of N for issue-tracking continuity", new Color(227, 119, 194)) {
            @Override
            Integer predict(Integer north, Integer west, Integer northWest) {
                return north;
            }
        },
        WEST_ONLY("WestOnly", PredictorFamily.W, "Alias of W for issue-tracking continuity", new Color(127, 127, 127)) {
            @Override
            Integer predict(Integer north, Integer west, Integer northWest) {
                return west;
            }
        };

        final String id;
        final PredictorFamily family;
        final String description;
        final Color color;

        PredictorCandidate(String id, PredictorFamily family, String description, Color color) {
            this.id = id;
            this.family = family;
            this.description = description;
            this.color = color;
        }

        abstract Integer predict(Integer north, Integer west, Integer northWest);
    }

    private static int medPredict(int west, int north, int northWest) {
        if (northWest >= Math.max(west, north)) return Math.min(west, north);
        if (northWest <= Math.min(west, north)) return Math.max(west, north);
        return west + north - northWest;
    }

    private static int paethPredict(int west, int north, int northWest) {
        int p = west + north - northWest;
        int pa = Math.abs(p - west);
        int pb = Math.abs(p - north);
        int pc = Math.abs(p - northWest);
        if (pa <= pb && pa <= pc) return west;
        if (pb <= pc) return north;
        return northWest;
    }

    static final class ResidualAccumulator {
        private final int maxResidual;
        private final long[] histogram;
        private long count;
        private double sum;
        private double sumSquares;
        private double sumCubes;
        private double sumFourth;

        ResidualAccumulator(int maxResidual) {
            this.maxResidual = maxResidual;
            this.histogram = new long[(maxResidual * 2) + 1];
        }

        void add(int residual) {
            count++;
            sum += residual;
            double square = (double) residual * residual;
            sumSquares += square;
            sumCubes += square * residual;
            sumFourth += square * square;
            histogram[residual + maxResidual]++;
        }

        ResidualStats finish() {
            if (count == 0) {
                return ResidualStats.EMPTY;
            }

            double mean = sum / count;
            double secondRaw = sumSquares / count;
            double thirdRaw = sumCubes / count;
            double fourthRaw = sumFourth / count;
            double variance = Math.max(0.0, secondRaw - mean * mean);
            double stdDev = Math.sqrt(variance);

            double centeredThird = thirdRaw - (3 * mean * secondRaw) + (2 * mean * mean * mean);
            double centeredFourth = fourthRaw - (4 * mean * thirdRaw)
                    + (6 * mean * mean * secondRaw) - (3 * Math.pow(mean, 4));
            double skewness = stdDev > 0 ? centeredThird / Math.pow(stdDev, 3) : 0.0;
            double kurtosis = stdDev > 0 ? centeredFourth / Math.pow(stdDev, 4) - 3.0 : 0.0;

            int median = percentileFromHistogram(histogram, maxResidual, count / 2);
            long[] madHistogram = new long[maxResidual + 1];
            for (int i = 0; i < histogram.length; i++) {
                long bucket = histogram[i];
                if (bucket == 0) {
                    continue;
                }
                int residual = i - maxResidual;
                madHistogram[Math.abs(residual - median)] += bucket;
            }
            int mad = percentileFromHistogram(madHistogram, 0, count / 2);

            return new ResidualStats(count, mean, median, stdDev, mad, skewness, kurtosis, histogram.clone());
        }

        private static int percentileFromHistogram(long[] histogram, int offset, long targetIndex) {
            long seen = 0;
            for (int i = 0; i < histogram.length; i++) {
                seen += histogram[i];
                if (seen > targetIndex) {
                    return i - offset;
                }
            }
            return histogram.length - 1 - offset;
        }
    }

    record ResidualStats(long count,
                         double meanResidual,
                         int medianResidual,
                         double stdDevResidual,
                         int madResidual,
                         double skewness,
                         double kurtosis,
                         long[] histogram) {
        static final ResidualStats EMPTY = new ResidualStats(0, 0, 0, 0, 0, 0, 0, new long[1]);
    }

    record ResidualResult(String fixture,
                          String scene,
                          int plane,
                          AnalysisArea area,
                          PredictorCandidate candidate,
                          ResidualStats stats) {
    }

    record FixtureReport(String fixture, String scene, Map<String, String> generatedPlots) {
    }

    record FamilyRank(PredictorFamily family, double rank) {
    }
}
