package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.Cr3Exception;
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
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferUShort;
import java.awt.image.Raster;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Predictor evidence matrix 2.0.
 *
 * <p>The predictor evidence part is now measured against the reference-backed
 * CR3 data source rather than against the decoder output. In parallel, the test
 * emits decoder-vs-reference residual diagnostics so the report answers the
 * operational question: where does the decoder fail, and what spatial /
 * periodic structure does the failure have?</p>
 */
class PredictorEvidenceMatrixIT {

    private static final Logger log = LoggerFactory.getLogger(PredictorEvidenceMatrixIT.class);

    private static final Path SAMPLES_DIR = Path.of("src", "test", "resources", "samples");
    private static final Path REPORT_DIR = Path.of("target", "predictor-evidence");
    private static final Path IMAGE_DIR = REPORT_DIR.resolve("images");
    private static final Path CSV_DIR = REPORT_DIR.resolve("csv");
    private static final Path CROP_DIR = IMAGE_DIR.resolve("crops");

    private static final int DISPLAY_RANGE = 2048;
    private static final int HISTOGRAM_BUCKET_WIDTH = 64;
    private static final int[] AUTOCORR_LAGS = {1, 2, 4, 8, 16, 32};
    private static final int SUSPICIOUS_LIMIT = 20;
    private static final int SUSPICIOUS_WINDOW = 64;
    private static final int SUSPICIOUS_STEP = 32;

    private static final List<AnalysisArea> AREA_ORDER = List.of(
            AnalysisArea.LEFT_OB, AnalysisArea.TOP_OB, AnalysisArea.IMAGE_AREA
    );

    @Test
    @DisplayName("Predictor evidence matrix 2.0 against reference-backed CR3 data")
    void runPredictorEvidenceMatrix() throws Exception {
        if (!Files.isDirectory(SAMPLES_DIR)) {
            log.info("No samples dir at {} — skipping", SAMPLES_DIR.toAbsolutePath());
            return;
        }

        List<Path> fixtures = discoverFixtures();
        if (fixtures.isEmpty()) {
            log.info("No CR3 fixtures — skipping");
            return;
        }

        Files.createDirectories(REPORT_DIR);
        Files.createDirectories(IMAGE_DIR);
        Files.createDirectories(CSV_DIR);
        Files.createDirectories(CROP_DIR);

        List<CandidateResult> candidateResults = new ArrayList<>();
        List<AreaDiagnostic> diagnostics = new ArrayList<>();
        List<FixtureSummary> fixtureSummaries = new ArrayList<>();
        List<SkippedFixture> skippedFixtures = new ArrayList<>();

        for (Path fixture : fixtures) {
            analyseFixture(fixture, candidateResults, diagnostics, fixtureSummaries, skippedFixtures);
        }

        writeCandidateTsv(candidateResults, REPORT_DIR.resolve("results.tsv"));
        writeDiagnosticTsv(diagnostics, REPORT_DIR.resolve("decoder-residuals.tsv"));
        writeSkippedTsv(skippedFixtures, REPORT_DIR.resolve("skipped-fixtures.tsv"));
        writeFixtureRankingTsv(fixtureSummaries, REPORT_DIR.resolve("fixture-ranking.tsv"));
        writeJson(candidateResults, diagnostics, fixtureSummaries, skippedFixtures, REPORT_DIR.resolve("results.json"));
        writeMarkdown(candidateResults, diagnostics, fixtureSummaries, skippedFixtures, REPORT_DIR.resolve("results.md"));

        log.info("Predictor evidence matrix 2.0 complete. {} predictor rows, {} decoder diagnostics, {} fixture summaries, {} skipped.",
                candidateResults.size(), diagnostics.size(), fixtureSummaries.size(), skippedFixtures.size());
    }

