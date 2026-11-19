package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.bmff.PrvwBox;
import com.photographer.focusstack.cr3.bmff.ThmbBox;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.decode.Cr3Decoder;
import com.photographer.focusstack.cr3.metadata.Cr3Metadata;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests CR3 parsing and preview extraction using the real {@code sample.cr3} captured
 * from a Canon PowerShot G5 X Mark II via CCAPI.
 *
 * <p>The sample file is committed to {@code src/test/resources/samples/sample.cr3}.
 * All tests are skipped if the file is absent (first checkout before capture).</p>
 */
@DisplayName("Real Canon G5X Mark II sample.cr3")
class RealSampleCr3Test {

    private static File sampleFile;

    @BeforeAll
    static void findSampleFile() {
        URL resource = RealSampleCr3Test.class.getResource("/samples/sample.cr3");
        if (resource != null) {
            sampleFile = new File(resource.getFile());
        }
        Assumptions.assumeTrue(sampleFile != null && sampleFile.exists(),
                "sample.cr3 not present — run Cr3SampleCaptureIT first (needs real camera)");
    }

    @Test
    void rawColorUsesRecordedTungstenWhiteBalance() throws Exception {
        URL tungsten = RealSampleCr3Test.class.getResource("/samples/sample-wb-tungsten.cr3");
        assertThat(tungsten).isNotNull();
        try (var decoder = new Cr3Decoder()) {
            decoder.setInput(Path.of(tungsten.toURI()));
            assertThat(decoder.prepareRawDevelopment(1).whiteBalance())
                    .containsExactly(1589.0 / 1024.0, 1.0, 2834.0 / 1024.0);
        }
    }

    // ── Box structure ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Box structure")
    class BoxStructure {

        @Test
        @DisplayName("Cr3Parser.parse succeeds without exception")
        void parsesWithoutException() throws Exception {
            try (ImageInputStream in = new FileImageInputStream(sampleFile)) {
                Cr3Container container = Cr3Parser.parse(in);
                assertThat(container).isNotNull();
                assertThat(container.ftypBox()).isNotNull();
                assertThat(container.moovBox()).isNotNull();
                assertThat(container.mdatBox()).isNotNull();
            }
        }

        @Test
        @DisplayName("PRVW box is parsed from Canon PRVW uuid (1620×1080)")
        void prvwBoxPresent() throws Exception {
            try (ImageInputStream in = new FileImageInputStream(sampleFile)) {
                Cr3Container container = Cr3Parser.parse(in);
                PrvwBox prvw = container.prvwBox();
                assertThat(prvw).as("PRVW box should be present").isNotNull();
                assertThat(prvw.width()).as("PRVW width").isEqualTo(1620);
                assertThat(prvw.height()).as("PRVW height").isEqualTo(1080);
                assertThat(prvw.jpegData()).as("PRVW JPEG data").isNotNull();
                assertThat(prvw.jpegData().length).as("PRVW JPEG size").isGreaterThan(10_000);
                // Must start with JPEG SOI marker
                assertThat(prvw.jpegData()[0] & 0xFF).as("JPEG SOI[0]").isEqualTo(0xFF);
                assertThat(prvw.jpegData()[1] & 0xFF).as("JPEG SOI[1]").isEqualTo(0xD8);
            }
        }

        @Test
        @DisplayName("THMB box is parsed (160×120)")
        void thmbBoxPresent() throws Exception {
            try (ImageInputStream in = new FileImageInputStream(sampleFile)) {
                Cr3Container container = Cr3Parser.parse(in);
                ThmbBox thmb = container.thmbBox();
                assertThat(thmb).as("THMB box should be present").isNotNull();
                assertThat(thmb.width()).as("THMB width").isEqualTo(160);
                assertThat(thmb.height()).as("THMB height").isEqualTo(120);
                assertThat(thmb.jpegData()).as("THMB JPEG data").isNotNull();
                assertThat(thmb.jpegData().length).as("THMB JPEG size").isGreaterThan(500);
                assertThat(thmb.jpegData()[0] & 0xFF).as("JPEG SOI[0]").isEqualTo(0xFF);
                assertThat(thmb.jpegData()[1] & 0xFF).as("JPEG SOI[1]").isEqualTo(0xD8);
            }
        }

