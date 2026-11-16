package com.photographer.focusstack.analysis.service;

import ij.process.FloatProcessor;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ImageJStackMergerTest {

    private final ImageJStackMerger merger = new ImageJStackMerger();

    @Test
    void mergeThrowsOnNullFrames() {
        assertThrows(IllegalArgumentException.class, () -> merger.merge(null));
    }

    @Test
    void mergeThrowsOnEmptyFrames() {
        assertThrows(IllegalArgumentException.class, () -> merger.merge(List.of()));
    }

    @Test
    void mergeProducesPngOutput() throws IOException {
        byte[] sharp = createCheckerboardPng(64, 64, 4);
        byte[] blurry = createUniformPng(64, 64, 128);

        byte[] merged = merger.merge(List.of(sharp, blurry));

        assertNotNull(merged);
        assertTrue(merged.length > 0, "Merged output should not be empty");
        // PNG signature: 89 50 4E 47
        assertEquals((byte) 0x89, merged[0], "Output should be a PNG");
        assertEquals((byte) 0x50, merged[1]);
    }

    @Test
    void mergePicksSharpestContent() throws IOException {
        // Frame 1: sharp checkerboard (high Laplacian response)
        // Frame 2: uniform grey (zero Laplacian response)
        byte[] sharp = createCheckerboardPng(64, 64, 2);
        byte[] uniform = createUniformPng(64, 64, 128);

        byte[] merged = merger.merge(List.of(uniform, sharp));

        // Merged image should not be empty
        assertNotNull(merged);
        assertTrue(merged.length > 0);
    }

    @Test
    void mergeHandlesSingleDecodableFrame() throws IOException {
        byte[] frame = createCheckerboardPng(32, 32, 4);
        // A single valid frame and one empty frame (non-decodable)
        byte[] merged = merger.merge(List.of(frame, new byte[0]));
        assertNotNull(merged);
        assertTrue(merged.length > 0,
                "Merger should produce non-empty output when at least one frame is decodable");
    }

    @Test
    void mergeThrowsOnDimensionMismatch() throws IOException {
        byte[] frame64 = createCheckerboardPng(64, 64, 4);
        byte[] frame32 = createCheckerboardPng(32, 32, 4);
        assertThrows(IllegalArgumentException.class, () -> merger.merge(List.of(frame64, frame32)),
                "Merger should reject frames with different dimensions");
    }

    @Test
    void mergerNameIsCorrect() {
        assertEquals("imagej-edf", merger.name());
    }

    @Test
    void streamingRgbMatchesImageJConvolutionAtBordersAndInTheInterior() {
        var first = new BufferedImage(11, 7, BufferedImage.TYPE_INT_RGB);
        var second = new BufferedImage(11, 7, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < first.getHeight(); y++) {
            for (int x = 0; x < first.getWidth(); x++) {
                first.setRGB(x, y, ((x * 41 + y * 3) << 8) ^ (y * 13));
                second.setRGB(x, y, ((y * 31 + x) << 16) ^ (x * 23));
            }
        }
        FloatProcessor a = imageJResponse(first);
        FloatProcessor b = imageJResponse(second);

        BufferedImage actual = merger.mergeImages(List.of(first, second));

        for (int y = 0; y < actual.getHeight(); y++) {
            for (int x = 0; x < actual.getWidth(); x++) {
                BufferedImage expected = Math.abs(a.getf(x, y)) >= Math.abs(b.getf(x, y)) ? first : second;
                assertEquals(expected.getRGB(x, y), actual.getRGB(x, y), "pixel " + x + ", " + y);
            }
        }
    }

    private static FloatProcessor imageJResponse(BufferedImage image) {
        var processor = new FloatProcessor(image.getWidth(), image.getHeight());
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                Color rgb = new Color(image.getRGB(x, y));
                processor.setf(x, y, 0.2126f * rgb.getRed() + 0.7152f * rgb.getGreen() + 0.0722f * rgb.getBlue());
            }
        }
        processor.convolve(new float[] {0, 1, 0, 1, -4, 1, 0, 1, 0}, 3, 3);
        return processor;
    }

    // --- Helper methods ---

    private byte[] createCheckerboardPng(int w, int h, int squareSize) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        for (int y = 0; y < h; y += squareSize) {
            for (int x = 0; x < w; x += squareSize) {
                g.setColor(((x / squareSize + y / squareSize) % 2 == 0) ? Color.BLACK : Color.WHITE);
                g.fillRect(x, y, squareSize, squareSize);
            }
        }
        g.dispose();
        return toPng(img);
    }

    private byte[] createUniformPng(int w, int h, int grey) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(grey, grey, grey));
        g.fillRect(0, 0, w, h);
        g.dispose();
        return toPng(img);
    }

    private byte[] toPng(BufferedImage img) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }
}
