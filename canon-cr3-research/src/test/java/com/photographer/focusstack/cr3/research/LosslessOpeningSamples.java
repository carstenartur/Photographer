package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.raw.codec.BitReader;
import java.io.IOException;

/** RG3 research candidate, restricted to two opening samples in the measured lossless layout. */
final class LosslessOpeningSamples {
    private static final int OBSERVED_PREFIX = 0x80;
    private static final int SENSOR_DEPTH = 14;
    private static final int MAX_SAMPLE = (1 << SENSOR_DEPTH) - 1;
    private static final int ESCAPE_QUOTIENT = 41;
    private static final int ESCAPE_LITERAL_WIDTH = 21;

    private LosslessOpeningSamples() {}

    record Opening(int first, int second, int secondLeadingZeros, int secondCode, int endBit) {}

    static int prefix18(byte[] payload) throws IOException {
        return new BitReader(payload).readBits(18);
    }

    static int firstCode(byte[] payload) throws IOException {
        BitReader bits = new BitReader(payload);
        bits.readBits(18);
        return bits.readBits(SENSOR_DEPTH);
    }

    static int first(byte[] payload, int bitDepth) throws IOException {
        return readFirst(new BitReader(payload), bitDepth);
    }

    static int readFirst(BitReader bits, int bitDepth) throws IOException {
        if (bitDepth != SENSOR_DEPTH) {
            throw new IOException("Unsupported sensor depth: " + bitDepth);
        }
        int prefix = bits.readBits(18);
        if (prefix != OBSERVED_PREFIX) {
            throw new IOException("Unsupported opening prefix: " + prefix);
        }
        return (1 << (SENSOR_DEPTH - 1)) + signed(bits.readBits(SENSOR_DEPTH));
    }

    static Opening decode(byte[] payload, int bitDepth) throws IOException {
        BitReader bits = new BitReader(payload);
        int first = readFirst(bits, bitDepth);
        var residual = readResidual(bits, 2);
        int second = addResidual(first, residual.unsigned());
        return new Opening(first, second, residual.zeros(), residual.unsigned(), 32 + residual.bits());
    }

    record Residual(int unsigned, int zeros, int bits) {}

    static Residual readResidual(BitReader bits, int k) throws IOException {
        int zeros = 0;
        while (bits.readBit() == 0) {
            if (++zeros > ESCAPE_QUOTIENT) {
                throw new IOException("Unsupported quotient above " + ESCAPE_QUOTIENT);
            }
        }
        int width = zeros == ESCAPE_QUOTIENT ? ESCAPE_LITERAL_WIDTH : k;
        int code = bits.readBits(width);
        if (zeros < ESCAPE_QUOTIENT) {
            code += zeros << k;
        }
        return new Residual(code, zeros, zeros + 1 + width);
    }

    static int addResidual(int predictor, int unsigned) throws IOException {
        int value = predictor + signed(unsigned);
        if (value < 0 || value > MAX_SAMPLE) {
            throw new IOException("Sample outside 14-bit range: " + value);
        }
        return value;
    }

    private static int signed(int unsigned) {
        return (unsigned >>> 1) ^ -(unsigned & 1);
    }
}
