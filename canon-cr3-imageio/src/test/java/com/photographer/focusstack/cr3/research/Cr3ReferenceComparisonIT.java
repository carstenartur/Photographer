package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.bmff.Iad1Box;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.decode.Cr3Decoder;
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
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Reference-backed end-to-end comparison for the current lossless decoder. */
class Cr3ReferenceComparisonIT {

    private static final Path SAMPLES_DIR = Path.of("src", "test", "resources", "samples");
    private static final Path DOCS_REPORT = Path.of("..", "docs", "cr3-results", "reference-comparison.md");
    private static final Path DOCS_IMAGE_DIR = Path.of(
            "..", "docs", "cr3-results", "images", "reference-comparison");
    private static final Path TARGET_DIR = Path.of("target", "cr3-reference-comparison");
    private static final Path TARGET_IMAGE_DIR = TARGET_DIR.resolve("images");

    @Test
    void compareLosslessFixturesAgainstReference() throws Exception {
        List<Path> fixtures = discoverFixtures();
        assumeTrue(!fixtures.isEmpty(), "No reference-backed lossless CR3 fixtures found");

        Files.createDirectories(DOCS_IMAGE_DIR);
        Files.createDirectories(TARGET_IMAGE_DIR);
        Files.createDirectories(TARGET_DIR);

        List<FixtureResult> results = new ArrayList<>();
        for (Path fixture : fixtures) {
            results.add(analyseFixture(fixture));
        }

        String report = buildReport(results);
        Files.createDirectories(DOCS_REPORT.getParent());
        Files.writeString(DOCS_REPORT, report);
        Files.writeString(TARGET_DIR.resolve("reference-comparison.md"), report);

        assertThat(results).isNotEmpty();
        assertThat(results).allSatisfy(result -> {
            assertThat(result.globalMetrics().mae()).isFinite();
            assertThat(result.globalMetrics().rmse()).isFinite();
            assertThat(result.globalMetrics().pearson()).isFinite();
        });
    }

