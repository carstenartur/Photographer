package com.photographer.focusstack.cr3.container;

import com.photographer.focusstack.cr3.Cr3FormatException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link Cr3Parser}.
 */
class Cr3ParserTest {

    @Test
    @DisplayName("isCr3 should return true for CR3 signature")
    void isCr3True() throws IOException {
        byte[] data = buildCr3Signature();
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
        assertThat(Cr3Parser.isCr3(input)).isTrue();
    }

    @Test
    @DisplayName("isCr3 should return false for non-CR3 file")
    void isCr3False() throws IOException {
        byte[] data = buildFtypBox("isom");
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
        assertThat(Cr3Parser.isCr3(input)).isFalse();
    }

    @Test
    @DisplayName("isCr3 should return false for empty file")
    void isCr3Empty() throws IOException {
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(new byte[0]));
        assertThat(Cr3Parser.isCr3(input)).isFalse();
    }

    @Test
    @DisplayName("isCr3 should return false for too-small file")
    void isCr3TooSmall() throws IOException {
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        assertThat(Cr3Parser.isCr3(input)).isFalse();
    }

    @Test
    @DisplayName("parse should throw for missing ftyp box")
    void parseMissingFtyp() {
        byte[] data = buildBox("moov", new byte[0]);
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
        assertThatThrownBy(() -> Cr3Parser.parse(input))
                .isInstanceOf(Cr3FormatException.class)
                .hasMessageContaining("ftyp");
    }

    @Test
    @DisplayName("parse should throw for non-CR3 ftyp")
    void parseNonCr3() {
        byte[] ftyp = buildFtypBox("isom");
        byte[] moov = buildBox("moov", new byte[0]);
        byte[] data = concat(ftyp, moov);
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
        assertThatThrownBy(() -> Cr3Parser.parse(input))
                .isInstanceOf(Cr3FormatException.class)
                .hasMessageContaining("Not a CR3");
    }

    @Test
    @DisplayName("isCr3 should reset stream position after check")
    void isCr3ResetsPosition() throws IOException {
        byte[] data = buildCr3Signature();
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
        Cr3Parser.isCr3(input);
        assertThat(input.getStreamPosition()).isEqualTo(0);
    }

    // Helper: build a minimal ftyp box with the given brand
    private static byte[] buildFtypBox(String brand) {
        ByteBuffer payload = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        payload.put(brand.getBytes());
        payload.putInt(0); // minor version
        return buildBox("ftyp", payload.array());
    }

    // Helper: build CR3 ftyp + moov + mdat for a valid-looking file
    private static byte[] buildCr3Signature() {
        return buildFtypBox("crx ");
    }

    private static byte[] buildBox(String type, byte[] payload) {
        int size = 8 + payload.length;
        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(size);
        buf.put(type.getBytes());
        buf.put(payload);
        return buf.array();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
