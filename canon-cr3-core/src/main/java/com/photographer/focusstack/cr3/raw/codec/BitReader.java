package com.photographer.focusstack.cr3.raw.codec;

import java.io.IOException;

/**
 * Bitwise reader for binary data streams.
 *
 * <p>Reads individual bits and variable-length codes from a byte array.
 * Used by {@link RiceGolombDecoder} and {@link CrxLosslessRawDecoder} to
 * parse the entropy-coded CRX bitstream.</p>
 *
 * <p>Bits are read MSB-first (big-endian bit order) within each byte.</p>
 */
public final class BitReader {

    private final byte[] data;
    private int bytePos;
    private int bitPos;  // bits remaining in current byte (8..1)

    /**
     * Creates a new BitReader over the given data.
     *
     * @param data the byte array to read from
     */
    public BitReader(byte[] data) {
        this.data = data;
        this.bytePos = 0;
        this.bitPos = 8;
    }

    /**
     * Reads a single bit.
     *
     * @return 0 or 1
     * @throws IOException if end of data is reached
     */
    public int readBit() throws IOException {
        if (bytePos >= data.length) {
            throw new IOException("BitReader: end of data at byte " + bytePos);
        }
        bitPos--;
        int bit = (data[bytePos] >> bitPos) & 1;
        if (bitPos == 0) {
            bytePos++;
            bitPos = 8;
        }
        return bit;
    }

    /**
     * Reads the specified number of bits as an unsigned integer.
     *
     * @param count number of bits to read (1-32)
     * @return the unsigned value
     * @throws IOException if end of data is reached
     */
    public int readBits(int count) throws IOException {
        if (count < 0 || count > 32) {
            throw new IllegalArgumentException("Invalid bit count: " + count);
        }
        int value = 0;
        for (int i = 0; i < count; i++) {
            value = (value << 1) | readBit();
        }
        return value;
    }

    /**
     * Reads a unary code (count of 0-bits before the first 1-bit).
     *
     * @return the unary value
     * @throws IOException if end of data is reached
     */
    public int readUnary() throws IOException {
        int count = 0;
        while (readBit() == 0) {
            count++;
        }
        return count;
    }

    /** Returns the current byte position. */
    public int bytePosition() { return bytePos; }

    /** Returns the total number of bytes. */
    public int length() { return data.length; }

    /** Returns whether there is more data to read. */
    public boolean hasMore() { return bytePos < data.length; }
}
