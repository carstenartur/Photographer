package com.photographer.focusstack.cr3.raw.codec;

import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import javax.imageio.stream.ImageOutputStream;

/** Writes the CRX V1 lossless sample defined by Photographer's clean-room format reference. */
public final class CrxLosslessEncoder {
    private static final int PLANES = 4;
    private static final int MAX_TILES = 16;
    private static final int RECORD_BODY_BYTES = 8;
    private static final int RECORD_BYTES = 4 + RECORD_BODY_BYTES;
    private static final int TILE_DIRECTORY_BYTES = RECORD_BYTES * (1 + 2 * PLANES);
    private static final int SENSOR_MIDPOINT = 1 << 13;
    private static final byte[] ALIGNMENT_PADDING = new byte[Long.BYTES - 1];

    private CrxLosslessEncoder() {
    }

    private static int directorySize(int width, int tileWidth) {
        if (width <= 0 || (width & 1) != 0 || tileWidth <= 0 || tileWidth > width || (tileWidth & 1) != 0) {
            throw new IllegalArgumentException("CRX width and tile width must be positive and even, with tile width at most the image width");
        }
        int tiles = Math.ceilDiv(width, tileWidth);
        if (tiles > MAX_TILES) {
            throw new IllegalArgumentException("A CRX V1 sample supports at most 16 horizontal tiles");
        }
        return tiles * TILE_DIRECTORY_BYTES;
    }

    /**
     * Writes a CRX sample at the current byte-aligned position using a lossless V1 camera directory.
     * The sensor has one band of unsigned 14-bit sample codes and even dimensions. The plane order,
     * structural fields and optional terminal word are retained. Byte counts and band padding are
     * recalculated. The caller retains ownership of the output and its byte order.
     */
    public static void write(ImageOutputStream output, Raster sensor, int tileWidth,
                             byte[] cameraDirectory) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(sensor, "sensor");
        int width = sensor.getWidth();
        int height = sensor.getHeight();
        int directoryBytes = directorySize(width, tileWidth);
        if (height <= 0 || (height & 1) != 0 || (long) width * height > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Unsupported CRX sensor dimensions");
        }
        if (sensor.getNumBands() != 1 || sensor.getTransferType() == DataBuffer.TYPE_FLOAT
                || sensor.getTransferType() == DataBuffer.TYPE_DOUBLE) {
            throw new IllegalArgumentException("CRX requires a one-band integer sensor raster");
        }
        if (output.getBitOffset() != 0) {
            throw new IllegalArgumentException("A CRX sample must start at a byte boundary");
        }

