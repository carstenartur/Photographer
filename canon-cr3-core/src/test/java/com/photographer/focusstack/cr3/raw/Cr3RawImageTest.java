package com.photographer.focusstack.cr3.raw;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Cr3RawImageTest {

    @Nested
    class Construction {
        @Test
        void validConstruction() {
            int[] data = new int[100];
            Cr3RawImage img = new Cr3RawImage(10, 10, data, 14, Cr3RawImage.BayerPattern.RGGB);
            assertEquals(10, img.width());
            assertEquals(10, img.height());
            assertEquals(14, img.bitDepth());
            assertEquals(16383, img.maxValue());
            assertEquals(Cr3RawImage.BayerPattern.RGGB, img.bayerPattern());
        }

        @Test
        void rejectsMismatchedDataLength() {
            int[] data = new int[50]; // should be 100 for 10x10
            assertThrows(IllegalArgumentException.class,
                    () -> new Cr3RawImage(10, 10, data, 14, Cr3RawImage.BayerPattern.RGGB));
        }
    }

    @Nested
    class PixelAccess {
        @Test
        void getPixelReturnsCorrectValue() {
            int[] data = new int[4];
            data[0] = 100; data[1] = 200; data[2] = 300; data[3] = 400;
            Cr3RawImage img = new Cr3RawImage(2, 2, data, 14, Cr3RawImage.BayerPattern.RGGB);
            assertEquals(100, img.getPixel(0, 0));
            assertEquals(200, img.getPixel(1, 0));
            assertEquals(300, img.getPixel(0, 1));
            assertEquals(400, img.getPixel(1, 1));
        }

        @Test
        void setPixelUpdatesValue() {
            int[] data = new int[4];
            Cr3RawImage img = new Cr3RawImage(2, 2, data, 14, Cr3RawImage.BayerPattern.RGGB);
            img.setPixel(1, 0, 999);
            assertEquals(999, img.getPixel(1, 0));
        }

        @Test
        void getPixelNegativeXThrows() {
            Cr3RawImage img = new Cr3RawImage(2, 2, new int[4], 14, Cr3RawImage.BayerPattern.RGGB);
            assertThrows(IndexOutOfBoundsException.class, () -> img.getPixel(-1, 0));
        }

        @Test
        void getPixelNegativeYThrows() {
            Cr3RawImage img = new Cr3RawImage(2, 2, new int[4], 14, Cr3RawImage.BayerPattern.RGGB);
            assertThrows(IndexOutOfBoundsException.class, () -> img.getPixel(0, -1));
        }

        @Test
        void getPixelBeyondWidthThrows() {
            Cr3RawImage img = new Cr3RawImage(2, 2, new int[4], 14, Cr3RawImage.BayerPattern.RGGB);
            assertThrows(IndexOutOfBoundsException.class, () -> img.getPixel(2, 0));
        }

        @Test
        void getPixelBeyondHeightThrows() {
            Cr3RawImage img = new Cr3RawImage(2, 2, new int[4], 14, Cr3RawImage.BayerPattern.RGGB);
            assertThrows(IndexOutOfBoundsException.class, () -> img.getPixel(0, 2));
        }

        @Test
        void setPixelBeyondBoundsThrows() {
            Cr3RawImage img = new Cr3RawImage(2, 2, new int[4], 14, Cr3RawImage.BayerPattern.RGGB);
            assertThrows(IndexOutOfBoundsException.class, () -> img.setPixel(2, 2, 0));
        }
    }

    @Nested
    class MaxValue {
        @Test
        void maxValueFor14Bit() {
            Cr3RawImage img = new Cr3RawImage(1, 1, new int[1], 14, Cr3RawImage.BayerPattern.RGGB);
            assertEquals(16383, img.maxValue());
        }

        @Test
        void maxValueFor12Bit() {
            Cr3RawImage img = new Cr3RawImage(1, 1, new int[1], 12, Cr3RawImage.BayerPattern.RGGB);
            assertEquals(4095, img.maxValue());
        }

        @Test
        void maxValueFor16Bit() {
            Cr3RawImage img = new Cr3RawImage(1, 1, new int[1], 16, Cr3RawImage.BayerPattern.RGGB);
            assertEquals(65535, img.maxValue());
        }

        @Test
        void maxValueFor8Bit() {
            Cr3RawImage img = new Cr3RawImage(1, 1, new int[1], 8, Cr3RawImage.BayerPattern.RGGB);
            assertEquals(255, img.maxValue());
        }
    }

    @Nested
    class AllBayerPatterns {
        @Test
        void allPatternsExist() {
            assertEquals(4, Cr3RawImage.BayerPattern.values().length);
        }

        @Test
        void toStringIncludesPattern() {
            Cr3RawImage img = new Cr3RawImage(4, 4, new int[16], 14, Cr3RawImage.BayerPattern.BGGR);
            assertTrue(img.toString().contains("BGGR"));
        }
    }
}