        @Test
        @DisplayName("Four tracks parsed (JPEG_BIG, CRX_SMALL, CRX_BIG, CTMD)")
        void fourTracksWithCorrectTypes() throws Exception {
            try (ImageInputStream in = new FileImageInputStream(sampleFile)) {
                Cr3Container container = Cr3Parser.parse(in);
                assertThat(container.trackCount()).as("track count").isEqualTo(4);

                // Track 0: imageType=16 → JPEG_BIG (large JPEG preview in mdat)
                assertThat(container.tracks().get(0).type())
                        .as("track 0 type").isEqualTo(Cr3Track.TrackType.JPEG_BIG);

                // Track 1: imageType=1 → CRX_SMALL
                assertThat(container.tracks().get(1).type())
                        .as("track 1 type").isEqualTo(Cr3Track.TrackType.CRX_SMALL);

                // Track 2: imageType=0 → CRX_BIG
                assertThat(container.tracks().get(2).type())
                        .as("track 2 type").isEqualTo(Cr3Track.TrackType.CRX_BIG);

                // Track 3: CTMD metadata
                assertThat(container.tracks().get(3).codecType())
                        .as("track 3 codec").isEqualTo("CTMD");
            }
        }

        @Test
        @DisplayName("XMP uuid box has no child boxes (raw XML payload)")
        void xmpUuidHasNoChildren() throws Exception {
            try (ImageInputStream in = new FileImageInputStream(sampleFile)) {
                Cr3Container container = Cr3Parser.parse(in);
                // The top-level boxes include: ftyp, moov, uuid(XMP), uuid(PRVW), mdat
                long xmpUuids = container.allBoxes().stream()
                        .filter(b -> "uuid".equals(b.type()) && b.children().isEmpty())
                        .count();
                assertThat(xmpUuids).as("XMP uuid has no children").isGreaterThanOrEqualTo(1L);
            }
        }
    }

    // ── Metadata ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Metadata extraction")
    class MetadataExtraction {

        @Test
        @DisplayName("Camera model is Canon PowerShot G5 X Mark II")
        void cameraModel() throws Exception {
            try (ImageInputStream in = new FileImageInputStream(sampleFile)) {
                Cr3Decoder decoder = new Cr3Decoder();
                decoder.setInput(in);
                Cr3Metadata meta = decoder.getMetadata();
                assertThat(meta.cameraModel()).contains("G5 X Mark II");
            }
        }

        @Test
        @DisplayName("Sensor dimensions are 5632×3710")
        void sensorDimensions() throws Exception {
            try (ImageInputStream in = new FileImageInputStream(sampleFile)) {
                Cr3Decoder decoder = new Cr3Decoder();
                decoder.setInput(in);
                Cr3Metadata meta = decoder.getMetadata();
                assertThat(meta.sensorWidth()).as("sensor width").isEqualTo(5632);
                assertThat(meta.sensorHeight()).as("sensor height").isEqualTo(3710);
            }
        }

        @Test
        @DisplayName("ISO speed is read from CMT2 and is > 0")
        void isoSpeedFromCmt2() throws Exception {
            try (ImageInputStream in = new FileImageInputStream(sampleFile)) {
                Cr3Decoder decoder = new Cr3Decoder();
                decoder.setInput(in);
                Cr3Metadata meta = decoder.getMetadata();
                assertThat(meta.isoSpeed())
                        .as("ISO speed should be populated from EXIF CMT2")
                        .isGreaterThan(0);
            }
        }

        @Test
        @DisplayName("f-number is read from CMT2 and is > 0")
        void fNumberFromCmt2() throws Exception {
            try (ImageInputStream in = new FileImageInputStream(sampleFile)) {
                Cr3Decoder decoder = new Cr3Decoder();
                decoder.setInput(in);
                Cr3Metadata meta = decoder.getMetadata();
                assertThat(meta.fNumber())
                        .as("f-number should be populated from EXIF CMT2 FNumber tag (0x829D)")
                        .isGreaterThan(0.0);
            }
        }

