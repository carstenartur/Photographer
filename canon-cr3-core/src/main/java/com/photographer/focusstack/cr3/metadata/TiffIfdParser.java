package com.photographer.focusstack.cr3.metadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cleanroom TIFF IFD parser for reading EXIF metadata from CR3 CMT boxes.
 *
 * <p>CR3 files store EXIF metadata in CMT1-CMT4 boxes, each containing a
 * TIFF-format IFD structure. This parser reads the IFD entries and extracts
 * standard EXIF tags.</p>
 *
 * <p>Implementation is based on the TIFF 6.0 specification and EXIF 2.3 standard,
 * both publicly available. No proprietary code was used.</p>
 */
public final class TiffIfdParser {

    private static final Logger LOG = LoggerFactory.getLogger(TiffIfdParser.class);

    // TIFF IFD data types
    private static final int TYPE_BYTE = 1;
    private static final int TYPE_ASCII = 2;
    private static final int TYPE_SHORT = 3;
    private static final int TYPE_LONG = 4;
    private static final int TYPE_RATIONAL = 5;
    private static final int TYPE_UNDEFINED = 7;
    private static final int TYPE_SLONG = 9;
    private static final int TYPE_SRATIONAL = 10;

    /** Maximum number of IFD entries considered valid (sanity check). */
    private static final int MAX_IFD_ENTRY_COUNT = 1000;
    /** Maximum length of an ASCII string tag (sanity check). */
    private static final int MAX_ASCII_STRING_LENGTH = 10000;
    /** TIFF magic number (always 42). */
    private static final int TIFF_MAGIC_NUMBER = 42;

    // Standard EXIF tags (TIFF IFD0 / CMT1)
    static final int TAG_MAKE = 0x010F;
    static final int TAG_MODEL = 0x0110;
    static final int TAG_ORIENTATION = 0x0112;
    static final int TAG_DATETIME = 0x0132;

    // EXIF IFD tags (CMT2)
    static final int TAG_EXPOSURE_TIME = 0x829A;
    static final int TAG_FNUMBER = 0x829D;
    static final int TAG_ISO_SPEED = 0x8827;
    static final int TAG_DATE_TIME_ORIGINAL = 0x9003;
    static final int TAG_SHUTTER_SPEED_VALUE = 0x9201;
    static final int TAG_APERTURE_VALUE = 0x9202;
    static final int TAG_FOCAL_LENGTH = 0x920A;
    static final int TAG_FOCAL_LENGTH_35MM = 0xA405;
    static final int TAG_LENS_MODEL = 0xA434;

    // GPS IFD tags (CMT4)
    static final int TAG_GPS_LATITUDE_REF = 0x0001;
    static final int TAG_GPS_LATITUDE = 0x0002;
    static final int TAG_GPS_LONGITUDE_REF = 0x0003;
    static final int TAG_GPS_LONGITUDE = 0x0004;
    static final int TAG_GPS_ALTITUDE_REF = 0x0005;
    static final int TAG_GPS_ALTITUDE = 0x0006;
    static final int TAG_GPS_TIMESTAMP = 0x0007;

    private TiffIfdParser() {}

    /**
     * Represents a parsed TIFF IFD entry value.
     */
    public sealed interface TagValue {
        record IntValue(int value) implements TagValue {}
        record LongValue(long value) implements TagValue {}
        record StringValue(String value) implements TagValue {}
        record RationalValue(int numerator, int denominator) implements TagValue {
            public double toDouble() {
                return denominator != 0 ? (double) numerator / denominator : 0.0;
            }
        }
        record IntArrayValue(int[] values) implements TagValue {}

        /**
         * An array of RATIONAL or SRATIONAL values.
         *
         * <p>Used for EXIF tags that store multiple rationals, such as
         * GPSTimeStamp (tag 0x0007) which stores 3 RATIONALs for
         * hours, minutes, and seconds.</p>
         *
         * @param numerators   array of numerator values
         * @param denominators array of denominator values (same length as numerators)
         */
        record RationalArrayValue(int[] numerators, int[] denominators) implements TagValue {
            /**
             * Returns the number of RATIONAL components.
             */
            public int count() {
                return numerators.length;
            }

            /**
             * Returns the double value of the i-th RATIONAL.
             *
             * @param index 0-based index
             * @return the value as double, or 0.0 if the denominator is zero
             */
            public double toDouble(int index) {
                if (index < 0 || index >= numerators.length) {
                    throw new IndexOutOfBoundsException(
                            "Index %d out of bounds for %d rationals".formatted(index, numerators.length));
                }
                return denominators[index] != 0 ? (double) numerators[index] / denominators[index] : 0.0;
            }
        }
    }

