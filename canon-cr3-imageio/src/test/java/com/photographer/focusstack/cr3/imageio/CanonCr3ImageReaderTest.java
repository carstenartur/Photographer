package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.testutil.Cr3TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Iterator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link CanonCr3ImageReader} using synthetic CR3 data.
 */
class CanonCr3ImageReaderTest {

    @Nested
    @DisplayName("Reading via CanonCr3ImageReader")
    class ReadTests {

        @Test
        @DisplayName("read() with default params should return preview image")
        void readDefaultPreview() throws Exception {
            byte[] cr3Data = Cr3TestData.buildMinimalCr3WithJpeg();
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(cr3Data));

            CanonCr3ImageReader reader = new CanonCr3ImageReader(new CanonCr3ImageReaderSpi());
            reader.setInput(input);

            BufferedImage image = reader.read(0);
            assertThat(image).isNotNull();
            assertThat(image.getWidth()).isGreaterThan(0);
            assertThat(image.getHeight()).isGreaterThan(0);

            reader.dispose();
        }

        @Test
        @DisplayName("read() with previewOnly=true should return preview image")
        void readPreviewOnly() throws Exception {
            byte[] cr3Data = Cr3TestData.buildMinimalCr3WithJpeg();
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(cr3Data));

            CanonCr3ImageReader reader = new CanonCr3ImageReader(new CanonCr3ImageReaderSpi());
            reader.setInput(input);

            CanonCr3ImageReadParam param = new CanonCr3ImageReadParam();
            param.setPreviewOnly(true);

            BufferedImage image = reader.read(0, param);
            assertThat(image).isNotNull();
            assertThat(image.getWidth()).isGreaterThan(0);
            assertThat(image.getHeight()).isGreaterThan(0);

