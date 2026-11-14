package com.photographer.focusstack.dng.imageio;

import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.imageio.CanonCr3ImageReadParam;
import java.awt.image.Raster;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.imageio.plugins.tiff.TIFFDirectory;
import javax.imageio.stream.FileImageInputStream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class Cr3DngRoundTripIT {
    @TempDir Path temp;

    @ParameterizedTest
    @ValueSource(strings = {"sample.cr3", "eosr-iso-low.cr3"})
    void preservesEveryNativeSensorCode(String fixture) throws Exception {
        Path source = Path.of(getClass().getResource("/samples/" + fixture).toURI());
        Path output = temp.resolve("sensor.dng");
        var cr3 = ImageIO.getImageReadersByFormatName("cr3").next();
        try (var input = new FileImageInputStream(source.toFile())) {
            cr3.setInput(input);
            var params = new CanonCr3ImageReadParam();
            params.setDecodeOptions(Cr3DecodeOptions.rawSensor());
            var sensor = cr3.read(0, params);
            assertTrue(ImageIO.write(sensor, "DNG", output.toFile()));
            var restored = ImageIO.read(output.toFile());
            assertNotNull(restored);
            var metadata = assertInstanceOf(DngImageMetadata.class, restored.getProperty(DngImageMetadata.PROPERTY_NAME));
            TIFFDirectory dng = metadata.toDirectory();
            assertEquals(16, dng.getTIFFField(258).getAsInt(0));
            assertEquals(32803, dng.getTIFFField(262).getAsInt(0));
            assertTrue(dng.containsTIFFField(50714), "BlackLevel");
            assertTrue(dng.containsTIFFField(50717), "WhiteLevel");
            assertTrue(dng.containsTIFFField(50721), "ColorMatrix1");
            assertTrue(dng.containsTIFFField(50829), "ActiveArea");
            assertMosaicEquals(sensor.getRaster(), restored.getRaster());
        } finally {
            cr3.dispose();
        }
    }

    private static void assertMosaicEquals(Raster expected, Raster actual) {
        assertEquals(expected.getBounds(), actual.getBounds());
        int width = actual.getWidth();
        int[] original = new int[width];
        int[] restored = new int[width];
        for (int y = 0; y < actual.getHeight(); y++) {
            expected.getSamples(0, y, width, 1, 0, original);
            actual.getSamples(0, y, width, 1, 0, restored);
            assertArrayEquals(original, restored, "Sensor row " + y);
        }
    }
}
