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
import com.photographer.focusstack.cr3.reference.ReferenceDifferenceImage;
import com.photographer.focusstack.cr3.reference.ReferenceImage;
import com.photographer.focusstack.cr3.reference.ReferenceImageLoader;
import com.photographer.focusstack.cr3.reference.ReferenceMetrics;
import com.photographer.focusstack.cr3.reference.SpatialReferenceMetrics;
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

class Cr3ReferenceComparisonIT {

    private static final Path SAMPLES_DIR = Path.of("src", "test", "resources", "samples");
    private static final Path DOCS_REPORT = Path.of("..", "docs", "cr3-results", "reference-comparison.md");
    private static final Path DOCS_IMAGE_DIR = Path.of("..", "docs", "cr3-results", "images", "reference-comparison");
    private static final Path TARGET_DIR = Path.of("target", "cr3-reference-comparison");
    private static final Path TARGET_IMAGE_DIR = TARGET_DIR.resolve("images");

    @Test
    void compareLosslessFixturesAgainstReference() throws Exception {
        List<Path> fixtures = discoverFixtures();
        assumeTrue(!fixtures.isEmpty(), "No reference-backed lossless CR3 fixtures found");

        Files.createDirectories(DOCS_IMAGE_DIR);
        Files.createDirectories(TARGET_IMAGE_DIR);

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
            assertThat(result.globalMetrics.mae()).isFinite();
            assertThat(result.globalMetrics.rmse()).isFinite();
            assertThat(result.globalMetrics.pearson()).isFinite();
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
                    .filter(Cr3ReferenceComparisonIT::hasReferenceSidecars)
                    .filter(Cr3ReferenceComparisonIT::isLosslessFixture)
                    .sorted(Comparator.comparing(path -> SAMPLES_DIR.relativize(path).toString()))
                    .toList();
        }
    }

    private static boolean hasReferenceSidecars(Path fixture) {
        return Files.isRegularFile(referenceTiffPath(fixture))
                && Files.isRegularFile(referencePngPath(fixture));
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

    private static FixtureResult analyseFixture(Path fixture) throws Exception {
        ReferenceImage reference = ReferenceImageLoader.load(referenceTiffPath(fixture));
        DecodedFixture decoded = decodeFixture(fixture);

        assertThat(decoded.raw.width()).as("width for %s", fixture.getFileName()).isEqualTo(reference.width());
        assertThat(decoded.raw.height()).as("height for %s", fixture.getFileName()).isEqualTo(reference.height());

        ReferenceMetrics.Metrics global = ReferenceMetrics.compare(reference.samples(), decoded.raw.data());
        List<PlaneResult> planes = computePlaneMetrics(reference.samples(), decoded.raw, decoded.cfaLayout);
        ObMetrics obMetrics = computeOpticalBlackMetrics(reference.samples(), decoded.raw.data(),
                decoded.raw.width(), decoded.raw.height(), decoded.iad1);
        double boundaryError = computeBoundaryError(reference.samples(), decoded.raw.data(),
                decoded.raw.width(), decoded.raw.height(), decoded.cmp1);

        BufferedImage preview = decodePreview(fixture);
        VisibleRect visible = resolveVisibleRect(decoded.iad1, decoded.raw.width(), decoded.raw.height());
        VisualScale scale = computeScale(reference.samples(), decoded.raw.width(), visible);
        BufferedImage referenceVisual = buildRawVisual(reference.samples(), decoded.raw.width(), visible, scale,
                preview.getWidth(), preview.getHeight());
        BufferedImage decoderVisual = buildRawVisual(decoded.raw.data(), decoded.raw.width(), visible, scale,
                preview.getWidth(), preview.getHeight());
        BufferedImage diffVisual = buildDifferenceVisual(reference.samples(), decoded.raw.data(),
                decoded.raw.width(), decoded.raw.height(), visible, preview.getWidth(), preview.getHeight());

        Path previewOut = writeImage(fixture, "preview", preview, DOCS_IMAGE_DIR, TARGET_IMAGE_DIR);
        Path referenceOut = writeImage(fixture, "reference", referenceVisual, DOCS_IMAGE_DIR, TARGET_IMAGE_DIR);
        Path decoderOut = writeImage(fixture, "decoder", decoderVisual, DOCS_IMAGE_DIR, TARGET_IMAGE_DIR);
        Path diffOut = writeImage(fixture, "difference", diffVisual, DOCS_IMAGE_DIR, TARGET_IMAGE_DIR);

        return new FixtureResult(
                SAMPLES_DIR.relativize(fixture).toString().replace('\\', '/'),
                decoded.raw.width(),
                decoded.raw.height(),
                global,
                planes,
                obMetrics,
                boundaryError,
                DOCS_REPORT.getParent().relativize(previewOut).toString().replace('\\', '/'),
                DOCS_REPORT.getParent().relativize(referenceOut).toString().replace('\\', '/'),
                DOCS_REPORT.getParent().relativize(decoderOut).toString().replace('\\', '/'),
                DOCS_REPORT.getParent().relativize(diffOut).toString().replace('\\', '/')
        );
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
            return new DecodedFixture(raw, cmp1, rawTrack.iad1(), cmp1.cfaLayout());
        }
    }

    private static BufferedImage decodePreview(Path fixture) throws IOException, Cr3Exception {
        try (Cr3Decoder decoder = new Cr3Decoder()) {
            decoder.setInput(fixture);
            BufferedImage preview = decoder.decode(Cr3DecodeOptions.defaults());
            assertThat(preview).as("preview for %s", fixture.getFileName()).isNotNull();
            return preview;
        }
    }

    private static List<PlaneResult> computePlaneMetrics(int[] reference, Cr3RawImage decoded, int cfaLayout) {
        List<PlaneSpec> specs = planeSpecs(cfaLayout);
        List<PlaneResult> results = new ArrayList<>(specs.size());
        int width = decoded.width();
        int height = decoded.height();
        int[] actual = decoded.data();
        for (PlaneSpec spec : specs) {
            double absSum = 0;
            double sqSum = 0;
            long count = 0;
            for (int y = spec.rowOffset; y < height; y += 2) {
                int rowBase = y * width;
                for (int x = spec.colOffset; x < width; x += 2) {
                    double diff = actual[rowBase + x] - reference[rowBase + x];
                    absSum += Math.abs(diff);
                    sqSum += diff * diff;
                    count++;
                }
            }
            results.add(new PlaneResult(spec.label,
                    new ReferenceMetrics.Metrics(absSum / count, Math.sqrt(sqSum / count), Double.NaN)));
        }
        return results;
    }

    private static ObMetrics computeOpticalBlackMetrics(int[] reference, int[] actual, int width, int height,
                                                         Iad1Box iad1) {
        if (iad1 == null) {
            return new ObMetrics(Double.NaN, Double.NaN);
        }
        List<VisibleRect> rects = new ArrayList<>(2);
        VisibleRect left = toRect(iad1.leftObLeft(), iad1.leftObTop(), iad1.leftObRight(), iad1.leftObBottom(),
                width, height);
        VisibleRect top = toRect(iad1.topObLeft(), iad1.topObTop(), iad1.topObRight(), iad1.topObBottom(),
                width, height);
        if (left.width > 0 && left.height > 0) {
            rects.add(left);
        }
        if (top.width > 0 && top.height > 0) {
            rects.add(top);
        }
        if (rects.isEmpty()) {
            return new ObMetrics(Double.NaN, Double.NaN);
        }

        double absSum = 0;
        double diffSum = 0;
        double diffSqSum = 0;
        long count = 0;
        for (VisibleRect rect : rects) {
            for (int y = rect.y; y < rect.y + rect.height; y++) {
                int rowBase = y * width;
                for (int x = rect.x; x < rect.x + rect.width; x++) {
                    int diff = actual[rowBase + x] - reference[rowBase + x];
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

    private static double computeBoundaryError(int[] reference, int[] actual, int width, int height, Cmp1Box cmp1) {
        if (cmp1 == null || cmp1.tileWidth() <= 0 || cmp1.tileHeight() <= 0) {
            return Double.NaN;
        }
        double absSum = 0;
        long count = 0;
        for (int seamX = cmp1.tileWidth(); seamX < width; seamX += cmp1.tileWidth()) {
            int left = seamX - 1;
            int right = seamX;
            for (int y = 0; y < height; y++) {
                int rowBase = y * width;
                absSum += Math.abs(actual[rowBase + left] - reference[rowBase + left]);
                absSum += Math.abs(actual[rowBase + right] - reference[rowBase + right]);
                count += 2;
            }
        }
        for (int seamY = cmp1.tileHeight(); seamY < height; seamY += cmp1.tileHeight()) {
            int top = seamY - 1;
            int bottom = seamY;
            int topBase = top * width;
            int bottomBase = bottom * width;
            for (int x = 0; x < width; x++) {
                absSum += Math.abs(actual[topBase + x] - reference[topBase + x]);
                absSum += Math.abs(actual[bottomBase + x] - reference[bottomBase + x]);
                count += 2;
            }
        }
        return count == 0 ? Double.NaN : absSum / count;
    }

    private static VisibleRect resolveVisibleRect(Iad1Box iad1, int width, int height) {
        if (iad1 == null) {
            return new VisibleRect(0, 0, width, height);
        }
        VisibleRect rect = toRect(iad1.activeLeft(), iad1.activeTop(), iad1.activeRight(),
                iad1.activeBottom(), width, height);
        return rect.width > 0 && rect.height > 0 ? rect : new VisibleRect(0, 0, width, height);
    }

    private static VisibleRect toRect(int left, int top, int right, int bottom, int width, int height) {
        int x0 = Math.max(0, Math.min(width, left));
        int y0 = Math.max(0, Math.min(height, top));
        int x1 = Math.max(x0, Math.min(width, right));
        int y1 = Math.max(y0, Math.min(height, bottom));
        return new VisibleRect(x0, y0, x1 - x0, y1 - y0);
    }

    private static VisualScale computeScale(int[] samples, int width, VisibleRect rect) {
        int total = rect.width * rect.height;
        int sampleSize = Math.min(total, 50_000);
        int[] values = new int[sampleSize];
        int step = Math.max(1, total / sampleSize);
        int seen = 0;
        int stored = 0;
        for (int y = rect.y; y < rect.y + rect.height; y++) {
            int rowBase = y * width;
            for (int x = rect.x; x < rect.x + rect.width; x++) {
                if (stored >= sampleSize) {
                    break;
                }
                if ((seen++ % step) == 0) {
                    values[stored++] = samples[rowBase + x];
                }
            }
        }
        values = Arrays.copyOf(values, stored);
        Arrays.sort(values);
        int lo = percentile(values, 0.5);
        int hi = percentile(values, 99.5);
        return new VisualScale(lo, Math.max(lo + 1, hi));
    }

    private static int percentile(int[] values, double percentile) {
        if (values.length == 0) {
            return 0;
        }
        int index = (int) Math.round((percentile / 100.0) * (values.length - 1));
        return values[Math.max(0, Math.min(values.length - 1, index))];
    }

    private static BufferedImage buildRawVisual(int[] samples, int width, VisibleRect rect, VisualScale scale,
                                                 int outWidth, int outHeight) {
        BufferedImage raw = new BufferedImage(rect.width, rect.height, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < rect.height; y++) {
            int rowBase = (rect.y + y) * width;
            for (int x = 0; x < rect.width; x++) {
                int value = samples[rowBase + rect.x + x];
                int normalized = normalize(value, scale);
                int gray = (normalized << 16) | (normalized << 8) | normalized;
                raw.setRGB(x, y, 0xFF000000 | gray);
            }
        }
        return scaleImage(raw, outWidth, outHeight);
    }

    private static BufferedImage buildDifferenceVisual(int[] reference, int[] actual,
                                                        int width, int height, VisibleRect rect,
                                                        int outWidth, int outHeight) {
        BufferedImage diff = ReferenceDifferenceImage.absoluteHeatmap(
                reference,
                actual,
                width,
                height,
                new SpatialReferenceMetrics.Rect(rect.x, rect.y, rect.width, rect.height),
                0,
                ReferenceDifferenceImage.DEFAULT_DISPLAY_DIVISOR);
        return ReferenceDifferenceImage.scale(diff, outWidth, outHeight);
    }

    private static BufferedImage scaleImage(BufferedImage source, int width, int height) {
        if (source.getWidth() == width && source.getHeight() == height) {
            return source;
        }
        BufferedImage scaled = new BufferedImage(width, height, source.getType() == 0
                ? BufferedImage.TYPE_INT_RGB
                : source.getType());
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
        if (value <= scale.low) {
            return 0;
        }
        if (value >= scale.high) {
            return 255;
        }
        return (int) Math.round((value - scale.low) * 255.0 / (scale.high - scale.low));
    }

    private static Path writeImage(Path fixture, String suffix, BufferedImage image,
                                   Path docsDir, Path targetDir) throws IOException {
        String stem = fixture.getFileName().toString().replaceAll("\\.cr3$", "");
        Path docsPath = docsDir.resolve(stem + "-" + suffix + ".png");
        Path targetPath = targetDir.resolve(stem + "-" + suffix + ".png");
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
            markdown.append("| `").append(result.fixture).append("` | ")
                    .append(result.width).append("×").append(result.height).append(" | ")
                    .append(fmt(result.globalMetrics.mae())).append(" | ")
                    .append(fmt(result.globalMetrics.rmse())).append(" | ")
                    .append(fmt(result.globalMetrics.pearson())).append(" | ")
                    .append(fmt(result.obMetrics.mae())).append(" | ")
                    .append(fmt(result.obMetrics.stddev())).append(" | ")
                    .append(fmt(result.boundaryError)).append(" |\n");
        }

        markdown.append("\n## Per fixture\n");
        for (FixtureResult result : results) {
            markdown.append("\n### `").append(result.fixture).append("`\n\n");
            markdown.append("| Scope | MAE | RMSE | Pearson |\n");
            markdown.append("|---|---:|---:|---:|\n");
            markdown.append("| Global | ").append(fmt(result.globalMetrics.mae())).append(" | ")
                    .append(fmt(result.globalMetrics.rmse())).append(" | ")
                    .append(fmt(result.globalMetrics.pearson())).append(" |\n");
            for (PlaneResult plane : result.planes) {
                markdown.append("| Plane ").append(plane.label).append(" | ")
                        .append(fmt(plane.metrics.mae())).append(" | ")
                        .append(fmt(plane.metrics.rmse())).append(" | ")
                        .append("— |\n");
            }
            markdown.append("| Optical Black | ").append(fmt(result.obMetrics.mae())).append(" | ")
                    .append(fmt(result.obMetrics.stddev())).append(" | ")
                    .append("— |\n");
            markdown.append("| Tile Boundaries | ").append(fmt(result.boundaryError)).append(" | — | — |\n\n");
            markdown.append("| Preview | Reference | Decoder | Difference |\n");
            markdown.append("|---|---|---|---|\n");
            markdown.append("| ![](").append(result.previewImage).append(") ")
                    .append("| ![](").append(result.referenceImage).append(") ")
                    .append("| ![](").append(result.decoderImage).append(") ")
                    .append("| ![](").append(result.diffImage).append(") |\n");
        }
        return markdown.toString();
    }

    private static String fmt(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.4f", value) : "n/a";
    }

    private static Path referenceTiffPath(Path fixture) {
        return fixture.resolveSibling(fixture.getFileName().toString().replaceAll("\\.cr3$", ".reference.tiff"));
    }

    private static Path referencePngPath(Path fixture) {
        return fixture.resolveSibling(fixture.getFileName().toString().replaceAll("\\.cr3$", ".reference.png"));
    }

    private static List<PlaneSpec> planeSpecs(int cfaLayout) {
        return switch (cfaLayout) {
            case 1 -> List.of(
                    new PlaneSpec("Gr", 0, 0),
                    new PlaneSpec("R", 0, 1),
                    new PlaneSpec("B", 1, 0),
                    new PlaneSpec("Gb", 1, 1)
            );
            case 2 -> List.of(
                    new PlaneSpec("Gb", 0, 0),
                    new PlaneSpec("B", 0, 1),
                    new PlaneSpec("R", 1, 0),
                    new PlaneSpec("Gr", 1, 1)
            );
            case 3 -> List.of(
                    new PlaneSpec("B", 0, 0),
                    new PlaneSpec("Gb", 0, 1),
                    new PlaneSpec("Gr", 1, 0),
                    new PlaneSpec("R", 1, 1)
            );
            default -> List.of(
                    new PlaneSpec("R", 0, 0),
                    new PlaneSpec("Gr", 0, 1),
                    new PlaneSpec("Gb", 1, 0),
                    new PlaneSpec("B", 1, 1)
            );
        };
    }

    private record DecodedFixture(Cr3RawImage raw, Cmp1Box cmp1, Iad1Box iad1, int cfaLayout) { }

    private record PlaneResult(String label, ReferenceMetrics.Metrics metrics) { }

    private record ObMetrics(double mae, double stddev) { }

    private record FixtureResult(
            String fixture,
            int width,
            int height,
            ReferenceMetrics.Metrics globalMetrics,
            List<PlaneResult> planes,
            ObMetrics obMetrics,
            double boundaryError,
            String previewImage,
            String referenceImage,
            String decoderImage,
            String diffImage
    ) { }

    private record VisibleRect(int x, int y, int width, int height) { }

    private record VisualScale(int low, int high) { }

    private record PlaneSpec(String label, int rowOffset, int colOffset) { }
}
