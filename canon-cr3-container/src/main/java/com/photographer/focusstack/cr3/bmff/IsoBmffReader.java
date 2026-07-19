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

    /**
     * Canon CMT (metadata) UUID — the moov-level uuid that contains CNCV/CCTP/CTBO/THMB/CMTx boxes.
     * Bytes: 85 C0 B6 87 82 0F 11 E0 81 11 F4 CE 46 2B 6A 48
     */
    static final byte[] UUID_CANON_CMT = {
        (byte)0x85,(byte)0xC0,(byte)0xB6,(byte)0x87,
        (byte)0x82,(byte)0x0F,(byte)0x11,(byte)0xE0,
        (byte)0x81,(byte)0x11,(byte)0xF4,(byte)0xCE,
        (byte)0x46,(byte)0x2B,(byte)0x6A,(byte)0x48
    };

    /**
     * Canon PRVW (preview) UUID — a top-level uuid that contains 8 unknown bytes
     * followed by the PRVW child box.
     * Bytes: EA F4 2B 5E 1C 98 4B 88 B9 FB B7 DC 40 6E 4D 16
     */
    static final byte[] UUID_CANON_PRVW = {
        (byte)0xEA,(byte)0xF4,(byte)0x2B,(byte)0x5E,
        (byte)0x1C,(byte)0x98,(byte)0x4B,(byte)0x88,
        (byte)0xB9,(byte)0xFB,(byte)0xB7,(byte)0xDC,
        (byte)0x40,(byte)0x6E,(byte)0x4D,(byte)0x16
    };

    /**
     * XMP data UUID — a top-level uuid whose entire payload is raw XMP text, not BMFF boxes.
     * Bytes: BE 7A CF CB 97 A9 42 E8 9C 71 99 94 91 E3 AF AC
     */
    static final byte[] UUID_XMP = {
        (byte)0xBE,(byte)0x7A,(byte)0xCF,(byte)0xCB,
        (byte)0x97,(byte)0xA9,(byte)0x42,(byte)0xE8,
        (byte)0x9C,(byte)0x71,(byte)0x99,(byte)0x94,
        (byte)0x91,(byte)0xE3,(byte)0xAF,(byte)0xAC
    };

    /** Box types that are known containers and should be recursively parsed. */
    private static final java.util.Set<String> CONTAINER_TYPES = java.util.Set.of(
            "moov", "trak", "mdia", "minf", "stbl", "dinf", "edts",
            "mvex", "moof", "traf", "mfra", "udta", "meta", "iloc",
            "CCTP", "CRAW", "CDI1", "uuid", "stsd"
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

            // Validate that the box fits within the parent range
            if (size > 0) {
                // Overflow guard
                long boxEnd = pos + size;
                if (boxEnd < pos) {
                    LOG.warn("Box '{}' at offset {} overflows (size {})", type, pos, size);
                    break;
                }
                // Bounds check: box must not exceed parent end
                if (end >= 0 && boxEnd > end) {
                    LOG.warn("Box '{}' at offset {} (size {}) exceeds parent boundary {}",
                            type, pos, size, end);
                    break;
                }
            }

            // Parse children for known container types
            List<IsoBox> children;
            if (CONTAINER_TYPES.contains(type)) {
                long headerSize = extendedHeader ? 16 : 8;
                long childStart = pos + headerSize;
                if ("meta".equals(type) || "CDI1".equals(type)) {
                    childStart += 4; // FullBox version/flags
                } else if ("CCTP".equals(type)) {
                    childStart += 12; // 3-long CCTP-specific header
                } else if ("CRAW".equals(type)) {
                    childStart += 82; // Visual SampleEntry: 82 bytes before children
                } else if ("uuid".equals(type)) {
                    // Read 16-byte UUID to decide how to parse children
                    byte[] uuid = new byte[16];
                    input.seek(pos + headerSize);
                    try { input.readFully(uuid); } catch (java.io.EOFException e) { uuid = new byte[16]; }
                    childStart = pos + headerSize + 16; // skip header + UUID

                    if (uuidEquals(uuid, UUID_CANON_PRVW)) {
                        // Canon PRVW uuid has 8 extra bytes before the PRVW child box
                        childStart += 8;
                        LOG.debug("uuid at {} identified as Canon PRVW uuid, childStart={}", pos, childStart);
                    } else if (uuidEquals(uuid, UUID_XMP)) {
                        // XMP uuid payload is raw XML text, not BMFF boxes — skip child parsing
                        LOG.debug("uuid at {} identified as XMP uuid, skipping child box parsing", pos);
                        boxes.add(new IsoBox(type, size, pos, List.of(), extendedHeader));
                        pos += size;
                        continue;
                    } else {
                        LOG.debug("uuid at {} UUID={}, parsing children from offset {}",
                                pos, uuidToHex(uuid), childStart);
                    }
                } else if ("stsd".equals(type)) {
                    childStart += 8; // FullBox 4 bytes + count 4 bytes
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

    /** Returns true if two 16-byte UUID arrays are equal. */
    static boolean uuidEquals(byte[] a, byte[] b) {
        if (a.length != 16 || b.length != 16) return false;
        for (int i = 0; i < 16; i++) {
            if (a[i] != b[i]) return false;
        }
        return true;
    }

    /** Formats a 16-byte UUID as a hex string for logging. */
    private static String uuidToHex(byte[] uuid) {
        StringBuilder sb = new StringBuilder(32);
        for (byte b : uuid) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}
