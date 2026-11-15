package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.encode.Cr3CameraProfile;
import com.photographer.focusstack.dng.imageio.DngImageMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.FileImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonCr3ImageWriterTest {
    @Test
    void rejectsUntruncatedDestinationWithoutOverwritingIt(@TempDir Path directory) throws Exception {
        var sensor = new BufferedImage(5632, 3710, BufferedImage.TYPE_USHORT_GRAY);
        var writer = cr3Writer();
        writer.setDefaultCameraProfile(Cr3CameraProfile.POWERSHOT_G5_X_MARK_II);
        byte[] oldBytes = {1, 2, 3, 4, 5};
        Path destination = Files.write(directory.resolve("existing.cr3"), oldBytes);
        try (var output = new FileImageOutputStream(destination.toFile())) {
            writer.setOutput(output);
            assertThrows(IIOException.class, () -> writer.write(sensor));
            assertEquals(0, output.getStreamPosition());
        } finally {
            writer.dispose();
        }
        assertArrayEquals(oldBytes, Files.readAllBytes(destination));
    }

    @Test
    void requiresACameraProfileBeforeWritingAnyBytes() throws Exception {
        var sensor = new BufferedImage(2, 2, BufferedImage.TYPE_USHORT_GRAY);
        var writer = cr3Writer();
        try (var output = new MemoryCacheImageOutputStream(new ByteArrayOutputStream())) {
            writer.setOutput(output);
            assertThrows(IIOException.class, () -> writer.write(sensor));
            assertEquals(0, output.getStreamPosition());
        } finally {
            writer.dispose();
        }
    }

    @Test
    void rejectsADifferentSensorGeometryBeforeWritingAnyBytes() throws Exception {
        var sensor = new BufferedImage(2, 2, BufferedImage.TYPE_USHORT_GRAY);
        var writer = cr3Writer();
        var parameter = writer.getDefaultWriteParam();
        parameter.setCameraProfile(Cr3CameraProfile.POWERSHOT_G5_X_MARK_II);
        try (var output = new MemoryCacheImageOutputStream(new ByteArrayOutputStream())) {
            writer.setOutput(output);
            assertThrows(IIOException.class, () -> writer.write(null, new IIOImage(sensor, null, null), parameter));
            assertEquals(0, output.getStreamPosition());
        } finally {
            writer.dispose();
        }
    }

    @Test
    void rejectsScaledSamplesBeforeWritingAnyBytes() throws Exception {
        var sensor = new BufferedImage(5632, 3710, BufferedImage.TYPE_USHORT_GRAY);
        sensor.getRaster().setSample(0, 0, 0, 16384);
        var writer = cr3Writer();
        writer.setDefaultCameraProfile(Cr3CameraProfile.POWERSHOT_G5_X_MARK_II);
        try (var output = new MemoryCacheImageOutputStream(new ByteArrayOutputStream())) {
            writer.setOutput(output);
            assertThrows(IIOException.class, () -> writer.write(sensor));
            assertEquals(0, output.getStreamPosition());
        } finally {
            writer.dispose();
        }
    }

    @ParameterizedTest
    @CsvSource({
        "EOS_R, Canon EOS R, 6888, 4546, 3444, 6720, 4480",
        "POWERSHOT_G5_X_MARK_II, Canon PowerShot G5 X Mark II, 5632, 3710, 2816, 5472, 3648",
        "EOS_M50, Canon EOS M50, 6288, 4056, 3144, 6000, 4000",
        "EOS_RP, Canon EOS RP, 6384, 4224, 3192, 6240, 4160"
    })
    void roundTripsSensorFromKnownCameraProfile(Cr3CameraProfile profile, String cameraModel,
            int width, int height, int tileWidth, int jpegWidth, int jpegHeight, @TempDir Path directory)
            throws Exception {
        Path destination = directory.resolve("generated.cr3");
        WritableRaster sensor = syntheticSensor(width, height);
        assertEquals(cameraModel, profile.cameraModel());
        assertEquals(width, profile.sensorWidth());
        assertEquals(height, profile.sensorHeight());

        var writer = cr3Writer();
        try (var output = new FileImageOutputStream(destination.toFile())) {
            writer.setOutput(output);
            writer.setDefaultCameraProfile(Cr3CameraProfile.EOS_R);
            output.setByteOrder(ByteOrder.LITTLE_ENDIAN);
            var image = new IIOImage(sensor.createTranslatedChild(3, 5), null, null);
            if (profile == Cr3CameraProfile.EOS_R) {
                writer.write(image);
            } else {
                var parameter = writer.getDefaultWriteParam();
                parameter.setCameraProfile(profile);
                writer.write(null, image, parameter);
            }
            assertEquals(ByteOrder.LITTLE_ENDIAN, output.getByteOrder());
        } finally {
            writer.dispose();
        }

        var preview = ImageIO.read(destination.toFile());
        assertNotNull(preview);
        assertEquals(1620, preview.getWidth());
        assertEquals(1080, preview.getHeight());
        var actualLayout = parse(destination);
        assertEquals(4, actualLayout.tracks().size());
        assertEquals(jpegWidth, actualLayout.tracks().get(0).width());
        assertEquals(jpegHeight, actualLayout.tracks().get(0).height());
        var reduced = actualLayout.tracks().get(1).cmp1();
        assertEquals(1624, reduced.imageWidth());
        assertEquals(1080, reduced.imageHeight());
        assertEquals("GRBG", reduced.cfaLayoutName());
        var raw = actualLayout.tracks().get(2).cmp1();
        assertEquals(width, raw.imageWidth());
        assertEquals(height, raw.imageHeight());
        assertEquals(tileWidth, raw.tileWidth());
        assertEquals(14, raw.bitsPerSample());
        assertTrue(raw.isLossless());
        assertEquals("RGGB", raw.cfaLayoutName());
        assertEquals(0, actualLayout.moovBox().findDescendant("THMB").size() % Long.BYTES);
        for (var track : actualLayout.tracks()) {
            assertEquals(0, (track.dataOffset() - actualLayout.mdatBox().dataOffset()) % Long.BYTES);
        }

        var decoded = readNative(destination, cameraModel);
        assertEquals(sensor.getWidth(), decoded.getWidth());
        assertEquals(sensor.getHeight(), decoded.getHeight());
        int[] expectedRow = new int[sensor.getWidth()];
        int[] decodedRow = new int[sensor.getWidth()];
        for (int y = 0; y < sensor.getHeight(); y++) {
            sensor.getSamples(0, y, sensor.getWidth(), 1, 0, expectedRow);
            decoded.getSamples(0, y, decoded.getWidth(), 1, 0, decodedRow);
            assertArrayEquals(expectedRow, decodedRow, "Sensor row " + y);
        }
    }

    private static CanonCr3ImageWriter cr3Writer() {
        var writers = ImageIO.getImageWritersByFormatName("cr3");
        assertTrue(writers.hasNext(), "ImageIO must discover a CR3 writer");
        return assertInstanceOf(CanonCr3ImageWriter.class, writers.next());
    }

    private static WritableRaster syntheticSensor(int width, int height) {
        var sensor = Raster.createInterleavedRaster(DataBuffer.TYPE_USHORT, width, height, 1, null);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                row[x] = (31 * x + 13 * y + (x & 1) * 557 + (y & 1) * 761) & 0x3fff;
            }
            sensor.setSamples(0, y, width, 1, 0, row);
        }
        sensor.setSample(width - 1, height - 1, 0, 16383);
        return sensor;
    }

    private static WritableRaster readNative(Path file, String cameraModel) throws Exception {
        try (var input = new FileImageInputStream(file.toFile())) {
            var reader = assertInstanceOf(CanonCr3ImageReader.class, ImageIO.getImageReaders(input).next());
            try {
                reader.setInput(input);
                var metadata = assertInstanceOf(Cr3ImageMetadata.class, reader.getImageMetadata(0));
                assertEquals("Canon", metadata.getCr3Metadata().cameraMake());
                assertEquals(cameraModel, metadata.getCr3Metadata().cameraModel());
                assertNotNull(DngImageMetadata.fromCr3(metadata));
                var thumbnail = reader.readThumbnail(0, 0);
                assertEquals(160, thumbnail.getWidth());
                assertEquals(120, thumbnail.getHeight());
                var parameter = new CanonCr3ImageReadParam();
                parameter.setDecodeOptions(Cr3DecodeOptions.rawSensor());
                return assertInstanceOf(WritableRaster.class, reader.readRaster(0, parameter));
            } finally {
                reader.dispose();
            }
        }
    }

    private static Cr3Container parse(Path file) throws Exception {
        try (var input = new FileImageInputStream(file.toFile())) {
            return Cr3Parser.parse(input);
        }
    }
}
