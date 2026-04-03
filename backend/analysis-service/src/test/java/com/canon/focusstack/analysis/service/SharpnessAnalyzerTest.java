package com.canon.focusstack.analysis.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SharpnessAnalyzerTest {

    private final SharpnessAnalyzer analyzer = new SharpnessAnalyzer();

    @Test
    void uniformImageShouldScoreLow() {
        // 10x10 uniform gray image
        byte[] uniform = new byte[100];
        java.util.Arrays.fill(uniform, (byte) 128);
        double score = analyzer.calculateSharpness(uniform);
        assertEquals(0.0, score, 0.001, "Uniform image should have near-zero sharpness");
    }

    @Test
    void edgeImageShouldScoreHigh() {
        // 10x10 image with strong vertical edge
        byte[] edgeImage = new byte[100];
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 10; x++) {
                edgeImage[y * 10 + x] = x < 5 ? (byte) 0 : (byte) 255;
            }
        }
        double score = analyzer.calculateSharpness(edgeImage);
        assertTrue(score > 0.1, "Edge image should have higher sharpness, got: " + score);
    }

    @Test
    void nullImageReturnsZero() {
        assertEquals(0.0, analyzer.calculateSharpness(null));
    }

    @Test
    void tooSmallImageReturnsZero() {
        assertEquals(0.0, analyzer.calculateSharpness(new byte[4]));
    }

    @Test
    void edgeScoresHigherThanUniform() {
        byte[] uniform = new byte[100];
        java.util.Arrays.fill(uniform, (byte) 128);

        byte[] edgeImage = new byte[100];
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 10; x++) {
                edgeImage[y * 10 + x] = x < 5 ? (byte) 0 : (byte) 255;
            }
        }

        assertTrue(analyzer.calculateSharpness(edgeImage) > analyzer.calculateSharpness(uniform));
    }
}