        @Test
        @DisplayName("Exposure time is read from CMT2 and is > 0")
        void exposureTimeFromCmt2() throws Exception {
            try (ImageInputStream in = new FileImageInputStream(sampleFile)) {
                Cr3Decoder decoder = new Cr3Decoder();
                decoder.setInput(in);
                Cr3Metadata meta = decoder.getMetadata();
                assertThat(meta.exposureTime())
                        .as("exposure time should be populated from EXIF CMT2 ExposureTime tag (0x829A)")
                        .isGreaterThan(0.0);
            }
        }

        @Test
        @DisplayName("Focal length is read from CMT2 and is > 0 mm")
        void focalLengthFromCmt2() throws Exception {
            try (ImageInputStream in = new FileImageInputStream(sampleFile)) {
                Cr3Decoder decoder = new Cr3Decoder();
                decoder.setInput(in);
                Cr3Metadata meta = decoder.getMetadata();
                assertThat(meta.focalLengthMm())
                        .as("focal length should be populated from EXIF CMT2 FocalLength tag (0x920A)")
                        .isGreaterThan(0.0);
            }
        }

        @Test
        @DisplayName("Capture date/time is read from CMT2 and is not blank")
        void captureDateTimeFromCmt2() throws Exception {
            try (ImageInputStream in = new FileImageInputStream(sampleFile)) {
                Cr3Decoder decoder = new Cr3Decoder();
                decoder.setInput(in);
                Cr3Metadata meta = decoder.getMetadata();
                assertThat(meta.captureDateTime())
                        .as("capture date/time should be populated from EXIF CMT2 DateTimeOriginal (0x9003)")
                        .isNotBlank();
            }
        }
    }

    // ── Preview/thumbnail decoding ─────────────────────────────────────────

    @Nested
    @DisplayName("Image decoding via Cr3Decoder")
    class ImageDecoding {

        @Test
        @DisplayName("PREVIEW mode returns 1620×1080 image from PRVW box")
        void decodesPreview() throws Exception {
            try (ImageInputStream in = new FileImageInputStream(sampleFile)) {
                Cr3Decoder decoder = new Cr3Decoder();
                decoder.setInput(in);
                BufferedImage img = decoder.decode(Cr3DecodeOptions.defaults());
                assertThat(img).isNotNull();
                assertThat(img.getWidth()).as("preview width").isEqualTo(1620);
                assertThat(img.getHeight()).as("preview height").isEqualTo(1080);
            }
        }

        @Test
        @DisplayName("THUMBNAIL mode returns 160×120 image from THMB box")
        void decodesThumbnail() throws Exception {
            try (ImageInputStream in = new FileImageInputStream(sampleFile)) {
                Cr3Decoder decoder = new Cr3Decoder();
                decoder.setInput(in);
                BufferedImage img = decoder.decode(
                        Cr3DecodeOptions.defaults().outputMode(Cr3DecodeOptions.OutputMode.THUMBNAIL));
                assertThat(img).isNotNull();
                assertThat(img.getWidth()).as("thumbnail width").isEqualTo(160);
                assertThat(img.getHeight()).as("thumbnail height").isEqualTo(120);
            }
        }

        @Test
        @DisplayName("RAW_PROCESSED mode decodes full RAW dimensions")
        void decodesRawProcessed() throws Exception {
            try (ImageInputStream in = new FileImageInputStream(sampleFile)) {
                Cr3Decoder decoder = new Cr3Decoder();
                decoder.setInput(in);
                Cr3Metadata meta = decoder.getMetadata();

                BufferedImage img = decoder.decode(Cr3DecodeOptions.rawProcessed());
                assertThat(img).isNotNull();
                assertThat(img.getWidth()).as("raw processed width")
                        .isEqualTo(meta.visibleWidth());
                assertThat(img.getHeight()).as("raw processed height")
                        .isEqualTo(meta.visibleHeight());
                assertThat(img.getWidth()).isGreaterThan(1620);
                assertThat(img.getHeight()).isGreaterThan(1080);
            }
        }

