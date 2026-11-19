package com.photographer.focusstack.cr3.decode;

import com.photographer.focusstack.cr3.processing.BayerDemosaicer;
import com.photographer.focusstack.cr3.processing.BlackLevel;
import com.photographer.focusstack.cr3.raw.codec.CrxLosslessEncoder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

import static com.photographer.focusstack.cr3.testutil.Cr3TestData.buildBox;
import static com.photographer.focusstack.cr3.testutil.Cr3TestData.buildCr3FtypBox;
import static com.photographer.focusstack.cr3.testutil.Cr3TestData.concat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class Cr3CompactDevelopmentTest {
    @ParameterizedTest
    @CsvSource({"5, FAST", "5, LOW_MEMORY", "7, FAST", "7, LOW_MEMORY"})
    void rgb16MatchesPublicIntPreparationWithMaskedRowsOutsideTheCrop(
            int blackColumns, Cr3DecodeOptions.MemoryMode memoryMode) throws Exception {
        byte[] cr3 = sensorFile(blackColumns);
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(cr3));
             var decoder = new Cr3Decoder()) {
            decoder.setInput(input);
            var metadata = decoder.getMetadata();
            metadata.setCameraModel("Unknown");
            metadata.setActiveAreaLeft(blackColumns);
            metadata.setActiveAreaTop(3);
            metadata.setActiveAreaRight(13);
            metadata.setActiveAreaBottom(9);
            metadata.setWhiteBalanceMultipliers(new double[]{1.37, 0.91, 0.73});
            metadata.setPerChannelBlackLevels(new int[]{7000, 7100, 7200, 7300});

            for (int orientation = 1; orientation <= 8; orientation++) {
                metadata.setOrientation(orientation);
                var prepared = decoder.prepareRawDevelopment(1);
                assertEquals(BlackLevel.Mode.PER_ROW, prepared.blackLevel().mode());
                // The smallest masked code is in sensor row zero, outside the developed crop.
                assertEquals(16383 - 12, prepared.whiteLevel());
                assertEquals(blackColumns, prepared.sensorX());
                assertEquals(3, prepared.sensorY());
                assertEquals(2048 + 3 * 1100 + blackColumns * 223, prepared.image().data()[0]);
                int[] original = prepared.image().data().clone();

                for (boolean srgb : new boolean[]{false, true}) {
                    var options = Cr3DecodeOptions.rawProcessed().bitDepth(Cr3DecodeOptions.BitDepth.SIXTEEN)
                            .applySrgbConversion(srgb).maxWorkers(4).memoryMode(memoryMode);
                    var expected = BayerDemosaicer.develop16(prepared.image(), prepared.blackLevel(),
                            prepared.sensorX(), prepared.sensorY(), prepared.whiteBalance(), prepared.whiteLevel(),
                            prepared.colorMatrix(), new Rectangle(prepared.image().width(), prepared.image().height()),
                            prepared.orientation(), srgb, 1);
                    var actual = decoder.decode(options);
                    assertRegionSamples(expected, new Rectangle(expected.getWidth(), expected.getHeight()), actual);

                    var region = new Rectangle(1, 1, expected.getWidth() - 2, expected.getHeight() - 2);
                    var patch = decoder.decodeRawProcessedRegion(options, region);
                    assertRegionSamples(expected, region, patch);
                }
                assertArrayEquals(original, prepared.image().data());
            }
        }
    }

    private static void assertRegionSamples(BufferedImage expected, Rectangle region, BufferedImage actual) {
        assertEquals(region.width, actual.getWidth());
        assertEquals(region.height, actual.getHeight());
        assertEquals(expected.getColorModel(), actual.getColorModel());
        assertArrayEquals(expected.getRaster().getPixels(region.x, region.y, region.width, region.height, (int[]) null),
                actual.getRaster().getPixels(0, 0, region.width, region.height, (int[]) null));
    }

    private static byte[] sensorFile(int blackColumns) throws Exception {
        int width = 14;
        int height = 10;
        int tileWidth = 6;
        var sensor = Raster.createInterleavedRaster(DataBuffer.TYPE_USHORT, width, height, 1, null);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pedestal = 12 + (y / 2) * 31 + (2 * (y & 1) + (x & 1)) * 7;
                sensor.setSample(x, y, 0, x < blackColumns ? pedestal : 2048 + y * 1100 + x * 223);
            }
        }

        // Use the same V1 record layout exercised by CrxLosslessEncoderTest.
        var directory = ByteBuffer.allocate(3 * 108);
        for (int tile = 0; tile < 3; tile++) {
            directory.putShort((short) 0xff01).putShort((short) 8).putInt(32).putInt(tile << 16);
            for (int plane : new int[]{3, 1, 0, 2}) {
                directory.putShort((short) 0xff02).putShort((short) 8).putInt(8).putInt(((plane << 4) | 8) << 24);
                directory.putShort((short) 0xff03).putShort((short) 8).putInt(8).putInt(0);
            }
        }
        var payload = new ByteArrayOutputStream();
        try (var output = new MemoryCacheImageOutputStream(payload)) {
            CrxLosslessEncoder.write(output, sensor, tileWidth, directory.array());
        }
        byte[] sample = payload.toByteArray();

        var cmp = ByteBuffer.allocate(33);
        cmp.putShort(4, (short) 1).putInt(8, width).putInt(12, height)
                .putInt(16, tileWidth).putInt(20, height).put(24, (byte) 14).put(25, (byte) 0x40)
                .put(27, (byte) 0x80).putInt(28, directory.capacity());
        var visual = ByteBuffer.allocate(82);
        visual.putShort(6, (short) 1).putShort(24, (short) width).putShort(26, (short) height);
        byte[] craw = buildBox("CRAW", concat(visual.array(), buildBox("CMP1", cmp.array())));
        byte[] stsd = buildBox("stsd", concat(ByteBuffer.allocate(8).putInt(0).putInt(1).array(), craw));
        byte[] stsz = buildBox("stsz", ByteBuffer.allocate(12).putInt(0).putInt(sample.length).putInt(1).array());
        byte[] ftyp = buildCr3FtypBox();
        int sampleOffset = ftyp.length + movie(stsd, stsz, 0).length + 8;
        return concat(ftyp, movie(stsd, stsz, sampleOffset), buildBox("mdat", sample));
    }

    private static byte[] movie(byte[] stsd, byte[] stsz, int sampleOffset) {
        byte[] stco = buildBox("stco", ByteBuffer.allocate(12).putInt(0).putInt(1).putInt(sampleOffset).array());
        byte[] stbl = buildBox("stbl", concat(stsd, stsz, stco));
        return buildBox("moov", buildBox("trak", buildBox("mdia", buildBox("minf", stbl))));
    }
}
