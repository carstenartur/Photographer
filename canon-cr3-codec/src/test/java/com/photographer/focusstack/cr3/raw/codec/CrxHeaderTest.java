package com.photographer.focusstack.cr3.raw.codec;

import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link CrxHeader} parsing of CRX bitstream tile/plane/subband headers.
 */
class CrxHeaderTest {

    @Test
    void parseSingleTileSinglePlane() throws IOException {
        byte[] data = buildMinimalHeader(1, 1, 1, CrxHeader.CodecVersion.V1);
        CrxHeader header = CrxHeader.parse(data, CrxHeader.CodecVersion.V1);

        assertThat(header.tiles()).hasSize(1);
        assertThat(header.tiles().getFirst().index()).isEqualTo(0);
        assertThat(header.tiles().getFirst().planes()).hasSize(1);
        assertThat(header.tiles().getFirst().planes().getFirst().subbands()).hasSize(1);
        assertThat(header.headerSize()).isGreaterThan(0);
    }

    @Test
    void parseSingleTileFourPlanes() throws IOException {
        byte[] data = buildMinimalHeader(1, 4, 1, CrxHeader.CodecVersion.V1);
        CrxHeader header = CrxHeader.parse(data, CrxHeader.CodecVersion.V1);

        assertThat(header.tiles()).hasSize(1);
        CrxHeader.Tile tile = header.tiles().getFirst();
        assertThat(tile.planes()).hasSize(4);
        for (int i = 0; i < 4; i++) {
            assertThat(tile.planes().get(i).index()).isEqualTo(i);
            assertThat(tile.planes().get(i).subbands()).hasSize(1);
        }
    }

    @Test
    void parseTwoTiles() throws IOException {
        byte[] data = buildMinimalHeader(2, 4, 1, CrxHeader.CodecVersion.V1);
        CrxHeader header = CrxHeader.parse(data, CrxHeader.CodecVersion.V1);

        assertThat(header.tiles()).hasSize(2);
        assertThat(header.tiles().get(0).index()).isEqualTo(0);
        assertThat(header.tiles().get(1).index()).isEqualTo(1);
        for (CrxHeader.Tile tile : header.tiles()) {
            assertThat(tile.planes()).hasSize(4);
        }
    }

    @Test
    void parseV2Markers() throws IOException {
        byte[] data = buildMinimalHeader(1, 4, 1, CrxHeader.CodecVersion.V2);
        CrxHeader header = CrxHeader.parse(data, CrxHeader.CodecVersion.V2);

        assertThat(header.tiles()).hasSize(1);
        assertThat(header.tiles().getFirst().planes()).hasSize(4);
    }

    @Test
    void parseSubbandDataOffsets() throws IOException {
        int subbandDataSize = 1024;
        byte[] data = buildHeaderWithDataSize(1, 4, 1, subbandDataSize, CrxHeader.CodecVersion.V1);
        CrxHeader header = CrxHeader.parse(data, CrxHeader.CodecVersion.V1);

        int headerSize = header.headerSize();
        long offset = headerSize;
        for (CrxHeader.Tile tile : header.tiles()) {
            for (CrxHeader.Plane plane : tile.planes()) {
                for (CrxHeader.Subband sub : plane.subbands()) {
                    assertThat(sub.dataOffset()).isEqualTo(offset);
                    offset += sub.dataSize();
                }
            }
        }
    }

    @Test
    void dataTooShortThrowsIOException() {
        byte[] data = new byte[4];
        assertThatThrownBy(() -> CrxHeader.parse(data, CrxHeader.CodecVersion.V1))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("too short");
    }

    @Test
    void emptyTilesWhenNoValidMarker() throws IOException {
        byte[] data = new byte[16];
        CrxHeader header = CrxHeader.parse(data, CrxHeader.CodecVersion.V1);
        assertThat(header.tiles()).isEmpty();
    }

    @Test
    void detectVersionV1() {
        assertThat(CrxHeader.detectVersion("CanonCR3_001/00.09.00/00.00.00"))
                .isEqualTo(CrxHeader.CodecVersion.V1);
    }

    @Test
    void detectVersionV2() {
        assertThat(CrxHeader.detectVersion("CanonCR3_002/01.00.00/00.00.00"))
                .isEqualTo(CrxHeader.CodecVersion.V2);
    }

    @Test
    void detectVersionV2ForV3() {
        assertThat(CrxHeader.detectVersion("CanonCR3_003/01.00.00/00.00.00"))
                .isEqualTo(CrxHeader.CodecVersion.V2);
    }

    @Test
    void detectVersionDefaultsToV1ForNull() {
        assertThat(CrxHeader.detectVersion(null)).isEqualTo(CrxHeader.CodecVersion.V1);
    }

    @Test
    void codecVersionComesFromTheTrackDespiteDifferentContainerVersion() {
        var v1Track = new Cmp1Box((short) 0x0100, 16, 16, 16, 16,
                14, 4, 0, 0, 0, false, 0, false);
        var v2Track = new Cmp1Box((short) 0x0200, 16, 16, 16, 16,
                14, 4, 0, 0, 3, false, 0, false);
        assertThat(CrxHeader.detectVersion(v1Track, "CanonCR3_003/00.10.00/00.00.00"))
                .isEqualTo(CrxHeader.CodecVersion.V1);
        assertThat(CrxHeader.detectVersion(v2Track, "CanonCR3_001/00.11.00/00.00.00"))
                .isEqualTo(CrxHeader.CodecVersion.V2);
    }