    private static List<Path> discoverFixtures() throws IOException {
        try (Stream<Path> stream = Files.walk(SAMPLES_DIR)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".cr3"))
                    .sorted(Comparator.comparing(path -> SAMPLES_DIR.relativize(path).toString()))
                    .toList();
        }
    }

    private static void analyseFixture(Path file,
                                       List<CandidateResult> allCandidateResults,
                                       List<AreaDiagnostic> allDiagnostics,
                                       List<FixtureSummary> fixtureSummaries,
                                       List<SkippedFixture> skippedFixtures) throws Exception {
        String fixturePath = relativeFixture(file);
        String fixtureName = file.getFileName().toString().replaceFirst("\\.cr3$", "");
        String scene = sceneTag(fixtureName);

        if (!hasReferenceSidecars(file)) {
            skippedFixtures.add(new SkippedFixture(fixturePath, "missing reference sidecars"));
            return;
        }

        ReferenceImage reference;
        try {
            reference = loadReference(referenceTiffPath(file));
        } catch (Exception ex) {
            skippedFixtures.add(new SkippedFixture(fixturePath, "failed to load reference TIFF: " + ex.getMessage()));
            return;
        }

        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            if (in == null) {
                skippedFixtures.add(new SkippedFixture(fixturePath, "could not open fixture"));
                return;
            }
            Cr3Container container = Cr3Parser.parse(in);
            Cr3Track rawTrack = container.tracks().stream()
                    .filter(track -> track.type() == Cr3Track.TrackType.CRX_BIG
                            || track.type() == Cr3Track.TrackType.CRX_RAW)
                    .findFirst()
                    .orElse(null);
            if (rawTrack == null) {
                skippedFixtures.add(new SkippedFixture(fixturePath, "no CRX_BIG/CRX_RAW track"));
                return;
            }

            Cmp1Box cmp1 = rawTrack.cmp1();
            if (cmp1 == null) {
                skippedFixtures.add(new SkippedFixture(fixturePath, "missing CMP1"));
                return;
            }
            if (!cmp1.isLossless()) {
                skippedFixtures.add(new SkippedFixture(fixturePath, "C-RAW / lossy fixture"));
                return;
            }

            Iad1Box iad1 = rawTrack.iad1();
            if (iad1 == null) {
                skippedFixtures.add(new SkippedFixture(fixturePath, "missing IAD1"));
                return;
            }

            int imgW = cmp1.imageWidth() > 0 ? cmp1.imageWidth() : rawTrack.width();
            int imgH = cmp1.imageHeight() > 0 ? cmp1.imageHeight() : rawTrack.height();
            int bitDepth = cmp1.bitsPerSample() > 0 ? cmp1.bitsPerSample() : 14;
            if (reference.width() != imgW || reference.height() != imgH) {
                skippedFixtures.add(new SkippedFixture(fixturePath,
                        "reference dimension mismatch (" + reference.width() + "x" + reference.height()
                                + " vs " + imgW + "x" + imgH + ")"));
                return;
            }

            byte[] payload = new byte[(int) rawTrack.dataSize()];
            in.seek(rawTrack.dataOffset());
            in.readFully(payload);
            CrxHeader.CodecVersion version = container.cncvBox() == null
                    ? CrxHeader.CodecVersion.V1
                    : CrxHeader.detectVersion(container.cncvBox().versionString());

            Cr3RawImage decoded;
            try {
                decoded = CrxLosslessRawDecoder.decode(payload, imgW, imgH, bitDepth, version, cmp1);
            } catch (Cr3Exception ex) {
                skippedFixtures.add(new SkippedFixture(fixturePath, "decoder failed: " + ex.getMessage()));
                return;
            }

            int[] leftOb = clampRect(iad1.leftObLeft(), iad1.leftObTop(),
                    iad1.leftObRight(), iad1.leftObBottom(), imgW, imgH);
            int[] topOb = clampRect(iad1.topObLeft(), iad1.topObTop(),
                    iad1.topObRight(), iad1.topObBottom(), imgW, imgH);
            int[] imageArea = resolveImageArea(iad1, leftOb, topOb, imgW, imgH);

            int planeW = (imgW + 1) / 2;
            int planeH = (imgH + 1) / 2;
            int[] referenceBayer = reference.samples();
            int[] decodedBayer = decoded.data();

            log.info("Analysing reference-backed evidence for {} ({}×{}, {}bit, scene={})",
                    file.getFileName(), imgW, imgH, bitDepth, scene);

            Map<String, String> generatedPlots = new LinkedHashMap<>();
            List<CandidateResult> fixtureCandidateResults = new ArrayList<>();
            List<AreaDiagnostic> fixtureDiagnostics = new ArrayList<>();
            List<SuspiciousRegion> suspiciousRegions = new ArrayList<>();

            for (int plane = 0; plane < 4; plane++) {
                int[] referencePlane = extractPlane(referenceBayer, imgW, planeW, planeH, plane);
                int[] decodedPlane = extractPlane(decodedBayer, imgW, planeW, planeH, plane);

                List<CandidateResult> planeCandidateResults = analyseReferencePlane(
                        fixtureName, scene, plane, referencePlane, planeW, planeH, bitDepth,
                        leftOb, topOb, imageArea
                );
                fixtureCandidateResults.addAll(planeCandidateResults);
                allCandidateResults.addAll(planeCandidateResults);

                for (AnalysisArea area : AREA_ORDER) {
                    List<CandidateResult> areaCandidateResults = planeCandidateResults.stream()
                            .filter(result -> result.area() == area)
                            .toList();
                    if (!areaCandidateResults.isEmpty()) {
                        Path histogramPath = IMAGE_DIR.resolve(
                                fixtureName + "-" + area.fileLabel + "-plane" + plane + "-histogram.png");
                        renderHistogramComparison(fixtureName, plane, area, areaCandidateResults, histogramPath);
                        generatedPlots.put(area.fileLabel + "-plane" + plane + "-histogram",
                                "images/" + histogramPath.getFileName());
                    }

                    int[] rawRect = switch (area) {
                        case LEFT_OB -> leftOb;
                        case TOP_OB -> topOb;
                        case IMAGE_AREA -> imageArea;
                        case OTHER -> new int[]{0, 0, 0, 0};
                    };
                    PlaneRect planeRect = toPlaneRect(rawRect, plane);
                    if (planeRect.width() <= 0 || planeRect.height() <= 0) {
                        continue;
                    }

                    int[] residuals = extractResiduals(decodedPlane, referencePlane, planeW, planeRect);
                    DiagnosticStats stats = computeDiagnosticStats(residuals, planeRect.width(), planeRect.height(), bitDepth);

                    String artifactBase = fixtureName + "-" + area.fileLabel + "-plane" + plane;
                    Path heatmapPath = IMAGE_DIR.resolve(artifactBase + "-residual-map.png");
                    renderHeatmap(fixtureName, plane, area, residuals, planeRect.width(), planeRect.height(), heatmapPath);
                    generatedPlots.put(area.fileLabel + "-plane" + plane + "-residual-map",
                            "images/" + heatmapPath.getFileName());

                    Path rowCsv = CSV_DIR.resolve(artifactBase + "-row-mean.csv");
                    Path colCsv = CSV_DIR.resolve(artifactBase + "-column-mean.csv");
                    writeSeriesCsv(rowCsv, "row", stats.rowMeans());
                    writeSeriesCsv(colCsv, "column", stats.colMeans());
                    Path rowPlot = IMAGE_DIR.resolve(artifactBase + "-row-mean.png");
                    Path colPlot = IMAGE_DIR.resolve(artifactBase + "-column-mean.png");
                    renderSeriesPlot(fixtureName + " — plane " + plane + " — " + area.id + " row mean residual",
                            stats.rowMeans(), rowPlot);
                    renderSeriesPlot(fixtureName + " — plane " + plane + " — " + area.id + " column mean residual",
                            stats.colMeans(), colPlot);
                    generatedPlots.put(area.fileLabel + "-plane" + plane + "-row-mean", "images/" + rowPlot.getFileName());
                    generatedPlots.put(area.fileLabel + "-plane" + plane + "-column-mean", "images/" + colPlot.getFileName());

                    String rowPeaks = "-";
                    String colPeaks = "-";
                    String fftPlotPath = null;
                    if (area.isOpticalBlack()) {
                        Spectrum rowSpectrum = analyseSpectrum(stats.rowMeans());
                        Spectrum colSpectrum = analyseSpectrum(stats.colMeans());
                        rowPeaks = formatPeaks(rowSpectrum.peaks());
                        colPeaks = formatPeaks(colSpectrum.peaks());
                        Path fftPlot = IMAGE_DIR.resolve(artifactBase + "-fft.png");
                        renderSpectrumPlot(fixtureName, plane, area, rowSpectrum, colSpectrum, fftPlot);
                        fftPlotPath = "images/" + fftPlot.getFileName();
                        generatedPlots.put(area.fileLabel + "-plane" + plane + "-fft", fftPlotPath);
                    }

                    AreaDiagnostic diagnostic = new AreaDiagnostic(
                            fixtureName,
                            scene,
                            plane,
                            area,
                            stats,
                            "images/" + heatmapPath.getFileName(),
                            "csv/" + rowCsv.getFileName(),
                            "csv/" + colCsv.getFileName(),
                            "images/" + rowPlot.getFileName(),
                            "images/" + colPlot.getFileName(),
                            fftPlotPath,
                            rowPeaks,
                            colPeaks
                    );
                    fixtureDiagnostics.add(diagnostic);
                    allDiagnostics.add(diagnostic);
                }

                int[] fullResiduals = subtractPlanes(decodedPlane, referencePlane);
                suspiciousRegions.addAll(findSuspiciousRegions(
                        fixtureName, scene, plane, fullResiduals, planeW, planeH, leftOb, topOb, imageArea, cmp1
                ));
            }

            for (AnalysisArea area : AREA_ORDER) {
                List<CandidateResult> areaResults = fixtureCandidateResults.stream()
                        .filter(result -> result.area() == area)
                        .toList();
                if (areaResults.isEmpty()) {
                    continue;
                }
                Path plotPath = IMAGE_DIR.resolve(fixtureName + "-" + area.fileLabel + "-per-plane-stddev.png");
                renderPerPlaneStdDevPlot(fixtureName, area, areaResults, plotPath);
                generatedPlots.put(area.fileLabel + "-per-plane-stddev", "images/" + plotPath.getFileName());
            }

            List<SuspiciousRegion> selectedSuspicious = selectSuspiciousRegions(suspiciousRegions, SUSPICIOUS_LIMIT);
            for (int i = 0; i < selectedSuspicious.size(); i++) {
                SuspiciousRegion region = selectedSuspicious.get(i);
                Path cropPath = CROP_DIR.resolve(fixtureName + "-crop_" + String.format(Locale.ROOT, "%03d", i + 1) + ".png");
                renderSuspiciousCrop(region, cropPath);
                selectedSuspicious.set(i, region.withCropPath("images/crops/" + cropPath.getFileName()));
                generatedPlots.put("crop_" + String.format(Locale.ROOT, "%03d", i + 1),
                        "images/crops/" + cropPath.getFileName());
            }

            FixtureRanking ranking = buildFixtureRanking(fixtureName, scene, fixtureCandidateResults, fixtureDiagnostics);
            fixtureSummaries.add(new FixtureSummary(fixtureName, scene, generatedPlots, ranking, selectedSuspicious));
        }
    }

    private static FixtureRanking buildFixtureRanking(String fixture,
                                                      String scene,
                                                      List<CandidateResult> candidateResults,
                                                      List<AreaDiagnostic> diagnostics) {
        Map<PredictorCandidate, DoubleSummaryStatistics> byCandidate = new EnumMap<>(PredictorCandidate.class);
        for (PredictorCandidate candidate : PredictorCandidate.values()) {
            byCandidate.put(candidate, new DoubleSummaryStatistics());
        }
        for (CandidateResult result : candidateResults) {
            byCandidate.get(result.candidate()).accept(result.stats().stdDevResidual());
        }

        PredictorCandidate best = null;
        PredictorCandidate worst = null;
        double bestValue = Double.POSITIVE_INFINITY;
        double worstValue = Double.NEGATIVE_INFINITY;
        for (Map.Entry<PredictorCandidate, DoubleSummaryStatistics> entry : byCandidate.entrySet()) {
            if (entry.getValue().getCount() == 0) {
                continue;
            }
            double mean = entry.getValue().getAverage();
            if (mean < bestValue) {
                bestValue = mean;
                best = entry.getKey();
            }
            if (mean > worstValue) {
                worstValue = mean;
                worst = entry.getKey();
            }
        }

        double decoderStdDev = diagnostics.stream()
                .mapToDouble(diagnostic -> diagnostic.stats().residualStats().stdDevResidual())
                .average()
                .orElse(Double.NaN);

        return new FixtureRanking(
                fixture,
                scene,
                best == null ? "-" : best.id,
                worst == null ? "-" : worst.id,
                (Double.isFinite(bestValue) && Double.isFinite(worstValue)) ? worstValue - bestValue : Double.NaN,
                decoderStdDev
        );
    }

    private static List<CandidateResult> analyseReferencePlane(String fixture,
                                                               String scene,
                                                               int plane,
                                                               int[] planeData,
                                                               int planeW,
                                                               int planeH,
                                                               int bitDepth,
                                                               int[] leftOb,
                                                               int[] topOb,
                                                               int[] imageArea) {
        int maxResidual = ((1 << bitDepth) - 1) * 2;
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

        List<CandidateResult> results = new ArrayList<>();
        for (PredictorCandidate candidate : PredictorCandidate.values()) {
            EnumMap<AnalysisArea, ResidualAccumulator> perArea = accumulators.get(candidate);
            for (AnalysisArea area : AREA_ORDER) {
                ResidualStats stats = perArea.get(area).finish();
                if (stats.count() == 0) {
                    continue;
                }
                results.add(new CandidateResult(fixture, scene, plane, area, candidate, stats));
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

    private static void writeCandidateTsv(List<CandidateResult> results, Path path) throws IOException {
        try (var writer = Files.newBufferedWriter(path)) {
            writer.write("fixture\tscene\tsignalSource\tplane\tarea\tcandidate\tfamily\tcount"
                    + "\tmeanResidual\tmedianResidual\tstdDevResidual\tmadResidual"
                    + "\tskewness\tkurtosis\thistogram\n");
            for (CandidateResult result : results) {
                ResidualStats stats = result.stats();
                writer.write(String.format(Locale.ROOT,
                        "%s\t%s\treference\t%d\t%s\t%s\t%s\t%d\t%.6f\t%d\t%.6f\t%d\t%.6f\t%.6f\t%s%n",
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

    private static void writeDiagnosticTsv(List<AreaDiagnostic> diagnostics, Path path) throws IOException {
        try (var writer = Files.newBufferedWriter(path)) {
            writer.write("fixture\tscene\tplane\tarea\tcount\tmeanResidual\tmedianResidual\tstdDevResidual\tmadResidual"
                    + "\tskewness\tkurtosis\trowProfileStd\tcolProfileStd"
                    + "\tautocorrLag1\tautocorrLag2\tautocorrLag4\tautocorrLag8\tautocorrLag16\tautocorrLag32"
                    + "\tbandingScore\trowPeaks\tcolumnPeaks\theatmap\trowMeanCsv\tcolumnMeanCsv\n");
            for (AreaDiagnostic diagnostic : diagnostics) {
                ResidualStats stats = diagnostic.stats().residualStats();
                double[] autocorr = diagnostic.stats().autocorr();
                writer.write(String.format(Locale.ROOT,
                        "%s\t%s\t%d\t%s\t%d\t%.6f\t%d\t%.6f\t%d\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%s\t%s\t%s\t%s\t%s%n",
                        diagnostic.fixture(),
                        diagnostic.scene(),
                        diagnostic.plane(),
                        diagnostic.area().id,
                        stats.count(),
                        stats.meanResidual(),
                        stats.medianResidual(),
                        stats.stdDevResidual(),
                        stats.madResidual(),
                        stats.skewness(),
                        stats.kurtosis(),
                        diagnostic.stats().rowProfileStd(),
                        diagnostic.stats().colProfileStd(),
                        autocorr.length > 0 ? autocorr[0] : 0.0,
                        autocorr.length > 1 ? autocorr[1] : 0.0,
                        autocorr.length > 2 ? autocorr[2] : 0.0,
                        autocorr.length > 3 ? autocorr[3] : 0.0,
                        autocorr.length > 4 ? autocorr[4] : 0.0,
                        autocorr.length > 5 ? autocorr[5] : 0.0,
                        diagnostic.stats().bandingScore(),
                        diagnostic.rowPeaks(),
                        diagnostic.columnPeaks(),
                        diagnostic.heatmapPath(),
                        diagnostic.rowCsvPath(),
                        diagnostic.columnCsvPath()));
            }
        }
    }

    private static void writeSkippedTsv(List<SkippedFixture> skippedFixtures, Path path) throws IOException {
        try (var writer = Files.newBufferedWriter(path)) {
            writer.write("fixture\treason\n");
            for (SkippedFixture skipped : skippedFixtures) {
                writer.write(skipped.fixture() + "\t" + skipped.reason() + "\n");
            }
        }
    }

    private static void writeFixtureRankingTsv(List<FixtureSummary> fixtureSummaries, Path path) throws IOException {
        try (var writer = Files.newBufferedWriter(path)) {
            writer.write("fixture\tscene\tdecoderStdDev\tbestPredictor\tworstPredictor\tdelta\n");
            for (FixtureSummary summary : sortedFixtureSummaries(fixtureSummaries)) {
                FixtureRanking ranking = summary.ranking();
                writer.write(String.format(Locale.ROOT, "%s\t%s\t%.6f\t%s\t%s\t%.6f%n",
                        ranking.fixture(),
                        ranking.scene(),
                        ranking.decoderStdDev(),
                        ranking.bestPredictor(),
                        ranking.worstPredictor(),
                        ranking.delta()));
            }
        }
    }

    private static void writeJson(List<CandidateResult> candidateResults,
                                  List<AreaDiagnostic> diagnostics,
                                  List<FixtureSummary> fixtureSummaries,
                                  List<SkippedFixture> skippedFixtures,
                                  Path path) throws IOException {
        try (var writer = Files.newBufferedWriter(path)) {
            writer.write("{\n");
            writer.write(String.format(Locale.ROOT, "  \"generatedAt\": \"%s\",%n", jsonEscape(Instant.now().toString())));

            writer.write("  \"candidateResults\": [\n");
            for (int i = 0; i < candidateResults.size(); i++) {
                CandidateResult result = candidateResults.get(i);
                ResidualStats stats = result.stats();
                writer.write("    {\n");
                writer.write(String.format(Locale.ROOT, "      \"fixture\": \"%s\",%n", jsonEscape(result.fixture())));
                writer.write(String.format(Locale.ROOT, "      \"scene\": \"%s\",%n", jsonEscape(result.scene())));
                writer.write("      \"signalSource\": \"reference\",\n");
                writer.write(String.format(Locale.ROOT, "      \"plane\": %d,%n", result.plane()));
                writer.write(String.format(Locale.ROOT, "      \"area\": \"%s\",%n", jsonEscape(result.area().id)));
                writer.write(String.format(Locale.ROOT, "      \"candidate\": \"%s\",%n", jsonEscape(result.candidate().id)));
                writer.write(String.format(Locale.ROOT, "      \"family\": \"%s\",%n", jsonEscape(result.candidate().family.id)));
                writer.write(String.format(Locale.ROOT, "      \"count\": %d,%n", stats.count()));
                writer.write(String.format(Locale.ROOT, "      \"meanResidual\": %.6f,%n", stats.meanResidual()));
                writer.write(String.format(Locale.ROOT, "      \"medianResidual\": %d,%n", stats.medianResidual()));
                writer.write(String.format(Locale.ROOT, "      \"stdDevResidual\": %.6f,%n", stats.stdDevResidual()));
                writer.write(String.format(Locale.ROOT, "      \"madResidual\": %d,%n", stats.madResidual()));
                writer.write(String.format(Locale.ROOT, "      \"skewness\": %.6f,%n", stats.skewness()));
                writer.write(String.format(Locale.ROOT, "      \"kurtosis\": %.6f%n", stats.kurtosis()));
                writer.write(i + 1 < candidateResults.size() ? "    },\n" : "    }\n");
            }
            writer.write("  ],\n");

            writer.write("  \"decoderResiduals\": [\n");
            for (int i = 0; i < diagnostics.size(); i++) {
                AreaDiagnostic diagnostic = diagnostics.get(i);
                ResidualStats stats = diagnostic.stats().residualStats();
                writer.write("    {\n");
                writer.write(String.format(Locale.ROOT, "      \"fixture\": \"%s\",%n", jsonEscape(diagnostic.fixture())));
                writer.write(String.format(Locale.ROOT, "      \"scene\": \"%s\",%n", jsonEscape(diagnostic.scene())));
                writer.write(String.format(Locale.ROOT, "      \"plane\": %d,%n", diagnostic.plane()));
                writer.write(String.format(Locale.ROOT, "      \"area\": \"%s\",%n", jsonEscape(diagnostic.area().id)));
                writer.write(String.format(Locale.ROOT, "      \"count\": %d,%n", stats.count()));
                writer.write(String.format(Locale.ROOT, "      \"meanResidual\": %.6f,%n", stats.meanResidual()));
                writer.write(String.format(Locale.ROOT, "      \"medianResidual\": %d,%n", stats.medianResidual()));
                writer.write(String.format(Locale.ROOT, "      \"stdDevResidual\": %.6f,%n", stats.stdDevResidual()));
                writer.write(String.format(Locale.ROOT, "      \"bandingScore\": %.6f,%n", diagnostic.stats().bandingScore()));
                writer.write(String.format(Locale.ROOT, "      \"rowPeaks\": \"%s\",%n", jsonEscape(diagnostic.rowPeaks())));
                writer.write(String.format(Locale.ROOT, "      \"columnPeaks\": \"%s\",%n", jsonEscape(diagnostic.columnPeaks())));
                writer.write(String.format(Locale.ROOT, "      \"heatmapPath\": \"%s\"%n", jsonEscape(diagnostic.heatmapPath())));
                writer.write(i + 1 < diagnostics.size() ? "    },\n" : "    }\n");
            }
            writer.write("  ],\n");

            writer.write("  \"fixtureRankings\": [\n");
            List<FixtureSummary> sortedSummaries = sortedFixtureSummaries(fixtureSummaries);
            for (int i = 0; i < sortedSummaries.size(); i++) {
                FixtureRanking ranking = sortedSummaries.get(i).ranking();
                writer.write("    {\n");
                writer.write(String.format(Locale.ROOT, "      \"fixture\": \"%s\",%n", jsonEscape(ranking.fixture())));
                writer.write(String.format(Locale.ROOT, "      \"scene\": \"%s\",%n", jsonEscape(ranking.scene())));
                writer.write(String.format(Locale.ROOT, "      \"decoderStdDev\": %.6f,%n", ranking.decoderStdDev()));
                writer.write(String.format(Locale.ROOT, "      \"bestPredictor\": \"%s\",%n", jsonEscape(ranking.bestPredictor())));
                writer.write(String.format(Locale.ROOT, "      \"worstPredictor\": \"%s\",%n", jsonEscape(ranking.worstPredictor())));
                writer.write(String.format(Locale.ROOT, "      \"delta\": %.6f%n", ranking.delta()));
                writer.write(i + 1 < sortedSummaries.size() ? "    },\n" : "    }\n");
            }
            writer.write("  ],\n");

            writer.write("  \"skippedFixtures\": [\n");
            for (int i = 0; i < skippedFixtures.size(); i++) {
                SkippedFixture skipped = skippedFixtures.get(i);
                writer.write("    {\n");
                writer.write(String.format(Locale.ROOT, "      \"fixture\": \"%s\",%n", jsonEscape(skipped.fixture())));
                writer.write(String.format(Locale.ROOT, "      \"reason\": \"%s\"%n", jsonEscape(skipped.reason())));
                writer.write(i + 1 < skippedFixtures.size() ? "    },\n" : "    }\n");
            }
            writer.write("  ]\n");
            writer.write("}\n");
        }
    }

    private static void writeMarkdown(List<CandidateResult> candidateResults,
                                      List<AreaDiagnostic> diagnostics,
                                      List<FixtureSummary> fixtureSummaries,
                                      List<SkippedFixture> skippedFixtures,
                                      Path path) throws IOException {
        try (var writer = Files.newBufferedWriter(path)) {
            writer.write("# Predictor Evidence Matrix 2.0 — Reference-backed Results\n\n");
            writer.write("Generated: " + Instant.now() + "\n\n");
            writer.write("This report splits the analysis into two layers:\n");
            writer.write("- **Predictor evidence** is measured against the **reference-backed CR3 data source**.\n");
            writer.write("- **Decoder residual diagnostics** compare **decoder output vs reference** to localise failure structure.\n\n");

            writer.write("## Scope\n\n");
            writer.write(String.format(Locale.ROOT,
                    "- Reference-backed fixtures analysed: **%d**%n", fixtureSummaries.size()));
            writer.write(String.format(Locale.ROOT,
                    "- Predictor result rows: **%d**%n", candidateResults.size()));
            writer.write(String.format(Locale.ROOT,
                    "- Decoder diagnostic rows: **%d**%n", diagnostics.size()));
            writer.write(String.format(Locale.ROOT,
                    "- Skipped fixtures: **%d**%n%n", skippedFixtures.size()));

            writer.write("## Predictor candidates\n\n");
            for (PredictorCandidate candidate : PredictorCandidate.values()) {
                writer.write(String.format("- **%s** (`%s` family): %s%n",
                        candidate.id, candidate.family.id, candidate.description));
            }
            writer.write("\n## Area definitions\n\n");
            writer.write("- `left-ob`: IAD1 left optical-black rectangle\n");
            writer.write("- `top-ob`: IAD1 top optical-black rectangle\n");
            writer.write("- `image-area`: IAD1 active area, or crop/fallback if absent\n\n");

            writer.write("## Fixture ranking\n\n");
            writer.write("| Fixture | Scene | Decoder StdDev | Best Predictor | Worst Predictor | Delta |\n");
            writer.write("|---|---|---:|---|---|---:|\n");
            for (FixtureSummary summary : sortedFixtureSummaries(fixtureSummaries)) {
                FixtureRanking ranking = summary.ranking();
                writer.write(String.format(Locale.ROOT, "| %s | %s | %.3f | %s | %s | %.3f |%n",
                        ranking.fixture(),
                        ranking.scene(),
                        ranking.decoderStdDev(),
                        ranking.bestPredictor(),
                        ranking.worstPredictor(),
                        ranking.delta()));
            }

            writer.write("\n## Family win counts (lowest predictor residual stddev on reference signal)\n\n");
            writer.write("| Area | Family | Wins |\n");
            writer.write("|---|---|---|\n");
            for (AnalysisArea area : AREA_ORDER) {
                Map<PredictorFamily, Long> wins = computeFamilyWins(candidateResults, area);
                for (Map.Entry<PredictorFamily, Long> entry : wins.entrySet()) {
                    writer.write(String.format("| %s | %s | %d |%n", area.id, entry.getKey().id, entry.getValue()));
                }
            }

            writer.write("\n## Best candidate per fixture / plane / area\n\n");
            writer.write("| Fixture | Plane | Area | Best candidate | Family | StdDev | MAD | Mean |\n");
            writer.write("|---|---|---|---|---|---:|---:|---:|\n");
            for (CandidateResult best : bestResults(candidateResults)) {
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
            writer.write("|---|---|---:|\n");
            for (AnalysisArea area : AREA_ORDER) {
                for (FamilyRank rank : computeFamilyRanks(candidateResults, area)) {
                    writer.write(String.format(Locale.ROOT, "| %s | %s | %.3f |%n",
                            area.id, rank.family().id, rank.rank()));
                }
            }

            writer.write("\n## Decoder residual plane comparison\n\n");
            Map<String, List<AreaDiagnostic>> diagnosticsByFixture = diagnostics.stream()
                    .collect(Collectors.groupingBy(AreaDiagnostic::fixture, LinkedHashMap::new, Collectors.toList()));
            for (FixtureSummary summary : fixtureSummaries) {
                writer.write("### " + summary.fixture() + " (" + summary.scene() + ")\n\n");
                writer.write("| Area | Plane | Mean | Median | StdDev | Banding | Lag-1 | Row profile std | Column profile std | Row peaks | Column peaks |\n");
                writer.write("|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---|\n");
                List<AreaDiagnostic> fixtureDiagnostics = diagnosticsByFixture.getOrDefault(summary.fixture(), List.of()).stream()
                        .sorted(Comparator.comparing((AreaDiagnostic d) -> d.area().ordinal()).thenComparingInt(AreaDiagnostic::plane))
                        .toList();
                for (AreaDiagnostic diagnostic : fixtureDiagnostics) {
                    ResidualStats stats = diagnostic.stats().residualStats();
                    double lag1 = diagnostic.stats().autocorr().length > 0 ? diagnostic.stats().autocorr()[0] : 0.0;
                    writer.write(String.format(Locale.ROOT,
                            "| %s | %d | %.3f | %d | %.3f | %.3f | %.3f | %.3f | %.3f | %s | %s |%n",
                            diagnostic.area().id,
                            diagnostic.plane(),
                            stats.meanResidual(),
                            stats.medianResidual(),
                            stats.stdDevResidual(),
                            diagnostic.stats().bandingScore(),
                            lag1,
                            diagnostic.stats().rowProfileStd(),
                            diagnostic.stats().colProfileStd(),
                            diagnostic.rowPeaks(),
                            diagnostic.columnPeaks()));
                }

                writer.write("\n#### Most suspicious regions\n\n");
                writer.write("| Rank | Plane | Area | Score | Crop |\n");
                writer.write("|---|---:|---|---:|---|\n");
                for (int i = 0; i < summary.suspiciousRegions().size(); i++) {
                    SuspiciousRegion region = summary.suspiciousRegions().get(i);
                    writer.write(String.format(Locale.ROOT, "| %d | %d | %s | %.3f | ![](%s) |%n",
                            i + 1, region.plane(), region.area(), region.score(), region.cropPath()));
                }

                writer.write("\n#### Diagnostic plots\n\n");
                for (Map.Entry<String, String> entry : summary.generatedPlots().entrySet()) {
                    writer.write(String.format("- `%s`: ![](%s)%n", entry.getKey(), entry.getValue()));
                }
                writer.write("\n");
            }

            writer.write("## Skipped fixtures\n\n");
            if (skippedFixtures.isEmpty()) {
                writer.write("- None\n");
            } else {
                writer.write("| Fixture | Reason |\n");
                writer.write("|---|---|\n");
                for (SkippedFixture skipped : skippedFixtures) {
                    writer.write("| " + skipped.fixture() + " | " + skipped.reason() + " |\n");
                }
            }

            writer.write("\n## Notes\n\n");
            writer.write("- `results.tsv` contains predictor residual metrics measured on the **reference** signal.\n");
            writer.write("- `decoder-residuals.tsv` contains **decoder-vs-reference** residual metrics, including banding and autocorrelation.\n");
            writer.write("- `row-mean.csv` and `column-mean.csv` artifacts are emitted per fixture / plane / area under `target/predictor-evidence/csv/`.\n");
            writer.write("- FFT plots are emitted for optical-black regions only.\n");
        }
    }

    private static List<FixtureSummary> sortedFixtureSummaries(List<FixtureSummary> fixtureSummaries) {
        return fixtureSummaries.stream()
                .sorted(Comparator.comparingDouble((FixtureSummary summary) -> summary.ranking().decoderStdDev()).reversed()
                        .thenComparing(summary -> summary.fixture().toLowerCase(Locale.ROOT)))
                .toList();
    }

    private static Map<PredictorFamily, Long> computeFamilyWins(List<CandidateResult> results, AnalysisArea area) {
        Map<PredictorFamily, Long> counts = new LinkedHashMap<>();
        for (PredictorFamily family : PredictorFamily.values()) {
            counts.put(family, 0L);
        }

        Map<String, List<CandidateResult>> groups = results.stream()
                .filter(result -> result.area() == area)
                .collect(Collectors.groupingBy(result -> result.fixture() + "#" + result.plane(),
                        LinkedHashMap::new, Collectors.toList()));

        for (List<CandidateResult> group : groups.values()) {
            Map<PredictorFamily, CandidateResult> bestPerFamily = new EnumMap<>(PredictorFamily.class);
            for (CandidateResult result : group) {
                CandidateResult existing = bestPerFamily.get(result.candidate().family);
                if (existing == null || compareResults(result, existing) < 0) {
                    bestPerFamily.put(result.candidate().family, result);
                }
            }
            CandidateResult winner = bestPerFamily.values().stream()
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

    private static List<CandidateResult> bestResults(List<CandidateResult> results) {
        Map<String, CandidateResult> best = new LinkedHashMap<>();
        for (CandidateResult result : results) {
            String key = result.fixture() + "#" + result.plane() + "#" + result.area().id;
            CandidateResult existing = best.get(key);
            if (existing == null || compareResults(result, existing) < 0) {
                best.put(key, result);
            }
        }
        return new ArrayList<>(best.values());
    }

    private static List<FamilyRank> computeFamilyRanks(List<CandidateResult> results, AnalysisArea area) {
        Map<PredictorFamily, DoubleSummaryStatistics> stats = new EnumMap<>(PredictorFamily.class);
        for (PredictorFamily family : PredictorFamily.values()) {
            stats.put(family, new DoubleSummaryStatistics());
        }

        Map<String, List<CandidateResult>> groups = results.stream()
                .filter(result -> result.area() == area)
                .collect(Collectors.groupingBy(result -> result.fixture() + "#" + result.plane(),
                        LinkedHashMap::new, Collectors.toList()));

        for (List<CandidateResult> group : groups.values()) {
            List<CandidateResult> ranked = new ArrayList<>();
            for (PredictorFamily family : PredictorFamily.values()) {
                group.stream()
                        .filter(result -> result.candidate().family == family)
                        .min(PredictorEvidenceMatrixIT::compareResults)
                        .ifPresent(ranked::add);
            }
            ranked.sort(PredictorEvidenceMatrixIT::compareResults);
            for (int i = 0; i < ranked.size(); i++) {
                stats.get(ranked.get(i).candidate().family).accept(i + 1.0);
            }
        }

        return stats.entrySet().stream()
                .filter(entry -> entry.getValue().getCount() > 0)
                .map(entry -> new FamilyRank(entry.getKey(), entry.getValue().getAverage()))
                .sorted(Comparator.comparingDouble(FamilyRank::rank))
                .toList();
    }

    private static int compareResults(CandidateResult left, CandidateResult right) {
        int byStdDev = Double.compare(left.stats().stdDevResidual(), right.stats().stdDevResidual());
        if (byStdDev != 0) {
            return byStdDev;
        }
        int byMad = Integer.compare(left.stats().madResidual(), right.stats().madResidual());
        if (byMad != 0) {
            return byMad;
        }
        return Double.compare(Math.abs(left.stats().meanResidual()), Math.abs(right.stats().meanResidual()));
    }

    private static DiagnosticStats computeDiagnosticStats(int[] residuals, int width, int height, int bitDepth) {
        int maxResidual = ((1 << bitDepth) - 1) * 2;
        ResidualAccumulator accumulator = new ResidualAccumulator(maxResidual);
        double[] rowMeans = new double[height];
        double[] colMeans = new double[width];
        double[] rowSums = new double[height];
        double[] colSums = new double[width];

        for (int y = 0; y < height; y++) {
            int rowBase = y * width;
            for (int x = 0; x < width; x++) {
                int residual = residuals[rowBase + x];
                accumulator.add(residual);
                rowSums[y] += residual;
                colSums[x] += residual;
            }
        }

        for (int y = 0; y < height; y++) {
            rowMeans[y] = width > 0 ? rowSums[y] / width : 0.0;
        }
        for (int x = 0; x < width; x++) {
            colMeans[x] = height > 0 ? colSums[x] / height : 0.0;
        }

        double[] autocorr = new double[AUTOCORR_LAGS.length];
        for (int i = 0; i < AUTOCORR_LAGS.length; i++) {
            autocorr[i] = computeAutocorr(rowMeans, AUTOCORR_LAGS[i]);
        }
        double bandingScore = 0.0;
        for (double value : autocorr) {
            if (Math.abs(value) > Math.abs(bandingScore)) {
                bandingScore = value;
            }
        }

        return new DiagnosticStats(
                accumulator.finish(),
                rowMeans,
                colMeans,
                computeProfileStd(rowMeans),
                computeProfileStd(colMeans),
                autocorr,
                bandingScore
        );
    }

    private static double computeProfileStd(double[] values) {
        if (values.length < 2) {
            return 0.0;
        }
        double mean = Arrays.stream(values).average().orElse(0.0);
        double variance = 0.0;
        for (double value : values) {
            double delta = value - mean;
            variance += delta * delta;
        }
        return Math.sqrt(variance / values.length);
    }

    private static double computeAutocorr(double[] values, int lag) {
        if (values.length <= lag) {
            return 0.0;
        }
        double mean = Arrays.stream(values).average().orElse(0.0);
        double numerator = 0.0;
        double denominator = 0.0;
        for (int i = 0; i < values.length; i++) {
            double delta = values[i] - mean;
            denominator += delta * delta;
            if (i + lag < values.length) {
                numerator += delta * (values[i + lag] - mean);
            }
        }
        return denominator > 0.0 ? numerator / denominator : 0.0;
    }

    private static Spectrum analyseSpectrum(double[] values) {
        if (values.length < 4) {
            return new Spectrum(new double[0], List.of());
        }
        int maxK = values.length / 2;
        double mean = Arrays.stream(values).average().orElse(0.0);
        double[] amplitudes = new double[maxK + 1];
        List<SpectrumPeak> peaks = new ArrayList<>();
        for (int k = 1; k <= maxK; k++) {
            double re = 0.0;
            double im = 0.0;
            for (int n = 0; n < values.length; n++) {
                double centered = values[n] - mean;
                double angle = -2.0 * Math.PI * k * n / values.length;
                re += centered * Math.cos(angle);
                im += centered * Math.sin(angle);
            }
            double amplitude = Math.hypot(re, im);
            amplitudes[k] = amplitude;
            peaks.add(new SpectrumPeak((double) values.length / k, amplitude));
        }
        peaks.sort(Comparator.comparingDouble(SpectrumPeak::amplitude).reversed());
        return new Spectrum(amplitudes, peaks.stream().limit(5).toList());
    }

    private static String formatPeaks(List<SpectrumPeak> peaks) {
        if (peaks.isEmpty()) {
            return "-";
        }
        return peaks.stream()
                .map(peak -> String.format(Locale.ROOT, "%.1fpx@%.1f", peak.periodPixels(), peak.amplitude()))
                .collect(Collectors.joining(", "));
    }

    private static List<SuspiciousRegion> findSuspiciousRegions(String fixture,
                                                                String scene,
                                                                int plane,
                                                                int[] fullResiduals,
                                                                int planeW,
                                                                int planeH,
                                                                int[] leftOb,
                                                                int[] topOb,
                                                                int[] imageArea,
                                                                Cmp1Box cmp1) {
        int rowOff = (plane >> 1) & 1;
        int colOff = plane & 1;
        int windowW = Math.min(SUSPICIOUS_WINDOW, planeW);
        int windowH = Math.min(SUSPICIOUS_WINDOW, planeH);
        int stepX = Math.max(8, Math.min(SUSPICIOUS_STEP, windowW));
        int stepY = Math.max(8, Math.min(SUSPICIOUS_STEP, windowH));
        List<SuspiciousRegion> out = new ArrayList<>();

        for (int y = 0; y <= Math.max(0, planeH - windowH); y += stepY) {
            for (int x = 0; x <= Math.max(0, planeW - windowW); x += stepX) {
                double absSum = 0.0;
                for (int yy = 0; yy < windowH; yy++) {
                    int rowBase = (y + yy) * planeW;
                    for (int xx = 0; xx < windowW; xx++) {
                        absSum += Math.abs(fullResiduals[rowBase + x + xx]);
                    }
                }
                double meanAbs = absSum / (windowW * windowH);
                int rawX = x * 2 + colOff;
                int rawY = y * 2 + rowOff;
                int rawW = windowW * 2;
                int rawH = windowH * 2;
                int[] rawWindow = {rawX, rawY, rawW, rawH};
                double score = meanAbs * suspiciousPriority(rawWindow, leftOb, topOb, cmp1);
                String area = areaForWindow(rawWindow, leftOb, topOb, imageArea);
                out.add(new SuspiciousRegion(
                        fixture, scene, plane, area, x, y, windowW, windowH, score, planeW, planeH, fullResiduals, null
                ));
            }
        }
        return out;
    }

    private static double suspiciousPriority(int[] rawWindow, int[] leftOb, int[] topOb, Cmp1Box cmp1) {
        double multiplier = 1.0;
        if (intersectsOrNear(rawWindow, leftOb, 32) || intersectsOrNear(rawWindow, topOb, 32)) {
            multiplier += 0.20;
        }
        if (cmp1 != null && (nearTileSeam(rawWindow, cmp1.tileWidth()) || nearTileSeamVertical(rawWindow, cmp1.tileHeight()))) {
            multiplier += 0.20;
        }
        return multiplier;
    }

    private static boolean nearTileSeam(int[] rect, int tileWidth) {
        if (tileWidth <= 0) {
            return false;
        }
        int start = rect[0];
        int end = rect[0] + rect[2];
        for (int seam = tileWidth; seam < end + 32; seam += tileWidth) {
            if (Math.abs(seam - start) <= 32 || Math.abs(seam - end) <= 32 || (seam >= start && seam <= end)) {
                return true;
            }
        }
        return false;
    }

    private static boolean nearTileSeamVertical(int[] rect, int tileHeight) {
        if (tileHeight <= 0) {
            return false;
        }
        int start = rect[1];
        int end = rect[1] + rect[3];
        for (int seam = tileHeight; seam < end + 32; seam += tileHeight) {
            if (Math.abs(seam - start) <= 32 || Math.abs(seam - end) <= 32 || (seam >= start && seam <= end)) {
                return true;
            }
        }
        return false;
    }

    private static boolean intersectsOrNear(int[] a, int[] b, int margin) {
        if (b[2] <= 0 || b[3] <= 0) {
            return false;
        }
        return a[0] < b[0] + b[2] + margin
                && a[0] + a[2] + margin > b[0]
                && a[1] < b[1] + b[3] + margin
                && a[1] + a[3] + margin > b[1];
    }

    private static String areaForWindow(int[] rawWindow, int[] leftOb, int[] topOb, int[] imageArea) {
        int centerX = rawWindow[0] + rawWindow[2] / 2;
        int centerY = rawWindow[1] + rawWindow[3] / 2;
        if (contains(leftOb, centerX, centerY)) {
            return AnalysisArea.LEFT_OB.id;
        }
        if (contains(topOb, centerX, centerY)) {
            return AnalysisArea.TOP_OB.id;
        }
        if (contains(imageArea, centerX, centerY)) {
            return AnalysisArea.IMAGE_AREA.id;
        }
        return "other";
    }

    private static List<SuspiciousRegion> selectSuspiciousRegions(List<SuspiciousRegion> regions, int limit) {
        List<SuspiciousRegion> sorted = regions.stream()
                .sorted(Comparator.comparingDouble(SuspiciousRegion::score).reversed())
                .toList();
        List<SuspiciousRegion> selected = new ArrayList<>();
        for (SuspiciousRegion candidate : sorted) {
            boolean overlaps = selected.stream().anyMatch(existing -> overlaps(candidate, existing));
            if (!overlaps) {
                selected.add(candidate);
                if (selected.size() == limit) {
                    break;
                }
            }
        }
        return selected;
    }

    private static boolean overlaps(SuspiciousRegion left, SuspiciousRegion right) {
        if (left.plane() != right.plane()) {
            return false;
        }
        int leftX2 = left.planeX() + left.width();
        int leftY2 = left.planeY() + left.height();
        int rightX2 = right.planeX() + right.width();
        int rightY2 = right.planeY() + right.height();
        return left.planeX() < rightX2
                && leftX2 > right.planeX()
                && left.planeY() < rightY2
                && leftY2 > right.planeY();
    }

    private static void renderSuspiciousCrop(SuspiciousRegion region, Path path) throws IOException {
        int[] crop = new int[region.width() * region.height()];
        for (int y = 0; y < region.height(); y++) {
            int srcBase = (region.planeY() + y) * region.planeWidth() + region.planeX();
            System.arraycopy(region.fullResiduals(), srcBase, crop, y * region.width(), region.width());
        }
        renderHeatmap(region.fixture(), region.plane(), AnalysisArea.fromId(region.area()),
                crop, region.width(), region.height(), path);
    }

    private static void writeSeriesCsv(Path path, String axisName, double[] values) throws IOException {
        try (var writer = Files.newBufferedWriter(path)) {
            writer.write(axisName + ",value\n");
            for (int i = 0; i < values.length; i++) {
                writer.write(String.format(Locale.ROOT, "%d,%.6f%n", i, values[i]));
            }
        }
    }

    private static void renderSeriesPlot(String title, double[] values, Path path) throws IOException {
        int width = 1200;
        int height = 600;
        int leftMargin = 70;
        int rightMargin = 20;
        int topMargin = 45;
        int bottomMargin = 50;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.drawString(title, 20, 25);

        int plotX = leftMargin;
        int plotY = topMargin;
        int plotWidth = width - leftMargin - rightMargin;
        int plotHeight = height - topMargin - bottomMargin;
        g.drawRect(plotX, plotY, plotWidth, plotHeight);

        if (values.length > 0) {
            double min = Arrays.stream(values).min().orElse(0.0);
            double max = Arrays.stream(values).max().orElse(0.0);
            if (min == max) {
                min -= 1.0;
                max += 1.0;
            }
            int zeroY = plotY + plotHeight - (int) Math.round((0.0 - min) / (max - min) * plotHeight);
            g.setColor(new Color(235, 235, 235));
            if (zeroY >= plotY && zeroY <= plotY + plotHeight) {
                g.drawLine(plotX, zeroY, plotX + plotWidth, zeroY);
            }

            g.setColor(new Color(31, 119, 180));
            int lastX = plotX;
            int lastY = plotY + plotHeight / 2;
            for (int i = 0; i < values.length; i++) {
                int x = plotX + (int) Math.round((double) i * plotWidth / Math.max(1, values.length - 1));
                int y = plotY + plotHeight - (int) Math.round((values[i] - min) / (max - min) * plotHeight);
                if (i > 0) {
                    g.drawLine(lastX, lastY, x, y);
                }
                lastX = x;
                lastY = y;
            }
            g.setColor(Color.BLACK);
            g.drawString(String.format(Locale.ROOT, "min %.2f", min), 10, plotY + plotHeight - 5);
            g.drawString(String.format(Locale.ROOT, "max %.2f", max), 10, plotY + 10);
        }

        g.dispose();
        ImageIO.write(image, "PNG", path.toFile());
    }

    private static void renderSpectrumPlot(String fixture,
                                           int plane,
                                           AnalysisArea area,
                                           Spectrum rowSpectrum,
                                           Spectrum colSpectrum,
                                           Path path) throws IOException {
        int width = 1200;
        int height = 700;
        int leftMargin = 70;
        int rightMargin = 30;
        int topMargin = 45;
        int bottomMargin = 60;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.drawString(fixture + " — plane " + plane + " — " + area.id + " FFT-like residual spectrum", 20, 25);

        int plotX = leftMargin;
        int plotY = topMargin;
        int plotWidth = width - leftMargin - rightMargin;
        int plotHeight = height - topMargin - bottomMargin;
        g.drawRect(plotX, plotY, plotWidth, plotHeight);

        double maxAmplitude = Math.max(
                Arrays.stream(rowSpectrum.amplitudes()).max().orElse(1.0),
                Arrays.stream(colSpectrum.amplitudes()).max().orElse(1.0)
        );
        maxAmplitude = Math.max(maxAmplitude, 1.0);
        drawSpectrumLine(g, rowSpectrum.amplitudes(), plotX, plotY, plotWidth, plotHeight, maxAmplitude, new Color(214, 39, 40));
        drawSpectrumLine(g, colSpectrum.amplitudes(), plotX, plotY, plotWidth, plotHeight, maxAmplitude, new Color(31, 119, 180));

        g.setColor(new Color(214, 39, 40));
        g.fillRect(width - 250, plotY + 10, 12, 12);
        g.setColor(Color.BLACK);
        g.drawString("row spectrum: " + formatPeaks(rowSpectrum.peaks()), width - 230, plotY + 20);
        g.setColor(new Color(31, 119, 180));
        g.fillRect(width - 250, plotY + 35, 12, 12);
        g.setColor(Color.BLACK);
        g.drawString("column spectrum: " + formatPeaks(colSpectrum.peaks()), width - 230, plotY + 45);
        g.dispose();
        ImageIO.write(image, "PNG", path.toFile());
    }

    private static void drawSpectrumLine(Graphics2D g,
                                         double[] amplitudes,
                                         int plotX,
                                         int plotY,
                                         int plotWidth,
                                         int plotHeight,
                                         double maxAmplitude,
                                         Color color) {
        if (amplitudes.length <= 1) {
            return;
        }
        g.setColor(color);
        int lastX = plotX;
        int lastY = plotY + plotHeight;
        for (int i = 1; i < amplitudes.length; i++) {
            int x = plotX + (int) Math.round((double) i * plotWidth / Math.max(1, amplitudes.length - 1));
            int y = plotY + plotHeight - (int) Math.round(amplitudes[i] / maxAmplitude * plotHeight);
            if (i > 1) {
                g.drawLine(lastX, lastY, x, y);
            }
            lastX = x;
            lastY = y;
        }
    }

    private static void renderHeatmap(String fixture,
                                      int plane,
                                      AnalysisArea area,
                                      int[] residuals,
                                      int width,
                                      int height,
                                      Path path) throws IOException {
        if (width <= 0 || height <= 0) {
            return;
        }
        BufferedImage raw = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int maxAbs = 1;
        for (int residual : residuals) {
            maxAbs = Math.max(maxAbs, Math.abs(residual));
        }
        for (int y = 0; y < height; y++) {
            int rowBase = y * width;
            for (int x = 0; x < width; x++) {
                raw.setRGB(x, y, colorForResidual(residuals[rowBase + x], maxAbs).getRGB());
            }
        }

        int scaledW = Math.max(1, Math.min(800, width * Math.max(1, 800 / Math.max(1, width))));
        int scaledH = Math.max(1, Math.min(800, height * Math.max(1, 800 / Math.max(1, height))));
        int titleHeight = 30;
        BufferedImage image = new BufferedImage(scaledW, scaledH + titleHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(raw, 0, titleHeight, scaledW, scaledH, null);
        g.setColor(Color.BLACK);
        g.drawString(fixture + " — plane " + plane + " — " + area.id + " residual-map", 10, 20);
        g.dispose();
        ImageIO.write(image, "PNG", path.toFile());
    }

    private static Color colorForResidual(int residual, int maxAbs) {
        double ratio = Math.min(1.0, Math.abs((double) residual) / maxAbs);
        int white = 255;
        int faded = (int) Math.round(white * (1.0 - ratio));
        if (residual > 0) {
            return new Color(255, faded, faded);
        }
        if (residual < 0) {
            return new Color(faded, faded, 255);
        }
        return Color.WHITE;
    }

    private static void renderHistogramComparison(String fixture,
                                                  int plane,
                                                  AnalysisArea area,
                                                  List<CandidateResult> results,
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
        g.drawString(fixture + " — plane " + plane + " — " + area.id + " predictor residual histogram (reference-backed)", 30, 30);

        int plotWidth = width - leftMargin - rightMargin;
        int plotHeight = height - topMargin - bottomMargin;
        int plotX = leftMargin;
        int plotY = topMargin;
        g.drawRect(plotX, plotY, plotWidth, plotHeight);

        double maxDensity = 0;
        Map<PredictorCandidate, long[]> displayHistograms = new LinkedHashMap<>();
        for (CandidateResult result : results) {
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
        for (CandidateResult result : results) {
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
                                                 List<CandidateResult> results,
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
        g.drawString(fixture + " — " + area.id + " predictor residual stddev by plane (reference-backed)", 30, 30);

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

            List<CandidateResult> planeResults = results.stream()
                    .filter(result -> result.plane() == planeIndex)
                    .sorted(Comparator.comparing(result -> result.candidate().ordinal()))
                    .toList();

            for (int i = 0; i < planeResults.size(); i++) {
                CandidateResult result = planeResults.get(i);
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
                if (bayerX < imgW && index < bayer.length) {
                    out[py * planeW + px] = bayer[index];
                }
            }
        }
        return out;
    }

    private static int[] subtractPlanes(int[] decodedPlane, int[] referencePlane) {
        int[] out = new int[decodedPlane.length];
        for (int i = 0; i < decodedPlane.length; i++) {
            out[i] = decodedPlane[i] - referencePlane[i];
        }
        return out;
    }

    private static int[] extractResiduals(int[] decodedPlane, int[] referencePlane, int planeW, PlaneRect rect) {
        int[] out = new int[rect.width() * rect.height()];
        for (int y = 0; y < rect.height(); y++) {
            int srcBase = (rect.y() + y) * planeW + rect.x();
            int dstBase = y * rect.width();
            for (int x = 0; x < rect.width(); x++) {
                out[dstBase + x] = decodedPlane[srcBase + x] - referencePlane[srcBase + x];
            }
        }
        return out;
    }

    private static PlaneRect toPlaneRect(int[] rawRect, int plane) {
        if (rawRect[2] <= 0 || rawRect[3] <= 0) {
            return PlaneRect.EMPTY;
        }
        int rowOff = (plane >> 1) & 1;
        int colOff = plane & 1;
        int firstX = firstCoordinateWithParity(rawRect[0], rawRect[2], colOff);
        int firstY = firstCoordinateWithParity(rawRect[1], rawRect[3], rowOff);
        if (firstX < 0 || firstY < 0) {
            return PlaneRect.EMPTY;
        }
        int lastX = lastCoordinateWithParity(rawRect[0], rawRect[2], colOff);
        int lastY = lastCoordinateWithParity(rawRect[1], rawRect[3], rowOff);
        return new PlaneRect(firstX / 2, firstY / 2, ((lastX - firstX) / 2) + 1, ((lastY - firstY) / 2) + 1);
    }

    private static int firstCoordinateWithParity(int start, int size, int parity) {
        int end = start + size - 1;
        int candidate = start;
        if ((candidate & 1) != parity) {
            candidate++;
        }
        return candidate <= end ? candidate : -1;
    }

    private static int lastCoordinateWithParity(int start, int size, int parity) {
        int candidate = start + size - 1;
        if ((candidate & 1) != parity) {
            candidate--;
        }
        return candidate;
    }

    private static boolean contains(int[] rect, int x, int y) {
        return rect[2] > 0
                && rect[3] > 0
                && x >= rect[0]
                && x < rect[0] + rect[2]
                && y >= rect[1]
                && y < rect[1] + rect[3];
    }

    private static int[] clampRect(int left, int top, int right, int bottom, int maxW, int maxH) {
        int x0 = Math.max(0, Math.min(left, maxW));
        int y0 = Math.max(0, Math.min(top, maxH));
        int x1 = Math.max(x0, Math.min(right, maxW));
        int y1 = Math.max(y0, Math.min(bottom, maxH));
        return new int[]{x0, y0, x1 - x0, y1 - y0};
    }

    private static int[] clampRect(int left, int top, int right, int bottom, int ignored, int ignored2, int maxW, int maxH) {
        return clampRect(left, top, right, bottom, maxW, maxH);
    }

    private static String sceneTag(String fixtureName) {
        String n = fixtureName.toLowerCase(Locale.ROOT);
        if (n.contains("-dark-")) return "dark";
        if (n.contains("-flat-")) return "flat";
        if (n.contains("-detail-") || n.contains("eosr")) return "detail";
        if (n.contains("-asis-")) return "asis";
        if (n.contains("-tele-")) return "tele";
        if (n.contains("-wide-")) return "wide";
        if (n.contains("sample")) return "sample";
        return "?";
    }

    private static String relativeFixture(Path file) {
        return SAMPLES_DIR.relativize(file).toString().replace('\\', '/');
    }

    private static boolean hasReferenceSidecars(Path fixture) {
        return Files.isRegularFile(referenceTiffPath(fixture))
                && Files.isRegularFile(referencePngPath(fixture));
    }

    private static Path referenceTiffPath(Path fixture) {
        return fixture.resolveSibling(fixture.getFileName().toString().replaceAll("\\.cr3$", ".reference.tiff"));
    }

    private static Path referencePngPath(Path fixture) {
        return fixture.resolveSibling(fixture.getFileName().toString().replaceAll("\\.cr3$", ".reference.png"));
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

    private static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    enum AnalysisArea {
        LEFT_OB("left-ob", "left-ob", true),
        TOP_OB("top-ob", "top-ob", true),
        IMAGE_AREA("image-area", "image-area", false),
        OTHER("other", "other", false);

        final String id;
        final String fileLabel;
        final boolean opticalBlack;

        AnalysisArea(String id, String fileLabel, boolean opticalBlack) {
            this.id = id;
            this.fileLabel = fileLabel;
            this.opticalBlack = opticalBlack;
        }

        boolean isOpticalBlack() {
            return opticalBlack;
        }

        static AnalysisArea fromId(String id) {
            for (AnalysisArea area : values()) {
                if (area.id.equals(id)) {
                    return area;
                }
            }
            return OTHER;
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
            if (residual < -maxResidual || residual > maxResidual) {
                throw new IllegalArgumentException("Residual " + residual
                        + " exceeds configured range ±" + maxResidual);
            }
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
            long[] madHistogram = new long[(maxResidual * 2) + 1];
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

    record DiagnosticStats(ResidualStats residualStats,
                           double[] rowMeans,
                           double[] colMeans,
                           double rowProfileStd,
                           double colProfileStd,
                           double[] autocorr,
                           double bandingScore) {
    }

    record CandidateResult(String fixture,
                           String scene,
                           int plane,
                           AnalysisArea area,
                           PredictorCandidate candidate,
                           ResidualStats stats) {
    }

    record AreaDiagnostic(String fixture,
                          String scene,
                          int plane,
                          AnalysisArea area,
                          DiagnosticStats stats,
                          String heatmapPath,
                          String rowCsvPath,
                          String columnCsvPath,
                          String rowPlotPath,
                          String columnPlotPath,
                          String fftPlotPath,
                          String rowPeaks,
                          String columnPeaks) {
    }

    record ReferenceImage(int width, int height, int[] samples) {
    }

    record FixtureRanking(String fixture,
                          String scene,
                          String bestPredictor,
                          String worstPredictor,
                          double delta,
                          double decoderStdDev) {
    }

    record FixtureSummary(String fixture,
                          String scene,
                          Map<String, String> generatedPlots,
                          FixtureRanking ranking,
                          List<SuspiciousRegion> suspiciousRegions) {
    }

    record SkippedFixture(String fixture, String reason) {
    }

    record Spectrum(double[] amplitudes, List<SpectrumPeak> peaks) {
    }

    record SpectrumPeak(double periodPixels, double amplitude) {
    }

    record SuspiciousRegion(String fixture,
                            String scene,
                            int plane,
                            String area,
                            int planeX,
                            int planeY,
                            int width,
                            int height,
                            double score,
                            int planeWidth,
                            int planeHeight,
                            int[] fullResiduals,
                            String cropPath) {
        SuspiciousRegion withCropPath(String value) {
            return new SuspiciousRegion(fixture, scene, plane, area, planeX, planeY, width, height,
                    score, planeWidth, planeHeight, fullResiduals, value);
        }
    }

    record PlaneRect(int x, int y, int width, int height) {
        static final PlaneRect EMPTY = new PlaneRect(0, 0, 0, 0);
    }

    record FamilyRank(PredictorFamily family, double rank) {
    }
}
