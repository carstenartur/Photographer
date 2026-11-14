package com.photographer.focusstack.dng.imageio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import javax.imageio.IIOException;
import javax.imageio.plugins.tiff.TIFFTag;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageInputStreamImpl;

/**
 * A TIFF view relative to the DNG header. It exposes just the fields used to describe
 * or decode the Bayer image. This lets the standard TIFF reader read nonstandard DNG
 * tags without loading embedded original files, previews, or unrelated private data.
 */
final class DngTiffInput extends ImageInputStreamImpl {
    private static final int ENTRY_BYTES = 12;
    private static final long MAX_FIELD_DATA_BYTES = 64L * 1024 * 1024;
    private final ImageInputStream source;
    private final long start;
    private final long length;
    private final long ifdOffset;
    private final byte[] directory;
    private final int directoryLength;
    private final byte[] single = new byte[1];

    DngTiffInput(ImageInputStream source) throws IOException {
        this.source = source;
        start = source.getStreamPosition();
        long end = source.length();
        length = end < 0 ? -1 : end - start;
        byte[] header = new byte[8];
        source.readFully(header);
        ByteOrder order = header[0] == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        ifdOffset = Integer.toUnsignedLong(ByteBuffer.wrap(header).order(order).getInt(4));
        source.seek(Math.addExact(start, ifdOffset));
        byte[] countBytes = new byte[2];
        source.readFully(countBytes);
        int entries = Short.toUnsignedInt(ByteBuffer.wrap(countBytes).order(order).getShort());
        directory = new byte[2 + entries * ENTRY_BYTES + 4];
        source.readFully(directory, 2, entries * ENTRY_BYTES + 4);
        ByteBuffer fields = ByteBuffer.wrap(directory).order(order);
        int retained = 0;
        long totalBytes = 0;
        for (int i = 0; i < entries; i++) {
            int entry = 2 + i * ENTRY_BYTES;
            int tag = Short.toUnsignedInt(fields.getShort(entry));
            if (!DngImageMetadata.isCopiedTag(tag) && !neededToDecode(tag)) {
                continue;
            }
            int type = Short.toUnsignedInt(fields.getShort(entry + 2));
            if (type < TIFFTag.MIN_DATATYPE || type > TIFFTag.MAX_DATATYPE) {
                throw new IIOException("Invalid TIFF field type for DNG tag " + tag);
            }
            long bytes = TIFFTag.getSizeOfType(type) * Integer.toUnsignedLong(fields.getInt(entry + 4));
            totalBytes += bytes;
            if (totalBytes > MAX_FIELD_DATA_BYTES) {
                throw new IIOException("DNG image directory exceeds 64 MiB of field data");
            }
            if (bytes > 4 && length >= 0) {
                long offset = Integer.toUnsignedLong(fields.getInt(entry + 8));
                if (offset + bytes > length) {
                    throw new IIOException("DNG tag " + tag + " points outside the input");
                }
            }
            System.arraycopy(directory, entry, directory, 2 + retained * ENTRY_BYTES, ENTRY_BYTES);
            retained++;
        }
        fields.putShort(0, (short) retained);
        fields.putInt(2 + retained * ENTRY_BYTES, 0); // Only the RAW IFD is exposed.
        directoryLength = 2 + retained * ENTRY_BYTES + 4;
    }

    private static boolean neededToDecode(int tag) {
        // FillOrder, StripOffsets, RowsPerStrip, StripByteCounts and four tile geometry/offset fields.
        return tag == 266 || tag == 273 || tag == 278 || tag == 279 || (tag >= 322 && tag <= 325);
    }

    @Override
    public long length() {
        return length;
    }

    @Override
    public int read() throws IOException {
        return read(single, 0, 1) < 0 ? -1 : single[0] & 0xff;
    }

    @Override
    public int read(byte[] bytes, int offset, int count) throws IOException {
        checkClosed();
        Objects.checkFromIndexSize(offset, count, bytes.length);
        bitOffset = 0;
        if (count == 0) return 0;
        if (streamPos >= ifdOffset && streamPos < ifdOffset + directoryLength) {
            int index = (int) (streamPos - ifdOffset);
            int copied = Math.min(count, directoryLength - index);
            System.arraycopy(directory, index, bytes, offset, copied);
            streamPos += copied;
            return copied;
        }
        if (streamPos < ifdOffset) {
            count = (int) Math.min(count, ifdOffset - streamPos);
        }
        long absolute = Math.addExact(start, streamPos);
        if (source.getStreamPosition() != absolute) source.seek(absolute);
        int copied = source.read(bytes, offset, count);
        if (copied > 0) streamPos += copied;
        return copied;
    }
}
