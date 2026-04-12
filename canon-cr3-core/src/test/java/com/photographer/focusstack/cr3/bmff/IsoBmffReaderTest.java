package com.photographer.focusstack.cr3.bmff;

import com.photographer.focusstack.cr3.testutil.Cr3TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link IsoBmffReader}.
 */
class IsoBmffReaderTest {

    @Nested
    @DisplayName("Basic box parsing")
    class BasicBoxParsing {

        @Test
        @DisplayName("Should parse a single leaf box")
        void parseSingleBox() throws IOException {
            byte[] data = Cr3TestData.buildBox("test", new byte[]{1, 2, 3, 4, 5, 6, 7, 8});

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
            byte[] box1 = Cr3TestData.buildBox("aaaa", new byte[]{1, 2});
            byte[] box2 = Cr3TestData.buildBox("bbbb", new byte[]{3, 4, 5, 6});
            byte[] data = Cr3TestData.concat(box1, box2);

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
        @DisplayName("Should handle empty data gracefully")
        void emptyData() throws IOException {
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(new byte[0]));
            List<IsoBox> boxes = IsoBmffReader.readBoxes(input);
            assertThat(boxes).isEmpty();
        }

        @Test
        @DisplayName("Should read box payload")
        void readBoxPayload() throws IOException {
            byte[] payload = {10, 20, 30, 40};
            byte[] data = Cr3TestData.buildBox("payl", payload);

            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            List<IsoBox> boxes = IsoBmffReader.readBoxes(input);

            byte[] readPayload = IsoBmffReader.readBoxPayload(input, boxes.getFirst());
            assertThat(readPayload).isEqualTo(payload);
        }
    }

    @Nested
    @DisplayName("Extended size (64-bit) box headers")
    class ExtendedSizeBoxParsing {

