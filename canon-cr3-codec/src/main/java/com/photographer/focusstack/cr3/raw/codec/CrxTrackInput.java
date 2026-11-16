package com.photographer.focusstack.cr3.raw.codec;

import javax.imageio.stream.ImageInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.Objects;

/** Random access within one CRX track. Each active entropy stream keeps its own read-ahead window. */
final class CrxTrackInput {
    private static final int READ_AHEAD = 32 * 1024;
    private static final VarHandle BIG_ENDIAN_INT =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle BIG_ENDIAN_LONG =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    @FunctionalInterface
    private interface Reader {
        void readFully(long position, ByteBuffer target) throws IOException;
    }

    private final byte[] bytes;
    private final Reader reader;
    private final long start;
    final int length;

    private CrxTrackInput(byte[] bytes, Reader reader, long start, int length) {
        this.bytes = bytes;
        this.reader = reader;
        this.start = start;
        this.length = length;
    }

    static CrxTrackInput fromArray(byte[] bytes) {
        return new CrxTrackInput(bytes, null, 0, bytes.length);
    }

    static CrxTrackInput fromStream(ImageInputStream input, long start, int length) throws IOException {
        Objects.requireNonNull(input);
        long end = checkedEnd(start, length);
        synchronized (input) {
            // Entropy readers may legitimately finish before the last padding byte.
            long fileLength = input.length();
            if (fileLength < 0) {
                input.seek(end - 1);
                input.readUnsignedByte();
            } else if (end > fileLength) {
                throw new EOFException("Incomplete CRX track");
            }
        }
        return new CrxTrackInput(null, (position, target) -> {
            synchronized (input) {
                input.seek(position);
                input.readFully(target.array(), 0, target.remaining());
            }
        }, start, length);
    }

    static CrxTrackInput fromChannel(FileChannel channel, long start, int length) throws IOException {
        Objects.requireNonNull(channel);
        if (checkedEnd(start, length) > channel.size()) {
            throw new EOFException("Incomplete CRX track");
        }
        return new CrxTrackInput(null, (position, target) -> {
            while (target.hasRemaining()) {
                int n = channel.read(target, position + target.position());
                if (n < 0) throw new EOFException("Incomplete CRX byte slice");
                if (n == 0) throw new IOException("No progress reading CRX byte slice");
            }
        }, start, length);
    }

    private static long checkedEnd(long start, int length) throws IOException {
        if (start < 0 || length <= 0 || start > Long.MAX_VALUE - length) {
            throw new IOException("Invalid CRX track extent");
        }
        return start + length;
    }

    Window window(int offset, int count) {
        if (offset < 0 || count < 0 || offset > length - count) {
            throw new IllegalArgumentException("Invalid CRX byte slice");
        }
        return bytes != null ? new Window(bytes, offset, count) : new Window(reader, start + offset, count);
    }

    /** Positions are relative to the slice. An array slice needs no copy or I/O. */
    static final class Window {
        private final byte[] data;
        private final Reader reader;
        private final ByteBuffer target;
        private final long start;
        final int length;
        private int base;
        private int limit;

        Window(byte[] bytes, int offset, int length) {
            if (offset < 0 || length < 0 || offset > bytes.length - length) {
                throw new IllegalArgumentException("Invalid CRX byte slice");
            }
            this.data = bytes;
            this.reader = null;
            this.target = null;
            this.start = 0;
            this.length = length;
            this.base = -offset;
            this.limit = length;
        }

        private Window(Reader reader, long start, int length) {
            this.reader = reader;
            this.start = start;
            this.length = length;
            data = new byte[Math.min(READ_AHEAD, length)];
            target = ByteBuffer.wrap(data);
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

        long longWord(int pos) throws IOException {
            return (long) BIG_ENDIAN_LONG.get(data, index(pos, Long.BYTES));
        }

        private int index(int pos, int count) throws IOException {
            if (pos < 0 || pos < base || pos > limit - count) {
                refill(pos, count);
            }
            return pos - base;
        }

        private void refill(int pos, int count) throws IOException {
            if (pos < 0 || pos > length - count || reader == null) {
                throw new EOFException("Read outside CRX byte slice");
            }
            int available = Math.min(data.length, length - pos);
            reader.readFully(start + pos, target.clear().limit(available));
            base = pos;
            limit = pos + available;
        }
    }
}
