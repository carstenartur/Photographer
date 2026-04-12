package com.photographer.focusstack.cr3.bmff;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link IsoBmffReader}.
 */
class IsoBmffReaderTest {

    @Test
    @DisplayName("Should parse a single leaf box")
    void parseSingleBox() throws IOException {
        // Build a simple 16-byte box: size=16, type="test", payload=8 bytes
        byte[] data = buildBox("test", new byte[]{1, 2, 3, 4, 5, 6, 7, 8});

        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
        List<IsoBox> boxes = IsoBmffReader.readBoxes(input);

        assertThat(boxes).hasSize(1);
        IsoBox box = boxes.getFirst();
        assertThat(box.type()).isEqualTo("test");
        assertThat(box.size()).isEqualTo(16);
        assertThat(box.offset()).isEqualTo(0);
        assertThat(box.children()).isEmpty();
        assertThat(box.dataSize()).isEqualTo(8);
        assertThat(box.dataOffset()).isEqualTo(8);
    }

    @Test
    @DisplayName("Should parse multiple sequential boxes")
    void parseMultipleBoxes() throws IOException {
        byte[] box1 = buildBox("aaaa", new byte[]{1, 2});
        byte[] box2 = buildBox("bbbb", new byte[]{3, 4, 5, 6});
        byte[] data = concat(box1, box2);

        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
        List<IsoBox> boxes = IsoBmffReader.readBoxes(input);

        assertThat(boxes).hasSize(2);
        assertThat(boxes.get(0).type()).isEqualTo("aaaa");
        assertThat(boxes.get(0).size()).isEqualTo(10);
        assertThat(boxes.get(1).type()).isEqualTo("bbbb");
        assertThat(boxes.get(1).size()).isEqualTo(12);
        assertThat(boxes.get(1).offset()).isEqualTo(10);
    }

    @Test
    @DisplayName("Should parse ftyp box and detect CR3 brand")
    void parseFtypBox() throws IOException {
        // ftyp box: major brand "crx ", minor version 1, compatible brands "crx ", "isom"
        ByteBuffer payload = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
        payload.put("crx ".getBytes());
        payload.putInt(1);
        payload.put("crx ".getBytes());
        payload.put("isom".getBytes());
        byte[] data = buildBox("ftyp", payload.array());

        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
        List<IsoBox> boxes = IsoBmffReader.readBoxes(input);

        assertThat(boxes).hasSize(1);
        IsoBox ftypIsoBox = boxes.getFirst();
        assertThat(ftypIsoBox.type()).isEqualTo("ftyp");

        FtypBox ftyp = FtypBox.parse(input, ftypIsoBox);
        assertThat(ftyp.majorBrand()).isEqualTo("crx ");
        assertThat(ftyp.minorVersion()).isEqualTo(1);
        assertThat(ftyp.compatibleBrands()).containsExactly("crx ", "isom");
        assertThat(ftyp.isCr3()).isTrue();
    }

    @Test
    @DisplayName("Should detect non-CR3 ftyp box")
    void nonCr3FtypBox() throws IOException {
        ByteBuffer payload = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        payload.put("isom".getBytes());
        payload.putInt(0);
        byte[] data = buildBox("ftyp", payload.array());

        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
        List<IsoBox> boxes = IsoBmffReader.readBoxes(input);

        FtypBox ftyp = FtypBox.parse(input, boxes.getFirst());
        assertThat(ftyp.isCr3()).isFalse();
    }

    @Test
    @DisplayName("Should read box payload")
    void readBoxPayload() throws IOException {
        byte[] payload = {10, 20, 30, 40};
        byte[] data = buildBox("payl", payload);

        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
        List<IsoBox> boxes = IsoBmffReader.readBoxes(input);

        byte[] readPayload = IsoBmffReader.readBoxPayload(input, boxes.getFirst());
        assertThat(readPayload).isEqualTo(payload);
    }

    @Test
    @DisplayName("Should handle empty data gracefully")
    void emptyData() throws IOException {
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(new byte[0]));
        List<IsoBox> boxes = IsoBmffReader.readBoxes(input);
        assertThat(boxes).isEmpty();
    }

    @Test
    @DisplayName("IsoBox findChild should return matching child or null")
    void findChild() {
        IsoBox child1 = new IsoBox("aaaa", 10, 0);
        IsoBox child2 = new IsoBox("bbbb", 10, 10);
        IsoBox parent = new IsoBox("root", 100, 0, List.of(child1, child2));

        assertThat(parent.findChild("aaaa")).isSameAs(child1);
        assertThat(parent.findChild("bbbb")).isSameAs(child2);
        assertThat(parent.findChild("cccc")).isNull();
    }

    @Test
    @DisplayName("IsoBox findDescendant should search recursively")
    void findDescendant() {
        IsoBox leaf = new IsoBox("leaf", 10, 0);
        IsoBox inner = new IsoBox("innr", 30, 0, List.of(leaf));
        IsoBox root = new IsoBox("root", 100, 0, List.of(inner));

        assertThat(root.findDescendant("leaf")).isSameAs(leaf);
        assertThat(root.findDescendant("innr")).isSameAs(inner);
        assertThat(root.findDescendant("nope")).isNull();
    }

    // Helper: build an ISO BMFF box from type + payload
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