            reader.dispose();
        }

        @Test
        void standardReadParamMapsSamplesIntoTheSuppliedDestination() throws Exception {
            byte[] cr3 = Cr3TestData.buildMinimalCr3WithJpeg();
            ImageReader reader = ImageIO.getImageReadersBySuffix("cr3").next();
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(cr3))) {
                reader.setInput(input);
                BufferedImage preview = reader.read(0);
                BufferedImage destination = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
                destination.setRGB(2, 1, 0x223344);
                ImageReadParam param = new ImageReadParam();
                param.setSourceRegion(new Rectangle(0, 0, 2, 2));
                param.setSourceSubsampling(2, 2, 1, 1);
                param.setSourceBands(new int[]{2});
                param.setDestinationBands(new int[]{1});
                param.setDestination(destination);
                param.setDestinationOffset(new Point(2, 1));

                assertThat(reader.read(0, param)).isSameAs(destination);
                assertThat(destination.getRaster().getPixel(2, 1, (int[]) null))
                        .containsExactly(0x22, preview.getRaster().getSample(1, 1, 2), 0x44);
                assertThat(destination.getRGB(2, 2)).isEqualTo(0xff000000);
            } finally {
                reader.dispose();
            }
        }

        @Test
        void standardReadParamClipsNegativeOffsetsAndValidatesBands() throws Exception {
            byte[] cr3 = Cr3TestData.buildMinimalCr3WithJpeg();
            ImageReader reader = ImageIO.getImageReadersBySuffix("cr3").next();
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(cr3))) {
                reader.setInput(input);
                BufferedImage preview = reader.read(0);
                ImageReadParam param = reader.getDefaultReadParam();
                param.setDestinationType(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB));
                param.setDestinationOffset(new Point(-1, 0));
                BufferedImage right = reader.read(0, param);
                assertThat(right.getType()).isEqualTo(BufferedImage.TYPE_INT_RGB);
                assertThat(right.getWidth()).isOne();
                assertThat(right.getRGB(0, 1)).isEqualTo(preview.getRGB(1, 1));

                param.setDestinationOffset(new Point(0, 0));
                param.setSourceRegion(new Rectangle(0, 0, 2, 1));
                param.setSourceBands(new int[]{2, 0, 1});
                param.setDestinationBands(new int[]{0, 1, 2});
                BufferedImage remapped = reader.read(0, param);
                int[] original = preview.getRaster().getPixel(1, 0, (int[]) null);
                assertThat(remapped.getRaster().getPixel(1, 0, (int[]) null))
                        .containsExactly(original[2], original[0], original[1]);

                param.setSourceBands(new int[]{3});
                param.setDestinationBands(new int[]{0});
                assertThatThrownBy(() -> reader.read(0, param)).isInstanceOf(IllegalArgumentException.class);
            } finally {
                reader.dispose();
            }
        }

        @Test
        void extremeSubsamplingAndDestinationOffsetsDoNotOverflow() throws Exception {
            byte[] cr3 = Cr3TestData.buildMinimalCr3WithJpeg();
            ImageReader reader = ImageIO.getImageReadersBySuffix("cr3").next();
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(cr3))) {
                reader.setInput(input);
                BufferedImage preview = reader.read(0);
                ImageReadParam param = new ImageReadParam();
                param.setSourceSubsampling(Integer.MAX_VALUE, Integer.MAX_VALUE, 0, 0);
                BufferedImage onlyPixel = reader.read(0, param);
                assertThat(onlyPixel.getWidth()).isOne();
                assertThat(onlyPixel.getHeight()).isOne();
                assertThat(onlyPixel.getRGB(0, 0)).isEqualTo(preview.getRGB(0, 0));

                param.setSourceSubsampling(2, 2, 0, 0);
                param.setDestinationOffset(new Point(Integer.MIN_VALUE, 0));
                assertThatThrownBy(() -> reader.read(0, param)).isInstanceOf(IllegalArgumentException.class);
                param.setDestinationOffset(new Point(Integer.MAX_VALUE, 0));
                assertThatThrownBy(() -> reader.read(0, param)).isInstanceOf(IllegalArgumentException.class);
            } finally {
                reader.dispose();
            }
        }

        @Test
        void subsampledBayerReadWritesIntoATranslatedDestination() throws Exception {
            var reader = new CanonCr3ImageReader(new CanonCr3ImageReaderSpi());
            try (var fixture = CanonCr3ImageReaderTest.class.getResourceAsStream("four-sites.cr3");
                 var input = new MemoryCacheImageInputStream(fixture)) {
                reader.setInput(input);
                BufferedImage parent = new BufferedImage(7, 6, BufferedImage.TYPE_USHORT_GRAY);
                short[] initial = new short[7 * 6];
                Arrays.fill(initial, (short) 0xcdef);
                parent.getRaster().setDataElements(0, 0, 7, 6, initial);
                BufferedImage destination = parent.getSubimage(2, 1, 3, 4);
                var param = reader.getDefaultReadParam();
                param.setDecodeOptions(Cr3DecodeOptions.rawSensor());
                param.setSourceSubsampling(2, 1, 1, 0);
                param.setDestinationOffset(new Point(1, 1));
                param.setDestination(destination);

                assertThat(reader.read(0, param)).isSameAs(destination);
                initial[2 * 7 + 3] = 8193;
                initial[3 * 7 + 3] = 8194;
                assertThat((short[]) parent.getRaster().getDataElements(0, 0, 7, 6, null))
                        .containsExactly(initial);
            } finally {
                reader.dispose();
            }
        }

        @Test
        void processedRegionMapsOrientedSubsampledPixelsAndBandsIntoTheDestination() throws Exception {
            var reader = new CanonCr3ImageReader(new CanonCr3ImageReaderSpi());
            try (var fixture = CanonCr3ImageReaderTest.class.getResourceAsStream("four-sites.cr3");
                 var input = new MemoryCacheImageInputStream(fixture)) {
                reader.setInput(input);
                reader.getImageMetadata(0);
                var metadata = reader.getDecoder().getMetadata();
                metadata.setActiveAreaLeft(1);
                metadata.setActiveAreaRight(2);
                metadata.setActiveAreaBottom(2);
                metadata.setPerChannelBlackLevels(new int[]{1, 4096, 4000, 2048});
                metadata.setWhiteBalanceMultipliers(new double[]{2, 1, 0.5});
                metadata.setOrientation(6);

                var param = reader.getDefaultReadParam();
                param.setDecodeOptions(Cr3DecodeOptions.rawProcessed().applyColorMatrix(false).applySrgbConversion(false));
                BufferedImage full = reader.read(0, param);
                assertThat(full.getWidth()).isEqualTo(2);
                assertThat(full.getHeight()).isOne();
                BufferedImage destination = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
                destination.setRGB(2, 1, 0x223344);
                param.setSourceSubsampling(2, 1, 1, 0);
                param.setSourceBands(new int[]{0});
                param.setDestinationBands(new int[]{1});
                param.setDestinationOffset(new Point(2, 1));
                param.setDestination(destination);

                assertThat(reader.read(0, param)).isSameAs(destination);
                assertThat(destination.getRaster().getPixel(2, 1, (int[]) null))
                        .containsExactly(0x22, full.getRaster().getSample(1, 0, 0), 0x44);
                assertThat(destination.getRGB(1, 1)).isEqualTo(0xff000000);
            } finally {
                reader.dispose();
            }
        }

        @Test
        void usesAnExplicitRgbWorkspaceForCompleteRawReads() throws Exception {
            var reader = new CanonCr3ImageReader(new CanonCr3ImageReaderSpi());
            try (var fixture = CanonCr3ImageReaderTest.class.getResourceAsStream("four-sites.cr3");
                 var input = new MemoryCacheImageInputStream(fixture)) {
                reader.setInput(input);
                var param = reader.getDefaultReadParam();
                param.setDecodeOptions(Cr3DecodeOptions.rawProcessed().maxWorkers(1));
                BufferedImage expected = reader.read(0, param);
                var workspace = new BufferedImage(expected.getWidth(), expected.getHeight(), BufferedImage.TYPE_INT_RGB);
                param.setDestination(workspace);
                int[] packed = ((DataBufferInt) workspace.getRaster().getDataBuffer()).getData();
                Arrays.fill(packed, 0xa5000000);
                assertThat(reader.read(0, param)).isSameAs(workspace);
                assertThat(packed[0] >>> 24).isEqualTo(0xa5);

                param.setUseDestinationAsWorkspace(true);
                assertThat(reader.read(0, param)).isSameAs(workspace);
                assertThat(packed[0] >>> 24).isZero();
                int width = expected.getWidth();
                int height = expected.getHeight();
                assertThat(workspace.getRGB(0, 0, width, height, null, 0, width))
                        .containsExactly(expected.getRGB(0, 0, width, height, null, 0, width));
            } finally {
                reader.dispose();
            }
        }

        @Test
        void rejectsAnEmptyProcessedRegionBeforeDecodingTheRawPayload() throws Exception {
            byte[] cr3;
            try (var fixture = CanonCr3ImageReaderTest.class.getResourceAsStream("four-sites.cr3")) {
                cr3 = fixture.readAllBytes();
            }
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(cr3))) {
                int offset = Math.toIntExact(Cr3Parser.parse(input).tracks().getFirst().dataOffset());
                Arrays.fill(cr3, offset, cr3.length, (byte) 0);
            }

            var reader = new CanonCr3ImageReader(new CanonCr3ImageReaderSpi());
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(cr3))) {
                reader.setInput(input);
                var param = reader.getDefaultReadParam();
                param.setDecodeOptions(Cr3DecodeOptions.rawProcessed());
                param.setSourceRegion(new Rectangle(2, 0, 1, 1));

                assertThatThrownBy(() -> reader.read(0, param)).isInstanceOf(IllegalArgumentException.class);
            } finally {
                reader.dispose();
            }
        }

        @Test
        @DisplayName("readThumbnail should return thumbnail image")
        void readThumbnail() throws Exception {
            byte[] cr3Data = Cr3TestData.buildMinimalCr3WithJpeg();
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(cr3Data));

            CanonCr3ImageReader reader = new CanonCr3ImageReader(new CanonCr3ImageReaderSpi());
            reader.setInput(input);

            assertThat(reader.readerSupportsThumbnails()).isTrue();
            int numThumbs = reader.getNumThumbnails(0);
            assertThat(numThumbs).isEqualTo(1);

            BufferedImage thumb = reader.readThumbnail(0, 0);
            assertThat(thumb).isNotNull();
            assertThat(thumb.getWidth()).isGreaterThan(0);

            reader.dispose();
        }

        @Test
        @DisplayName("getNumImages should return 1")
        void getNumImages() throws Exception {
            byte[] cr3Data = Cr3TestData.buildMinimalCr3WithJpeg();
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(cr3Data));

            CanonCr3ImageReader reader = new CanonCr3ImageReader(new CanonCr3ImageReaderSpi());
            reader.setInput(input);

            assertThat(reader.getNumImages(true)).isEqualTo(1);

            reader.dispose();
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("read() with invalid image index should throw")
        void readInvalidIndex() throws Exception {
            byte[] cr3Data = Cr3TestData.buildMinimalCr3WithJpeg();
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(cr3Data));

            CanonCr3ImageReader reader = new CanonCr3ImageReader(new CanonCr3ImageReaderSpi());
            reader.setInput(input);

            assertThatThrownBy(() -> reader.read(1))
                    .isInstanceOf(IndexOutOfBoundsException.class);

            reader.dispose();
        }

        @Test
        @DisplayName("readThumbnail with invalid index should throw")
        void readThumbnailInvalidIndex() throws Exception {
            byte[] cr3Data = Cr3TestData.buildMinimalCr3WithJpeg();
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(cr3Data));

            CanonCr3ImageReader reader = new CanonCr3ImageReader(new CanonCr3ImageReaderSpi());
            reader.setInput(input);

            assertThatThrownBy(() -> reader.readThumbnail(0, 1))
                    .isInstanceOf(IndexOutOfBoundsException.class);

            reader.dispose();
        }

        @Test
        @DisplayName("read() without input should throw")
        void readNoInput() {
            CanonCr3ImageReader reader = new CanonCr3ImageReader(new CanonCr3ImageReaderSpi());
            assertThatThrownBy(() -> reader.read(0))
                    .isInstanceOf(IllegalStateException.class);
            reader.dispose();
        }
    }

    @Nested
    @DisplayName("SPI-based reader discovery")
    class SpiDiscovery {

        @Test
        @DisplayName("ImageIO should find CR3 reader by suffix")
        void findBySuffix() {
            Iterator<ImageReader> readers = ImageIO.getImageReadersBySuffix("cr3");
            assertThat(readers.hasNext()).isTrue();
            ImageReader reader = readers.next();
            assertThat(reader).isInstanceOf(CanonCr3ImageReader.class);
        }

        @Test
        @DisplayName("ImageIO should find CR3 reader by format name")
        void findByFormatName() {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("cr3");
            assertThat(readers.hasNext()).isTrue();
            ImageReader reader = readers.next();
            assertThat(reader).isInstanceOf(CanonCr3ImageReader.class);
        }

        @Test
        @DisplayName("ImageIO should find CR3 reader by MIME type")
        void findByMimeType() {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByMIMEType("image/x-canon-cr3");
            assertThat(readers.hasNext()).isTrue();
        }
    }
}
