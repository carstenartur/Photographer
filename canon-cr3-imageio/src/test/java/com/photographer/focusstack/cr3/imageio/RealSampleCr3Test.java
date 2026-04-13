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
                BufferedImage img = reader.read(0);
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
        @DisplayName("ImageIO.read(File) works end-to-end on real CR3")
        void imageIoReadFile() throws Exception {
            BufferedImage img = ImageIO.read(sampleFile);
            assertThat(img).isNotNull();
            assertThat(img.getWidth()).isEqualTo(1620);
            assertThat(img.getHeight()).isEqualTo(1080);
        }
    }
}
