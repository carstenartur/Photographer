package com.photographer.focusstack.cr3.raw.codec;

import java.io.IOException;

/** MSB-first bitwise reader for CRX entropy-coded byte arrays. */
public final class BitReader {

    private final byte[] data;
    private final int startOffset;
    private final int endOffset;
    private int bytePos;
    private int bitPos;

    public BitReader(byte[] data) {
        this(data, 0, data.length);
    }

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

    public int readUnary() throws IOException {
        int count = 0;
        while (readBit() == 0) {
            count++;
        }
        return count;
    }

    public int bytePosition() { return bytePos - startOffset; }
    public int length() { return endOffset - startOffset; }
    public boolean hasMore() { return bytePos < endOffset; }
}
