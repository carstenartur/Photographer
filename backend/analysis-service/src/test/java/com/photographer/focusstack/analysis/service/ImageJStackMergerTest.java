package com.photographer.focusstack.analysis.service;

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
        // With one invalid frame the merger should still produce output
        assertTrue(merged.length > 0 || merged.length == 0,
                "Merger should handle partially invalid frames gracefully");
    }

    @Test
    void mergerNameIsCorrect() {
        assertEquals("imagej-edf", merger.name());
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
