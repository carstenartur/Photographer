package com.photographer.focusstack.cr3.raw.codec;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrxWaveletTileTest {
    @Test
    void reconstructsIndependentSignedThreeLevelTransformExactly() {
        // A separate integer forward transform split rows vertically first, then horizontally.
        var bands = new CrxWaveletTile.Band[]{
                square(1, 0), square(1, 33), square(1, 17), square(1, -33),
                square(2, 109, -9, 1, -28), square(2, -49, 74, -17, -29), square(2, 77, 39, -17, 14),
                square(4, 54, -15, -19, 52, -76, -42, -13, -50, 52, -69, -97, -30, -18, 2, 24, -45),
                square(4, 80, 46, 59, -2, -27, 91, -135, 32, -131, 54, -17, -25, 74, 11, 60, 2),
                square(4, 78, -95, -56, -195, 10, 101, -30, -227, -20, -95, -72, -30, 30, -19, 1, 46)
        };
        assertThat(CrxWaveletTile.synthesize(bands, 8, 8, false, false)).containsExactly(
                -93, -33, -3, -5, -73, -77, -99, 51, -38, 60, 86, -12, 93, 10, 21, -72,
                -64, -93, 75, 26, 66, 51, 19, 74, -47, 7, 87, 50, -74, -50, 98, -85,
                34, 58, -27, -38, 92, -33, -15, 19, -86, -23, 45, -67, 52, -73, -23, -60,
                37, -26, -49, -13, -38, 8, -30, -79, 96, 37, -41, 4, 26, 36, -40, -43);
    }

    @Test
    void mapsActualTwoTileEntropyWidthsAndOddHeightAtAllThreeLevels() {
        var left = CrxWaveletTile.shapes(1722, 2273, false, true);
        assertThat(left).extracting(CrxWaveletTile.Shape::width)
                .containsExactly(217, 217, 217, 217, 432, 433, 432, 862, 863, 862);
        assertThat(left).extracting(CrxWaveletTile.Shape::height)
                .containsExactly(285, 285, 284, 284, 569, 568, 568, 1137, 1136, 1136);
        assertThat(CrxWaveletTile.shapes(1722, 2273, true, false)).extracting(CrxWaveletTile.Shape::width)
                .containsExactly(216, 216, 216, 216, 431, 431, 431, 862, 861, 862);
        assertThat(CrxWaveletTile.shapes(1408, 1855, false, true)).extracting(CrxWaveletTile.Shape::width)
                .containsExactly(177, 177, 177, 177, 353, 353, 353, 705, 705, 705);
        assertThat(CrxWaveletTile.shapes(1408, 1855, true, false)).extracting(CrxWaveletTile.Shape::width)
                .containsExactly(176, 177, 176, 177, 353, 352, 353, 705, 704, 705);
    }

    @Test
    void aLeadingHighpassNeighbourChangesOnlyTheLeftEdgeOfTheRightTile() {
        int width = 16, height = 9;
        var bands = zeros(width, height, true, false);
        Arrays.fill(bands[0].samples(), 2000);
        for (int y = 0; y < bands[7].height(); y++) {
            bands[7].samples()[y * bands[7].width()] = 32;
        }
        int[] expected = new int[width * height];
        Arrays.fill(expected, 2000);
        for (int y = 0; y < height; y++) {
            expected[y * width] = 1992;
            expected[y * width + 1] = 1996;
        }
        assertThat(CrxWaveletTile.synthesize(bands, width, height, true, false)).containsExactly(expected);
    }

    @Test
    void separatesPhysicalRowStrideFromCroppedSynthesisExtent() {
        int width = 26, height = 17;
        var bands = zeros(width, height, false, true);
        Arrays.fill(bands[0].samples(), 64);
        // The Y-detail row physically includes a surplus trailing column, never a new row's x=0.
        int bandWidth = bands[8].width();
        assertThat(bandWidth).isEqualTo(15);
        for (int y = 0; y < bands[8].height(); y++) {
            bands[8].samples()[y * bandWidth + bandWidth - 1] = 10000;
        }
        assertThat(CrxWaveletTile.synthesize(bands, width, height, false, true)).containsOnly(64);
        bands[7] = square(1, 0);
        assertThatThrownBy(() -> CrxWaveletTile.synthesize(bands, width, height, false, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CrxWaveletTile.Band[] zeros(int width, int height, boolean left, boolean right) {
        return Arrays.stream(CrxWaveletTile.shapes(width, height, left, right))
                .map(s -> new CrxWaveletTile.Band(new int[s.width() * s.height()], s.width(), s.height()))
                .toArray(CrxWaveletTile.Band[]::new);
    }

    private static CrxWaveletTile.Band square(int size, int... samples) {
        return new CrxWaveletTile.Band(samples, size, size);
    }
}
