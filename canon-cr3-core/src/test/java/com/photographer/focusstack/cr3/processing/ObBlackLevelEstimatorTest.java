package com.photographer.focusstack.cr3.processing;

import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link ObBlackLevelEstimator} and per-row {@link BlackLevel}.
 */
class ObBlackLevelEstimatorTest {

    @Test
    @DisplayName("Returns null when OB region is too narrow")
    void tooNarrowObRegion() {
        // Image with only 2 columns of OB (below MIN_OB_COLUMNS = 4)
        int[] data = new int[10 * 10];
        Cr3RawImage image = new Cr3RawImage(10, 10, data, 14, Cr3RawImage.BayerPattern.RGGB);
        BlackLevel bl = ObBlackLevelEstimator.estimate(image, 2);
        assertThat(bl).isNull();
    }

    @Test
    @DisplayName("Estimates per-row black level from uniform OB region")
    void uniformObRegion() {
        int width = 20;
        int height = 8;
        int obCols = 8; // 8 OB columns
        int[] data = new int[width * height];

        // Fill OB region with a constant value (500)
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < obCols; x++) {
                data[y * width + x] = 500;
            }
        }
        // Active area has higher values
        for (int y = 0; y < height; y++) {
            for (int x = obCols; x < width; x++) {
                data[y * width + x] = 2000;
            }
        }

        Cr3RawImage image = new Cr3RawImage(width, height, data, 14, Cr3RawImage.BayerPattern.RGGB);
        BlackLevel bl = ObBlackLevelEstimator.estimate(image, obCols);

        assertThat(bl).isNotNull();
        assertThat(bl.isPerRow()).isTrue();
        assertThat(bl.mode()).isEqualTo(BlackLevel.Mode.PER_ROW);

        // All channels should have black level ~500
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int level = bl.levelAt(x, y, Cr3RawImage.BayerPattern.RGGB);
                assertThat(level).isEqualTo(500);
            }
        }
    }

    @Test
    @DisplayName("Estimates varying per-row black levels")
    void varyingRowBlackLevels() {
        int width = 20;
        int height = 4;
        int obCols = 8;
        int[] data = new int[width * height];

        // Row 0 (even): OB = 500
        // Row 1 (odd):  OB = 510
        // Row 2 (even): OB = 520
        // Row 3 (odd):  OB = 530
        for (int y = 0; y < height; y++) {
            int obVal = 500 + y * 10;
            for (int x = 0; x < obCols; x++) {
                data[y * width + x] = obVal;
            }
        }

        Cr3RawImage image = new Cr3RawImage(width, height, data, 14, Cr3RawImage.BayerPattern.RGGB);
        BlackLevel bl = ObBlackLevelEstimator.estimate(image, obCols);

        assertThat(bl).isNotNull();
        assertThat(bl.isPerRow()).isTrue();

        // Row 0 at even col → channel 0 (R in RGGB), halfRow 0, should be 500
        assertThat(bl.levelAt(0, 0, Cr3RawImage.BayerPattern.RGGB)).isEqualTo(500);
        // Row 1 at even col → channel 2 (Gb in RGGB), halfRow 0, should be 510
        assertThat(bl.levelAt(0, 1, Cr3RawImage.BayerPattern.RGGB)).isEqualTo(510);
        // Row 2 at even col → channel 0, halfRow 1, should be 520
        assertThat(bl.levelAt(0, 2, Cr3RawImage.BayerPattern.RGGB)).isEqualTo(520);
    }

    @Test
    @DisplayName("Per-row BlackLevel toString describes mode")
    void perRowToString() {
        int[][] levels = {
                {500, 510}, {505, 515}, {502, 512}, {508, 518}
        };
        BlackLevel bl = BlackLevel.perRow(levels);
        assertThat(bl.toString()).contains("perRow").contains("2 rows");
    }

    @Test
    @DisplayName("Per-row factory rejects wrong channel count")
    void perRowWrongChannelCount() {
        assertThatThrownBy(() -> BlackLevel.perRow(new int[3][10]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Per-row factory rejects mismatched row lengths")
    void perRowMismatchedLengths() {
        int[][] levels = {new int[10], new int[10], new int[10], new int[5]};
        assertThatThrownBy(() -> BlackLevel.perRow(levels))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same length");
    }

    @Test
    @DisplayName("BlackLevelProcessor handles per-row black level")
    void processorPerRow() {
        int width = 8;
        int height = 4;
        int[] data = new int[width * height];
        java.util.Arrays.fill(data, 1000);

        Cr3RawImage image = new Cr3RawImage(width, height, data, 14, Cr3RawImage.BayerPattern.RGGB);

        // Per-row: all channels, both half-rows have level 500
        int[][] levels = {
                {500, 500}, {500, 500}, {500, 500}, {500, 500}
        };
        BlackLevel bl = BlackLevel.perRow(levels);
        BlackLevelProcessor.process(image, bl);

        // All pixels should be 1000 - 500 = 500
        for (int val : image.data()) {
            assertThat(val).isEqualTo(500);
        }
    }
}
