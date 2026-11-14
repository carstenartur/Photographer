package com.photographer.focusstack.dng.imageio;

import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.event.IIOWriteProgressListener;
import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.plugins.tiff.TIFFDirectory;
import javax.imageio.plugins.tiff.TIFFImageReadParam;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Hashtable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DngImageWriterTest {

    private static final int[] SENSOR_VALUES = {64, 65, 16382, 16383};

    @Test
    void serviceRegistryAdvertisesDngForUnsignedShortMosaics() {
        var type = ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_USHORT_GRAY);
        assertThat(ImageIO.getImageWriters(type, "DNG").hasNext()).isTrue();
        var rgb = ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB);
        assertThat(ImageIO.getImageWriters(rgb, "DNG").hasNext()).isFalse();
    }

    @Test
    void convenienceWritePreservesSensorValuesAndCfaTags() throws Exception {
        var gray = sensorImage();
        var properties = new Hashtable<String, Object>();
        properties.put(DngImageMetadata.PROPERTY_NAME, metadata());
        var sensor = new BufferedImage(gray.getColorModel(), gray.getRaster(), false, properties);
        var bytes = new ByteArrayOutputStream();
        assertThat(ImageIO.write(sensor, "DNG", bytes)).isTrue();
        assertSensorDng(bytes.toByteArray());
    }

    @Test
    void writesTranslatedRasterWithExplicitCameraMetadata() throws Exception {
        WritableRaster translated = sensorImage().getRaster().createWritableTranslatedChild(5, 12);
        var bytes = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("DNG").next();
        try (var output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            assertThat(writer.canWriteRasters()).isTrue();
            writer.write(null, new IIOImage(translated, null, metadata()), writer.getDefaultWriteParam());
        } finally {
            writer.dispose();
        }
        assertSensorDng(bytes.toByteArray());
    }

    @Test
    void allowsCancellingAtTheStartOfAWritingOperation() throws Exception {
        var callbacks = new ArrayList<String>();
        var writer = ImageIO.getImageWritersByFormatName("DNG").next();
        writer.addIIOWriteProgressListener(new IIOWriteProgressListener() {
            @Override public void imageStarted(ImageWriter source, int index) {
                callbacks.add("started");
                source.abort();
            }
            @Override public void writeAborted(ImageWriter source) { callbacks.add("aborted"); }
            @Override public void imageComplete(ImageWriter source) { callbacks.add("complete"); }
            @Override public void imageProgress(ImageWriter source, float percentage) { }
            @Override public void thumbnailStarted(ImageWriter source, int image, int thumbnail) { }
            @Override public void thumbnailProgress(ImageWriter source, float percentage) { }
            @Override public void thumbnailComplete(ImageWriter source) { }
        });
        var bytes = new ByteArrayOutputStream();
        try (var output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            writer.write(null, new IIOImage(sensorImage(), null, metadata()), null);
        } finally {
            writer.dispose();
        }
        assertThat(callbacks).containsExactly("started", "aborted");
        assertThat(bytes.size()).isZero();
    }

    @Test
    void writesOffsetsRelativeToCurrentOutputPosition() throws Exception {
        byte[] prefix = {7, 8, 9};
        var bytes = new ByteArrayOutputStream();
        var writer = ImageIO.getImageWritersByFormatName("DNG").next();
        try (var output = ImageIO.createImageOutputStream(bytes)) {
            output.write(prefix);
            writer.setOutput(output);
            writer.write(null, new IIOImage(sensorImage(), null, metadata()), null);
            output.writeByte(0xab);
        } finally {
            writer.dispose();
        }
        byte[] result = bytes.toByteArray();
        assertThat(Byte.toUnsignedInt(result[result.length - 1])).isEqualTo(0xab);
        assertThat(Arrays.copyOf(result, prefix.length)).containsExactly(prefix);
        assertSensorDng(Arrays.copyOfRange(result, prefix.length, result.length - 1));
    }

    @Test
    void refusesUnlabelledGrayscaleAndPartialSensorGeometry() throws Exception {
        var image = sensorImage();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("DNG").next();
        try (var output = ImageIO.createImageOutputStream(new ByteArrayOutputStream())) {
            writer.setOutput(output);
            assertThatThrownBy(() -> writer.write(image)).isInstanceOf(IIOException.class);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setSourceRegion(new Rectangle(0, 0, 1, 2));
            assertThatThrownBy(() -> writer.write(null, new IIOImage(image, null, metadata()), param))
                    .isInstanceOf(IIOException.class);
            assertThat(output.getStreamPosition()).isZero();

            var partial = image.getSubimage(0, 0, 1, 2);
            assertThatThrownBy(() -> writer.write(null, new IIOImage(partial, null, metadata()), null))
                    .isInstanceOf(IIOException.class);
        } finally {
            writer.dispose();
        }
    }

    private static BufferedImage sensorImage() {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_USHORT_GRAY);
        image.getRaster().setSamples(0, 0, 2, 2, 0, SENSOR_VALUES);
        return image;
    }

    private static DngImageMetadata metadata() throws IIOException {
        var directory = DngTags.bayerDirectory(2, 2, "Test Camera", new int[]{0, 1, 1, 2}, 16383);
        directory.addTIFFField(DngTags.signedRationals(50721, new int[][]{
                {1, 1}, {0, 1}, {0, 1}, {0, 1}, {1, 1}, {0, 1}, {0, 1}, {0, 1}, {1, 1}}));
        directory.addTIFFField(DngTags.shorts(50778, 21));
        return DngImageMetadata.fromDirectory(directory);
    }

    private static void assertSensorDng(byte[] bytes) throws Exception {
        var reader = ImageIO.getImageReadersByFormatName("TIFF").next();
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            reader.setInput(input);
            var param = (TIFFImageReadParam) reader.getDefaultReadParam();
            param.setReadUnknownTags(true);
            var image = reader.read(0, param);
            TIFFDirectory dir = TIFFDirectory.createFromMetadata(reader.getImageMetadata(0));
            assertThat(dir.getTIFFField(262).getAsInt(0)).isEqualTo(32803);
            assertThat(dir.getTIFFField(258).getAsInt(0)).isEqualTo(16);
            assertThat(dir.getTIFFField(50717).getAsInt(0)).isEqualTo(16383);
            assertThat(dir.getTIFFField(33422).getAsBytes()).containsExactly(0, 1, 1, 2);
            assertThat(image.getRaster().getSamples(0, 0, 2, 2, 0, (int[]) null)).containsExactly(SENSOR_VALUES);
        } finally {
            reader.dispose();
        }
    }
}
