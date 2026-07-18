package com.photographer.focusstack.cr3.raw.codec;

import java.io.IOException;
import java.util.Objects;

/** Decodes one unsigned Rice-Golomb symbol from the CRX plane bitstream. */
final class CrxRiceSymbolDecoder {

    private static final int MAX_UNARY_COUNT = 65536;

    enum UnaryDirection {
        /** Count zero bits until a one terminator. */
        ZEROS_UNTIL_ONE,
        /** Count one bits until a zero terminator. */
        ONES_UNTIL_ZERO
    }

    private CrxRiceSymbolDecoder() {
    }

    static UnaryDirection forRule(CrxLosslessRawDecoder.KUpdateRule rule) {
        Objects.requireNonNull(rule, "rule");
        return rule == CrxLosslessRawDecoder.KUpdateRule.PATENT_BRACKET
                ? UnaryDirection.ONES_UNTIL_ZERO
                : UnaryDirection.ZEROS_UNTIL_ONE;
    }

    static int decode(BitReader reader, int k, UnaryDirection unaryDirection) throws IOException {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(unaryDirection, "unaryDirection");
        if (k < 0 || k > 31) {
            throw new IllegalArgumentException("Unsupported Rice parameter k=" + k);
        }

        int quotient = readUnary(reader, unaryDirection);
        int remainder = 0;
        if (k > 0) {
            if (!reader.hasMore()) {
                throw new IOException("Unexpected end of stream while reading CRX Rice-Golomb remainder");
            }
            remainder = reader.readBits(k);
        }

        long encoded = ((long) quotient << k) | Integer.toUnsignedLong(remainder);
        if (encoded > Integer.MAX_VALUE) {
            throw new IOException("CRX Rice-Golomb symbol exceeds integer range: " + encoded);
        }
        return (int) encoded;
    }

    private static int readUnary(BitReader reader, UnaryDirection unaryDirection) throws IOException {
        int quotient = 0;
        while (true) {
            if (!reader.hasMore()) {
                throw new IOException("Unexpected end of stream while reading CRX unary code");
            }
            int bit = reader.readBit();
            boolean terminated = switch (unaryDirection) {
                case ZEROS_UNTIL_ONE -> bit == 1;
                case ONES_UNTIL_ZERO -> bit == 0;
            };
            if (terminated) {
                return quotient;
            }
            quotient++;
            if (quotient > MAX_UNARY_COUNT) {
                throw new IOException("CRX unary overflow (>" + MAX_UNARY_COUNT + ")");
            }
        }
    }
}
