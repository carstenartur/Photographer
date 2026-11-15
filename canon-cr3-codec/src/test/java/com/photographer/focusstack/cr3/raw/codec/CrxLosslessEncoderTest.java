package com.photographer.focusstack.cr3.raw.codec;

import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import java.awt.Point;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HexFormat;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CrxLosslessEncoderTest {
    @Test
    void retainsCameraDirectoryFieldsAndUpdatesTheEightByteBandAlignment() throws Exception {
        var sensor = Raster.createInterleavedRaster(DataBuffer.TYPE_USHORT, 4, 2, 1, null);
        sensor.setSamples(0, 0, 4, 2, 0, new int[]{8192, 8192, 8192, 8192, 8192, 8192, 8192, 8192});
        var bytes = new ByteArrayOutputStream();
        var directory = ByteBuffer.allocate(112);
        directory.putShort((short) 0xff01).putShort((short) 8).putInt(32).putInt(0x00010000);
        for (int plane = 0; plane < 4; plane++) {
            directory.putShort((short) 0xff02).putShort((short) 8).putInt(8).putInt(((plane << 4) | 8) << 24);
            directory.putShort((short) 0xff03).putShort((short) 8).putInt(8).putInt(0x00200000);
        }
        try (var output = new MemoryCacheImageOutputStream(bytes)) {
            CrxLosslessEncoder.write(output, sensor, 4, directory.array());
        }
        ByteBuffer sample = ByteBuffer.wrap(bytes.toByteArray());
        assertEquals(176, sample.capacity());
        assertEquals(64, sample.getInt(4));
        assertEquals(0x00010000, sample.getInt(8));
        assertEquals(16, sample.getInt(28));
        assertEquals(0x00200007, sample.getInt(32));
        assertEquals(0, sample.getInt(108));
        assertEquals(0x200000L, sample.getLong(112));
    }

    @Test
    void reproducesTheFourSitesSampleAtTheCurrentStreamPosition() throws Exception {
        // Complete RAW sample from canon-cr3-core's four-sites.cr3, at file offset 255.
        byte[] expected = HexFormat.of().parseHex("""
                ff0100080000002000000000
                ff0200080000000808000000
                ff0300080000000800000000
                ff0200080000000818000000
                ff0300080000000800000000
                ff0200080000000828000000
                ff0300080000000800000000
                ff0200080000000838000000
                ff0300080000000800000000
                0000000000200000
                0000000000200002
                0000000000200001
                0000000000200004
                """.replace("\n", ""));
        var sensor = Raster.createInterleavedRaster(DataBuffer.TYPE_USHORT, 2, 2, 1, null);
        sensor.setSamples(0, 0, 2, 2, 0, new int[]{8192, 8193, 8191, 8194});
        var bytes = new ByteArrayOutputStream();
        try (var output = new MemoryCacheImageOutputStream(bytes)) {
            output.setByteOrder(ByteOrder.LITTLE_ENDIAN);
            output.writeByte(0x5a);
            CrxLosslessEncoder.write(output, sensor, 2, Arrays.copyOf(expected, 108));
            assertEquals(141, output.getStreamPosition());
            assertEquals(ByteOrder.LITTLE_ENDIAN, output.getByteOrder());
            output.writeByte(0x6b);
        }
        var expectedStream = new ByteArrayOutputStream();
        expectedStream.write(0x5a);
        expectedStream.writeBytes(expected);
        expectedStream.write(0x6b);
        assertArrayEquals(expectedStream.toByteArray(), bytes.toByteArray());
    }

    @Test
    void roundTripsMultiRowTilesWithANarrowFinalTileAndTranslatedOrigin() throws Exception {
        int width = 14;
        int height = 6;
        int tileWidth = 6;
        int[] samples = {
                0, 16383, 16383, 0, 0, 16383, 500, 501, 500, 501, 500, 501, 777, 888,
                10, 20, 10, 20, 10, 20, 700, 701, 700, 701, 700, 701, 812, 642,
                2, 16380, 25, 12, 2, 16380, 500, 501, 500, 501, 510, 512, 16383, 0,
                10, 20, 10, 20, 10, 20, 700, 701, 700, 701, 700, 701, 812, 642,
                16383, 0, 12, 24, 51, 6300, 620, 721, 520, 521, 511, 510, 777, 888,
                10, 11, 12, 13, 14, 15, 16200, 58, 111, 241, 500, 210, 812, 642
        };
        var sensor = Raster.createInterleavedRaster(DataBuffer.TYPE_USHORT, width, height, 1, new Point(17, -9));
        sensor.setSamples(17, -9, width, height, 0, samples);
        var bytes = new ByteArrayOutputStream();
        ByteBuffer directory = ByteBuffer.allocate(324);
        for (int tile = 0; tile < 3; tile++) {
            directory.putShort((short) 0xff01).putShort((short) 8).putInt(32).putInt(tile << 16);
            for (int plane : new int[]{3, 1, 0, 2}) {
                directory.putShort((short) 0xff02).putShort((short) 8).putInt(8).putInt(((plane << 4) | 8) << 24);
                directory.putShort((short) 0xff03).putShort((short) 8).putInt(8).putInt(0x00200000);
            }
        }
        try (var output = new MemoryCacheImageOutputStream(bytes)) {
            CrxLosslessEncoder.write(output, sensor, tileWidth, directory.array());
        }

        var cmp = new Cmp1Box((short) 1, width, height, tileWidth, height,
                14, 4, 0, 0, 0, true, 324, false);
        var decoder = CrxSensorDecoder.prepare(bytes.toByteArray(), width, height, 14, CrxHeader.CodecVersion.V1, cmp);
        int[] decoded = new int[samples.length];
        decoder.decodeRows((x, y, row) -> {
            for (int i = 0; i < row.length; i++) {
                decoded[y * width + x + 2 * i] = row[i];
            }
        });
        assertArrayEquals(samples, decoded);
    }
}