    @Test
    void subbandQuantValueParsing() throws IOException {
        byte[] data = buildHeaderWithQuant(0x35);
        CrxHeader header = CrxHeader.parse(data, CrxHeader.CodecVersion.V1);

        assertThat(header.tiles()).hasSize(1);
        CrxHeader.Subband sub = header.tiles().getFirst().planes().getFirst().subbands().getFirst();
        assertThat(sub.quantValue()).isEqualTo(0x35);
    }

    @Test
    void toStringIncludesTileCountAndHeaderSize() throws IOException {
        byte[] data = buildMinimalHeader(1, 1, 1, CrxHeader.CodecVersion.V1);
        CrxHeader header = CrxHeader.parse(data, CrxHeader.CodecVersion.V1);
        String s = header.toString();
        assertThat(s).contains("tiles=1");
        assertThat(s).contains("headerSize=");
    }

    @Test
    void directoryEndsBeforeZeroPrefixedEntropyPayload() throws IOException {
        var version = CrxHeader.CodecVersion.V1;
        byte[] track = buildMinimalHeader(1, 1, 1, version);
        int directorySize = track.length - 4;
        CrxHeader header = CrxHeader.parseDirectory(track, directorySize, version);
        assertThat(header.headerSize()).isEqualTo(directorySize);
        assertThat(header.tiles().getFirst().planes().getFirst().subbands().getFirst().dataOffset())
                .isEqualTo(directorySize);
    }

    @Test
    void rejectsRecordsThatWouldConsumeSensorBytesBeyondTheDirectory() {
        var version = CrxHeader.CodecVersion.V1;
        for (int record : new int[] {0, 12, 24}) {
            byte[] track = buildMinimalHeader(1, 1, 1, version);
            ByteBuffer.wrap(track).putShort(record + 2, (short) (37 - record));
            assertThatThrownBy(() -> CrxHeader.parseDirectory(track, 36, version))
                    .isInstanceOf(IOException.class);
        }
    }

    // --- Helper methods ---

    static byte[] buildMinimalHeader(int numTiles, int numPlanes, int numSubbands,
                                              CrxHeader.CodecVersion version) {
        return buildHeaderWithDataSize(numTiles, numPlanes, numSubbands, 100, version);
    }

    private static byte[] buildHeaderWithDataSize(int numTiles, int numPlanes, int numSubbands,
                                                   int subbandDataSize,
                                                   CrxHeader.CodecVersion version) {
        int totalHeaders = numTiles * (1 + numPlanes * (1 + numSubbands));
        int bufSize = totalHeaders * 12 + 4;
        ByteBuffer buf = ByteBuffer.allocate(bufSize).order(ByteOrder.BIG_ENDIAN);

        for (int t = 0; t < numTiles; t++) {
            long tileSize = (long) numPlanes * numSubbands * subbandDataSize;
            writeTileHeader(buf, version.tileMarker, tileSize, t);
            for (int p = 0; p < numPlanes; p++) {
                long planeSize = (long) numSubbands * subbandDataSize;
                writePlaneHeader(buf, version.planeMarker, planeSize, p);
                for (int s = 0; s < numSubbands; s++) {
                    writeSubbandHeader(buf, version.subbandMarker, subbandDataSize, s, 0);
                }
            }
        }
        buf.putInt(0);

        byte[] result = new byte[buf.position()];
        buf.flip();
        buf.get(result);
        return result;
    }

    private static byte[] buildHeaderWithQuant(int quantValue) {
        ByteBuffer buf = ByteBuffer.allocate(48).order(ByteOrder.BIG_ENDIAN);
        writeTileHeader(buf, 0xff01, 100, 0);
        writePlaneHeader(buf, 0xff02, 100, 0);
        writeSubbandHeader(buf, 0xff03, 100, 0, quantValue);
        buf.putInt(0);

        byte[] result = new byte[buf.position()];
        buf.flip();
        buf.get(result);
        return result;
    }

    private static void writeTileHeader(ByteBuffer buf, int marker, long dataSize, int index) {
        buf.putShort((short) marker);
        buf.putShort((short) 8);
        buf.putInt((int) dataSize);
        buf.put((byte) ((index & 0xF) << 4));
        buf.put(new byte[3]);
    }

    private static void writePlaneHeader(ByteBuffer buf, int marker, long dataSize, int index) {
        buf.putShort((short) marker);
        buf.putShort((short) 8);
        buf.putInt((int) dataSize);
        buf.put((byte) ((index & 0xF) << 4));
        buf.put(new byte[3]);
    }

    private static void writeSubbandHeader(ByteBuffer buf, int marker, long dataSize,
                                            int index, int quantValue) {
        buf.putShort((short) marker);
        buf.putShort((short) 8);
        buf.putInt((int) dataSize);
        int packed = ((index & 0xF) << 4) | ((quantValue >> 5) & 0x07);
        buf.put((byte) packed);
        buf.put((byte) ((quantValue & 0x1F) << 3));
        buf.put(new byte[2]);
    }
}
