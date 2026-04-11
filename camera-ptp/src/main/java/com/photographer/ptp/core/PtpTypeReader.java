package com.photographer.ptp.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Little-endian reader for PTP data types as specified in ISO 15740.
 * PTP uses little-endian byte order for all multi-byte values.
 */
public class PtpTypeReader {
    private final ByteBuffer buffer;

    public PtpTypeReader(byte[] data) {
        this.buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    }

    public PtpTypeReader(byte[] data, int offset, int length) {
        this.buffer = ByteBuffer.wrap(data, offset, length).order(ByteOrder.LITTLE_ENDIAN);
    }

    public int readInt8() {
        return buffer.get() & 0xFF;
    }

    public int readInt16() {
        return buffer.getShort() & 0xFFFF;
    }

    public int readInt32() {
        return buffer.getInt();
    }

    public long readUInt32() {
        return buffer.getInt() & 0xFFFFFFFFL;
    }

    public long readInt64() {
        return buffer.getLong();
    }

    /**
     * Reads a PTP string: 1 byte length (in chars including null terminator),
     * followed by UCS-2LE characters.
     */
    public String readPtpString() {
        int numChars = readInt8();
        if (numChars == 0) {
            return "";
        }
        char[] chars = new char[numChars - 1]; // exclude null terminator
        for (int i = 0; i < numChars - 1; i++) {
            chars[i] = (char) readInt16();
        }
        readInt16(); // consume null terminator
        return new String(chars);
    }

    /**
     * Reads a PTP array: 4-byte count followed by that many 32-bit values.
     */
    public int[] readUInt32Array() {
        int count = readInt32();
        int[] result = new int[count];
        for (int i = 0; i < count; i++) {
            result[i] = readInt32();
        }
        return result;
    }

    /**
     * Reads a PTP array of 16-bit values: 4-byte count followed by that many 16-bit values.
     */
    public int[] readUInt16Array() {
        int count = readInt32();
        int[] result = new int[count];
        for (int i = 0; i < count; i++) {
            result[i] = readInt16();
        }
        return result;
    }

    public byte[] readBytes(int length) {
        byte[] data = new byte[length];
        buffer.get(data);
        return data;
    }

    public int remaining() {
        return buffer.remaining();
    }

    public int position() {
        return buffer.position();
    }

    public void skip(int bytes) {
        buffer.position(buffer.position() + bytes);
    }

    public boolean hasRemaining() {
        return buffer.hasRemaining();
    }
}
