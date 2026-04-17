package com.photographer.focusstack.cr3.processing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ExifOrientationTransformer}.
 */
class ExifOrientationTransformerTest {

    @Test
    @DisplayName("Orientation 1 (normal) returns original image unchanged")
    void orientation1() {
        BufferedImage src = createTestImage(4, 3);
        BufferedImage result = ExifOrientationTransformer.apply(src, 1);
        assertThat(result).isSameAs(src);
    }

    @Test
    @DisplayName("Orientation 0 (invalid) returns original image unchanged")
    void orientation0() {
        BufferedImage src = createTestImage(4, 3);
        BufferedImage result = ExifOrientationTransformer.apply(src, 0);
        assertThat(result).isSameAs(src);
    }

    @Test
    @DisplayName("Orientation 2 (flip horizontal) mirrors left-right")
    void orientation2FlipH() {
        BufferedImage src = createTestImage(4, 2);
        // Set known pixel at (0,0)
        src.setRGB(0, 0, 0xFF0000);  // Red at top-left
        src.setRGB(3, 0, 0x00FF00);  // Green at top-right

        BufferedImage result = ExifOrientationTransformer.apply(src, 2);

        assertThat(result.getWidth()).isEqualTo(4);
        assertThat(result.getHeight()).isEqualTo(2);
        assertThat(result.getRGB(3, 0) & 0xFFFFFF).isEqualTo(0xFF0000);  // Red now at right
        assertThat(result.getRGB(0, 0) & 0xFFFFFF).isEqualTo(0x00FF00);  // Green now at left
    }

    @Test
    @DisplayName("Orientation 3 (rotate 180) flips both axes")
    void orientation3Rotate180() {
        BufferedImage src = createTestImage(4, 3);
        src.setRGB(0, 0, 0xFF0000);  // Red at top-left

        BufferedImage result = ExifOrientationTransformer.apply(src, 3);

        assertThat(result.getWidth()).isEqualTo(4);
        assertThat(result.getHeight()).isEqualTo(3);
        assertThat(result.getRGB(3, 2) & 0xFFFFFF).isEqualTo(0xFF0000);  // Red at bottom-right
    }

    @Test
    @DisplayName("Orientation 6 (rotate 90 CW) swaps width/height")
    void orientation6Rotate90CW() {
        BufferedImage src = createTestImage(4, 3);
        src.setRGB(0, 0, 0xFF0000);  // Red at top-left

        BufferedImage result = ExifOrientationTransformer.apply(src, 6);

        assertThat(result.getWidth()).isEqualTo(3);
        assertThat(result.getHeight()).isEqualTo(4);
        // After 90° CW rotation: top-left (0,0) → (h-1-0, 0) = (2, 0)
        assertThat(result.getRGB(2, 0) & 0xFFFFFF).isEqualTo(0xFF0000);
    }

    @Test
    @DisplayName("Orientation 8 (rotate 270 CW) swaps width/height")
    void orientation8Rotate270CW() {
        BufferedImage src = createTestImage(4, 3);
        src.setRGB(0, 0, 0xFF0000);  // Red at top-left

        BufferedImage result = ExifOrientationTransformer.apply(src, 8);

        assertThat(result.getWidth()).isEqualTo(3);
        assertThat(result.getHeight()).isEqualTo(4);
        // After 270° CW (= 90° CCW): (0,0) → (0, w-1-0) = (0, 3)
        assertThat(result.getRGB(0, 3) & 0xFFFFFF).isEqualTo(0xFF0000);
    }

    @ParameterizedTest
    @CsvSource({
            "1, 100, 200, 100, 200",
            "2, 100, 200, 100, 200",
            "3, 100, 200, 100, 200",
            "4, 100, 200, 100, 200",
            "5, 100, 200, 200, 100",
            "6, 100, 200, 200, 100",
            "7, 100, 200, 200, 100",
            "8, 100, 200, 200, 100"
    })
    @DisplayName("outputDimensions correctly swaps for orientations 5-8")
    void outputDimensions(int orientation, int inW, int inH, int outW, int outH) {
        int[] dims = ExifOrientationTransformer.outputDimensions(inW, inH, orientation);
        assertThat(dims[0]).isEqualTo(outW);
        assertThat(dims[1]).isEqualTo(outH);
    }

    /**
     * Creates a test image with known dimensions.
     */
    private static BufferedImage createTestImage(int width, int height) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    }
}