        @Test
        @DisplayName("Should parse box with extended 64-bit size header")
        void parseExtendedSizeBox() throws IOException {
            byte[] payload = new byte[]{0x41, 0x42, 0x43, 0x44};
            byte[] data = Cr3TestData.buildExtendedSizeBox("ext1", payload);

            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            List<IsoBox> boxes = IsoBmffReader.readBoxes(input);

            assertThat(boxes).hasSize(1);
            IsoBox box = boxes.getFirst();
            assertThat(box.type()).isEqualTo("ext1");
            // The size should be the full box size (16 header + 4 payload = 20)
            assertThat(box.size()).isEqualTo(20);
            assertThat(box.offset()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should parse extended size box followed by normal box")
        void parseExtendedThenNormal() throws IOException {
            byte[] ext = Cr3TestData.buildExtendedSizeBox("big1", new byte[]{1, 2});
            byte[] normal = Cr3TestData.buildBox("sml1", new byte[]{3, 4});
            byte[] data = Cr3TestData.concat(ext, normal);

            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            List<IsoBox> boxes = IsoBmffReader.readBoxes(input);

            assertThat(boxes).hasSize(2);
            assertThat(boxes.get(0).type()).isEqualTo("big1");
            assertThat(boxes.get(0).size()).isEqualTo(18); // 16 header + 2 payload
            assertThat(boxes.get(1).type()).isEqualTo("sml1");
            assertThat(boxes.get(1).offset()).isEqualTo(18);
        }

        @Test
        @DisplayName("Extended size box should have extendedSize=true and 16-byte header")
        void extendedSizeFlag() throws IOException {
            byte[] data = Cr3TestData.buildExtendedSizeBox("ext1", new byte[]{1, 2, 3, 4});
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            List<IsoBox> boxes = IsoBmffReader.readBoxes(input);

            IsoBox box = boxes.getFirst();
            assertThat(box.extendedSize()).isTrue();
            assertThat(box.headerSize()).isEqualTo(16);
            assertThat(box.dataOffset()).isEqualTo(16); // offset(0) + headerSize(16)
            assertThat(box.dataSize()).isEqualTo(4); // size(20) - headerSize(16)
        }

        @Test
        @DisplayName("Normal box should have extendedSize=false and 8-byte header")
        void normalSizeFlag() throws IOException {
            byte[] data = Cr3TestData.buildBox("norm", new byte[]{1, 2, 3, 4});
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            List<IsoBox> boxes = IsoBmffReader.readBoxes(input);

            IsoBox box = boxes.getFirst();
            assertThat(box.extendedSize()).isFalse();
            assertThat(box.headerSize()).isEqualTo(8);
            assertThat(box.dataOffset()).isEqualTo(8);
            assertThat(box.dataSize()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("Size-zero (extends-to-EOF) boxes")
    class SizeZeroBoxParsing {

        @Test
        @DisplayName("Should parse box with size=0 extending to end of stream")
        void parseSizeZeroBox() throws IOException {
            byte[] data = Cr3TestData.buildSizeZeroBox("last", new byte[]{0x10, 0x20, 0x30});

            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            List<IsoBox> boxes = IsoBmffReader.readBoxes(input);

            assertThat(boxes).hasSize(1);
            IsoBox box = boxes.getFirst();
            assertThat(box.type()).isEqualTo("last");
            // MemoryCacheImageInputStream reports length=-1, so size=0 means "unknown"
            // and the parser stores 0 as the size
            assertThat(box.size()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should parse normal box followed by size-zero box")
        void parseNormalThenSizeZero() throws IOException {
            byte[] first = Cr3TestData.buildBox("frst", new byte[]{1, 2});
            byte[] last = Cr3TestData.buildSizeZeroBox("last", new byte[]{3, 4, 5});
            byte[] data = Cr3TestData.concat(first, last);

            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            List<IsoBox> boxes = IsoBmffReader.readBoxes(input);

            assertThat(boxes).hasSize(2);
            assertThat(boxes.get(0).type()).isEqualTo("frst");
            assertThat(boxes.get(1).type()).isEqualTo("last");
            assertThat(boxes.get(1).offset()).isEqualTo(10); // after first box
            // Unknown size stored as 0
            assertThat(boxes.get(1).size()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Invalid / truncated box headers")
    class InvalidBoxHeaders {

        @Test
        @DisplayName("Should stop on truncated box header (less than 8 bytes)")
        void truncatedHeader() throws IOException {
            // Only 6 bytes — readInt succeeds but readFully for type throws EOF
            // which is caught and returns empty list
            byte[] data = new byte[]{0, 0, 0, 16, 't', 'e'};
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            List<IsoBox> boxes = IsoBmffReader.readBoxes(input);
            // The readInt reads 16 as size, then readFully for 4-byte type fails on only 2 bytes
            assertThat(boxes).isEmpty();
        }

        @Test
        @DisplayName("Should stop on box with size smaller than 8")
        void sizeSmallerthanMinimum() throws IOException {
            // Build a box header with size=4 (invalid, too small)
            ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
            buf.putInt(4); // invalid: size < 8
            buf.put("bad!".getBytes());
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(buf.array()));
            List<IsoBox> boxes = IsoBmffReader.readBoxes(input);
            assertThat(boxes).isEmpty();
        }

        @Test
        @DisplayName("Should stop parsing when remaining data is less than claimed box size")
        void insufficientDataForNextBox() throws IOException {
            byte[] box = Cr3TestData.buildBox("good", new byte[]{1});
            // Append a box header that claims 100 bytes but only has 8 total
            byte[] fakeHeader = new byte[8];
            ByteBuffer.wrap(fakeHeader).order(ByteOrder.BIG_ENDIAN).putInt(100);
            fakeHeader[4] = 'n';
            fakeHeader[5] = 'e';
            fakeHeader[6] = 'x';
            fakeHeader[7] = 't';
            byte[] data = Cr3TestData.concat(box, fakeHeader);
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            List<IsoBox> boxes = IsoBmffReader.readBoxes(input);
            // Should parse first box; second box is structurally valid (size=100, type=next)
            // but advances pos by 100 which is past EOF, so loop ends
            assertThat(boxes).hasSize(2);
            assertThat(boxes.get(0).type()).isEqualTo("good");
            assertThat(boxes.get(1).type()).isEqualTo("next");
        }

        @Test
        @DisplayName("Should reject readBoxPayload for box with unknown size")
        void rejectUnknownSizePayload() throws IOException {
            IsoBox unknownSizeBox = new IsoBox("unkn", 0, 0);
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(new byte[10]));
            assertThatThrownBy(() -> IsoBmffReader.readBoxPayload(input, unknownSizeBox))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unknown size");
        }

        @Test
        @DisplayName("Should reject readBoxPayload for oversized box (>100MB)")
        void rejectOversizedPayload() {
            IsoBox hugeBox = new IsoBox("huge", 200 * 1024 * 1024L, 0);
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(new byte[10]));
            assertThatThrownBy(() -> IsoBmffReader.readBoxPayload(input, hugeBox))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("too large");
        }
    }

    @Nested
    @DisplayName("Container box (recursive) parsing")
    class ContainerBoxParsing {

        @Test
        @DisplayName("Should recursively parse moov container with child boxes")
        void parseMoovWithChildren() throws IOException {
            // Build child boxes
            byte[] trak1 = Cr3TestData.buildBox("trak", Cr3TestData.buildBox("tkhd", new byte[]{1, 2}));
            byte[] trak2 = Cr3TestData.buildBox("trak", Cr3TestData.buildBox("tkhd", new byte[]{3, 4}));
            byte[] moovPayload = Cr3TestData.concat(trak1, trak2);
            byte[] moov = Cr3TestData.buildBox("moov", moovPayload);

            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(moov));
            List<IsoBox> boxes = IsoBmffReader.readBoxes(input);

            assertThat(boxes).hasSize(1);
            IsoBox moovBox = boxes.getFirst();
            assertThat(moovBox.type()).isEqualTo("moov");
            assertThat(moovBox.children()).hasSize(2);
            assertThat(moovBox.children().get(0).type()).isEqualTo("trak");
            assertThat(moovBox.children().get(1).type()).isEqualTo("trak");

            // Check nested tkhd children inside each trak
            IsoBox trak = moovBox.children().get(0);
            assertThat(trak.children()).hasSize(1);
            assertThat(trak.children().getFirst().type()).isEqualTo("tkhd");
        }

        @Test
        @DisplayName("Should parse deeply nested container hierarchy")
        void parseDeeplyNested() throws IOException {
            // moov -> trak -> mdia -> minf -> stbl -> stsz (leaf)
            byte[] stsz = Cr3TestData.buildBox("stsz", new byte[]{1, 2, 3, 4});
            byte[] stbl = Cr3TestData.buildBox("stbl", stsz);
            byte[] minf = Cr3TestData.buildBox("minf", stbl);
            byte[] mdia = Cr3TestData.buildBox("mdia", minf);
            byte[] trak = Cr3TestData.buildBox("trak", mdia);
            byte[] moov = Cr3TestData.buildBox("moov", trak);

            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(moov));
            List<IsoBox> boxes = IsoBmffReader.readBoxes(input);

            IsoBox moovBox = boxes.getFirst();
            IsoBox stszBox = moovBox.findDescendant("stsz");
            assertThat(stszBox).isNotNull();
            assertThat(stszBox.type()).isEqualTo("stsz");
            assertThat(stszBox.dataSize()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("FtypBox parsing")
    class FtypBoxParsing {

        @Test
        @DisplayName("Should parse CR3 ftyp box with compatible brands")
        void parseFtypBox() throws IOException {
            byte[] data = Cr3TestData.buildFtypBox("crx ", 1, "crx ", "isom");

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
            byte[] data = Cr3TestData.buildFtypBox("isom");

            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            List<IsoBox> boxes = IsoBmffReader.readBoxes(input);

            FtypBox ftyp = FtypBox.parse(input, boxes.getFirst());
            assertThat(ftyp.isCr3()).isFalse();
        }

        @Test
        @DisplayName("Should detect CR3 via compatible brands even if major brand differs")
        void cr3ViaCompatibleBrand() throws IOException {
            byte[] data = Cr3TestData.buildFtypBox("isom", 0, "crx ");

            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            List<IsoBox> boxes = IsoBmffReader.readBoxes(input);

            FtypBox ftyp = FtypBox.parse(input, boxes.getFirst());
            assertThat(ftyp.isCr3()).isTrue();
        }

        @Test
        @DisplayName("Should throw when parsing non-ftyp box as ftyp")
        void rejectNonFtypBox() throws IOException {
            byte[] data = Cr3TestData.buildBox("moov", new byte[8]);
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            List<IsoBox> boxes = IsoBmffReader.readBoxes(input);

            assertThatThrownBy(() -> FtypBox.parse(input, boxes.getFirst()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ftyp");
        }

        @Test
        @DisplayName("Should parse ftyp with no compatible brands")
        void ftypNoCompatibleBrands() throws IOException {
            byte[] data = Cr3TestData.buildFtypBox("crx ");

            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            List<IsoBox> boxes = IsoBmffReader.readBoxes(input);

            FtypBox ftyp = FtypBox.parse(input, boxes.getFirst());
            assertThat(ftyp.majorBrand()).isEqualTo("crx ");
            assertThat(ftyp.compatibleBrands()).isEmpty();
            assertThat(ftyp.isCr3()).isTrue();
        }
    }

    @Nested
    @DisplayName("IsoBox search operations")
    class IsoBoxSearch {

        @Test
        @DisplayName("findChild should return matching child or null")
        void findChild() {
            IsoBox child1 = new IsoBox("aaaa", 10, 0);
            IsoBox child2 = new IsoBox("bbbb", 10, 10);
            IsoBox parent = new IsoBox("root", 100, 0, List.of(child1, child2));

            assertThat(parent.findChild("aaaa")).isSameAs(child1);
            assertThat(parent.findChild("bbbb")).isSameAs(child2);
            assertThat(parent.findChild("cccc")).isNull();
        }

        @Test
        @DisplayName("findDescendant should search recursively")
        void findDescendant() {
            IsoBox leaf = new IsoBox("leaf", 10, 0);
            IsoBox inner = new IsoBox("innr", 30, 0, List.of(leaf));
            IsoBox root = new IsoBox("root", 100, 0, List.of(inner));

            assertThat(root.findDescendant("leaf")).isSameAs(leaf);
            assertThat(root.findDescendant("innr")).isSameAs(inner);
            assertThat(root.findDescendant("nope")).isNull();
        }
    }
}
