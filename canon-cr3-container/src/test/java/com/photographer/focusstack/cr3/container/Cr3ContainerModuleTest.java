package com.photographer.focusstack.cr3.container;

import org.junit.jupiter.api.Test;

import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent smoke tests for the extracted container module. */
class Cr3ContainerModuleTest {

    @Test
    void detectsCr3WithoutCoreModule() throws Exception {
        byte[] ftyp = ftyp("crx ", "crx ", "isom");
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(ftyp))) {
            assertThat(Cr3Parser.isCr3(input)).isTrue();
            assertThat(input.getStreamPosition()).isZero();
        }
    }

    @Test
    void parsesMinimalContainerWithoutTracks() throws Exception {
        byte[] file = concat(ftyp("crx ", "crx ", "isom"), box("moov", new byte[0]),
                box("mdat", new byte[0]));
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(file))) {
            Cr3Container container = Cr3Parser.parse(input);
            assertThat(container.ftypBox().isCr3()).isTrue();
            assertThat(container.trackCount()).isZero();
            assertThat(container.moovBox().type()).isEqualTo("moov");
            assertThat(container.mdatBox().type()).isEqualTo("mdat");
        }
    }

    private static byte[] ftyp(String majorBrand, String... compatibleBrands) {
        ByteBuffer payload = ByteBuffer.allocate(8 + compatibleBrands.length * 4)
                .order(ByteOrder.BIG_ENDIAN);
        payload.put(majorBrand.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        payload.putInt(1);
        for (String brand : compatibleBrands) {
            payload.put(brand.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        return box("ftyp", payload.array());
    }

    private static byte[] box(String type, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(8 + payload.length);
        buffer.put(type.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buffer.put(payload);
        return buffer.array();
    }

    private static byte[] concat(byte[]... values) {
        int size = java.util.Arrays.stream(values).mapToInt(value -> value.length).sum();
        byte[] result = new byte[size];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, result, offset, value.length);
            offset += value.length;
        }
        return result;
    }
}
