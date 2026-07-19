package com.photographer.focusstack.cr3.raw.codec;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrxDecoderStagesTest {

    @Test
    void residualMappingsAreIndependentlyTestable() {
        assertThat(CrxResidualSignMapper.decode(0, CrxResidualSignMapper.Mapping.BIT0_SIGN_MAGNITUDE)).isZero();
        assertThat(CrxResidualSignMapper.decode(1, CrxResidualSignMapper.Mapping.BIT0_SIGN_MAGNITUDE)).isZero();
        assertThat(CrxResidualSignMapper.decode(2, CrxResidualSignMapper.Mapping.BIT0_SIGN_MAGNITUDE)).isEqualTo(1);
        assertThat(CrxResidualSignMapper.decode(3, CrxResidualSignMapper.Mapping.BIT0_SIGN_MAGNITUDE)).isEqualTo(-1);

        assertThat(CrxResidualSignMapper.decode(0, CrxResidualSignMapper.Mapping.ZIG_ZAG)).isZero();
        assertThat(CrxResidualSignMapper.decode(1, CrxResidualSignMapper.Mapping.ZIG_ZAG)).isEqualTo(-1);
        assertThat(CrxResidualSignMapper.decode(2, CrxResidualSignMapper.Mapping.ZIG_ZAG)).isEqualTo(1);
        assertThat(CrxResidualSignMapper.decode(3, CrxResidualSignMapper.Mapping.ZIG_ZAG)).isEqualTo(-2);
    }

    @Test
    void predictorHandlesSeedEdgesAndMedInterior() {
        int[] reconstructed = {
                10, 20,
                30, 0
        };

        assertThat(CrxMedPredictor.predict(new int[4], 2, 0, 0)).isZero();
        assertThat(CrxMedPredictor.predict(reconstructed, 2, 1, 0)).isEqualTo(10);
        assertThat(CrxMedPredictor.predict(reconstructed, 2, 0, 1)).isEqualTo(10);
        assertThat(CrxMedPredictor.predict(reconstructed, 2, 1, 1)).isEqualTo(30);
        assertThat(CrxMedPredictor.med(100, 200, 150)).isEqualTo(150);
    }

    @Test
    void riceUnaryDirectionsDecodeTheSameQuotientAndRemainder() throws Exception {
        BitReader onesUntilZero = new BitReader(bits("11011"));
        BitReader zerosUntilOne = new BitReader(bits("00111"));

        assertThat(CrxRiceSymbolDecoder.decode(
                onesUntilZero, 2, CrxRiceSymbolDecoder.UnaryDirection.ONES_UNTIL_ZERO)).isEqualTo(11);
        assertThat(CrxRiceSymbolDecoder.decode(
                zerosUntilOne, 2, CrxRiceSymbolDecoder.UnaryDirection.ZEROS_UNTIL_ONE)).isEqualTo(11);
    }

    @Test
    void riceSymbolDecoderRejectsMissingUnaryTerminator() {
        BitReader reader = new BitReader(new byte[]{(byte) 0xff});

        assertThatThrownBy(() -> CrxRiceSymbolDecoder.decode(
                reader, 0, CrxRiceSymbolDecoder.UnaryDirection.ONES_UNTIL_ZERO))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("end of stream");
    }

    @Test
    void adaptationStateMakesRowResetPolicyVisible() {
        CrxRiceAdaptationState patent = new CrxRiceAdaptationState(
                CrxLosslessRawDecoder.KUpdateRule.PATENT_BRACKET, 14);
        assertThat(patent.k()).isEqualTo(1);
        patent.update(20);
        assertThat(patent.k()).isEqualTo(2);
        patent.startRow();
        assertThat(patent.k()).isEqualTo(1);
        assertThat(patent.accumulator()).isZero();
        assertThat(patent.sampleCount()).isEqualTo(1);

        CrxRiceAdaptationState jpegLs = new CrxRiceAdaptationState(
                CrxLosslessRawDecoder.KUpdateRule.JPEG_LS, 14);
        assertThat(jpegLs.k()).isZero();
        jpegLs.update(10);
        assertThat(jpegLs.k()).isEqualTo(1);
        jpegLs.startRow();
        assertThat(jpegLs.k()).isEqualTo(1);
        assertThat(jpegLs.accumulator()).isEqualTo(10);
    }

    @Test
    void planeAssemblyIsIndependentOfCfaColourMeaning() {
        int[][] planes = {
                {0, 1, 2, 3},
                {10, 11, 12, 13},
                {20, 21, 22, 23},
                {30, 31, 32, 33}
        };
        int[] output = new int[16];

        CrxPlaneAssembler.interleaveByPosition(planes, output, 4, 4, 2, 2);

        assertThat(output).containsExactly(
                0, 10, 1, 11,
                20, 30, 21, 31,
                2, 12, 3, 13,
                22, 32, 23, 33);
    }

    @Test
    void tileCopyWritesOnlyTheDeclaredHorizontalSlice() {
        int[] destination = new int[12];
        CrxPlaneAssembler.copyTilePlane(
                new int[]{1, 2, 3, 4}, destination, 2, 2, 4, 1);

        assertThat(destination).containsExactly(
                0, 1, 2, 0,
                0, 3, 4, 0,
                0, 0, 0, 0);
    }

    @Test
    void composedPlaneDecoderRoundTripsPatentProfile() throws Exception {
        int[] expected = {
                100, 105,
                102, 110
        };
        byte[] encoded = encodePatentPlane(expected, 2, 2);

        int[] decoded = CrxLosslessPlaneDecoder.decode(
                encoded,
                0,
                encoded.length,
                2,
                2,
                14,
                CrxLosslessRawDecoder.KUpdateRule.PATENT_BRACKET);

        assertThat(decoded).containsExactly(expected);
    }

    private static byte[] encodePatentPlane(int[] values, int width, int height) {
        ByteBitWriter bits = new ByteBitWriter();
        CrxRiceAdaptationState adaptation = new CrxRiceAdaptationState(
                CrxLosslessRawDecoder.KUpdateRule.PATENT_BRACKET, 14);
        int[] reconstructed = new int[values.length];

        for (int y = 0; y < height; y++) {
            adaptation.startRow();
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                int predicted = CrxMedPredictor.predict(reconstructed, width, x, y);
                int signedResidual = values[index] - predicted;
                int encodedResidual = (Math.abs(signedResidual) << 1) | (signedResidual < 0 ? 1 : 0);
                int k = adaptation.k();
                int quotient = encodedResidual >>> k;
                int remainder = encodedResidual & ((1 << k) - 1);

                for (int unary = 0; unary < quotient; unary++) {
                    bits.writeBit(1);
                }
                bits.writeBit(0);
                for (int bit = k - 1; bit >= 0; bit--) {
                    bits.writeBit((remainder >>> bit) & 1);
                }

                reconstructed[index] = values[index];
                adaptation.update(signedResidual);
            }
        }
        return bits.toByteArray();
    }

    private static byte[] bits(String bits) {
        ByteBitWriter writer = new ByteBitWriter();
        for (int index = 0; index < bits.length(); index++) {
            writer.writeBit(bits.charAt(index) == '1' ? 1 : 0);
        }
        return writer.toByteArray();
    }

    private static final class ByteBitWriter {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private int buffer;
        private int count;

        void writeBit(int bit) {
            buffer = (buffer << 1) | (bit & 1);
            count++;
            if (count == 8) {
                output.write(buffer);
                buffer = 0;
                count = 0;
            }
        }

        byte[] toByteArray() {
            if (count > 0) {
                output.write(buffer << (8 - count));
                count = 0;
                buffer = 0;
            }
            return output.toByteArray();
        }
    }
}
