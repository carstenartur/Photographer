package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.raw.codec.CrxLosslessEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static com.photographer.focusstack.cr3.testutil.Cr3TestData.buildBox;
import static com.photographer.focusstack.cr3.testutil.Cr3TestData.buildCr3FtypBox;
import static com.photographer.focusstack.cr3.testutil.Cr3TestData.concat;
import static org.assertj.core.api.Assertions.assertThat;

class Cr3InclusiveActiveAreaTest {
    @Test
    void fullSensorActiveAreaRetainsTheLastRowAndColumn(@TempDir Path directory) throws Exception {
        assertCrop(directory, 0, 0, 4, new int[]{13000, 14000, 15000, 16383});
    }

    @Test
    void insetActiveAreaRetainsItsInclusiveBottomRightPixel(@TempDir Path directory) throws Exception {
        assertCrop(directory, 2, 2, 2, new int[]{15000, 16383});
    }

    @ParameterizedTest
    @ValueSource(ints = {16, 32, 48})
    void missingActiveFieldsPreserveTheFullSensorFallback(int iad1Length) throws Exception {
        byte[] cr3 = sensorFile(0, 0, 0, 0, iad1Length);
        var reader = new CanonCr3ImageReader(new CanonCr3ImageReaderSpi());
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(cr3))) {
            reader.setInput(input);
            assertThat(reader.getWidth(0)).isEqualTo(4);
            assertThat(reader.getHeight(0)).isEqualTo(4);
            var image = reader.read(0);
            assertThat(image.getWidth()).isEqualTo(4);
            assertThat(image.getHeight()).isEqualTo(4);
        } finally {
            reader.dispose();
        }
    }

    private static void assertCrop(Path directory, int left, int top, int size, int[] lastSensorRow)
            throws Exception {
        byte[] cr3 = sensorFile(left, top, 3, 3, 48);
        var reader = new CanonCr3ImageReader(new CanonCr3ImageReaderSpi());
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(cr3))) {
            reader.setInput(input);
            var metadata = ((Cr3ImageMetadata) reader.getImageMetadata(0)).getCr3Metadata();
            assertThat(metadata.activeAreaRight()).isEqualTo(4);
            assertThat(metadata.activeAreaBottom()).isEqualTo(4);
            assertThat(metadata.visibleWidth()).isEqualTo(size);
            assertThat(metadata.visibleHeight()).isEqualTo(size);
            assertThat(reader.getWidth(0)).isEqualTo(size);
            assertThat(reader.getHeight(0)).isEqualTo(size);

            // Public preparation keeps native codes, including the previously omitted last row.
            var prepared = reader.getDecoder().prepareRawDevelopment(1);
            assertThat(prepared.sensorX()).isEqualTo(left);
            assertThat(prepared.sensorY()).isEqualTo(top);
            assertThat(prepared.image().width()).isEqualTo(size);
            assertThat(prepared.image().height()).isEqualTo(size);
            assertThat(Arrays.copyOfRange(prepared.image().data(), size * (size - 1), size * size))
                    .containsExactly(lastSensorRow);

            var param = reader.getDefaultReadParam();
            param.setDecodeOptions(Cr3DecodeOptions.rawLinear().applyBlackLevel(false).maxWorkers(1));
            var linear = reader.read(0, param);
            assertThat(linear.getWidth()).isEqualTo(size);
            assertThat(linear.getHeight()).isEqualTo(size);
            assertThat(linear.getRaster().getSample(size - 1, size - 1, 0)).isEqualTo(65535);

            for (var depth : Cr3DecodeOptions.BitDepth.values()) {
                param.setDecodeOptions(Cr3DecodeOptions.rawProcessed().bitDepth(depth)
                        .applyBlackLevel(false).applyWhiteBalance(false).applyColorMatrix(false)
                        .applySrgbConversion(false).maxWorkers(1));
                var rgb = reader.read(0, param);
                assertThat(rgb.getWidth()).isEqualTo(size);
                assertThat(rgb.getHeight()).isEqualTo(size);
                // The bottom-right site is blue at both even crop origins, with native code 16383.
                assertThat(rgb.getRaster().getSample(size - 1, size - 1, 2))
                        .isEqualTo(depth == Cr3DecodeOptions.BitDepth.SIXTEEN ? 65535 : 255);
            }

            param.setDecodeOptions(Cr3DecodeOptions.rawSensor().maxWorkers(1));
            var nativeSensor = reader.read(0, param);
            assertThat(nativeSensor.getWidth()).isEqualTo(4);
            assertThat(nativeSensor.getHeight()).isEqualTo(4);
            assertThat(nativeSensor.getRaster().getSamples(0, 3, 4, 1, 0, (int[]) null))
                    .containsExactly(13000, 14000, 15000, 16383);
        } finally {
            reader.dispose();
        }

        Path file = Files.write(directory.resolve("inclusive-active.cr3"), cr3);
        var defaultImage = ImageIO.read(file.toFile());
        assertThat(defaultImage.getWidth()).isEqualTo(size);
        assertThat(defaultImage.getHeight()).isEqualTo(size);
    }

    /** A valid 4×4 V1 file with distinct native sites and an explicit on-disk IAD1 rectangle. */
    private static byte[] sensorFile(int left, int top, int right, int bottom, int iad1Length) throws Exception {
        var sensor = Raster.createInterleavedRaster(DataBuffer.TYPE_USHORT, 4, 4, 1, null);
        sensor.setSamples(0, 0, 4, 4, 0, new int[]{
                1000, 2000, 3000, 4000, 5000, 6000, 7000, 8000,
                9000, 10000, 11000, 12000, 13000, 14000, 15000, 16383});
        var directory = ByteBuffer.allocate(108);
        directory.putShort((short) 0xff01).putShort((short) 8).putInt(32).putInt(0);
        for (int plane = 0; plane < 4; plane++) {
            directory.putShort((short) 0xff02).putShort((short) 8).putInt(8).putInt(((plane << 4) | 8) << 24);
            directory.putShort((short) 0xff03).putShort((short) 8).putInt(8).putInt(0);
        }
        var payload = new ByteArrayOutputStream();
        try (var output = new MemoryCacheImageOutputStream(payload)) {
            CrxLosslessEncoder.write(output, sensor, 4, directory.array());
        }
        byte[] sample = payload.toByteArray();
        var cmp = ByteBuffer.allocate(33);
        cmp.putShort(4, (short) 1).putInt(8, 4).putInt(12, 4).putInt(16, 4).putInt(20, 4)
                .put(24, (byte) 14).put(25, (byte) 0x40).putInt(28, directory.capacity());
        var iad = ByteBuffer.allocate(48);
        iad.putShort(4, (short) 4).putShort(6, (short) 4).putShort(8, (short) 1)
                .putShort(10, (short) (iad1Length == 48 ? 2 : 0)).putShort(12, (short) 1)
                .putShort(40, (short) left).putShort(42, (short) top)
                .putShort(44, (short) right).putShort(46, (short) bottom);
        byte[] cdi = buildBox("CDI1", concat(new byte[4], buildBox("IAD1", Arrays.copyOf(iad.array(), iad1Length))));
        var visual = ByteBuffer.allocate(82);
        visual.putShort(6, (short) 1).putShort(24, (short) 4).putShort(26, (short) 4);
        byte[] craw = buildBox("CRAW", concat(visual.array(), buildBox("CMP1", cmp.array()), cdi));
        byte[] stsd = buildBox("stsd", concat(ByteBuffer.allocate(8).putInt(0).putInt(1).array(), craw));
        byte[] stsz = buildBox("stsz", ByteBuffer.allocate(12).putInt(0).putInt(sample.length).putInt(1).array());
        byte[] ftyp = buildCr3FtypBox();
        int sampleOffset = ftyp.length + movie(stsd, stsz, 0).length + 8;
        return concat(ftyp, movie(stsd, stsz, sampleOffset), buildBox("mdat", sample));
    }

    private static byte[] movie(byte[] stsd, byte[] stsz, int sampleOffset) {
        byte[] stco = buildBox("stco", ByteBuffer.allocate(12).putInt(0).putInt(1).putInt(sampleOffset).array());
        return buildBox("moov", buildBox("trak", buildBox("mdia", buildBox("minf",
                buildBox("stbl", concat(stsd, stsz, stco))))));
    }
}
