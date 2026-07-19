package com.photographer.focusstack.cr3.processing;

import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BlackLevelProcessorTest {

    private static Cr3RawImage createTestImage(int width, int height, int fillValue) {
        int[] data = new int[width * height];
        java.util.Arrays.fill(data, fillValue);
        return new Cr3RawImage(width, height, data, 14, Cr3RawImage.BayerPattern.RGGB);
    }

    @Nested
    class DefaultBlackLevel {
        @Test
        void subtractsDefaultBlackLevel() {
            Cr3RawImage img = createTestImage(4, 4, 1024);
            BlackLevelProcessor.process(img);
            // 1024 - 512 = 512
            assertEquals(512, img.getPixel(0, 0));
            assertEquals(512, img.getPixel(3, 3));
        }

        @Test
        void clampsToZero() {
            Cr3RawImage img = createTestImage(4, 4, 100);
            BlackLevelProcessor.process(img);
            // 100 - 512 clamped to 0
            assertEquals(0, img.getPixel(0, 0));
        }
    }

    @Nested
    class CustomBlackLevel {
        @Test
        void subtractsCustomBlackLevel() {
            Cr3RawImage img = createTestImage(4, 4, 2000);
            BlackLevelProcessor.process(img, 256);
            assertEquals(1744, img.getPixel(0, 0));
        }

        @Test
        void zeroBlackLevelLeavesDataUnchanged() {
            Cr3RawImage img = createTestImage(4, 4, 5000);
            BlackLevelProcessor.process(img, 0);
            assertEquals(5000, img.getPixel(0, 0));
        }

        @Test
        void allPixelsProcessed() {
            int w = 6, h = 4;
            Cr3RawImage img = createTestImage(w, h, 1000);
            BlackLevelProcessor.process(img, 300);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    assertEquals(700, img.getPixel(x, y));
                }
            }
        }
    }
}
