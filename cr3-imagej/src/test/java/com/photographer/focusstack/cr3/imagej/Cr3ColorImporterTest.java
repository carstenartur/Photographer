package com.photographer.focusstack.cr3.imagej;

import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.decode.Cr3Decoder;
import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.macro.Interpreter;
import ij.measure.Calibration;
import ij.plugin.PlugIn;
import ij.process.ShortProcessor;
import java.awt.Color;
import java.awt.Point;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferUShort;
import java.awt.image.Raster;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Cr3ColorImporterTest {
    private static final Path FOUR_SITES = Path.of(
            "../canon-cr3-imageio/src/test/resources/com/photographer/focusstack/cr3/imageio/four-sites.cr3");

    @Test
    void opensEveryDevelopedSampleWithoutShowingAWindowOrApplyingGlobalCalibration() throws Exception {
        var globalOwner = new ImagePlus();
        var previous = globalOwner.getGlobalCalibration();
        var calibration = new Calibration();
        calibration.pixelWidth = 2.5;
        globalOwner.setGlobalCalibration(calibration);
        try (var decoder = new Cr3Decoder()) {
            decoder.setInput(FOUR_SITES);
            var expected = decoder.decode(Cr3DecodeOptions.rawProcessed().bitDepth(Cr3DecodeOptions.BitDepth.SIXTEEN))
                    .getRaster();
            CompositeImage image = Cr3ColorImporter.open(FOUR_SITES);
            assertNull(image.getWindow());
            assertEquals("four-sites.cr3", image.getTitle());
            assertEquals(CompositeImage.COMPOSITE, image.getMode());
            assertArrayEquals(new int[]{2, 2, 3, 1, 1}, image.getDimensions());
            assertEquals(1.0, image.getCalibration().pixelWidth);
            assertEquals(1.0, image.getLocalCalibration().pixelWidth);
            var colors = new Color[]{Color.RED, Color.GREEN, Color.BLUE};
            for (int band = 0; band < 3; band++) {
                var plane = assertInstanceOf(ShortProcessor.class, image.getStack().getProcessor(band + 1));
                var lut = image.getChannelLut(band + 1);
                assertEquals(colors[band].getRGB(), lut.getRGB(255));
                assertEquals(0.0, lut.min);
                assertEquals(65535.0, lut.max);
                for (int y = 0; y < 2; y++) {
                    for (int x = 0; x < 2; x++) {
                        assertEquals(expected.getSample(x, y, band), plane.get(x, y));
                    }
                }
            }
        } finally {
            globalOwner.setGlobalCalibration(previous);
        }
    }

    @Test
    void adoptsContiguousBanksInBandOrderAndCopiesStridedSubimages() {
        var buffer = new DataBufferUShort(6, 3);
        var raster = Raster.createBandedRaster(buffer, 3, 2, 3, new int[]{2, 0, 1}, new int[3], new Point());
        var image = Cr3ColorImporter.toComposite("banks", raster);
        assertSame(buffer.getData(2), image.getStack().getPixels(1));
        assertSame(buffer.getData(0), image.getStack().getPixels(2));
        assertSame(buffer.getData(1), image.getStack().getPixels(3));

        raster.setPixel(2, 1, new int[]{1, 32771, 65534});
        var child = raster.createChild(1, 0, 2, 2, 0, 0, null);
        var crop = Cr3ColorImporter.toComposite("crop", child);
        assertArrayEquals(new short[]{0, 0, 0, 1}, (short[]) crop.getStack().getPixels(1));
        assertArrayEquals(new short[]{0, 0, 0, (short) 32771}, (short[]) crop.getStack().getPixels(2));
        assertArrayEquals(new short[]{0, 0, 0, (short) 65534}, (short[]) crop.getStack().getPixels(3));
        var bytes = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, 2, 2, 3, null);
        assertThrows(IllegalArgumentException.class, () -> Cr3ColorImporter.toComposite("eight", bytes));
    }

    @Test
    void reportsMissingInput() {
        assertThrows(IOException.class, () -> Cr3ColorImporter.open(FOUR_SITES.resolveSibling("absent.cr3")));
    }

    @Test
    void colorMenuImportsAnRgbComposite() throws Exception {
        String registration = "File>Import, \"CR3 Color Image...\", ";
        String className;
        try (var config = getClass().getResourceAsStream("/plugins.config")) {
            assertNotNull(config);
            className = new String(config.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> line.startsWith(registration)).map(line -> line.substring(registration.length()))
                    .findFirst().orElse(null);
        }
        assertNotNull(className, "missing color import menu command");
        boolean previousMode = Interpreter.batchMode;
        ImagePlus previousImage = WindowManager.getTempCurrentImage();
        ImagePlus image = null;
        try {
            Interpreter.batchMode = true;
            assertInstanceOf(PlugIn.class, IJ.runPlugIn(className, FOUR_SITES.toString()));
            image = WindowManager.getCurrentImage();
            assertInstanceOf(CompositeImage.class, image);
            assertEquals(16, image.getBitDepth());
            assertEquals(3, image.getNChannels());
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
