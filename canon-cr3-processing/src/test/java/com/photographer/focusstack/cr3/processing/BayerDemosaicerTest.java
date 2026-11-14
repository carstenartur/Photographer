package com.photographer.focusstack.cr3.processing;

import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class BayerDemosaicerTest {

    /**
     * Creates a synthetic Bayer image with known R/G/B values per channel.
     * For RGGB: R=8000, Gr=4000, Gb=4000, B=2000 (14-bit).
     */
    private static Cr3RawImage createSyntheticRGGBImage(int width, int height) {
        int[] data = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean evenRow = (y % 2) == 0;
                boolean evenCol = (x % 2) == 0;
                if (evenRow && evenCol) data[y * width + x] = 8000;       // R
                else if (evenRow) data[y * width + x] = 4000;              // Gr
                else if (evenCol) data[y * width + x] = 4000;              // Gb
                else data[y * width + x] = 2000;                           // B
            }
        }
        return new Cr3RawImage(width, height, data, 14, Cr3RawImage.BayerPattern.RGGB);
    }

    @Nested
    class DemosaicRGGB {
        @Test
        void producesCorrectDimensions() {
            Cr3RawImage raw = createSyntheticRGGBImage(100, 80);
            BufferedImage result = BayerDemosaicer.demosaic(raw);
            assertEquals(100, result.getWidth());
            assertEquals(80, result.getHeight());
            assertEquals(BufferedImage.TYPE_INT_RGB, result.getType());
        }

        @Test
        void producesNonZeroPixels() {
            Cr3RawImage raw = createSyntheticRGGBImage(10, 10);
            BufferedImage result = BayerDemosaicer.demosaic(raw);
            int rgb = result.getRGB(5, 5);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            assertTrue(r > 0, "Red channel should be non-zero");
            assertTrue(g > 0, "Green channel should be non-zero");
            assertTrue(b > 0, "Blue channel should be non-zero");
        }

        @Test
        void redChannelDominatesForRedPixel() {
            Cr3RawImage raw = createSyntheticRGGBImage(10, 10);
            BufferedImage result = BayerDemosaicer.demosaic(raw);
            // Center red pixel (even row, even col)
            int rgb = result.getRGB(4, 4);
            int r = (rgb >> 16) & 0xFF;
            int b = rgb & 0xFF;
            assertTrue(r > b, "Red should exceed blue at RGGB red pixel position");
        }
    }

    @Nested
    class DemosaicAllPatterns {
        @Test
        void demosaicBGGR() {
            int[] data = new int[16];
            java.util.Arrays.fill(data, 4000);
            Cr3RawImage raw = new Cr3RawImage(4, 4, data, 14, Cr3RawImage.BayerPattern.BGGR);
            BufferedImage result = BayerDemosaicer.demosaic(raw);
            assertNotNull(result);
            assertEquals(4, result.getWidth());
            assertEquals(4, result.getHeight());
        }

        @Test
        void demosaicGRBG() {
            int[] data = new int[16];
            java.util.Arrays.fill(data, 4000);
            Cr3RawImage raw = new Cr3RawImage(4, 4, data, 14, Cr3RawImage.BayerPattern.GRBG);
            BufferedImage result = BayerDemosaicer.demosaic(raw);
            assertNotNull(result);
            assertEquals(4, result.getWidth());
        }

        @Test
        void demosaicGBRG() {
            int[] data = new int[16];
            java.util.Arrays.fill(data, 4000);
            Cr3RawImage raw = new Cr3RawImage(4, 4, data, 14, Cr3RawImage.BayerPattern.GBRG);
            BufferedImage result = BayerDemosaicer.demosaic(raw);
            assertNotNull(result);
            assertEquals(4, result.getWidth());
        }

        @Test
        void uniformInputProducesUniformOutput() {
            // All pixels = 8192 (midpoint of 14-bit) should produce grey output
            int[] data = new int[16];
            java.util.Arrays.fill(data, 8192);
            Cr3RawImage raw = new Cr3RawImage(4, 4, data, 14, Cr3RawImage.BayerPattern.RGGB);
            BufferedImage result = BayerDemosaicer.demosaic(raw);

            int rgb = result.getRGB(2, 2);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            // Uniform input → near-uniform output (within ±1 for rounding)
            assertTrue(Math.abs(r - g) <= 1 && Math.abs(g - b) <= 1,
                    "Uniform input should produce near-grey output, got r=%d g=%d b=%d".formatted(r, g, b));
        }
    }

    @Nested
    class EdgeCases {
        @Test
        void workerStripesMatchSeparateOutputAcrossSeamsAndImageBorders() {
            int width = 1027;
            int height = 773;
            int[] samples = new Random(342).ints(width * height, -320, 16384).toArray();
            for (var pattern : Cr3RawImage.BayerPattern.values()) {
                var raw = new Cr3RawImage(width, height, samples, 14, pattern);
                BufferedImage reference = BayerDemosaicer.demosaic(raw, 14271);
                int[] expected = ((DataBufferInt) reference.getRaster().getDataBuffer()).getData();
                int referenceHash = switch (pattern) {
                    case RGGB -> -893752329;
                    case BGGR -> 112064845;
                    case GRBG -> 2059201541;
                    case GBRG -> 583535712;
                };
                assertEquals(referenceHash, Arrays.hashCode(expected));
                for (int workers : new int[]{0, 2, 3}) {
                    var input = new Cr3RawImage(width, height, samples.clone(), 14, pattern);
                    var result = BayerDemosaicer.demosaicInPlace(input, 14271, workers);
                    assertSame(input.data(), ((DataBufferInt) result.getRaster().getDataBuffer()).getData());
                    assertArrayEquals(expected, input.data());
                }
            }
        }

        @Test
        void inPlaceConsumesSuppliedBufferAndMatchesSeparateOutput() {
            Random random = new Random(802);
            for (var pattern : Cr3RawImage.BayerPattern.values()) {
                for (int width : new int[]{1, 2, 7, 8}) {
                    for (int height : new int[]{1, 2, 5, 6}) {
                        int[] samples = random.ints(width * height, -400, 16384).toArray();
                        int[] original = samples.clone();
                        var raw = new Cr3RawImage(width, height, samples, 14, pattern);
                        BufferedImage separate = BayerDemosaicer.demosaic(raw, 13000);
                        assertArrayEquals(original, samples);

                        BufferedImage inPlace = BayerDemosaicer.demosaicInPlace(raw, 13000);

                        assertEquals(BufferedImage.TYPE_INT_RGB, inPlace.getType());
                        assertSame(samples, ((DataBufferInt) inPlace.getRaster().getDataBuffer()).getData());
                        assertArrayEquals(separate.getRGB(0, 0, width, height, null, 0, width),
                                inPlace.getRGB(0, 0, width, height, null, 0, width));
                    }
                }
            }
        }

        @Test
        void minimumSize2x2() {
            int[] data = {8000, 4000, 4000, 2000};
            Cr3RawImage raw = new Cr3RawImage(2, 2, data, 14, Cr3RawImage.BayerPattern.RGGB);
            BufferedImage result = BayerDemosaicer.demosaic(raw);
            assertEquals(2, result.getWidth());
            assertEquals(2, result.getHeight());
        }

        @Test
        void demosaicSnapshotIsByteIdenticalForUniformInput() {
            int[] data = {8192, 8192, 8192, 8192};
            Cr3RawImage raw = new Cr3RawImage(2, 2, data, 14, Cr3RawImage.BayerPattern.RGGB);
            BufferedImage result = BayerDemosaicer.demosaic(raw);

            int[] actual = {
                    result.getRGB(0, 0) & 0xFFFFFF,
                    result.getRGB(1, 0) & 0xFFFFFF,
                    result.getRGB(0, 1) & 0xFFFFFF,
                    result.getRGB(1, 1) & 0xFFFFFF
            };
            int[] expected = {0x7F7F7F, 0x7F7F7F, 0x7F7F7F, 0x7F7F7F};
            assertArrayEquals(expected, actual);
        }

        @Test
        void demosaicNormalized() {
            Cr3RawImage raw = createSyntheticRGGBImage(10, 10);
            BufferedImage result = BayerDemosaicer.demosaicNormalized(raw);
            assertNotNull(result);
            assertEquals(10, result.getWidth());
            assertEquals(10, result.getHeight());
        }

        @SuppressWarnings("deprecation")
        @Test
        void demosaic16bitDeprecatedDelegates() {
            Cr3RawImage raw = createSyntheticRGGBImage(10, 10);
            BufferedImage result = BayerDemosaicer.demosaic16bit(raw);
            assertNotNull(result);
            assertEquals(10, result.getWidth());
            assertEquals(10, result.getHeight());
        }
    }
}
