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
    private final int startOffset;
    private final int endOffset;
    private int bytePos;
    private int bitPos;  // bits remaining in current byte (8..1)

    /**
     * Creates a new BitReader over the given data.
     *
     * @param data the byte array to read from
     */
    public BitReader(byte[] data) {
        this(data, 0, data.length);
    }

    /**
     * Creates a new BitReader over a slice of the given data.
     *
     * <p>Reads from {@code data[offset]} to {@code data[offset + length - 1]}
     * without copying the underlying array.</p>
     *
     * @param data   the byte array to read from
     * @param offset start offset within the array
     * @param length number of bytes to read
     */
    public BitReader(byte[] data, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IllegalArgumentException(
                    "Invalid slice: offset=%d, length=%d, array length=%d"
                            .formatted(offset, length, data.length));
        }
        this.data = data;
        this.startOffset = offset;
        this.endOffset = offset + length;
        this.bytePos = offset;
        this.bitPos = 8;
    }

    /**
     * Reads a single bit.
     *
     * @return 0 or 1
     * @throws IOException if end of data is reached
     */
    public int readBit() throws IOException {
        if (bytePos >= endOffset) {
            throw new IOException("BitReader: end of data at byte " + (bytePos - startOffset));
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

    /** Returns the current byte position relative to the start offset. */
    public int bytePosition() { return bytePos - startOffset; }

    /** Returns the total number of bytes in this reader's slice. */
    public int length() { return endOffset - startOffset; }

    /** Returns whether there is more data to read. */
    public boolean hasMore() { return bytePos < endOffset; }
}
