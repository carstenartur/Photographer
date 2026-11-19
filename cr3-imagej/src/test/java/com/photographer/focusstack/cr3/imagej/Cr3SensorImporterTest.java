package com.photographer.focusstack.cr3.imagej;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.macro.Interpreter;
import ij.plugin.PlugIn;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class Cr3SensorImporterTest {
    private static final Path SAMPLES = Path.of("../canon-cr3-imageio/src/test/resources/samples");

    @Test
    void importsAFileWithoutExposureCalibration() throws Exception {
        Path fourSites = Path.of("../canon-cr3-core/src/test/resources/com/photographer/focusstack/cr3/decode/four-sites.cr3");
        ImagePlus image = Cr3SensorImporter.open(fourSites);
        assertEquals(8192, image.getPixel(0, 0)[0]);
        assertEquals(8194, image.getPixel(1, 1)[0]);
        assertNull(image.getProp("CR3.ActiveAreaLTRBInclusive"));
        assertNull(image.getProp("CR3.WhiteLevel"));
        assertNull(image.getProp("CR3.BlackLevelRGGB"));
        assertNull(image.getProp("CR3.WhiteBalanceGainRGGB"));
    }

    @Test
    void reportsUnreadableInputs(@TempDir Path dir) throws Exception {
        Path damaged = Files.writeString(dir.resolve("damaged.cr3"), "This file contains no CR3 data.");
        assertThrows(IOException.class, () -> Cr3SensorImporter.open(damaged));
        assertThrows(IOException.class, () -> Cr3SensorImporter.open(dir.resolve("absent.cr3")));
        IJ.redirectErrorMessages();
        new Cr3SensorImporter().run(damaged.toString());
        assertTrue(IJ.getErrorMessage().contains(damaged.toString()));
    }

    @Test
    void registeredPluginImportsEveryNativeSampleAndItsCalibration() throws Exception {
        String className;
        try (InputStream config = getClass().getResourceAsStream("/plugins.config")) {
            assertNotNull(config, "ImageJ menu configuration");
            String registration = "File>Import, \"CR3 Sensor Data...\", ";
            className = new String(config.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> line.startsWith(registration))
                    .map(line -> line.substring(registration.length())).findFirst().orElseThrow();
        }

        boolean previousMode = Interpreter.batchMode;
        ImagePlus previousImage = WindowManager.getTempCurrentImage();
        ImagePlus image = null;
        try {
            Interpreter.batchMode = true;
            assertInstanceOf(PlugIn.class, IJ.runPlugIn(className, SAMPLES.resolve("sample.cr3").toString()));
            image = WindowManager.getCurrentImage();
            assertNotNull(image);
            assertEquals("sample.cr3", image.getTitle());
            assertEquals(16, image.getBitDepth());
            assertEquals(ImagePlus.GRAY16, image.getType());
            assertEquals(1, image.getStackSize());
            Raster reference = ImageIO.read(SAMPLES.resolve("sample.reference.tiff").toFile()).getRaster();
            assertEquals(DataBuffer.TYPE_USHORT, reference.getTransferType());
            assertEquals(1, reference.getNumBands());
            assertEquals(reference.getWidth(), image.getWidth());
            assertEquals(reference.getHeight(), image.getHeight());
            int maxDifference = 0;
            int[] row = new int[image.getWidth()];
            for (int y = 0; y < image.getHeight(); y++) {
                reference.getSamples(0, y, row.length, 1, 0, row);
                for (int x = 0; x < row.length; x++) {
                    maxDifference = Math.max(maxDifference, Math.abs(row[x] - image.getPixel(x, y)[0]));
                }
            }
            assertEquals(0, maxDifference, "maximum absolute difference from LibRaw, in sensor codes");
            assertFalse(image.getCalibration().calibrated());
            assertEquals("RGGB", image.getProp("CR3.CFA"));
            assertEquals("14", image.getProp("CR3.BitDepth"));
            assertEquals("120,28,5615,3695", image.getProp("CR3.ActiveAreaLTRBInclusive"));
            assertEquals("2048,2048,2048,2048", image.getProp("CR3.BlackLevelRGGB"));
            assertEquals("2533,1024,1024,1827", image.getProp("CR3.WhiteBalanceGainRGGB"));
            assertEquals("14335", image.getProp("CR3.WhiteLevel"));
        } finally {
            if (image != null) {
                Interpreter.removeBatchModeImage(image);
                image.flush();
            }
            WindowManager.setTempCurrentImage(previousImage);
            Interpreter.batchMode = previousMode;
        }
    }
}
