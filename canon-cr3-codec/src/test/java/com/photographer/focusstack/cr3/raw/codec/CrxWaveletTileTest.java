package com.photographer.focusstack.cr3.raw.codec;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrxWaveletTileTest {
    @Test
    void reconstructsIndependentSignedThreeLevelTransformExactly() throws IOException {
        // A separate integer forward transform split rows vertically first, then horizontally.
        var bands = new CrxWaveletTile.Band[]{
                square(1, 0), square(1, 33), square(1, 17), square(1, -33),
                square(2, 109, -9, 1, -28), square(2, -49, 74, -17, -29), square(2, 77, 39, -17, 14),
                square(4, 54, -15, -19, 52, -76, -42, -13, -50, 52, -69, -97, -30, -18, 2, 24, -45),
                square(4, 80, 46, 59, -2, -27, 91, -135, 32, -131, 54, -17, -25, 74, 11, 60, 2),
                square(4, 78, -95, -56, -195, 10, 101, -30, -227, -20, -95, -72, -30, 30, -19, 1, 46)
        };
        assertThat(WaveletTestRows.collect(CrxWaveletTile.synthesize(bands, 8, 8, false, false))).containsExactly(
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
    void aLeadingHighpassNeighbourChangesOnlyTheLeftEdgeOfTheRightTile() throws IOException {
        int width = 16, height = 9;
        var bands = zeros(width, height, true, false);
        bands[0] = fill(bands[0], 2000);
        bands[7] = new CrxWaveletTile.Band(bands[7].width(), bands[7].height(), row -> row[0] = 32);
        int[] expected = new int[width * height];
        Arrays.fill(expected, 2000);
        for (int y = 0; y < height; y++) {
            expected[y * width] = 1992;
            expected[y * width + 1] = 1996;
        }
        assertThat(WaveletTestRows.collect(CrxWaveletTile.synthesize(bands, width, height, true, false))).containsExactly(expected);
    }

    @Test
    void separatesPhysicalRowStrideFromCroppedSynthesisExtent() throws IOException {
        int width = 26, height = 17;
        var bands = zeros(width, height, false, true);
        bands[0] = fill(bands[0], 64);
        // The Y-detail row physically includes a surplus trailing column, never a new row's x=0.
        int bandWidth = bands[8].width();
        assertThat(bandWidth).isEqualTo(15);
        bands[8] = new CrxWaveletTile.Band(bandWidth, bands[8].height(), row -> row[bandWidth - 1] = 10000);
        assertThat(WaveletTestRows.collect(CrxWaveletTile.synthesize(bands, width, height, false, true))).containsOnly(64);
        bands[7] = square(1, 0);
        assertThatThrownBy(() -> CrxWaveletTile.synthesize(bands, width, height, false, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nativeLiftingMatchesCheckedLiftingAtTheEncodedCoefficientLimits() throws IOException {
        int width = 26;
        int height = 17;
        for (boolean left : new boolean[]{false, true}) {
            boolean right = !left;
            var shapes = CrxWaveletTile.shapes(width, height, left, right);
            var bands = new CrxWaveletTile.Band[shapes.length];
            for (int i = 0; i < bands.length; i++) {
                var shape = shapes[i];
                int positive = i == 0 ? (1 << 20) - 1 : 1 << 26;
                int negative = i == 0 ? -(1 << 20) : -(1 << 26);
                int parity = i & 1;
                bands[i] = new CrxWaveletTile.Band(shape.width(), shape.height(),
                        row -> Arrays.setAll(row, x -> (x & 1) == parity ? positive : negative));
            }
            int[] expected = WaveletTestRows.collect(CrxWaveletTile.synthesize(bands, width, height, left, right));
            assertThat(WaveletTestRows.collect(CrxWaveletTile.synthesizeNative(bands, width, height, left, right)))
                    .containsExactly(expected);
        }
    }

    @Test
    void nativeLiftingPreservesSignedCoefficientsAcrossVectorBoundaries() throws IOException {
        var random = new Random(67633);
        for (int width : new int[]{9, 17, 18, 19, 31, 32, 33, 129, 130}) {
            for (boolean left : new boolean[]{false, true}) {
                for (boolean right : new boolean[]{false, true}) {
                    int height = 19;
                    var shapes = CrxWaveletTile.shapes(width, height, left, right);
                    var bands = new CrxWaveletTile.Band[shapes.length];
                    for (int b = 0; b < bands.length; b++) {
                        var shape = shapes[b];
                        int magnitude = b == 0 ? 1 << 20 : 1 << 26;
                        int[] values = new int[shape.width()];
                        Arrays.setAll(values, x -> random.nextInt(-magnitude, magnitude));
                        bands[b] = new CrxWaveletTile.Band(shape.width(), shape.height(),
                                row -> System.arraycopy(values, 0, row, 0, values.length));
                    }
                    int[] expected = WaveletTestRows.collect(CrxWaveletTile.synthesize(
                            bands, width, height, left, right));
                    assertThat(WaveletTestRows.collect(CrxWaveletTile.synthesizeNative(
                            bands, width, height, left, right))).containsExactly(expected);
                }
            }
        }
    }

    @Test
    void adaptiveHorizontalVectorsPreserveTheLargestAdmittedCoefficients() throws IOException {
        int width = 131;
        int height = 37;
        var shapes = CrxWaveletTile.shapes(width, height, false, false);
        int[] factors = {1, 1, 1, 1, 51, 51, 102, 102, 102, 204};
        var scalar = new CrxWaveletTile.Band[shapes.length];
        var adaptive = new CrxWaveletTile.Band[shapes.length];
        var random = new Random(51237);
        for (int b = 0; b < shapes.length; b++) {
            var shape = shapes[b];
            int positive = ((1 << 20) - 1) * factors[b];
            int negative = -(1 << 20) * factors[b];
            int[] values = new int[shape.width() * shape.height()];
            Arrays.setAll(values, i -> random.nextBoolean() ? positive : negative);
            scalar[b] = liftingBand(shape.width(), shape.height(), values);
            adaptive[b] = liftingBand(shape.width(), shape.height(), values);
        }

        int[] expected = WaveletTestRows.collect(CrxWaveletTile.synthesize(scalar, width, height, false, false));
        assertThat(WaveletTestRows.collect(CrxWaveletTile.synthesizeAdaptiveChecked(adaptive, width, height)))
                .containsExactly(expected);
    }

    @Test
    @EnabledIfSystemProperty(named = "cr3.vector.tests", matches = "true")
    void vectorKernelProcessesInteriorWhenHardwareSupports256BitVectors() throws ReflectiveOperationException {
        Class<?> vector = Class.forName("jdk.incubator.vector.IntVector");
        Class<?> species = Class.forName("jdk.incubator.vector.VectorSpecies");
        Object preferred = vector.getField("SPECIES_PREFERRED").get(null);
        int bits = (int) species.getMethod("vectorBitSize").invoke(preferred);

        int pairs = CrxWaveletTile.liftHorizontalVectors(
                new int[33], new int[32], new int[65], 33, 32, 0, 65, 0);

        assertThat(pairs > 0).isEqualTo(bits >= 256);
    }

    @Test
    void checkedLiftingCanAverageTwoMaximumIntegers() throws IOException {
        var bands = zeros(8, 8, false, false);
        var approximation = bands[0];
        bands[0] = new CrxWaveletTile.Band(approximation.width(), approximation.height(),
                row -> Arrays.fill(row, Integer.MAX_VALUE));
        assertThat(WaveletTestRows.collect(CrxWaveletTile.synthesize(bands, 8, 8, false, false)))
                .containsOnly(Integer.MAX_VALUE);
    }

    private static CrxWaveletTile.Band[] zeros(int width, int height, boolean left, boolean right) {
        return Arrays.stream(CrxWaveletTile.shapes(width, height, left, right))
                .map(s -> new CrxWaveletTile.Band(s.width(), s.height(), row -> Arrays.fill(row, 0)))
                .toArray(CrxWaveletTile.Band[]::new);
    }

    private static CrxWaveletTile.Band fill(CrxWaveletTile.Band band, int value) {
        return new CrxWaveletTile.Band(band.width(), band.height(), row -> Arrays.fill(row, value));
    }

    private static CrxWaveletTile.Band square(int size, int... samples) {
        return liftingBand(size, size, samples);
    }

    private static CrxWaveletTile.Band liftingBand(int width, int height, int[] samples) {
        return new CrxWaveletTile.Band(width, height, new CrxWaveletTile.RowReader() {
            int offset;

            @Override
            public void read(int[] row) {
                System.arraycopy(samples, offset, row, 0, width);
                offset += width;
            }
        });
    }
}
