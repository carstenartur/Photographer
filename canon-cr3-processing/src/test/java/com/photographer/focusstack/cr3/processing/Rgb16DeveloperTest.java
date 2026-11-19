package com.photographer.focusstack.cr3.processing;

import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import com.photographer.focusstack.cr3.raw.Cr3RawImage.BayerPattern;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferUShort;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Rgb16DeveloperTest {
    @Test
    void bilinearEdgesUseOnlyExistingNeighborsForEachCfaLayout() {
        int[] sites = {100, 201, 301, 400};
        // Each row is the RGB result at the top-left site for one Bayer layout.
        assertCorner(BayerPattern.RGGB, sites, new int[]{100, 251, 400});
        assertCorner(BayerPattern.BGGR, sites, new int[]{400, 251, 100});
        assertCorner(BayerPattern.GRBG, sites, new int[]{201, 100, 301});
        assertCorner(BayerPattern.GBRG, sites, new int[]{301, 100, 201});
    }

    private static void assertCorner(BayerPattern pattern, int[] sites, int[] expected) {
        var image = new Cr3RawImage(2, 2, sites, 16, pattern);
        var rgb = linear(image, new Rectangle(2, 2), 1, 1);
        assertArrayEquals(expected, rgb.getRaster().getPixel(0, 0, (int[]) null), pattern.name());
    }

    @Test
    void onePixelAndSingleRowUseZeroForMissingColorSites() {
        var blue = new Cr3RawImage(1, 1, new int[]{1}, 16, BayerPattern.BGGR);
        var srgb = BayerDemosaicer.develop16(blue, null, 0, 0, null, 65535, null,
                new Rectangle(1, 1), 1, true, 1);
        assertArrayEquals(new int[]{0, 0, 13}, srgb.getRaster().getPixel(0, 0, (int[]) null));

        var row = new Cr3RawImage(3, 1, new int[]{13, 31, 23}, 16, BayerPattern.GRBG);
        assertArrayEquals(new int[]{31, 13, 0, 31, 18, 0, 31, 23, 0},
                linear(row, new Rectangle(3, 1), 1, 1).getRaster().getPixels(0, 0, 3, 1, (int[]) null));
    }

    @Test
    void placesDistinctCropSamplesInEveryExifOrientation() {
        int width = 5;
        int height = 4;
        int[] sites = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                sites[y * width + x] = 1000 + 31 * x + 127 * y;
            }
        }
        // A linear ramp interpolates exactly at interior sites, in every channel.
        int[] values = {1158, 1189, 1220, 1285, 1316, 1347};
        int[][] order = {
                {0, 1, 2, 3, 4, 5}, {2, 1, 0, 5, 4, 3},
                {5, 4, 3, 2, 1, 0}, {3, 4, 5, 0, 1, 2},
                {0, 3, 1, 4, 2, 5}, {3, 0, 4, 1, 5, 2},
                {5, 2, 4, 1, 3, 0}, {2, 5, 1, 4, 0, 3}
        };
        var bayer = new Cr3RawImage(width, height, sites, 16, BayerPattern.RGGB);
        for (int orientation = 1; orientation <= 8; orientation++) {
            var output = linear(bayer, new Rectangle(1, 1, 3, 2), orientation, 1);
            assertEquals(orientation <= 4 ? 3 : 2, output.getWidth());
            assertEquals(orientation <= 4 ? 2 : 3, output.getHeight());
            for (int i = 0; i < values.length; i++) {
                int value = values[order[orientation - 1][i]];
                assertArrayEquals(new int[]{value, value, value}, output.getRaster()
                        .getPixel(i % output.getWidth(), i / output.getWidth(), (int[]) null),
                        "orientation " + orientation + ", pixel " + i);
            }
        }
    }

    @Test
    void preservesNativeGradationsAndMatchesParallelStripesThroughRotation() {
        int width = 1025;
        int height = 769;
        int[] sites = new int[width * height];
        for (int i = 0; i < sites.length; i++) {
            sites[i] = (i * 31) & 16383;
        }
        var bayer = new Cr3RawImage(width, height, sites, 14, BayerPattern.RGGB);
        var region = new Rectangle(3, 2, width - 5, height - 5);
        var serial = linear(bayer, region, 7, 1);
        var parallel = linear(bayer, region, 7, 4);
        assertEquals(region.height, serial.getWidth());
        assertEquals(region.width, serial.getHeight());
        for (int band = 0; band < 3; band++) {
            var samples = serial.getRaster().getSamples(0, 0, serial.getWidth(), serial.getHeight(), band, (int[]) null);
            assertTrue(Arrays.stream(samples).distinct().count() > 256);
            assertTrue(Arrays.stream(samples).anyMatch(value -> value % 257 != 0));
            var a = (DataBufferUShort) serial.getRaster().getDataBuffer();
            var b = (DataBufferUShort) parallel.getRaster().getDataBuffer();
            assertArrayEquals(a.getData(band), b.getData(band));
        }
        var normal = linear(bayer, new Rectangle(width, height), 1, 1);
        // Transverse orientation maps this crop's top left to the output's bottom right.
        assertArrayEquals(normal.getRaster().getPixel(region.x, region.y, (int[]) null),
                serial.getRaster().getPixel(serial.getWidth() - 1, serial.getHeight() - 1, (int[]) null));
    }

    private static BufferedImage linear(Cr3RawImage image, Rectangle region, int orientation, int workers) {
        return BayerDemosaicer.develop16(image, null, 0, 0, null, image.maxValue(), null,
                region, orientation, false, workers);
    }
}
