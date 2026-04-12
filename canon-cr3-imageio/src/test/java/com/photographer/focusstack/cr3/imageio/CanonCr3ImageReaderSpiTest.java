package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CanonCr3ImageReaderSpi}.
 */
class CanonCr3ImageReaderSpiTest {

    private final CanonCr3ImageReaderSpi spi = new CanonCr3ImageReaderSpi();

    @Nested
    @DisplayName("canDecodeInput - valid inputs")
    class ValidInputs {

        @Test
        @DisplayName("canDecodeInput should accept CR3 stream")
        void canDecodeCr3() throws IOException {
            byte[] data = buildFtypBox("crx ");
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            assertThat(spi.canDecodeInput(input)).isTrue();
        }

        @Test
        @DisplayName("canDecodeInput should reset stream position")
        void resetsPosition() throws IOException {
            byte[] data = buildFtypBox("crx ");
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            spi.canDecodeInput(input);
            assertThat(input.getStreamPosition()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("canDecodeInput - invalid inputs")
    class InvalidInputs {

        @Test
        @DisplayName("canDecodeInput should reject non-CR3 stream (isom)")
        void cannotDecodeNonCr3() throws IOException {
            byte[] data = buildFtypBox("isom");
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            assertThat(spi.canDecodeInput(input)).isFalse();
        }

        @Test
        @DisplayName("canDecodeInput should reject HEIF stream")
        void cannotDecodeHeif() throws IOException {
            byte[] data = buildFtypBox("heic");
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            assertThat(spi.canDecodeInput(input)).isFalse();
        }

        @Test
        @DisplayName("canDecodeInput should reject non-ImageInputStream")
        void cannotDecodeNonStream() throws IOException {
            assertThat(spi.canDecodeInput("not a stream")).isFalse();
        }

        @Test
        @DisplayName("canDecodeInput should reject empty stream")
        void cannotDecodeEmpty() throws IOException {
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(new byte[0]));
            assertThat(spi.canDecodeInput(input)).isFalse();
        }

        @Test
        @DisplayName("canDecodeInput should reject JPEG data")
        void cannotDecodeJpeg() throws IOException {
            byte[] data = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                    0, 16, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1};
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            assertThat(spi.canDecodeInput(input)).isFalse();
        }

        @Test
        @DisplayName("canDecodeInput should reject PNG data")
        void cannotDecodePng() throws IOException {
            byte[] data = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                    0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52};
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            assertThat(spi.canDecodeInput(input)).isFalse();
        }

        @Test
        @DisplayName("canDecodeInput should reset stream position on rejection")
        void resetsPositionOnReject() throws IOException {
            byte[] data = buildFtypBox("isom");
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            spi.canDecodeInput(input);
            assertThat(input.getStreamPosition()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("SPI metadata")
    class SpiMetadata {

        @Test
        @DisplayName("createReaderInstance should return CanonCr3ImageReader")
        void createReader() {
            var reader = spi.createReaderInstance(null);
            assertThat(reader).isInstanceOf(CanonCr3ImageReader.class);
        }

        @Test
        @DisplayName("getDescription should return non-null")
        void getDescription() {
            assertThat(spi.getDescription(null)).isNotBlank();
        }
    }

    @Nested
    @DisplayName("CanonCr3ImageReadParam")
    class ReadParamTests {

        @Test
        @DisplayName("default previewOnly should be false")
        void defaultPreviewOnly() {
            CanonCr3ImageReadParam param = new CanonCr3ImageReadParam();
            assertThat(param.isPreviewOnly()).isFalse();
        }

        @Test
        @DisplayName("setPreviewOnly(true) should set preview mode")
        void setPreviewOnlyTrue() {
            CanonCr3ImageReadParam param = new CanonCr3ImageReadParam();
            param.setPreviewOnly(true);
            assertThat(param.isPreviewOnly()).isTrue();
            assertThat(param.getDecodeOptions().outputMode())
                    .isEqualTo(Cr3DecodeOptions.OutputMode.PREVIEW);
        }

        @Test
        @DisplayName("setDecodeOptions should override decode options")
        void setDecodeOptions() {
            CanonCr3ImageReadParam param = new CanonCr3ImageReadParam();
            Cr3DecodeOptions options = Cr3DecodeOptions.rawProcessed();
            param.setDecodeOptions(options);
            assertThat(param.getDecodeOptions().outputMode())
                    .isEqualTo(Cr3DecodeOptions.OutputMode.RAW_PROCESSED);
        }
    }

    private static byte[] buildFtypBox(String brand) {
        ByteBuffer payload = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        payload.put(brand.getBytes());
        payload.putInt(0);
        int size = 8 + 8;
        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(size);
        buf.put("ftyp".getBytes());
        buf.put(payload.array());
        return buf.array();
    }
}