    /**
     * Parses a TIFF IFD from a CR3 CMT box.
     *
     * <p>CMT boxes contain raw TIFF IFD data with a TIFF header (byte order marker,
     * magic 42, and IFD offset).</p>
     *
     * @param input       the image input stream
     * @param boxDataOffset the absolute file offset of the CMT box data
     * @param boxDataSize   the size of the CMT box data
     * @return map of tag ID → parsed value
     * @throws IOException if reading fails
     */
    public static Map<Integer, TagValue> parseIfd(ImageInputStream input,
                                                   long boxDataOffset,
                                                   long boxDataSize) throws IOException {
        Map<Integer, TagValue> tags = new LinkedHashMap<>();
        if (boxDataSize < 8) {
            return tags;
        }

        input.seek(boxDataOffset);

        // Read TIFF header: byte order (2 bytes) + magic (2 bytes) + IFD0 offset (4 bytes)
        ByteOrder savedOrder = input.getByteOrder();
        int byte1 = input.readUnsignedByte();
        int byte2 = input.readUnsignedByte();

        ByteOrder tiffOrder;
        if (byte1 == 'I' && byte2 == 'I') {
            tiffOrder = ByteOrder.LITTLE_ENDIAN;
        } else if (byte1 == 'M' && byte2 == 'M') {
            tiffOrder = ByteOrder.BIG_ENDIAN;
        } else {
            LOG.warn("Invalid TIFF byte order marker: 0x{}{}", Integer.toHexString(byte1),
                    Integer.toHexString(byte2));
            input.setByteOrder(savedOrder);
            return tags;
        }

        input.setByteOrder(tiffOrder);
        int magic = input.readUnsignedShort();
        if (magic != TIFF_MAGIC_NUMBER) {
            LOG.warn("Invalid TIFF magic number: {} (expected {})", magic, TIFF_MAGIC_NUMBER);
            input.setByteOrder(savedOrder);
            return tags;
        }

        long ifdOffset = Integer.toUnsignedLong(input.readInt());
        if (ifdOffset == 0 || ifdOffset >= boxDataSize) {
            input.setByteOrder(savedOrder);
            return tags;
        }

        // Read IFD entries
        long ifdAbsoluteOffset = boxDataOffset + ifdOffset;
        input.seek(ifdAbsoluteOffset);
        int entryCount = input.readUnsignedShort();

        if (entryCount <= 0 || entryCount > MAX_IFD_ENTRY_COUNT) {
            LOG.warn("Suspicious IFD entry count: {}", entryCount);
            input.setByteOrder(savedOrder);
            return tags;
        }

        for (int i = 0; i < entryCount; i++) {
            long entryOffset = ifdAbsoluteOffset + 2 + (long) i * 12;
            if (entryOffset + 12 > boxDataOffset + boxDataSize) {
                break;
            }
            input.seek(entryOffset);

            int tagId = input.readUnsignedShort();
            int dataType = input.readUnsignedShort();
            long count = Integer.toUnsignedLong(input.readInt());
            // The next 4 bytes are either the value or an offset to the value
            long valueOrOffset = Integer.toUnsignedLong(input.readInt());

            TagValue value = readTagValue(input, tagId, dataType, count, valueOrOffset,
                    boxDataOffset, boxDataSize);
            if (value != null) {
                tags.put(tagId, value);
            }
        }

        input.setByteOrder(savedOrder);
        return tags;
    }

