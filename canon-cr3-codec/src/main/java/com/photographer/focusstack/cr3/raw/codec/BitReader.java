package com.photographer.focusstack.cr3.raw.codec;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

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

    private static final VarHandle BIG_ENDIAN_INT =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);

    private final byte[] data;
    private final int startOffset;
    private final int endOffset;
    private int nextByte;
    // Unread bits are left-aligned, with the next bit at the sign bit.
    private long buffer;
    private int available;

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
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new IllegalArgumentException(
                    "Invalid slice: offset=%d, length=%d, array length=%d"
                            .formatted(offset, length, data.length));
        }
        this.data = data;
        this.startOffset = offset;
        this.endOffset = offset + length;
        this.nextByte = offset;
    }

    /**
     * Reads a single bit.
     *
     * @return 0 or 1
     * @throws IOException if end of data is reached
     */
    public int readBit() throws IOException {
        if (available == 0) {
            refill();
        }
        int bit = (int) (buffer >>> 63);
        consume(1);
        return bit;
    }

    /**
     * Reads the specified number of bits as an unsigned integer.
     *
     * @param count number of bits to read (0-32)
     * @return the unsigned value
     * @throws IOException if end of data is reached
     */
    public int readBits(int count) throws IOException {
        if (count < 0 || count > 32) {
            throw new IllegalArgumentException("Invalid bit count: " + count);
        }
        if (count == 0) {
            return 0;
        }
        while (available < count) {
            refill();
        }
        int value = (int) (buffer >>> (64 - count));
        consume(count);
        return value;
    }

    /**
     * Reads a unary code (count of 0-bits before the first 1-bit).
     *
     * @return the unary value
     * @throws IOException if end of data is reached
     */
    public int readUnary() throws IOException {
        return readUnary(Integer.MAX_VALUE);
    }

    /** A native Rice symbol has a bounded unary prefix, including the escape code. */
    int readUnary(int maximum) throws IOException {
        int count = 0;
        while (true) {
            if (available == 0) {
                refill();
            }
            int zeros = Math.min(Long.numberOfLeadingZeros(buffer), available);
            if (zeros > maximum - count) {
                throw new IOException("Unsupported CRX Rice quotient");
            }
            count += zeros;
            if (zeros < available) {
                consume(zeros + 1);
                return count;
            }
            consume(zeros);
        }
    }

    private void consume(int bits) {
        buffer <<= bits;
        available -= bits;
    }

    private void refill() throws IOException {
        if (endOffset - nextByte >= Integer.BYTES) {
            long word = Integer.toUnsignedLong((int) BIG_ENDIAN_INT.get(data, nextByte));
            buffer |= word << (32 - available);
            nextByte += Integer.BYTES;
            available += Integer.SIZE;
        } else if (nextByte < endOffset) {
            buffer |= (data[nextByte++] & 255L) << (56 - available);
            available += Byte.SIZE;
        } else {
            // Match sequential reads: a truncated multi-bit value consumes the slice through EOF.
            consume(available);
            throw new IOException("BitReader: end of data at byte " + length());
        }
    }

    /** Returns the number of complete bytes consumed relative to the start offset. */
    public int bytePosition() { return nextByte - startOffset - Math.ceilDiv(available, 8); }

    /** Includes a partially consumed final byte, excluding prefetched bytes. */
    int consumedBytes() { return nextByte - startOffset - available / 8; }

    /** Returns the total number of bytes in this reader's slice. */
    public int length() { return endOffset - startOffset; }

    /** Returns whether there is more data to read. */
    public boolean hasMore() { return available > 0 || nextByte < endOffset; }
}
