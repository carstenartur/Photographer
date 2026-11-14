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

    private static int readFirst(BitReader bits, int bitDepth) throws IOException {
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
        int zeros = 0;
        while (bits.readBit() == 0) {
            if (++zeros > ESCAPE_QUOTIENT) {
                throw new IOException("Unsupported second-sample quotient above " + ESCAPE_QUOTIENT);
            }
        }
        int width = zeros == ESCAPE_QUOTIENT ? ESCAPE_LITERAL_WIDTH : 2;
        int code = bits.readBits(width);
        if (zeros < ESCAPE_QUOTIENT) {
            code += zeros << 2;
        }
        int second = first + signed(code);
        if (second < 0 || second > MAX_SAMPLE) {
            throw new IOException("Second sample outside 14-bit range: " + second);
        }
        return new Opening(first, second, zeros, code, 32 + zeros + 1 + width);
    }

    private static int signed(int unsigned) {
        return (unsigned >>> 1) ^ -(unsigned & 1);
    }
}
