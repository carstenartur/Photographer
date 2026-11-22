package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.bmff.PrvwBox;
import com.photographer.focusstack.cr3.bmff.ThmbBox;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.decode.Cr3Decoder;
import com.photographer.focusstack.cr3.metadata.Cr3Metadata;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Parameterized test that discovers <em>all</em> CR3 files in
 * {@code src/test/resources/samples/} and verifies the complete parse chain
 * for each one: container structure, EXIF metadata, preview/thumbnail decoding,
 * and full RAW decode.
 *
 * <p>This test catches regressions where a parser change works for one CR3
 * variant but breaks for another (e.g. different zoom / ISO / WB settings
 * produce different EXIF tag layouts that the parser must handle).</p>
 *
 * <p>The sample files are produced by {@link Cr3SampleCaptureIT} (default
 * {@code sample.cr3}) and {@link Cr3MultiVariantCaptureIT} (zoom, ISO, WB
 * variants). If no sample files are present, all dynamic tests are skipped.</p>
 */
class Cr3VariantVerificationTest {

    private static final Logger log = LoggerFactory.getLogger(Cr3VariantVerificationTest.class);

    /**
     * Discovers all {@code *.cr3} files on the classpath under {@code /samples/}
     * and returns them as test candidates.
     */
    private static List<File> discoverCr3Samples() {
        List<File> samples = new ArrayList<>();

        // Try classpath first (works during mvn test / IDE runs)
        URL samplesUrl = Cr3VariantVerificationTest.class.getResource("/samples");
        if (samplesUrl != null) {
            Path samplesDir = Paths.get(samplesUrl.getPath().replaceFirst("^/([A-Z]:)", "$1"));
            if (Files.isDirectory(samplesDir)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(samplesDir, "*.cr3")) {
                    for (Path p : stream) {
                        samples.add(p.toFile());
                    }
                } catch (IOException e) {
                    log.warn("Error scanning samples directory: {}", e.getMessage());
                }
            }
        }