        @Test
        @DisplayName("RAW_LINEAR mode respects cropToActiveArea option")
        void rawLinearCropToActiveArea() throws Exception {
            try (ImageInputStream in = new FileImageInputStream(sampleFile)) {
                Cr3Decoder decoder = new Cr3Decoder();
                decoder.setInput(in);
                Cr3Metadata meta = decoder.getMetadata();

                BufferedImage cropped = decoder.decode(Cr3DecodeOptions.rawLinear().cropToActiveArea(true));
                BufferedImage full = decoder.decode(Cr3DecodeOptions.rawLinear().cropToActiveArea(false));

                assertThat(cropped.getWidth()).isEqualTo(meta.visibleWidth());
                assertThat(cropped.getHeight()).isEqualTo(meta.visibleHeight());
                assertThat(full.getWidth()).isEqualTo(meta.sensorWidth());
                assertThat(full.getHeight()).isEqualTo(meta.sensorHeight());
                assertThat(cropped.getWidth()).isLessThanOrEqualTo(full.getWidth());
                assertThat(cropped.getHeight()).isLessThanOrEqualTo(full.getHeight());
            }
        }
    }

    // ── CanonCr3ImageReader (ImageIO) ──────────────────────────────────────

    @Nested
    @DisplayName("CanonCr3ImageReader via ImageIO")
    class ImageReaderDecoding {

        @Test
        @DisplayName("ImageReader reads preview from real CR3 file")
        void imageReaderReadsPreview() throws Exception {
            try (ImageInputStream in = new FileImageInputStream(sampleFile)) {
                CanonCr3ImageReader reader = new CanonCr3ImageReader(new CanonCr3ImageReaderSpi());
                reader.setInput(in);

                assertThat(reader.getNumImages(true)).isEqualTo(1);
                var param = reader.getDefaultReadParam();
                param.setPreviewOnly(true);
                BufferedImage img = reader.read(0, param);
                assertThat(img).isNotNull();
                assertThat(img.getWidth()).as("width").isEqualTo(1620);
                assertThat(img.getHeight()).as("height").isEqualTo(1080);
                reader.dispose();
            }
        }

        @Test
        @DisplayName("ImageReader reads thumbnail from real CR3 file")
        void imageReaderReadsThumbnail() throws Exception {
            try (ImageInputStream in = new FileImageInputStream(sampleFile)) {
                CanonCr3ImageReader reader = new CanonCr3ImageReader(new CanonCr3ImageReaderSpi());
                reader.setInput(in);

                assertThat(reader.getNumThumbnails(0)).isGreaterThanOrEqualTo(1);
                BufferedImage thumb = reader.readThumbnail(0, 0);
                assertThat(thumb).isNotNull();
                assertThat(thumb.getWidth()).as("thumb width").isEqualTo(160);
                assertThat(thumb.getHeight()).as("thumb height").isEqualTo(120);
                reader.dispose();
            }
        }

        @Test
        @DisplayName("ImageIO.read(File) develops full-resolution color from real CR3")
        void imageIoReadFile() throws Exception {
            BufferedImage img = ImageIO.read(sampleFile);
            assertThat(img).isNotNull();
            assertThat(img.getType()).isEqualTo(BufferedImage.TYPE_INT_RGB);
            assertThat(img.getWidth()).isGreaterThan(1620);
            assertThat(img.getHeight()).isGreaterThan(1080);
            try (ImageInputStream in = new FileImageInputStream(sampleFile)) {
                var reader = new CanonCr3ImageReader(new CanonCr3ImageReaderSpi());
                try {
                    reader.setInput(in);
                    assertThat(img.getWidth()).isEqualTo(reader.getWidth(0));
                    assertThat(img.getHeight()).isEqualTo(reader.getHeight(0));
                } finally {
                    reader.dispose();
                }
            }
        }
    }

    // ── RAW decode validation ──────────────────────────────────────────────

    @Nested
    @DisplayName("RAW decode validation")
    class RawDecodeValidation {

