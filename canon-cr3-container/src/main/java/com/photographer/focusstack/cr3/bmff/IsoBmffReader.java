package com.photographer.focusstack.cr3.bmff;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Reads ISO Base Media File Format box structures from an ImageInputStream. */
public final class IsoBmffReader {

    private static final Logger LOG = LoggerFactory.getLogger(IsoBmffReader.class);
    private static final int MIN_BOX_HEADER = 8;

    static final byte[] UUID_CANON_CMT = {
        (byte)0x85,(byte)0xC0,(byte)0xB6,(byte)0x87,
        (byte)0x82,(byte)0x0F,(byte)0x11,(byte)0xE0,
        (byte)0x81,(byte)0x11,(byte)0xF4,(byte)0xCE,
        (byte)0x46,(byte)0x2B,(byte)0x6A,(byte)0x48
    };

    static final byte[] UUID_CANON_PRVW = {
        (byte)0xEA,(byte)0xF4,(byte)0x2B,(byte)0x5E,
        (byte)0x1C,(byte)0x98,(byte)0x4B,(byte)0x88,
        (byte)0xB9,(byte)0xFB,(byte)0xB7,(byte)0xDC,
        (byte)0x40,(byte)0x6E,(byte)0x4D,(byte)0x16
    };

    static final byte[] UUID_XMP = {
        (byte)0xBE,(byte)0x7A,(byte)0xCF,(byte)0xCB,
        (byte)0x97,(byte)0xA9,(byte)0x42,(byte)0xE8,
        (byte)0x9C,(byte)0x71,(byte)0x99,(byte)0x94,
        (byte)0x91,(byte)0xE3,(byte)0xAF,(byte)0xAC
    };

    private static final java.util.Set<String> CONTAINER_TYPES = java.util.Set.of(
            "moov", "trak", "mdia", "minf", "stbl", "dinf", "edts",
            "mvex", "moof", "traf", "mfra", "udta", "meta", "iloc",
            "CCTP", "CRAW", "CDI1", "uuid", "stsd"
    );

    private IsoBmffReader() {
    }

    public static List<IsoBox> readBoxes(ImageInputStream input) throws IOException {
        input.setByteOrder(ByteOrder.BIG_ENDIAN);
        return readBoxes(input, 0, input.length());
    }

    public static List<IsoBox> readBoxes(ImageInputStream input, long start, long end)
            throws IOException {
        List<IsoBox> boxes = new ArrayList<>();
        long pos = start;

        while (end < 0 || pos < end) {
            input.seek(pos);
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

            boolean extendedHeader = false;
            if (size == 1) {
                size = input.readLong();
                extendedHeader = true;
                if (size < 16) {
                    LOG.warn("Invalid extended box size {} for type '{}' at offset {}", size, type, pos);
                    break;
                }
            } else if (size == 0) {
                size = (end >= 0) ? (end - pos) : -1;
                if (size < 0) {
                    LOG.debug("Box '{}' at offset {} extends to EOF (size unknown)", type, pos);
                    boxes.add(new IsoBox(type, 0, pos));
                    break;
                }
            } else if (size < MIN_BOX_HEADER) {
                LOG.warn("Invalid box size {} for type '{}' at offset {}", size, type, pos);
                break;
            }

            LOG.debug("Box '{}' at offset {} size {}", type, pos, size);

            if (size > 0) {
                long boxEnd = pos + size;
                if (boxEnd < pos) {
                    LOG.warn("Box '{}' at offset {} overflows (size {})", type, pos, size);
                    break;
                }
                if (end >= 0 && boxEnd > end) {
                    LOG.warn("Box '{}' at offset {} (size {}) exceeds parent boundary {}",
                            type, pos, size, end);
                    break;
                }
            }

            List<IsoBox> children;
            if (CONTAINER_TYPES.contains(type)) {
                long headerSize = extendedHeader ? 16 : 8;
                long childStart = pos + headerSize;
                if ("meta".equals(type) || "CDI1".equals(type)) {
                    childStart += 4;
                } else if ("CCTP".equals(type)) {
                    childStart += 12;
                } else if ("CRAW".equals(type)) {
                    childStart += 82;
                } else if ("uuid".equals(type)) {
                    byte[] uuid = new byte[16];
                    input.seek(pos + headerSize);
                    try {
                        input.readFully(uuid);
                    } catch (java.io.EOFException e) {
                        uuid = new byte[16];
                    }
                    childStart = pos + headerSize + 16;

                    if (uuidEquals(uuid, UUID_CANON_PRVW)) {
                        childStart += 8;
                        LOG.debug("uuid at {} identified as Canon PRVW uuid, childStart={}", pos, childStart);
                    } else if (uuidEquals(uuid, UUID_XMP)) {
                        LOG.debug("uuid at {} identified as XMP uuid, skipping child box parsing", pos);
                        boxes.add(new IsoBox(type, size, pos, List.of(), extendedHeader));
                        pos += size;
                        continue;
                    } else {
                        LOG.debug("uuid at {} UUID={}, parsing children from offset {}",
                                pos, uuidToHex(uuid), childStart);
                    }
                } else if ("stsd".equals(type)) {
                    childStart += 8;
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

    static boolean uuidEquals(byte[] a, byte[] b) {
        if (a.length != 16 || b.length != 16) return false;
        for (int i = 0; i < 16; i++) {
            if (a[i] != b[i]) return false;
        }
        return true;
    }

    private static String uuidToHex(byte[] uuid) {
        StringBuilder sb = new StringBuilder(32);
        for (byte b : uuid) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}
