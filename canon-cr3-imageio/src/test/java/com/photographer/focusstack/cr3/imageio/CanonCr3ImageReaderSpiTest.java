package com.photographer.focusstack.cr3.imageio;

import org.junit.jupiter.api.DisplayName;
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

    @Test
    @DisplayName("canDecodeInput should accept CR3 stream")
    void canDecodeCr3() throws IOException {
        byte[] data = buildFtypBox("crx ");
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
        assertThat(spi.canDecodeInput(input)).isTrue();
    }

    @Test
    @DisplayName("canDecodeInput should reject non-CR3 stream")
    void cannotDecodeNonCr3() throws IOException {
        byte[] data = buildFtypBox("isom");
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
    @DisplayName("canDecodeInput should reset stream position")
    void resetsPosition() throws IOException {
        byte[] data = buildFtypBox("crx ");
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
        spi.canDecodeInput(input);
        assertThat(input.getStreamPosition()).isEqualTo(0);
    }

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