        var directory = ByteBuffer.wrap(cameraDirectory.clone());
        validateDirectory(directory, directoryBytes);
        long start = output.getStreamPosition();
        ByteOrder byteOrder = output.getByteOrder();
        output.setByteOrder(ByteOrder.BIG_ENDIAN);
        try {
            output.write(directory.array());
            var bits = new PlaneBits(output);
            int[] previousRow = new int[tileWidth / 2];
            int tiles = directoryBytes / TILE_DIRECTORY_BYTES;
            for (int tile = 0; tile < tiles; tile++) {
                int tileX = tile * tileWidth;
                int planeWidth = Math.min(tileWidth, width - tileX) / 2;
                long tileStart = output.getStreamPosition();
                int tileRecord = tile * TILE_DIRECTORY_BYTES;
                for (int ordinal = 0; ordinal < PLANES; ordinal++) {
                    int planeRecord = tileRecord + RECORD_BYTES * (1 + 2 * ordinal);
                    int bandRecord = planeRecord + RECORD_BYTES;
                    int plane = (directory.get(planeRecord + 8) & 0xff) >>> 4;
                    int x = sensor.getMinX() + tileX + (plane & 1);
                    int y = sensor.getMinY() + plane / 2;
                    long planeStart = output.getStreamPosition();
                    writePlane(bits, sensor, x, y, planeWidth, height / 2, previousRow);
                    int remainder = (int) ((output.getStreamPosition() - planeStart) % Long.BYTES);
                    int padding = remainder == 0 ? 0 : Long.BYTES - remainder;
                    output.write(ALIGNMENT_PADDING, 0, padding);
                    long planeEnd = output.getStreamPosition();
                    if (planeEnd - start > Integer.MAX_VALUE) {
                        throw new IOException("CRX sample exceeds the supported size");
                    }
                    int planeBytes = (int) (planeEnd - planeStart);
                    directory.putInt(planeRecord + 4, planeBytes);
                    directory.putInt(bandRecord + 4, planeBytes);
                    directory.putShort(bandRecord + 10, (short) padding);
                }
                directory.putInt(tileRecord + 4, (int) (output.getStreamPosition() - tileStart));
            }
            long end = output.getStreamPosition();
            output.seek(start);
            output.write(directory.array());
            output.seek(end);
        } finally {
            output.setByteOrder(byteOrder);
        }
    }

    private static void validateDirectory(ByteBuffer directory, int bytes) throws IOException {
        if (directory.capacity() != bytes
                && (directory.capacity() != bytes + Integer.BYTES || directory.getInt(bytes) != 0)) {
            throw new IOException("Unsupported CRX V1 camera directory length or terminator");
        }
        var version = CrxHeader.CodecVersion.V1;
        for (int tile = 0; tile < bytes; tile += TILE_DIRECTORY_BYTES) {
            requireRecord(directory, tile, version.tileMarker);
            int seenPlanes = 0;
            for (int ordinal = 0; ordinal < PLANES; ordinal++) {
                int plane = tile + RECORD_BYTES * (1 + 2 * ordinal);
                int band = plane + RECORD_BYTES;
                requireRecord(directory, plane, version.planeMarker);
                requireRecord(directory, band, version.subbandMarker);
                int planeFlags = directory.get(plane + 8) & 0xff;
                int index = planeFlags >>> 4;
                if (index >= PLANES || (seenPlanes & (1 << index)) != 0
                        || (planeFlags & 0x0e) != 8 || (directory.get(band + 8) & 0xf0) != 0) {
                    throw new IOException("Unsupported CRX V1 camera plane layout");
                }
                seenPlanes |= 1 << index;
            }
        }
    }

    private static void requireRecord(ByteBuffer directory, int offset, int marker) throws IOException {
        if (Short.toUnsignedInt(directory.getShort(offset)) != marker
                || Short.toUnsignedInt(directory.getShort(offset + 2)) != RECORD_BODY_BYTES) {
            throw new IOException("Unexpected CRX V1 directory record");
        }
    }

    private static void writePlane(PlaneBits bits, Raster sensor, int x0, int y0, int width, int height,
                                   int[] previous) throws IOException {
        int first = sample(sensor, x0, y0);
        int opening = CrxResidualSignMapper.encodeZigZag(first - SENSOR_MIDPOINT);
        // 32 zero bits, then the 18-bit prefix and the 14-bit opening literal.
        bits.output.writeLong((128L << 14) | opening);
        previous[0] = first;
        int k = 2;
        for (int x = 1; x < width; x++) {
            int current = sample(sensor, x0 + 2 * x, y0);
            int unsigned = CrxResidualSignMapper.encodeZigZag(current - previous[x - 1]);
            bits.writeRice(unsigned, k);
            k = CrxEntropyState.adaptK(k, unsigned);
            previous[x] = current;
        }

        for (int y = 1; y < height; y++) {
            int left = previous[0];
            int aboveLeft = left;
            for (int x = 0; x < width; x++) {
                int above = previous[x];
                boolean last = x == width - 1;
                int aboveRight = last ? above : previous[x + 1];
                if (!last && left == above && left == aboveRight) {
                    bits.write(0, 1); // Select regular prediction at a run-eligible site.
                }
                int current = sample(sensor, x0 + 2 * x, y0 + 2 * y);
                int prediction = CrxMedPredictor.med(left, above, aboveLeft);
                int unsigned = CrxResidualSignMapper.encodeZigZag(current - prediction);
                bits.writeRice(unsigned, k);
                int adaptation = last ? unsigned : (unsigned >>> 1) + Math.abs(aboveRight - above);
                k = CrxEntropyState.adaptK(k, adaptation);
                previous[x] = current;
                left = current;
                aboveLeft = above;
            }
        }
        bits.finishPlane();
    }

    private static int sample(Raster sensor, int x, int y) {
        int value = sensor.getSample(x, y, 0);
        if (value < 0 || value >= 2 * SENSOR_MIDPOINT) {
            throw new IllegalArgumentException("CRX sample outside the 14-bit sensor range at (" + x + ", " + y + "): " + value);
        }
        return value;
    }

    private static final class PlaneBits {
        private final ImageOutputStream output;
        private final byte[] buffer = new byte[8192];
        private int bufferedBytes;
        private long pending;
        private int pendingBits;

        PlaneBits(ImageOutputStream output) {
            this.output = output;
        }

        void writeRice(int unsigned, int k) throws IOException {
            int quotient = unsigned >>> k;
            if (quotient < CrxEntropyState.ESCAPE_QUOTIENT) {
                write(1, quotient + 1);
                write(unsigned & ((1 << k) - 1), k);
            } else {
                write(1, CrxEntropyState.ESCAPE_QUOTIENT + 1);
                write(unsigned, CrxEntropyState.LITERAL_BITS);
            }
        }

        void write(int value, int length) throws IOException {
            pending = (pending << length) | value;
            pendingBits += length;
            while (pendingBits >= Byte.SIZE) {
                pendingBits -= Byte.SIZE;
                buffer[bufferedBytes++] = (byte) (pending >>> pendingBits);
                if (bufferedBytes == buffer.length) {
                    flushBytes();
                }
            }
        }

        void finishPlane() throws IOException {
            if (pendingBits != 0) {
                write(0, Byte.SIZE - pendingBits);
            }
            flushBytes();
            pending = 0;
        }

        private void flushBytes() throws IOException {
            if (bufferedBytes != 0) {
                output.write(buffer, 0, bufferedBytes);
                bufferedBytes = 0;
            }
        }
    }
}
