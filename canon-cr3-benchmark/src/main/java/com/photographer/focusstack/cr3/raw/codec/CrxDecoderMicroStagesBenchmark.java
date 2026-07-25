package com.photographer.focusstack.cr3.raw.codec;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Nanosecond-level measurements for the per-symbol CRX lossless decoder stages.
 *
 * <p>Every mutable stage has an independent JMH state. Reader construction and
 * Rice-adaptation initialisation happen in invocation setup, outside the timed
 * region, while deterministic input arrays are prepared once per trial.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class CrxDecoderMicroStagesBenchmark {

    private static final int COMPONENT_OPERATIONS = 16_384;
    private static final int SYNTHETIC_RICE_K = 2;
    private static final int SYNTHETIC_ROW_WIDTH = 256;
    private static final int SYNTHETIC_BIT_DEPTH = 14;

    @Benchmark
    @OperationsPerInvocation(COMPONENT_OPERATIONS)
    public long decodeRiceSymbols(RiceSymbolState state) throws IOException {
        long checksum = 0;
        for (int i = 0; i < COMPONENT_OPERATIONS; i++) {
            checksum += CrxRiceSymbolDecoder.decode(
                    state.reader,
                    SYNTHETIC_RICE_K,
                    CrxRiceSymbolDecoder.UnaryDirection.ONES_UNTIL_ZERO);
        }
        return checksum;
    }

    @Benchmark
    @OperationsPerInvocation(COMPONENT_OPERATIONS)
    public long mapResidualSigns(ResidualMappingState state) {
        long checksum = 0;
        for (int encodedResidual : state.encodedResiduals) {
            checksum += CrxResidualSignMapper.decode(
                    encodedResidual,
                    CrxResidualSignMapper.Mapping.BIT0_SIGN_MAGNITUDE);
        }
        return checksum;
    }

    @Benchmark
    @OperationsPerInvocation(COMPONENT_OPERATIONS)
    public long updateRiceAdaptation(AdaptationState state) {
        long checksum = 0;
        for (int i = 0; i < state.signedResiduals.length; i++) {
            if (i % SYNTHETIC_ROW_WIDTH == 0) {
                state.adaptation.startRow();
            }
            state.adaptation.update(state.signedResiduals[i]);
            checksum += state.adaptation.k();
        }
        return checksum;
    }

    @State(Scope.Thread)
    public static class RiceSymbolState {
        private byte[] encodedData;
        private BitReader reader;

        @Setup(Level.Trial)
        public void prepareData() {
            encodedData = encodeRiceSymbols(createEncodedResiduals(), SYNTHETIC_RICE_K);
        }

        @Setup(Level.Invocation)
        public void resetReader() {
            reader = new BitReader(encodedData);
        }
    }

    @State(Scope.Thread)
    public static class ResidualMappingState {
        private int[] encodedResiduals;

        @Setup(Level.Trial)
        public void prepareData() {
            encodedResiduals = createEncodedResiduals();
        }
    }

    @State(Scope.Thread)
    public static class AdaptationState {
        private int[] signedResiduals;
        private CrxRiceAdaptationState adaptation;

        @Setup(Level.Trial)
        public void prepareData() {
            signedResiduals = new int[COMPONENT_OPERATIONS];
            for (int i = 0; i < signedResiduals.length; i++) {
                int magnitude = i & 15;
                signedResiduals[i] = (i & 1) == 0 ? magnitude : -magnitude;
            }
        }

        @Setup(Level.Invocation)
        public void resetAdaptation() {
            adaptation = new CrxRiceAdaptationState(
                    CrxLosslessRawDecoder.DEFAULT_K_UPDATE_RULE,
                    SYNTHETIC_BIT_DEPTH);
        }
    }

    private static int[] createEncodedResiduals() {
        int[] residuals = new int[COMPONENT_OPERATIONS];
        for (int i = 0; i < residuals.length; i++) {
            int magnitude = i & 15;
            residuals[i] = (magnitude << 1) | (i & 1);
        }
        return residuals;
    }

    private static byte[] encodeRiceSymbols(int[] symbols, int k) {
        int bitCount = 0;
        for (int symbol : symbols) {
            bitCount = Math.addExact(bitCount, (symbol >>> k) + 1 + k);
        }
        byte[] encoded = new byte[(bitCount + 7) / 8];
        int bitIndex = 0;
        for (int symbol : symbols) {
            int quotient = symbol >>> k;
            for (int i = 0; i < quotient; i++) {
                bitIndex = writeBit(encoded, bitIndex, 1);
            }
            bitIndex = writeBit(encoded, bitIndex, 0);
            for (int shift = k - 1; shift >= 0; shift--) {
                bitIndex = writeBit(encoded, bitIndex, (symbol >>> shift) & 1);
            }
        }
        return encoded;
    }

    private static int writeBit(byte[] destination, int bitIndex, int value) {
        if (value != 0) {
            int byteIndex = bitIndex >>> 3;
            int shift = 7 - (bitIndex & 7);
            destination[byteIndex] |= (byte) (1 << shift);
        }
        return bitIndex + 1;
    }
}