        // Fallback: scan the file-system path relative to the module root
        if (samples.isEmpty()) {
            Path fallback = Path.of("src", "test", "resources", "samples");
            if (Files.isDirectory(fallback)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(fallback, "*.cr3")) {
                    for (Path p : stream) {
                        samples.add(p.toFile());
                    }
                } catch (IOException e) {
                    log.warn("Error scanning fallback samples directory: {}", e.getMessage());
                }
            }
        }

        samples.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return samples;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Container structure tests
    // ═══════════════════════════════════════════════════════════════════════

    @TestFactory
    Collection<DynamicTest> containerStructure() {
        List<File> samples = discoverCr3Samples();
        assumeTrue(!samples.isEmpty(),
                "No CR3 samples found — run Cr3SampleCaptureIT / Cr3MultiVariantCaptureIT first");

        List<DynamicTest> tests = new ArrayList<>();
        for (File cr3 : samples) {
            String name = cr3.getName();
            tests.add(dynamicTest("Container: " + name, () -> {
                try (ImageInputStream in = new FileImageInputStream(cr3)) {
                    Cr3Container container = Cr3Parser.parse(in);
                    assertThat(container).as(name + " container").isNotNull();
                    assertThat(container.ftypBox()).as(name + " ftyp").isNotNull();
                    assertThat(container.moovBox()).as(name + " moov").isNotNull();
                    assertThat(container.mdatBox()).as(name + " mdat").isNotNull();
                    assertThat(container.trackCount())
                            .as(name + " should have at least 3 tracks")
                            .isGreaterThanOrEqualTo(3);

                    PrvwBox prvw = container.prvwBox();
                    assertThat(prvw).as(name + " PRVW box").isNotNull();
                    assertThat(prvw.jpegData()).as(name + " PRVW JPEG data").isNotNull();
                    assertThat(prvw.jpegData().length).as(name + " PRVW JPEG size")
                            .isGreaterThan(10_000);

                    ThmbBox thmb = container.thmbBox();
                    assertThat(thmb).as(name + " THMB box").isNotNull();
                    assertThat(thmb.jpegData()).as(name + " THMB JPEG data").isNotNull();
                    assertThat(thmb.jpegData().length).as(name + " THMB JPEG size")
                            .isGreaterThan(500);

                    log.info("✓ {} — container: {} tracks, PRVW={}x{} ({}B), THMB={}x{} ({}B)",
                            name, container.trackCount(),
                            prvw.width(), prvw.height(), prvw.jpegData().length,
                            thmb.width(), thmb.height(), thmb.jpegData().length);
                }
            }));
        }
        return tests;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Metadata tests (EXIF from CMT1/CMT2)
    // ═══════════════════════════════════════════════════════════════════════

    @TestFactory
    Collection<DynamicTest> metadataExtraction() {
        List<File> samples = discoverCr3Samples();
        assumeTrue(!samples.isEmpty(),
                "No CR3 samples found — run capture ITs first");

        List<DynamicTest> tests = new ArrayList<>();
        for (File cr3 : samples) {
            String name = cr3.getName();
            tests.add(dynamicTest("Metadata: " + name, () -> {
                try (ImageInputStream in = new FileImageInputStream(cr3)) {
                    Cr3Decoder decoder = new Cr3Decoder();
                    decoder.setInput(in);
                    Cr3Metadata meta = decoder.getMetadata();

                    assertThat(meta).as(name + " metadata").isNotNull();
                    assertThat(meta.cameraModel())
                            .as(name + " camera model").isNotBlank().containsIgnoringCase("Canon");
                    assertThat(meta.isoSpeed())
                            .as(name + " ISO speed").isGreaterThan(0);
                    assertThat(meta.fNumber())
                            .as(name + " f-number").isGreaterThan(0.0);
                    assertThat(meta.exposureTime())
                            .as(name + " exposure time").isGreaterThan(0.0);
                    assertThat(meta.focalLengthMm())
                            .as(name + " focal length (mm)").isGreaterThan(0.0);
                    assertThat(meta.captureDateTime())
                            .as(name + " capture date/time").isNotBlank();
                    assertThat(meta.sensorWidth())
                            .as(name + " sensor width").isGreaterThan(0);
                    assertThat(meta.sensorHeight())
                            .as(name + " sensor height").isGreaterThan(0);

                    log.info("✓ {} — model='{}', {}x{}, ISO={}, f/{}, {}s, {}mm, dt='{}'",
                            name, meta.cameraModel(),
                            meta.sensorWidth(), meta.sensorHeight(),
                            meta.isoSpeed(), meta.fNumber(), meta.exposureTime(),
                            meta.focalLengthMm(), meta.captureDateTime());
                }
            }));
        }
        return tests;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Preview decoding (PRVW embedded JPEG)
    // ═══════════════════════════════════════════════════════════════════════

    @TestFactory
    Collection<DynamicTest> previewDecoding() {
        List<File> samples = discoverCr3Samples();
        assumeTrue(!samples.isEmpty(),
                "No CR3 samples found — run capture ITs first");

        List<DynamicTest> tests = new ArrayList<>();
        for (File cr3 : samples) {
            String name = cr3.getName();
            tests.add(dynamicTest("Preview: " + name, () -> {
                try (ImageInputStream in = new FileImageInputStream(cr3)) {
                    Cr3Decoder decoder = new Cr3Decoder();
                    decoder.setInput(in);

                    BufferedImage preview = decoder.decode(Cr3DecodeOptions.defaults());
                    assertThat(preview).as(name + " preview").isNotNull();
                    assertThat(preview.getWidth())
                            .as(name + " preview width").isGreaterThan(0);
                    assertThat(preview.getHeight())
                            .as(name + " preview height").isGreaterThan(0);

                    log.info("✓ {} — preview: {}x{}", name,
                            preview.getWidth(), preview.getHeight());
                }
            }));
        }
        return tests;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Thumbnail decoding (THMB)
    // ═══════════════════════════════════════════════════════════════════════

    @TestFactory
    Collection<DynamicTest> thumbnailDecoding() {
        List<File> samples = discoverCr3Samples();
        assumeTrue(!samples.isEmpty(),
                "No CR3 samples found — run capture ITs first");

        List<DynamicTest> tests = new ArrayList<>();
        for (File cr3 : samples) {
            String name = cr3.getName();
            tests.add(dynamicTest("Thumbnail: " + name, () -> {
                try (ImageInputStream in = new FileImageInputStream(cr3)) {
                    Cr3Decoder decoder = new Cr3Decoder();
                    decoder.setInput(in);

                    BufferedImage thumb = decoder.decode(
                            Cr3DecodeOptions.defaults().outputMode(Cr3DecodeOptions.OutputMode.THUMBNAIL));
                    assertThat(thumb).as(name + " thumbnail").isNotNull();
                    assertThat(thumb.getWidth())
                            .as(name + " thumbnail width").isGreaterThan(0);
                    assertThat(thumb.getHeight())
                            .as(name + " thumbnail height").isGreaterThan(0);

                    log.info("✓ {} — thumbnail: {}x{}", name,
                            thumb.getWidth(), thumb.getHeight());
                }
            }));
        }
        return tests;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Full RAW decode (demosaiced sRGB)
    // ═══════════════════════════════════════════════════════════════════════

    @TestFactory
    Collection<DynamicTest> rawProcessedDecoding() {
        List<File> samples = discoverCr3Samples();
        assumeTrue(!samples.isEmpty(),
                "No CR3 samples found — run capture ITs first");

        List<DynamicTest> tests = new ArrayList<>();
        for (File cr3 : samples) {
            String name = cr3.getName();
            tests.add(dynamicTest("RAW processed: " + name, () -> {
                try (ImageInputStream in = new FileImageInputStream(cr3)) {
                    Cr3Decoder decoder = new Cr3Decoder();
                    decoder.setInput(in);
                    Cr3Metadata meta = decoder.getMetadata();

                    BufferedImage rawProcessed = decoder.decode(Cr3DecodeOptions.rawProcessed());
                    assertThat(rawProcessed).as(name + " RAW processed").isNotNull();
                    assertThat(rawProcessed.getWidth())
                            .as(name + " RAW width").isEqualTo(meta.visibleWidth());
                    assertThat(rawProcessed.getHeight())
                            .as(name + " RAW height").isEqualTo(meta.visibleHeight());
                    // RAW should be larger than the preview
                    assertThat(rawProcessed.getWidth())
                            .as(name + " RAW width > preview width")
                            .isGreaterThan(1620);

                    log.info("✓ {} — RAW processed: {}x{}", name,
                            rawProcessed.getWidth(), rawProcessed.getHeight());
                }
            }));
        }
        return tests;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ImageIO integration (end-to-end read via SPI)
    // ═══════════════════════════════════════════════════════════════════════

    @TestFactory
    Collection<DynamicTest> imageIoReadFile() {
        List<File> samples = discoverCr3Samples();
        assumeTrue(!samples.isEmpty(),
                "No CR3 samples found — run capture ITs first");

        List<DynamicTest> tests = new ArrayList<>();
        for (File cr3 : samples) {
            String name = cr3.getName();
            tests.add(dynamicTest("ImageIO.read: " + name, () -> {
                BufferedImage img = ImageIO.read(cr3);
                assertThat(img)
                        .as(name + " — ImageIO.read() should return non-null image")
                        .isNotNull();
                assertThat(img.getSampleModel().getSampleSize()).as(name + " developed RGB channels").containsExactly(16, 16, 16);
                assertThat(img.getWidth()).as(name + " width").isGreaterThan(0);
                assertThat(img.getHeight()).as(name + " height").isGreaterThan(0);

                log.info("✓ {} — ImageIO.read: {}x{}", name,
                        img.getWidth(), img.getHeight());
            }));
        }
        return tests;
    }
}
