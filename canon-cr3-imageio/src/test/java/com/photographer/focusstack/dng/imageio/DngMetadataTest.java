package com.photographer.focusstack.dng.imageio;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import javax.imageio.IIOException;
import javax.imageio.plugins.tiff.TIFFDirectory;
import org.junit.jupiter.api.Test;

class DngMetadataTest {
    private static TIFFDirectory sensor() {
        TIFFDirectory dir = DngTags.bayerDirectory(4, 3, "Test camera", new int[]{0, 1, 1, 2}, 16383);
        dir.addTIFFField(DngTags.signedRationals(50721, new int[][]{
                {1, 1}, {0, 1}, {0, 1}, {0, 1}, {1, 1}, {0, 1}, {0, 1}, {0, 1}, {1, 1}}));
        dir.addTIFFField(DngTags.shorts(50778, 21));
        return dir;
    }

    @Test
    void retainsSensorCalibrationButNeverCopiesFileOffsets() throws Exception {
        TIFFDirectory dir = sensor();
        dir.addTIFFField(DngTags.longs(273, 1000));
        DngImageMetadata metadata = DngImageMetadata.fromDirectory(dir);
        assertEquals(4, metadata.sensorWidth());
        assertEquals(3, metadata.sensorHeight());
        assertEquals(16383, metadata.toDirectory().getTIFFField(50717).getAsInt(0));
        assertFalse(metadata.toDirectory().containsTIFFField(273));
        dir.getTIFFField(50721).getAsSRational(0)[0] = 12;
        assertEquals(1, metadata.toDirectory().getTIFFField(50721).getAsInt(0));
        dir.getTIFFField(33422).getAsBytes()[2] = 2;
        assertEquals(1, metadata.toDirectory().getTIFFField(33422).getAsInt(2));
    }

    @Test
    void rejectsInvalidGeometryAndMissingColorCalibration() {
        TIFFDirectory dir = sensor();
        dir.addTIFFField(DngTags.longs(50829, 0, 0, 10, 10));
        assertThrows(IIOException.class, () -> DngImageMetadata.fromDirectory(dir));
        dir.removeTIFFField(50829);
        dir.removeTIFFField(50721);
        assertThrows(IIOException.class, () -> DngImageMetadata.fromDirectory(dir));
    }

    @Test
    void acceptsFourBayerPhasesAndRejectsTwoGreenSitesBesideEachOther() throws Exception {
        var dir = sensor();
        for (int[] phase : new int[][]{{0,1,1,2}, {1,0,2,1}, {1,2,0,1}, {2,1,1,0}}) {
            dir.addTIFFField(DngTags.bytes(33422, phase));
            assertEquals(4, DngImageMetadata.fromDirectory(dir).sensorWidth());
        }
        dir.addTIFFField(DngTags.bytes(33422, 1, 1, 0, 2));
        assertThrows(IIOException.class, () -> DngImageMetadata.fromDirectory(dir));
    }

    @Test
    void attachingCalibrationSharesTheSensorBuffer() throws Exception {
        var source = new BufferedImage(4, 3, BufferedImage.TYPE_USHORT_GRAY);
        var metadata = DngImageMetadata.fromDirectory(sensor());
        var image = DngImageMetadata.attach(source, metadata);
        assertSame(source.getRaster().getDataBuffer(), image.getRaster().getDataBuffer());
        assertSame(metadata, image.getProperty(DngImageMetadata.PROPERTY_NAME));
    }
}
