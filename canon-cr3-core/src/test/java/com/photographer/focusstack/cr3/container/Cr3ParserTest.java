package com.photographer.focusstack.cr3.container;

import com.photographer.focusstack.cr3.Cr3FormatException;
import com.photographer.focusstack.cr3.testutil.Cr3TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

    @Nested
    @DisplayName("CR3 file detection (isCr3)")
    class IsCr3Detection {

        @Test
        @DisplayName("isCr3 should return true for CR3 signature")
        void isCr3True() throws IOException {
            byte[] data = Cr3TestData.buildCr3FtypBox();
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            assertThat(Cr3Parser.isCr3(input)).isTrue();
        }

        @Test
        @DisplayName("isCr3 should return false for non-CR3 ftyp (isom brand)")
        void isCr3FalseIsom() throws IOException {
            byte[] data = Cr3TestData.buildFtypBox("isom");
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            assertThat(Cr3Parser.isCr3(input)).isFalse();
        }

        @Test
        @DisplayName("isCr3 should return false for HEIF ftyp")
        void isCr3FalseHeif() throws IOException {
            byte[] data = Cr3TestData.buildFtypBox("heic");
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
        @DisplayName("isCr3 should return false for too-small file (3 bytes)")
        void isCr3TooSmall() throws IOException {
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(new byte[]{1, 2, 3}));
            assertThat(Cr3Parser.isCr3(input)).isFalse();
        }

        @Test
        @DisplayName("isCr3 should return false for 11-byte file (just under minimum)")
        void isCr3AlmostMinimum() throws IOException {
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(new byte[11]));
            assertThat(Cr3Parser.isCr3(input)).isFalse();
        }

        @Test
        @DisplayName("isCr3 should return false when first box is not ftyp")
        void isCr3NotFtypFirst() throws IOException {
            byte[] data = Cr3TestData.buildBox("moov", new byte[8]);
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            assertThat(Cr3Parser.isCr3(input)).isFalse();
        }

        @Test
        @DisplayName("isCr3 should return false for random/garbage data")
        void isCr3RandomData() throws IOException {
            byte[] data = new byte[]{
                    (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1,  // JPEG header
                    0x00, 0x10, 0x45, 0x78, 0x69, 0x66, 0x00, 0x00,
                    0x4D, 0x4D, 0x00, 0x2A
            };
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            assertThat(Cr3Parser.isCr3(input)).isFalse();
        }

        @Test
        @DisplayName("isCr3 should return false for PNG header")
        void isCr3PngData() throws IOException {
            byte[] data = new byte[]{
                    (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                    0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
            };
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            assertThat(Cr3Parser.isCr3(input)).isFalse();
        }

        @Test
        @DisplayName("isCr3 should reset stream position after check")
        void isCr3ResetsPosition() throws IOException {
            byte[] data = Cr3TestData.buildCr3FtypBox();
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            Cr3Parser.isCr3(input);
            assertThat(input.getStreamPosition()).isEqualTo(0);
        }

        @Test
        @DisplayName("isCr3 should reset stream position after failed check")
        void isCr3ResetsPositionOnFalse() throws IOException {
            byte[] data = Cr3TestData.buildFtypBox("isom");
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            Cr3Parser.isCr3(input);
            assertThat(input.getStreamPosition()).isEqualTo(0);
        }

        @Test
        @DisplayName("isCr3 should return false for ftyp with corrupt brand bytes")
        void isCr3CorruptBrand() throws IOException {
            // Build an ftyp box with garbage brand
            ByteBuffer payload = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
            payload.put(new byte[]{0, 0, 0, 0}); // null brand
            payload.putInt(0);
            byte[] data = Cr3TestData.buildBox("ftyp", payload.array());
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            assertThat(Cr3Parser.isCr3(input)).isFalse();
        }
    }

    @Nested
    @DisplayName("CR3 parsing errors")
    class ParseErrors {

        @Test
        @DisplayName("parse should throw for missing ftyp box")
        void parseMissingFtyp() {
            byte[] data = Cr3TestData.buildBox("moov", new byte[0]);
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            assertThatThrownBy(() -> Cr3Parser.parse(input))
                    .isInstanceOf(Cr3FormatException.class)
                    .hasMessageContaining("ftyp");
        }

        @Test
        @DisplayName("parse should throw for non-CR3 ftyp")
        void parseNonCr3() {
            byte[] ftyp = Cr3TestData.buildFtypBox("isom");
            byte[] moov = Cr3TestData.buildBox("moov", new byte[0]);
            byte[] data = Cr3TestData.concat(ftyp, moov);
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            assertThatThrownBy(() -> Cr3Parser.parse(input))
                    .isInstanceOf(Cr3FormatException.class)
                    .hasMessageContaining("Not a CR3");
        }

        @Test
        @DisplayName("parse should throw for empty file")
        void parseEmpty() {
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(new byte[0]));
            assertThatThrownBy(() -> Cr3Parser.parse(input))
                    .isInstanceOf(Cr3FormatException.class)
                    .hasMessageContaining("No boxes");
        }

        @Test
        @DisplayName("parse should throw for CR3 ftyp without moov box")
        void parseMissingMoov() {
            byte[] ftyp = Cr3TestData.buildCr3FtypBox();
            byte[] mdat = Cr3TestData.buildBox("mdat", new byte[10]);
            byte[] data = Cr3TestData.concat(ftyp, mdat);
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            assertThatThrownBy(() -> Cr3Parser.parse(input))
                    .isInstanceOf(Cr3FormatException.class)
                    .hasMessageContaining("moov");
        }

        @Test
        @DisplayName("parse should throw for CR3 ftyp + moov but no mdat")
        void parseMissingMdat() {
            byte[] ftyp = Cr3TestData.buildCr3FtypBox();
            byte[] moov = Cr3TestData.buildBox("moov", new byte[0]);
            byte[] data = Cr3TestData.concat(ftyp, moov);
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            assertThatThrownBy(() -> Cr3Parser.parse(input))
                    .isInstanceOf(Cr3FormatException.class)
                    .hasMessageContaining("mdat");
        }
    }

    @Nested
    @DisplayName("Successful CR3 parsing with tracks")
    class SuccessfulParsing {

        @Test
        @DisplayName("parse should successfully parse minimal CR3 with JPEG tracks")
        void parseMinimalCr3() throws Exception {
            byte[] cr3Data = Cr3TestData.buildMinimalCr3WithJpeg();
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(cr3Data));

            Cr3Container container = Cr3Parser.parse(input);

            assertThat(container).isNotNull();
            assertThat(container.ftypBox().isCr3()).isTrue();
            assertThat(container.moovBox()).isNotNull();
            assertThat(container.mdatBox()).isNotNull();
            assertThat(container.trackCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("parsed tracks should have correct types")
        void parsedTrackTypes() throws Exception {
            byte[] cr3Data = Cr3TestData.buildMinimalCr3WithJpeg();
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(cr3Data));

            Cr3Container container = Cr3Parser.parse(input);

            // Track 0 = thumbnail, Track 1 = preview
            assertThat(container.tracks().get(0).type())
                    .isEqualTo(Cr3Track.TrackType.JPEG_THUMBNAIL);
            assertThat(container.tracks().get(1).type())
                    .isEqualTo(Cr3Track.TrackType.JPEG_PREVIEW);
        }

        @Test
        @DisplayName("parsed tracks should have non-zero data offsets and sizes")
        void parsedTrackDataInfo() throws Exception {
            byte[] cr3Data = Cr3TestData.buildMinimalCr3WithJpeg();
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(cr3Data));

            Cr3Container container = Cr3Parser.parse(input);

            for (Cr3Track track : container.tracks()) {
                assertThat(track.dataOffset()).isGreaterThan(0);
                assertThat(track.dataSize()).isGreaterThan(0);
            }
        }
    }
}
