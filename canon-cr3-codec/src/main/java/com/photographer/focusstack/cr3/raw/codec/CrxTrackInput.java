package com.photographer.focusstack.cr3.raw.codec;

import javax.imageio.stream.ImageInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Objects;

/** Random access within one CRX track. Each active entropy stream keeps its own read-ahead window. */
final class CrxTrackInput {
    private static final int READ_AHEAD = 32 * 1024;
    private static final VarHandle BIG_ENDIAN_INT =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);

    private final byte[] bytes;
    private final ImageInputStream input;
    private final long start;
    final int length;

    private CrxTrackInput(byte[] bytes, ImageInputStream input, long start, int length) {
        this.bytes = bytes;
        this.input = input;
        this.start = start;
        this.length = length;
    }

    static CrxTrackInput fromArray(byte[] bytes) {
        return new CrxTrackInput(bytes, null, 0, bytes.length);
    }

    static CrxTrackInput fromStream(ImageInputStream input, long start, int length) throws IOException {
        Objects.requireNonNull(input);
        if (start < 0 || length <= 0 || start > Long.MAX_VALUE - length) {
            throw new IOException("Invalid CRX track extent");
        }
        // Entropy readers may legitimately finish before the last padding byte.
        long end = start + length;
        long fileLength = input.length();
        if (fileLength < 0) {
            input.seek(end - 1);
            input.readUnsignedByte();
        } else if (end > fileLength) {
            throw new EOFException("Incomplete CRX track");
        }
        return new CrxTrackInput(null, input, start, length);
    }

    Window window(int offset, int count) {
        if (offset < 0 || count < 0 || offset > length - count) {
            throw new IllegalArgumentException("Invalid CRX byte slice");
        }
        return bytes != null ? new Window(bytes, offset, count) : new Window(input, start + offset, count);
    }

    /** Positions are relative to the slice. An array slice needs no copy or I/O. */
    static final class Window {
        private final byte[] data;
        private final ImageInputStream input;
        private final long start;
        final int length;
        private int base;
        private int limit;

        Window(byte[] bytes, int offset, int length) {
            if (offset < 0 || length < 0 || offset > bytes.length - length) {
                throw new IllegalArgumentException("Invalid CRX byte slice");
            }
            this.data = bytes;
            this.input = null;
            this.start = 0;
            this.length = length;
            this.base = -offset;
            this.limit = length;
        }

        private Window(ImageInputStream input, long start, int length) {
            this.input = input;
            this.start = start;
            this.length = length;
            data = new byte[Math.min(READ_AHEAD, length)];
        }

        int unsignedByte(int pos) throws IOException {
            return data[index(pos, 1)] & 255;
        }

        int unsignedShort(int pos) throws IOException {
            int i = index(pos, 2);
            return (data[i] & 255) << 8 | (data[i + 1] & 255);
        }

        int word(int pos) throws IOException {
            return (int) BIG_ENDIAN_INT.get(data, index(pos, Integer.BYTES));
        }

        private int index(int pos, int count) throws IOException {
            if (pos < 0 || pos < base || pos > limit - count) {
                refill(pos, count);
            }
            return pos - base;
        }

        private void refill(int pos, int count) throws IOException {
            if (pos < 0 || pos > length - count || input == null) {
                throw new EOFException("Read outside CRX byte slice");
            }
            int available = Math.min(data.length, length - pos);
            input.seek(start + pos);
            input.readFully(data, 0, available);
            base = pos;
            limit = pos + available;
        }
    }
}
