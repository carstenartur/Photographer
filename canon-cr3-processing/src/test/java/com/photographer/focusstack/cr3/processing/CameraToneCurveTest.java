package com.photographer.focusstack.cr3.processing;

import org.junit.jupiter.api.Test;

import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class CameraToneCurveTest {
    private static final String G5 = "Canon PowerShot G5 X Mark II";

    @Test
    void matchesOnlyMeasuredBodiesAndRecordedColorStyles() {
        assertTrue(CameraToneCurve.supports(G5, 0x84));
        assertTrue(CameraToneCurve.supports(G5, 0x85));
        assertTrue(CameraToneCurve.supports("Canon EOS R", 0x81));
        assertTrue(CameraToneCurve.supports("Canon EOS R", 0x83));
        assertTrue(CameraToneCurve.supports("Canon EOS R", 0x87));
        for (String camera : new String[]{null, "", "Canon EOS RP", "Canon EOS R5", "Canon EOS R6"}) {
            assertFalse(CameraToneCurve.supports(camera, 0x87));
        }
        assertFalse(CameraToneCurve.supports(G5, 0x86)); // Monochrome JPEG
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0xff234567);
        assertFalse(CameraToneCurve.apply(image, G5, -1, 0));
        assertEquals(0xff234567, image.getRGB(0, 0));
    }

    @Test
    void ushortRampIsMonotoneNeutralAndKeepsInteriorHighlights() {
        var image = rgb16(256, 256, true);
        int[] pixel = new int[3];
        for (int i = 0; i <= 65535; i++) {
            java.util.Arrays.fill(pixel, i);
            image.getRaster().setPixel(i % 256, i / 256, pixel);
        }
        assertTrue(CameraToneCurve.apply(image, G5, 0x85, 1));
        int previous = 0, distinct = 0;
        for (int i = 0; i <= 65535; i++) {
            image.getRaster().getPixel(i % 256, i / 256, pixel);
            int result = pixel[0];
            assertEquals(result, pixel[1]);
            assertEquals(result, pixel[2]);
            assertTrue(result >= previous);
            assertTrue(i == 65535 || result < 65535);
            assertEquals(reference(i, i), result);
            if (result != previous) distinct++;
            previous = result;
        }
        assertTrue(distinct > 20000);
        assertEquals(65535, previous);
        assertEquals(0, image.getRaster().getSample(0, 0, 0));
    }

    @Test
    void preservesLinearChannelRatiosInTranslated16BitDestination() {
        var parent = rgb16(61, 25, false);
        var image = parent.getSubimage(5, 3, 48, 19);
        var original = new int[image.getWidth() * image.getHeight()][3];
        var random = new Random(56327);
        for (int i = 0; i < original.length; i++) {
            for (int c = 0; c < 3; c++) original[i][c] = random.nextInt(65536);
            image.getRaster().setPixel(i % 48, i / 48, original[i]);
        }
        int[] clipped = {65535, 14322, 1000};
        original[0] = clipped;
        image.getRaster().setPixel(0, 0, clipped);
        assertTrue(CameraToneCurve.apply(image, "Canon EOS R", 0x81, 2));
        for (int i = 0; i < original.length; i++) {
            int[] pixel = original[i];
            int peak = Math.max(pixel[0], Math.max(pixel[1], pixel[2]));
            for (int c = 0; c < 3; c++) {
                assertEquals(reference(pixel[c], peak), image.getRaster().getSample(i % 48, i / 48, c));
            }
        }
        assertEquals(0, parent.getRaster().getSample(3, 4, 0));
    }

    @Test
    void byteSubimageUsesSameHuePreservingCurveAndKeepsClippedPixels() {
        var parent = new BufferedImage(6, 5, BufferedImage.TYPE_INT_RGB);
        parent.setRGB(2, 2, 0xff20708e);
        parent.setRGB(3, 2, 0xffff2317);
        parent.setRGB(1, 2, 0xff124578);
        assertTrue(CameraToneCurve.apply(parent.getSubimage(2, 2, 2, 1), G5, 0x85, 1));
        int peak = 0x8e * 257;
        var pixel = parent.getRaster().getPixel(2, 2, (int[]) null);
        int[] codes = {0x20, 0x70, 0x8e};
        for (int i = 0; i < 3; i++) assertEquals((reference(codes[i] * 257, peak) + 128) / 257, pixel[i]);
        assertEquals(0xffff2317, parent.getRGB(3, 2));
        assertEquals(0xff124578, parent.getRGB(1, 2));
        var linear = new BufferedImage(2, 2, BufferedImage.TYPE_USHORT_GRAY);
        assertThrows(IllegalArgumentException.class, () -> CameraToneCurve.apply(linear, G5, 0x85, 1));
    }

    private static int reference(int channel, int peak) {
        if (peak == 0 || peak == 65535) return channel;
        double p = linear(peak);
        double gain = CameraToneCurve.mapPeak(p) / p;
        return Math.min(65534, (int) Math.round(SrgbConverter.linearToSrgb(linear(channel) * gain) * 65535));
    }

    private static double linear(int sample) {
        double code = sample / 65535.0;
        return code <= .04045 ? code / 12.92 : Math.pow((code + .055) / 1.055, 2.4);
    }

    private static BufferedImage rgb16(int width, int height, boolean banded) {
        var model = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB),
                false, false, Transparency.OPAQUE, DataBuffer.TYPE_USHORT);
        var raster = banded ? Raster.createBandedRaster(DataBuffer.TYPE_USHORT, width, height, 3, null)
                : model.createCompatibleWritableRaster(width, height);
        return new BufferedImage(model, raster, false, null);
    }
}
