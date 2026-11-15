package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.FileImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.net.URISyntaxException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonCr3ImageWriterTest {
    @Test
    void rejectsUntruncatedDestinationWithoutOverwritingIt(@TempDir Path directory) throws Exception {
        var sensor = new BufferedImage(5632, 3710, BufferedImage.TYPE_USHORT_GRAY);
        var writer = cr3Writer();
        writer.setDefaultCameraReference(cameraReference("sample-wide.cr3"));
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
    void requiresACameraReferenceBeforeWritingAnyBytes() throws Exception {
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
        parameter.setCameraReference(cameraReference("sample-wide.cr3"));
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
        writer.setDefaultCameraReference(cameraReference("sample-wide.cr3"));
        try (var output = new MemoryCacheImageOutputStream(new ByteArrayOutputStream())) {
            writer.setOutput(output);
            assertThrows(IIOException.class, () -> writer.write(sensor));
            assertEquals(0, output.getStreamPosition());
        } finally {
            writer.dispose();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sample-wide.cr3", "eosr-iso-low.cr3"})
    void roundTripsNativeValuesAndRetainsCameraLayout(String sample, @TempDir Path directory) throws Exception {
        Path reference = cameraReference(sample);
        Path destination = directory.resolve("rewritten.cr3");
        WritableRaster sensor = readNative(reference);
        int changed = (sensor.getSample(140, 42, 0) + 1) & 0x3fff;
        sensor.setSample(140, 42, 0, changed);

        var writer = cr3Writer();
        try (var output = new FileImageOutputStream(destination.toFile())) {
            writer.setOutput(output);
            writer.setDefaultCameraReference(reference);
            output.setByteOrder(ByteOrder.LITTLE_ENDIAN);
            writer.write(new IIOImage(sensor.createTranslatedChild(3, 5), null, null));
            assertEquals(ByteOrder.LITTLE_ENDIAN, output.getByteOrder());
        } finally {
            writer.dispose();
        }

        var preview = ImageIO.read(destination.toFile());
        assertNotNull(preview);
        assertEquals(1620, preview.getWidth());
        assertEquals(1080, preview.getHeight());
        var expectedLayout = parse(reference);
        var actualLayout = parse(destination);
        assertEquals(expectedLayout.cncvBox(), actualLayout.cncvBox());
        assertEquals(expectedLayout.tracks().get(1).cmp1(), actualLayout.tracks().get(1).cmp1());
        assertEquals(expectedLayout.tracks().get(2).cmp1(), actualLayout.tracks().get(2).cmp1());
        assertEquals(expectedLayout.tracks().get(2).iad1(), actualLayout.tracks().get(2).iad1());
        assertArrayEquals(timedMetadata(reference, expectedLayout), timedMetadata(destination, actualLayout));
        assertFalse(Arrays.equals(expectedLayout.prvwBox().jpegData(), actualLayout.prvwBox().jpegData()));
        assertEquals(0, actualLayout.moovBox().findDescendant("THMB").size() % Long.BYTES);
        for (var track : actualLayout.tracks()) {
            assertEquals(0, (track.dataOffset() - actualLayout.mdatBox().dataOffset()) % Long.BYTES);
        }

        var decoded = readNative(destination);
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

    private static Path cameraReference(String name) throws URISyntaxException {
        return Path.of(CanonCr3ImageWriterTest.class.getResource("/samples/" + name).toURI());
    }

    private static WritableRaster readNative(Path file) throws Exception {
        try (var input = new FileImageInputStream(file.toFile())) {
            var reader = assertInstanceOf(CanonCr3ImageReader.class, ImageIO.getImageReaders(input).next());
            try {
                reader.setInput(input);
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

    private static byte[] timedMetadata(Path file, Cr3Container layout) throws Exception {
        var track = layout.tracks().get(3);
        try (var input = new FileImageInputStream(file.toFile())) {
            input.seek(track.dataOffset());
            byte[] bytes = new byte[Math.toIntExact(track.dataSize())];
            input.readFully(bytes);
            return bytes;
        }
    }
}