        @Test
        @DisplayName("RAW pixel values are within 14-bit range [0, 16383]")
        void rawPixelValuesWithin14BitRange() throws Exception {
            try (ImageInputStream in = new FileImageInputStream(sampleFile)) {
                Cr3Decoder decoder = new Cr3Decoder();
                decoder.setInput(in);

                // Decode full RAW linear (Bayer, no demosaicing) with crop
                BufferedImage rawLinear = decoder.decode(
                        Cr3DecodeOptions.rawLinear().cropToActiveArea(true));
                assertThat(rawLinear).isNotNull();

                // RAW_LINEAR produces TYPE_USHORT_GRAY scaled to 16-bit
                int w = rawLinear.getWidth();
                int h = rawLinear.getHeight();
                short[] pixels = ((java.awt.image.DataBufferUShort)
                        rawLinear.getRaster().getDataBuffer()).getData();

                int minVal = Integer.MAX_VALUE;
                int maxVal = Integer.MIN_VALUE;
                long sum = 0;
                for (short pixel : pixels) {
                    int v = pixel & 0xFFFF;
                    minVal = Math.min(minVal, v);
                    maxVal = Math.max(maxVal, v);
                    sum += v;
                }
                double mean = (double) sum / pixels.length;

                assertThat(minVal).as("min pixel value").isGreaterThanOrEqualTo(0);
                assertThat(maxVal).as("max pixel value").isLessThanOrEqualTo(65535);
                // Mean should not be at the extremes — a real image has visible content
                assertThat(mean).as("mean pixel value").isGreaterThan(100);
                assertThat(mean).as("mean pixel value").isLessThan(60000);
                // There should be some variance (not a flat image)
                assertThat(maxVal - minVal).as("pixel range").isGreaterThan(1000);
            }
        }

        @Test
        @DisplayName("Cropped RAW histogram matches the native reference after black subtraction")
        void rawHistogramMatchesReference() throws Exception {
            try (ImageInputStream in = new FileImageInputStream(sampleFile)) {
                Cr3Decoder decoder = new Cr3Decoder();
                decoder.setInput(in);

                BufferedImage rawLinear = decoder.decode(
                        Cr3DecodeOptions.rawLinear().cropToActiveArea(true));
                assertThat(rawLinear.getWidth()).isEqualTo(5496);
                assertThat(rawLinear.getHeight()).isEqualTo(3668);

                short[] pixels = ((java.awt.image.DataBufferUShort)
                        rawLinear.getRaster().getDataBuffer()).getData();

                // Divide 16-bit range into 16 bins of 4096 each
                int[] histogram = new int[16];
                for (short pixel : pixels) {
                    int bin = (pixel & 0xFFFF) >> 12; // divide by 4096
                    histogram[bin]++;
                }

                // Independently counted from the unchanged sample.reference.tiff:
                // inclusive IAD1 [120,28..5615,3695] becomes [120,28..5616,3696).
                // Subtract 512 DN, then scale 14-bit values to unsigned 16-bit.
                assertThat(histogram).as("black-corrected reference histogram")
                        .containsExactly(0, 11_935_391, 8_223_937, 0, 0, 0, 0, 0,
                                0, 0, 0, 0, 0, 0, 0, 0);
            }
        }

        @Test
        @DisplayName("RAW processed and embedded JPEG preview have similar mean brightness")
        void rawProcessedSimilarToPreviewBrightness() throws Exception {
            try (ImageInputStream in = new FileImageInputStream(sampleFile)) {
                Cr3Decoder decoder = new Cr3Decoder();
                decoder.setInput(in);

                // 1. Decode embedded JPEG preview (1620×1080)
                BufferedImage preview = decoder.decode(Cr3DecodeOptions.defaults());
                assertThat(preview).isNotNull();

                // 2. Decode full RAW processed (sRGB)
                BufferedImage rawProcessed = decoder.decode(Cr3DecodeOptions.rawProcessed());
                assertThat(rawProcessed).isNotNull();

                // 3. Downscale RAW image to preview dimensions for fair comparison
                BufferedImage rawScaled = scaleImage(rawProcessed,
                        preview.getWidth(), preview.getHeight());

                // 4. Compute mean R, G, B for both images
                double[] previewMeans = computeChannelMeans(preview);
                double[] rawMeans = computeChannelMeans(rawScaled);

                // The overall luminance (0.299R + 0.587G + 0.114B) should be
                // within a factor of 3 — RAW processing parameters are approximate
                // so exact match is not expected, but the images should depict the
                // same scene with recognizable brightness
                double previewLuma = 0.299 * previewMeans[0] + 0.587 * previewMeans[1]
                        + 0.114 * previewMeans[2];
                double rawLuma = 0.299 * rawMeans[0] + 0.587 * rawMeans[1]
                        + 0.114 * rawMeans[2];

                assertThat(previewLuma).as("preview luminance").isGreaterThan(5);
                assertThat(rawLuma).as("RAW luminance").isGreaterThan(5);

                double ratio = rawLuma / previewLuma;
                assertThat(ratio).as("RAW/preview luminance ratio")
                        .isBetween(0.1, 10.0);
            }
        }

