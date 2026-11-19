package com.photographer.focusstack.cr3.processing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.ImageTypeSpecifier;
import java.awt.Rectangle;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ExifOrientationTransformer}.
 */
class ExifOrientationTransformerTest {

    @Test
    void preservesSixteenBitSamplesAndTheirColorModelWhenOrientingABandedView() {
        var type = ImageTypeSpecifier.createBanded(ColorSpace.getInstance(ColorSpace.CS_LINEAR_RGB),
                new int[]{0, 1, 2}, new int[3], DataBuffer.TYPE_USHORT, false, false);
        int[][] order = {
                {1, 2, 3, 4, 5, 6}, {3, 2, 1, 6, 5, 4},
                {6, 5, 4, 3, 2, 1}, {4, 5, 6, 1, 2, 3},
                {1, 4, 2, 5, 3, 6}, {4, 1, 5, 2, 6, 3},
                {6, 3, 5, 2, 4, 1}, {3, 6, 2, 5, 1, 4}
        };
        for (int orientation = 1; orientation <= 8; orientation++) {
            var parent = type.createBufferedImage(5, 4);
            var view = parent.getSubimage(1, 1, 3, 2);
            for (int i = 0; i < 6; i++) {
                view.getRaster().setPixel(i % 3, i / 3, new int[]{i + 1, 1001 + i, 65001 + i});
            }
            var result = ExifOrientationTransformer.applyToOwnedImage(view, orientation);
            assertThat(result.getColorModel()).isEqualTo(view.getColorModel());
            for (int i = 0; i < 6; i++) {
                int n = order[orientation - 1][i];
                assertThat(result.getRaster().getPixel(i % result.getWidth(), i / result.getWidth(), (int[]) null))
                        .containsExactly(n, 1000 + n, 65000 + n);
            }
        }
    }

    @Test
    void sourceRectangleReproducesEveryOrientedWindow() {
        BufferedImage source = createTestImage(7, 5);
        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 7; x++) {
                source.setRGB(x, y, 32 * y + x);
            }
        }
        Rectangle requested = new Rectangle(1, 2, 3, 2);
        for (int orientation = 1; orientation <= 8; orientation++) {
            Rectangle input = ExifOrientationTransformer.sourceRegion(requested, 7, 5, orientation);
            var patch = source.getSubimage(input.x, input.y, input.width, input.height);
            var partial = ExifOrientationTransformer.apply(patch, orientation);
            var full = ExifOrientationTransformer.apply(source, orientation);
            assertThat(partial.getWidth()).isEqualTo(requested.width);
            assertThat(partial.getHeight()).isEqualTo(requested.height);
            assertThat(partial.getRGB(0, 0, 3, 2, null, 0, 3))
                    .containsExactly(full.getRGB(1, 2, 3, 2, null, 0, 3));
        }
    }

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

    @Test
    void orientsEveryPixelAndLimitsInPlaceFlipsToTheOwnedView() {
        int[][] expectedOrder = {
                {1, 2, 3, 4, 5, 6}, {3, 2, 1, 6, 5, 4},
                {6, 5, 4, 3, 2, 1}, {4, 5, 6, 1, 2, 3},
                {1, 4, 2, 5, 3, 6}, {4, 1, 5, 2, 6, 3},
                {6, 3, 5, 2, 4, 1}, {3, 6, 2, 5, 1, 4}
        };
        for (int type : new int[]{BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_3BYTE_BGR}) {
            for (int orientation = 1; orientation <= 8; orientation++) {
                BufferedImage parent = new BufferedImage(5, 4, type);
                BufferedImage src = parent.getSubimage(1, 1, 3, 2);
                src.setRGB(0, 0, 3, 2, expectedOrder[0], 0, 3);
                BufferedImage copy = ExifOrientationTransformer.apply(src, orientation);
                assertThat(pixelValues(src)).containsExactly(expectedOrder[0]);
                BufferedImage owned = ExifOrientationTransformer.applyToOwnedImage(src, orientation);
                assertThat(pixelValues(copy)).containsExactly(expectedOrder[orientation - 1]);
                assertThat(pixelValues(owned)).containsExactly(expectedOrder[orientation - 1]);
                if (type == BufferedImage.TYPE_INT_RGB && orientation <= 4) {
                    assertThat(owned).isSameAs(src);
                }
                for (int y = 0; y < 4; y++) {
                    for (int x = 0; x < 5; x++) {
                        if (x == 0 || x == 4 || y == 0 || y == 3) {
                            assertThat(parent.getRGB(x, y) & 0xffffff).isZero();
                        }
                    }
                }
            }
        }
    }

    @Test
    void packedRotationMatchesGenericPixelsAcrossTileAndOddMiddleRow() {
        int width = 71;
        int height = 9;
        int[] sourceValues = new int[width * height];
        for (int i = 0; i < sourceValues.length; i++) {
            sourceValues[i] = (0x12345 * i) & 0xffffff;
        }
        for (int orientation = 2; orientation <= 8; orientation++) {
            BufferedImage generic = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
            BufferedImage packed = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            generic.setRGB(0, 0, width, height, sourceValues, 0, width);
            packed.setRGB(0, 0, width, height, sourceValues, 0, width);
            BufferedImage expected = ExifOrientationTransformer.apply(generic, orientation);
            BufferedImage actual = ExifOrientationTransformer.applyToOwnedImage(packed, orientation);
            assertThat(pixelValues(actual)).containsExactly(pixelValues(expected));
        }
    }

    private static int[] pixelValues(BufferedImage image) {
        int width = image.getWidth();
        int[] values = image.getRGB(0, 0, width, image.getHeight(), null, 0, width);
        for (int i = 0; i < values.length; i++) {
            values[i] &= 0xffffff;
        }
        return values;
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