    private static List<Path> discoverFixtures() throws IOException {
        if (!Files.isDirectory(SAMPLES_DIR)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(SAMPLES_DIR)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".cr3"))
                    .filter(ReferencePaths::hasBothSidecars)
                    .filter(Cr3ReferenceComparisonIT::isLosslessFixture)
                    .sorted(Comparator.comparing(path -> SAMPLES_DIR.relativize(path).toString()))
                    .toList();
        }
    }

    private static boolean isLosslessFixture(Path fixture) {
        try (ImageInputStream input = ImageIO.createImageInputStream(fixture.toFile())) {
            if (input == null) {
                return false;
            }
            Cr3Container container = Cr3Parser.parse(input);
            Cr3Track rawTrack = findRawTrack(container);
            return rawTrack != null && rawTrack.cmp1() != null && rawTrack.cmp1().isLossless();
        } catch (Exception exception) {
            return false;
        }
    }

    private static FixtureResult analyseFixture(Path fixture) throws Exception {
        ReferenceImage reference = ReferenceImageLoader.load(ReferencePaths.rawLinearTiff(fixture));
        DecodedFixture decoded = decodeFixture(fixture);

        assertThat(decoded.raw().width()).as("width for %s", fixture.getFileName())
                .isEqualTo(reference.width());
        assertThat(decoded.raw().height()).as("height for %s", fixture.getFileName())
                .isEqualTo(reference.height());

        int[] referenceSamples = reference.samples();
        int[] actualSamples = decoded.raw().data();
        ReferenceMetrics.MetricTriple globalMetrics =
                ReferenceMetrics.compare(referenceSamples, actualSamples);
        List<PlaneResult> planeMetrics = computePlaneMetrics(referenceSamples, decoded.raw(), decoded.cfaLayout());
        ObMetrics obMetrics = computeOpticalBlackMetrics(
                referenceSamples,
                actualSamples,
                decoded.raw().width(),
                decoded.raw().height(),
                decoded.iad1());
        double boundaryError = decoded.cmp1() == null
                ? Double.NaN
                : ReferenceMetrics.tileBoundaryMae(
                        referenceSamples,
                        actualSamples,
                        decoded.raw().width(),
                        decoded.raw().height(),
                        decoded.cmp1().tileWidth(),
                        decoded.cmp1().tileHeight());

        BufferedImage preview = decodePreview(fixture);
        VisibleRect visible = resolveVisibleRect(decoded.iad1(), decoded.raw().width(), decoded.raw().height());
        VisualScale scale = computeScale(referenceSamples, decoded.raw().width(), visible);
        BufferedImage referenceVisual = buildRawVisual(
                referenceSamples,
                decoded.raw().width(),
                visible,
                scale,
                preview.getWidth(),
                preview.getHeight());
        BufferedImage decoderVisual = buildRawVisual(
                actualSamples,
                decoded.raw().width(),
                visible,
                scale,
                preview.getWidth(),
                preview.getHeight());
        BufferedImage diffVisual = buildDifferenceVisual(
                referenceSamples,
                actualSamples,
                decoded.raw().width(),
                visible,
                preview.getWidth(),
                preview.getHeight());

        Path previewOut = writeImage(fixture, "preview", preview, DOCS_IMAGE_DIR, TARGET_IMAGE_DIR);
        Path referenceOut = writeImage(fixture, "reference", referenceVisual, DOCS_IMAGE_DIR, TARGET_IMAGE_DIR);
        Path decoderOut = writeImage(fixture, "decoder", decoderVisual, DOCS_IMAGE_DIR, TARGET_IMAGE_DIR);
        Path diffOut = writeImage(fixture, "difference", diffVisual, DOCS_IMAGE_DIR, TARGET_IMAGE_DIR);

        return new FixtureResult(
                SAMPLES_DIR.relativize(fixture).toString().replace('\\', '/'),
                decoded.raw().width(),
                decoded.raw().height(),
                globalMetrics,
                planeMetrics,
                obMetrics,
                boundaryError,
                DOCS_REPORT.getParent().relativize(previewOut).toString().replace('\\', '/'),
                DOCS_REPORT.getParent().relativize(referenceOut).toString().replace('\\', '/'),
                DOCS_REPORT.getParent().relativize(decoderOut).toString().replace('\\', '/'),
                DOCS_REPORT.getParent().relativize(diffOut).toString().replace('\\', '/'));
    }

    private static DecodedFixture decodeFixture(Path fixture) throws IOException, Cr3Exception {
        try (ImageInputStream input = ImageIO.createImageInputStream(fixture.toFile())) {
            if (input == null) {
                throw new IOException("Could not open fixture " + fixture);
            }
            Cr3Container container = Cr3Parser.parse(input);
            Cr3Track rawTrack = findRawTrack(container);
            if (rawTrack == null) {
                throw new IOException("No CRX raw track in " + fixture);
            }
            Cmp1Box cmp1 = rawTrack.cmp1();
            if (cmp1 == null) {
                throw new IOException("No CMP1 metadata in " + fixture);
            }

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
                    cmp1);
            return new DecodedFixture(raw, cmp1, rawTrack.iad1(), cmp1.cfaLayout());
        }
    }

    private static Cr3Track findRawTrack(Cr3Container container) {
        return container.tracks().stream()
                .filter(track -> track.type() == Cr3Track.TrackType.CRX_BIG
                        || track.type() == Cr3Track.TrackType.CRX_RAW)
                .findFirst()
                .orElse(null);
    }

    private static BufferedImage decodePreview(Path fixture) throws IOException, Cr3Exception {
        try (Cr3Decoder decoder = new Cr3Decoder()) {
            decoder.setInput(fixture);
            BufferedImage preview = decoder.decode(Cr3DecodeOptions.defaults());
            if (preview == null) {
                throw new IOException("No preview for " + fixture);
            }
            return preview;
        }
    }

    private static List<PlaneResult> computePlaneMetrics(int[] reference, Cr3RawImage decoded, int cfaLayout) {
        List<PlaneResult> results = new ArrayList<>(4);
        int width = decoded.width();
        int height = decoded.height();
        int[] actual = decoded.data();

        for (PlaneSpec spec : planeSpecs(cfaLayout)) {
            int planeWidth = (width - spec.colOffset() + 1) / 2;
            int planeHeight = (height - spec.rowOffset() + 1) / 2;
            int[] referencePlane = new int[planeWidth * planeHeight];
            int[] actualPlane = new int[referencePlane.length];
            int destination = 0;
            for (int y = spec.rowOffset(); y < height; y += 2) {
                int rowOffset = y * width;
                for (int x = spec.colOffset(); x < width; x += 2) {
                    int source = rowOffset + x;
                    referencePlane[destination] = reference[source];
                    actualPlane[destination] = actual[source];
                    destination++;
                }
            }
            results.add(new PlaneResult(spec.label(), ReferenceMetrics.compare(referencePlane, actualPlane)));
        }
        return results;
    }

    private static ObMetrics computeOpticalBlackMetrics(
            int[] reference,
            int[] actual,
            int width,
            int height,
            Iad1Box iad1) {
        if (iad1 == null) {
            return new ObMetrics(Double.NaN, Double.NaN);
        }

        List<ReferenceMetrics.Region> regions = new ArrayList<>(2);
        addNonEmpty(regions, toRegion(
                iad1.leftObLeft(), iad1.leftObTop(), iad1.leftObRight(), iad1.leftObBottom(), width, height));
        addNonEmpty(regions, toRegion(
                iad1.topObLeft(), iad1.topObTop(), iad1.topObRight(), iad1.topObBottom(), width, height));
        if (regions.isEmpty()) {
            return new ObMetrics(Double.NaN, Double.NaN);
        }

        ReferenceMetrics.RegionMetrics metrics = ReferenceMetrics.compareRegions(
                reference,
                actual,
                width,
                height,
                regions,
                0,
                Integer.MIN_VALUE,
                Integer.MAX_VALUE);
        return new ObMetrics(metrics.mae(), metrics.stddev());
    }

    private static void addNonEmpty(List<ReferenceMetrics.Region> regions, ReferenceMetrics.Region region) {
        if (!region.isEmpty()) {
            regions.add(region);
        }
    }

    private static ReferenceMetrics.Region toRegion(
            int left, int top, int right, int bottom, int width, int height) {
        int x0 = Math.max(0, Math.min(width, left));
        int y0 = Math.max(0, Math.min(height, top));
        int x1 = Math.max(x0, Math.min(width, right));
        int y1 = Math.max(y0, Math.min(height, bottom));
        return new ReferenceMetrics.Region(x0, y0, x1 - x0, y1 - y0);
    }

    private static VisibleRect resolveVisibleRect(Iad1Box iad1, int width, int height) {
        if (iad1 == null) {
            return new VisibleRect(0, 0, width, height);
        }
        ReferenceMetrics.Region region = toRegion(
                iad1.activeLeft(), iad1.activeTop(), iad1.activeRight(), iad1.activeBottom(), width, height);
        return region.isEmpty()
                ? new VisibleRect(0, 0, width, height)
                : new VisibleRect(region.x(), region.y(), region.width(), region.height());
    }

    private static VisualScale computeScale(int[] samples, int width, VisibleRect rect) {
        int total = rect.width() * rect.height();
        int sampleSize = Math.min(total, 50_000);
        int[] values = new int[sampleSize];
        int step = Math.max(1, total / sampleSize);
        int seen = 0;
        int stored = 0;
        for (int y = rect.y(); y < rect.y() + rect.height(); y++) {
            int rowOffset = y * width;
            for (int x = rect.x(); x < rect.x() + rect.width() && stored < sampleSize; x++) {
                if ((seen++ % step) == 0) {
                    values[stored++] = samples[rowOffset + x];
                }
            }
        }
        values = Arrays.copyOf(values, stored);
        Arrays.sort(values);
        int low = percentile(values, 0.5);
        int high = percentile(values, 99.5);
        return new VisualScale(low, Math.max(low + 1, high));
    }

    private static int percentile(int[] values, double percentile) {
        if (values.length == 0) {
            return 0;
        }
        int index = (int) Math.round((percentile / 100.0) * (values.length - 1));
        return values[Math.max(0, Math.min(values.length - 1, index))];
    }

    private static BufferedImage buildRawVisual(
            int[] samples,
            int width,
            VisibleRect rect,
            VisualScale scale,
            int outputWidth,
            int outputHeight) {
        BufferedImage raw = new BufferedImage(rect.width(), rect.height(), BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < rect.height(); y++) {
            int rowOffset = (rect.y() + y) * width;
            for (int x = 0; x < rect.width(); x++) {
                int normalized = normalize(samples[rowOffset + rect.x() + x], scale);
                int gray = (normalized << 16) | (normalized << 8) | normalized;
                raw.setRGB(x, y, 0xFF000000 | gray);
            }
        }
        return scaleImage(raw, outputWidth, outputHeight);
    }

    private static BufferedImage buildDifferenceVisual(
            int[] reference,
            int[] actual,
            int width,
            VisibleRect rect,
            int outputWidth,
            int outputHeight) {
        BufferedImage difference = new BufferedImage(rect.width(), rect.height(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < rect.height(); y++) {
            int rowOffset = (rect.y() + y) * width;
            for (int x = 0; x < rect.width(); x++) {
                int index = rowOffset + rect.x() + x;
                int delta = Math.abs(actual[index] - reference[index]);
                int intensity = Math.min(255, delta / 8);
                int rgb = (intensity << 16) | (Math.min(255, intensity / 2) << 8);
                difference.setRGB(x, y, 0xFF000000 | rgb);
            }
        }
        return scaleImage(difference, outputWidth, outputHeight);
    }

    private static BufferedImage scaleImage(BufferedImage source, int width, int height) {
        if (source.getWidth() == width && source.getHeight() == height) {
            return source;
        }
        BufferedImage scaled = new BufferedImage(
                width,
                height,
                source.getType() == BufferedImage.TYPE_CUSTOM ? BufferedImage.TYPE_INT_RGB : source.getType());
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
        if (value <= scale.low()) {
            return 0;
        }
        if (value >= scale.high()) {
            return 255;
        }
        return (int) Math.round((value - scale.low()) * 255.0 / (scale.high() - scale.low()));
    }

    private static Path writeImage(
            Path fixture,
            String suffix,
            BufferedImage image,
            Path docsDirectory,
            Path targetDirectory) throws IOException {
        String stem = fixture.getFileName().toString().replaceAll("(?i)\\.cr3$", "");
        Path docsPath = docsDirectory.resolve(stem + "-" + suffix + ".png");
        Path targetPath = targetDirectory.resolve(stem + "-" + suffix + ".png");
        ImageIO.write(image, "PNG", docsPath.toFile());
        ImageIO.write(image, "PNG", targetPath.toFile());
        return docsPath;
    }

    private static String buildReport(List<FixtureResult> results) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# CR3 Reference Comparison\n\n");
        markdown.append("- Generated: ").append(Instant.now()).append('\n');
        markdown.append("- Fixtures: ").append(results.size()).append(" lossless CR3 fixtures\n");
        markdown.append("- Exclusions: C-RAW / wavelet CR3 fixtures are intentionally skipped\n");
        markdown.append("- Reference manifest: `canon-cr3-imageio/src/test/resources/samples/reference-manifest.json`\n");
        markdown.append("- Reference source: LibRaw 0.22.1 via rawpy 0.27.0 full-sensor mosaic TIFF sidecars\n\n");

        markdown.append("## Summary\n\n");
        markdown.append("| Fixture | Size | Global MAE | Global RMSE | Pearson | OB MAE | OB StdDev | Boundary Error |\n");
        markdown.append("|---|---:|---:|---:|---:|---:|---:|---:|\n");
        for (FixtureResult result : results) {
            markdown.append("| `").append(result.fixture()).append("` | ")
                    .append(result.width()).append('×').append(result.height()).append(" | ")
                    .append(fmt(result.globalMetrics().mae())).append(" | ")
                    .append(fmt(result.globalMetrics().rmse())).append(" | ")
                    .append(fmt(result.globalMetrics().pearson())).append(" | ")
                    .append(fmt(result.obMetrics().mae())).append(" | ")
                    .append(fmt(result.obMetrics().stddev())).append(" | ")
                    .append(fmt(result.boundaryError())).append(" |\n");
        }

        markdown.append("\n## Per fixture\n");
        for (FixtureResult result : results) {
            markdown.append("\n### `").append(result.fixture()).append("`\n\n");
            markdown.append("| Scope | MAE | RMSE | Pearson |\n");
            markdown.append("|---|---:|---:|---:|\n");
            markdown.append("| Global | ").append(fmt(result.globalMetrics().mae())).append(" | ")
                    .append(fmt(result.globalMetrics().rmse())).append(" | ")
                    .append(fmt(result.globalMetrics().pearson())).append(" |\n");
            for (PlaneResult plane : result.planes()) {
                markdown.append("| Plane ").append(plane.label()).append(" | ")
                        .append(fmt(plane.metrics().mae())).append(" | ")
                        .append(fmt(plane.metrics().rmse())).append(" | ")
                        .append(fmt(plane.metrics().pearson())).append(" |\n");
            }
            markdown.append("| Optical Black | ").append(fmt(result.obMetrics().mae())).append(" | ")
                    .append(fmt(result.obMetrics().stddev())).append(" | — |\n");
            markdown.append("| Tile Boundaries | ").append(fmt(result.boundaryError())).append(" | — | — |\n\n");
            markdown.append("| Preview | Reference | Decoder | Difference |\n");
            markdown.append("|---|---|---|---|\n");
            markdown.append("| ![](").append(result.previewImage()).append(") ")
                    .append("| ![](").append(result.referenceImage()).append(") ")
                    .append("| ![](").append(result.decoderImage()).append(") ")
                    .append("| ![](").append(result.diffImage()).append(") |\n");
        }
        return markdown.toString();
    }

    private static String fmt(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.4f", value) : "n/a";
    }

    private static List<PlaneSpec> planeSpecs(int cfaLayout) {
        return switch (cfaLayout) {
            case 1 -> List.of(
                    new PlaneSpec("Gr", 0, 0),
                    new PlaneSpec("R", 0, 1),
                    new PlaneSpec("B", 1, 0),
                    new PlaneSpec("Gb", 1, 1));
            case 2 -> List.of(
                    new PlaneSpec("Gb", 0, 0),
                    new PlaneSpec("B", 0, 1),
                    new PlaneSpec("R", 1, 0),
                    new PlaneSpec("Gr", 1, 1));
            case 3 -> List.of(
                    new PlaneSpec("B", 0, 0),
                    new PlaneSpec("Gb", 0, 1),
                    new PlaneSpec("Gr", 1, 0),
                    new PlaneSpec("R", 1, 1));
            default -> List.of(
                    new PlaneSpec("R", 0, 0),
                    new PlaneSpec("Gr", 0, 1),
                    new PlaneSpec("Gb", 1, 0),
                    new PlaneSpec("B", 1, 1));
        };
    }

    private record DecodedFixture(Cr3RawImage raw, Cmp1Box cmp1, Iad1Box iad1, int cfaLayout) {
    }

    private record PlaneResult(String label, ReferenceMetrics.MetricTriple metrics) {
    }

    private record ObMetrics(double mae, double stddev) {
    }

    private record FixtureResult(
            String fixture,
            int width,
            int height,
            ReferenceMetrics.MetricTriple globalMetrics,
            List<PlaneResult> planes,
            ObMetrics obMetrics,
            double boundaryError,
            String previewImage,
            String referenceImage,
            String decoderImage,
            String diffImage) {
    }

    private record VisibleRect(int x, int y, int width, int height) {
    }

    private record VisualScale(int low, int high) {
    }

    private record PlaneSpec(String label, int rowOffset, int colOffset) {
    }
}