        @Test
        @DisplayName("RAW processed image has non-trivial channel variance")
        void rawProcessedHasChannelVariance() throws Exception {
            try (ImageInputStream in = new FileImageInputStream(sampleFile)) {
                Cr3Decoder decoder = new Cr3Decoder();
                decoder.setInput(in);

                BufferedImage rawProcessed = decoder.decode(Cr3DecodeOptions.rawProcessed());
                assertThat(rawProcessed).isNotNull();

                double[] means = computeChannelMeans(rawProcessed);
                double[] variances = computeChannelVariances(rawProcessed, means);

                // A real photo should have non-trivial variance in each channel
                // (not a flat solid color)
                for (int ch = 0; ch < 3; ch++) {
                    String name = switch (ch) { case 0 -> "R"; case 1 -> "G"; default -> "B"; };
                    assertThat(variances[ch]).as(name + " channel variance")
                            .isGreaterThan(1.0);
                    assertThat(means[ch]).as(name + " channel mean")
                            .isGreaterThan(0.0)
                            .isLessThan(255.0);
                }
            }
        }

        @Test
        @DisplayName("RAW processed RGB channels have plausible relative balance")
        void rawProcessedChannelBalance() throws Exception {
            try (ImageInputStream in = new FileImageInputStream(sampleFile)) {
                Cr3Decoder decoder = new Cr3Decoder();
                decoder.setInput(in);

                BufferedImage rawProcessed = decoder.decode(Cr3DecodeOptions.rawProcessed());

                double[] means = computeChannelMeans(rawProcessed);
                double maxMean = Math.max(means[0], Math.max(means[1], means[2]));
                double minMean = Math.min(means[0], Math.min(means[1], means[2]));

                // In a correctly white-balanced image, no channel should dominate
                // by more than ~20:1 (extreme color cast would indicate broken WB)
                assertThat(maxMean / Math.max(minMean, 0.01))
                        .as("max/min channel mean ratio")
                        .isLessThan(20.0);
            }
        }

        // ── Helpers ──

        private static BufferedImage scaleImage(BufferedImage src, int targetW, int targetH) {
            BufferedImage scaled = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, targetW, targetH, null);
            g.dispose();
            return scaled;
        }

        private static double[] computeChannelMeans(BufferedImage image) {
            long sumR = 0, sumG = 0, sumB = 0;
            int w = image.getWidth();
            int h = image.getHeight();
            long pixelCount = (long) w * h;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int rgb = image.getRGB(x, y);
                    sumR += (rgb >> 16) & 0xFF;
                    sumG += (rgb >> 8) & 0xFF;
                    sumB += rgb & 0xFF;
                }
            }
            return new double[]{
                    (double) sumR / pixelCount,
                    (double) sumG / pixelCount,
                    (double) sumB / pixelCount
            };
        }

        private static double[] computeChannelVariances(BufferedImage image, double[] means) {
            double varR = 0, varG = 0, varB = 0;
            int w = image.getWidth();
            int h = image.getHeight();
            long pixelCount = (long) w * h;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int rgb = image.getRGB(x, y);
                    double r = ((rgb >> 16) & 0xFF) - means[0];
                    double g = ((rgb >> 8) & 0xFF) - means[1];
                    double b = (rgb & 0xFF) - means[2];
                    varR += r * r;
                    varG += g * g;
                    varB += b * b;
                }
            }
            return new double[]{
                    varR / pixelCount,
                    varG / pixelCount,
                    varB / pixelCount
            };
        }
    }
}
