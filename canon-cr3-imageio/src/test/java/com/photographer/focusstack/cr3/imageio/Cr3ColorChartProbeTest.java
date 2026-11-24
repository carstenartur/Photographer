package com.photographer.focusstack.cr3.imageio;

import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.photographer.focusstack.cr3.imageio.ImageQualityHelper.ReferenceWhite.D50;
import static com.photographer.focusstack.cr3.imageio.ImageQualityHelper.ReferenceWhite.D65;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Cr3ColorChartProbeTest {
    @TempDir Path dir;

    @Test
    void standardSrgbPrimariesAndPublishedCiede2000Example() {
        assertArrayEquals(new double[]{53.2408, 80.0925, 67.2032},
                ImageQualityHelper.linearSrgbToLab(1, 0, 0, D65), 0.001);
        assertArrayEquals(new double[]{54.2917, 80.8124, 69.8851},
                ImageQualityHelper.linearSrgbToLab(1, 0, 0, D50), 0.001);
        assertArrayEquals(new double[]{100, 0, 0}, ImageQualityHelper.linearSrgbToLab(1, 1, 1, D50), 0.001);
        // The first Sharma, Wu and Dalal supplementary test pair.
        assertEquals(2.0425, ImageQualityHelper.deltaE2000(new double[]{50, 2.6772, -79.7751},
                new double[]{50, 0, -82.7485}), 0.00005);
    }

    @Test
    void averagesLinearLightAtSixteenBitsWithPatchCoordinatesAndSignedErrors() throws IOException {
        var cm = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB), false, false,
                Transparency.OPAQUE, DataBuffer.TYPE_USHORT);
        var image = new BufferedImage(cm, cm.createCompatibleWritableRaster(4, 2), false, null);
        var raster = image.getRaster();
        raster.setPixel(3, 0, new int[]{65535, 65535, 65535});
        raster.setPixel(0, 1, new int[]{10003, 10003, 10003});
        raster.setPixel(1, 1, new int[]{10000, 10000, 10000});

        var ref = table("ref.tsv", "patch\tL\ta\tb", "half\t70\t0\t0\nfirst\t50\t0\t0\nsecond\t50\t0\t0");
        var roi = table("roi.tsv", "patch\tx\ty\twidth\theight", "second\t1\t1\t1\t1\nhalf\t2\t0\t2\t1\nfirst\t0\t1\t1\t1");
        var output = new ByteArrayOutputStream();
        try (var report = new PrintStream(output, false, StandardCharsets.UTF_8)) {
            Cr3ColorChartProbe.evaluate(image, D65, ref, roi, report);
        }
        var lines = output.toString(StandardCharsets.UTF_8).lines().toList();
        var half = lines.get(2).split("\t");
        assertEquals("half", half[0]);
        assertEquals(76.0693, Double.parseDouble(half[4]), 0.0001);
        assertEquals(6.0693, Double.parseDouble(half[7]), 0.0001);
        assertArrayEquals(new String[]{"2", "1", "1"}, java.util.Arrays.copyOfRange(half, 11, 14));
        assertTrue(Double.parseDouble(lines.get(3).split("\t")[4]) > Double.parseDouble(lines.get(4).split("\t")[4]));
        assertTrue(lines.getLast().startsWith("# patches=3 mean_delta_E00="));
    }

    @Test
    void rejectsAmbiguousRegionsAndInvalidReferences() throws IOException {
        assertThrows(IOException.class, () -> table("dup.tsv", "patch\tL\ta\tb", "a\t0\t0\t0\na\t0\t0\t0"));
        var cm = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB), false, false,
                Transparency.OPAQUE, DataBuffer.TYPE_USHORT);
        var image = new BufferedImage(cm, cm.createCompatibleWritableRaster(1, 1), false, null);
        var ref = table("ref.tsv", "patch\tL\ta\tb", "a\t50\t0\t0");
        var off = table("off.tsv", "patch\tx\ty\twidth\theight", "a\t1\t0\t1\t1");
        var valid = table("valid.tsv", "patch\tx\ty\twidth\theight", "a\t0\t0\t1\t1");
        assertThrows(IllegalArgumentException.class, () -> Cr3ColorChartProbe.evaluate(image, D50, ref, Map.of(), System.out));
        assertThrows(IllegalArgumentException.class, () -> Cr3ColorChartProbe.evaluate(image, D50, ref, off, System.out));
        var bad = table("invalid.tsv", "patch\tL\ta\tb", "a\tNaN\t0\t0");
        assertThrows(IllegalArgumentException.class, () -> Cr3ColorChartProbe.evaluate(image, D50, bad, valid, System.out));
        var rgb8 = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        assertThrows(IllegalArgumentException.class, () -> Cr3ColorChartProbe.evaluate(rgb8, D65, ref, valid, System.out));
    }

    private Map<String, String[]> table(String name, String header, String rows) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, header + "\n" + rows + "\n", StandardCharsets.UTF_8);
        return Cr3ColorChartProbe.readTable(file, header);
    }
}