    private static TagValue readTagValue(ImageInputStream input, int tagId,
                                          int dataType, long count, long valueOrOffset,
                                          long tiffBase, long boxDataSize) throws IOException {
        int typeSize = typeSize(dataType);
        if (typeSize <= 0 || count <= 0) {
            return null;
        }

        long totalSize = count * typeSize;

        switch (dataType) {
            case TYPE_ASCII -> {
                if (count > MAX_ASCII_STRING_LENGTH) return null; // sanity
                if (totalSize <= 4) {
                    // Value is inline in the 4-byte field
                    byte[] buf = new byte[(int) count];
                    boolean littleEndian = input.getByteOrder() == ByteOrder.LITTLE_ENDIAN;
                    for (int i = 0; i < count; i++) {
                        int shift = 8 * (littleEndian ? i : 3 - i);
                        buf[i] = (byte) ((valueOrOffset >> shift) & 0xFF);
                    }
                    return new TagValue.StringValue(trimNull(new String(buf, StandardCharsets.US_ASCII)));
                }
                long strOffset = tiffBase + valueOrOffset;
                if (strOffset + count > tiffBase + boxDataSize) return null;
                input.seek(strOffset);
                byte[] buf = new byte[(int) count];
                input.readFully(buf);
                return new TagValue.StringValue(trimNull(new String(buf, StandardCharsets.US_ASCII)));
            }

            case TYPE_SHORT -> {
                if (count == 1) {
                    // SHORT value is left-justified in the 4-byte field.
                    // In BIG_ENDIAN, it's in the upper 16 bits; in LITTLE_ENDIAN, the lower 16 bits.
                    if (input.getByteOrder() == java.nio.ByteOrder.BIG_ENDIAN) {
                        return new TagValue.IntValue((int) ((valueOrOffset >> 16) & 0xFFFF));
                    } else {
                        return new TagValue.IntValue((int) (valueOrOffset & 0xFFFF));
                    }
                }
                if (count == 2 && totalSize <= 4) {
                    // Two shorts packed into 4 bytes
                    int v1, v2;
                    if (input.getByteOrder() == java.nio.ByteOrder.BIG_ENDIAN) {
                        v1 = (int) ((valueOrOffset >> 16) & 0xFFFF);
                        v2 = (int) (valueOrOffset & 0xFFFF);
                    } else {
                        v1 = (int) (valueOrOffset & 0xFFFF);
                        v2 = (int) ((valueOrOffset >> 16) & 0xFFFF);
                    }
                    return new TagValue.IntArrayValue(new int[]{v1, v2});
                }
                if (count <= 100) {
                    long arrOffset = tiffBase + valueOrOffset;
                    if (arrOffset + totalSize > tiffBase + boxDataSize) return null;
                    input.seek(arrOffset);
                    int[] values = new int[(int) count];
                    for (int i = 0; i < count; i++) {
                        values[i] = input.readUnsignedShort();
                    }
                    return new TagValue.IntArrayValue(values);
                }
                return null;
            }

            case TYPE_LONG -> {
                if (count == 1) {
                    return new TagValue.LongValue(valueOrOffset);
                }
                return null;
            }

            case TYPE_SLONG -> {
                if (count == 1) {
                    // SLONG is a signed 32-bit integer; cast to preserve sign
                    return new TagValue.LongValue((long) (int) valueOrOffset);
                }
                return null;
            }

            case TYPE_RATIONAL -> {
                if (count == 1) {
                    long ratOffset = tiffBase + valueOrOffset;
                    if (ratOffset + 8 > tiffBase + boxDataSize) return null;
                    input.seek(ratOffset);
                    int num = input.readInt();
                    int den = input.readInt();
                    return new TagValue.RationalValue(num, den);
                }
                // Multi-RATIONAL: e.g. GPSTimeStamp (3 RATIONALs for HH, MM, SS)
                if (count <= 20) {
                    long ratOffset = tiffBase + valueOrOffset;
                    if (ratOffset + count * 8 > tiffBase + boxDataSize) return null;
                    input.seek(ratOffset);
                    int[] nums = new int[(int) count];
                    int[] dens = new int[(int) count];
                    for (int i = 0; i < count; i++) {
                        nums[i] = input.readInt();
                        dens[i] = input.readInt();
                    }
                    return new TagValue.RationalArrayValue(nums, dens);
                }
                return null;
            }

            case TYPE_SRATIONAL -> {
                if (count == 1) {
                    long ratOffset = tiffBase + valueOrOffset;
                    if (ratOffset + 8 > tiffBase + boxDataSize) return null;
                    input.seek(ratOffset);
                    int num = input.readInt();
                    int den = input.readInt();
                    return new TagValue.RationalValue(num, den);
                }
                // Multi-SRATIONAL
                if (count <= 20) {
                    long ratOffset = tiffBase + valueOrOffset;
                    if (ratOffset + count * 8 > tiffBase + boxDataSize) return null;
                    input.seek(ratOffset);
                    int[] nums = new int[(int) count];
                    int[] dens = new int[(int) count];
                    for (int i = 0; i < count; i++) {
                        nums[i] = input.readInt();
                        dens[i] = input.readInt();
                    }
                    return new TagValue.RationalArrayValue(nums, dens);
                }
                return null;
            }

            case TYPE_BYTE, TYPE_UNDEFINED -> {
                if (count == 1) {
                    return new TagValue.IntValue((int) (valueOrOffset & 0xFF));
                }
                return null;
            }

            default -> {
                return null;
            }
        }
    }

    private static int typeSize(int dataType) {
        return switch (dataType) {
            case TYPE_BYTE, TYPE_ASCII, TYPE_UNDEFINED -> 1;
            case TYPE_SHORT -> 2;
            case TYPE_LONG, TYPE_SLONG -> 4;
            case TYPE_RATIONAL, TYPE_SRATIONAL -> 8;
            default -> 0;
        };
    }

    private static String trimNull(String s) {
        int end = s.indexOf('\0');
        return end >= 0 ? s.substring(0, end) : s;
    }
}
