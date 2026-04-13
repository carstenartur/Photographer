package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.testutil.Cr3TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
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
        @DisplayName("readThumbnail should return thumbnail image")
        void readThumbnail() throws Exception {
            byte[] cr3Data = Cr3TestData.buildMinimalCr3WithJpeg();
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(cr3Data));

            CanonCr3ImageReader reader = new CanonCr3ImageReader(new CanonCr3ImageReaderSpi());
            reader.setInput(input);

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
