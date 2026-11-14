package com.photographer.focusstack.dng.imageio;

import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.imageio.CanonCr3ImageReaderSpi;
import com.photographer.focusstack.cr3.imageio.CanonCr3ImageReadParam;
import com.photographer.focusstack.cr3.imageio.Cr3ImageMetadata;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import javax.imageio.ImageReader;
import javax.imageio.plugins.tiff.TIFFDirectory;
import javax.imageio.stream.FileImageInputStream;
import java.io.File;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Cr3DngMetadataTest {
    @Test
    void g5xIncludesCameraCropWhiteBalanceLevelsAndProfile() throws Exception {
        TIFFDirectory d = directory("sample.cr3");
        assertArrayEquals(new long[]{28, 120, 3696, 5616}, d.getTIFFField(50829).getAsLongs());
        assertArrayEquals(new long[]{12, 12}, d.getTIFFField(50719).getAsLongs());
        assertArrayEquals(new long[]{5472, 3648}, d.getTIFFField(50720).getAsLongs());
        assertArrayEquals(new byte[]{0, 1, 1, 2}, d.getTIFFField(33422).getAsBytes());
        assertEquals(14335, d.getTIFFField(50717).getAsInt(0));
        for (int i = 0; i < 4; i++) assertEquals(2048, d.getTIFFField(50714).getAsInt(i));
        assertEquals(1024.0 / 2533, d.getTIFFField(50728).getAsDouble(0));
        assertEquals(1024.0 / 1827, d.getTIFFField(50728).getAsDouble(2));
        assertEquals(0.831, d.getTIFFField(50722).getAsDouble(0));
        assertEquals(21, d.getTIFFField(50779).getAsInt(0));
    }

    @Test
    void eosRUsesItsOwnCalibrationAndActualIsoDependentWhiteLevel() throws Exception {
        TIFFDirectory d = directory("eosr-iso-high.cr3");
        assertEquals("Canon EOS R", d.getTIFFField(50708).getAsString(0));
        assertArrayEquals(new long[]{46, 144, 4546, 6888}, d.getTIFFField(50829).getAsLongs());
        assertArrayEquals(new long[]{6720, 4480}, d.getTIFFField(50720).getAsLongs());
        assertEquals(14448, d.getTIFFField(50717).getAsInt(0));
        assertEquals(0.5684, d.getTIFFField(50722).getAsDouble(0));
        assertEquals(1024.0 / 1876, d.getTIFFField(50728).getAsDouble(0));
    }

    @Test
    void onlyNativeFullImagesCarryTheSensorCalibrationForImageIOWrite() throws Exception {
        ImageReader reader = new CanonCr3ImageReaderSpi().createReaderInstance();
        try (var input = new FileImageInputStream(sample("sample.cr3"))) {
            reader.setInput(input);
            var param = new CanonCr3ImageReadParam();
            param.setDecodeOptions(Cr3DecodeOptions.rawSensor());
            assertInstanceOf(Cr3ImageMetadata.class, reader.read(0, param).getProperty(DngImageMetadata.PROPERTY_NAME));
            param.setSourceRegion(new Rectangle(120, 28, 4, 4));
            assertSame(Image.UndefinedProperty, reader.read(0, param).getProperty(DngImageMetadata.PROPERTY_NAME));
            param.setSourceRegion(null);
            param.setSourceSubsampling(2, 2, 0, 0);
            assertSame(Image.UndefinedProperty, reader.read(0, param).getProperty(DngImageMetadata.PROPERTY_NAME));
            param.setSourceSubsampling(1, 1, 0, 0);
            param.setDestination(new BufferedImage(4, 4, BufferedImage.TYPE_USHORT_GRAY));
            assertSame(param.getDestination(), reader.read(0, param));
            assertSame(Image.UndefinedProperty, param.getDestination().getProperty(DngImageMetadata.PROPERTY_NAME));
        } finally {
            reader.dispose();
        }
    }

    private static TIFFDirectory directory(String name) throws Exception {
        ImageReader reader = new CanonCr3ImageReaderSpi().createReaderInstance();
        try (var input = new FileImageInputStream(sample(name))) {
            reader.setInput(input);
            return DngImageMetadata.fromCr3((Cr3ImageMetadata) reader.getImageMetadata(0)).toDirectory();
        } finally {
            reader.dispose();
        }
    }

    private static File sample(String name) throws Exception {
        return new File(Cr3DngMetadataTest.class.getResource("/samples/" + name).toURI());
    }
}
