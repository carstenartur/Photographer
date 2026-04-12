package com.photographer.focusstack.cr3.bmff;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads ISO Base Media File Format (ISO 14496-12) box structures from an
 * {@link ImageInputStream}.
 *
 * <p>This reader is format-agnostic and can parse any BMFF-based file (CR3, HEIF, MP4).
 * Canon CR3-specific interpretation is handled by higher-level classes.</p>
 *
 * <p>Uses {@code long} offsets throughout and never loads the entire file into memory,
 * making it safe for multi-gigabyte files.</p>
 */
public final class IsoBmffReader {

    private static final Logger LOG = LoggerFactory.getLogger(IsoBmffReader.class);

    /** Minimum valid box size (4 bytes size + 4 bytes type). */
    private static final int MIN_BOX_HEADER = 8;

    /** Box types that are known containers and should be recursively parsed. */
    private static final java.util.Set<String> CONTAINER_TYPES = java.util.Set.of(
            "moov", "trak", "mdia", "minf", "stbl", "dinf", "edts",
            "mvex", "moof", "traf", "mfra", "udta", "meta", "iloc",
            // CR3-specific containers
            "CMT1", "CMT2", "CMT3", "CMT4", "CNCV", "CCTP", "CTBO"
    );

    private IsoBmffReader() {
        // utility class
    }

    /**
     * Reads all top-level boxes from the input stream.
     *
     * @param input the image input stream positioned at the start of the file
     * @return list of parsed top-level boxes
     * @throws IOException if an I/O error occurs
     */
    public static List<IsoBox> readBoxes(ImageInputStream input) throws IOException {
        input.setByteOrder(ByteOrder.BIG_ENDIAN);
        return readBoxes(input, 0, input.length());
    }

    /**
     * Reads boxes within the given byte range.
     *
     * @param input  the image input stream
     * @param start  start offset (inclusive)
     * @param end    end offset (exclusive), or -1 if unknown
     * @return list of parsed boxes
     * @throws IOException if an I/O error occurs
     */
    public static List<IsoBox> readBoxes(ImageInputStream input, long start, long end)
            throws IOException {
        List<IsoBox> boxes = new ArrayList<>();
        long pos = start;

        while (end < 0 || pos < end) {
            input.seek(pos);

            // Need at least 8 bytes for a box header
            if (end >= 0 && (end - pos) < MIN_BOX_HEADER) {
                break;
            }

            long size;
            try {
                size = Integer.toUnsignedLong(input.readInt());
            } catch (java.io.EOFException e) {
                break;
            }

            byte[] typeBytes = new byte[4];
            try {
                input.readFully(typeBytes);
            } catch (java.io.EOFException e) {
                break;
            }
            String type = new String(typeBytes, StandardCharsets.US_ASCII);

            // Handle extended size (size == 1 means 64-bit size follows)
            boolean extendedHeader = false;
            if (size == 1) {
                size = input.readLong();
                extendedHeader = true;
                if (size < 16) {
                    LOG.warn("Invalid extended box size {} for type '{}' at offset {}", size, type, pos);
                    break;
                }
            } else if (size == 0) {
                // size == 0 means box extends to end of file
                size = (end >= 0) ? (end - pos) : -1;
                if (size < 0) {
                    LOG.debug("Box '{}' at offset {} extends to EOF (size unknown)", type, pos);
                    // Read what we can — create a box with unknown size
                    boxes.add(new IsoBox(type, 0, pos));
                    break;
                }
            } else if (size < MIN_BOX_HEADER) {
                LOG.warn("Invalid box size {} for type '{}' at offset {}", size, type, pos);
                break;
            }

            LOG.debug("Box '{}' at offset {} size {}", type, pos, size);

            // Parse children for known container types
            List<IsoBox> children;
            if (CONTAINER_TYPES.contains(type)) {
                long headerSize = extendedHeader ? 16 : 8;
                // 'meta' box has a 4-byte version/flags field after the header
                long childStart = pos + headerSize;
                if ("meta".equals(type)) {
                    childStart += 4;
                }
                children = readBoxes(input, childStart, pos + size);
            } else {
                children = List.of();
            }

            boxes.add(new IsoBox(type, size, pos, children, extendedHeader));
            pos += size;
        }

        return boxes;
    }

    /**
     * Reads the raw payload bytes of a box.
     *
     * @param input the image input stream
     * @param box   the box whose payload to read
     * @return the payload bytes
     * @throws IOException           if an I/O error occurs
     * @throws IllegalArgumentException if the payload is too large (> 100 MB)
     */
    public static byte[] readBoxPayload(ImageInputStream input, IsoBox box) throws IOException {
        long dataSize = box.dataSize();
        if (dataSize < 0) {
            throw new IllegalArgumentException(
                    "Box '%s' has unknown size; cannot read payload".formatted(box.type()));
        }
        if (dataSize > 100 * 1024 * 1024) {
            throw new IllegalArgumentException(
                    "Box '%s' payload too large: %d bytes (max 100 MB)".formatted(box.type(), dataSize));
        }
        byte[] data = new byte[(int) dataSize];
        input.seek(box.dataOffset());
        input.readFully(data);
        return data;
    }
}
