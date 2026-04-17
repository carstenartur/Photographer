package com.photographer.focusstack.cr3.metadata;

import org.junit.jupiter.api.Test;

import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link TiffIfdParser}.
 */
class TiffIfdParserTest {

    @Test
    void parseSimpleBigEndianIfd() throws IOException {
        byte[] tiffData = buildTiffData(ByteOrder.BIG_ENDIAN, new IfdEntry[]{
                new IfdEntry(TiffIfdParser.TAG_MAKE, 2, "Canon\0"),
                new IfdEntry(TiffIfdParser.TAG_MODEL, 2, "Canon EOS R5\0")
        });

        MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(
                new ByteArrayInputStream(tiffData));
        Map<Integer, TiffIfdParser.TagValue> tags =
                TiffIfdParser.parseIfd(input, 0, tiffData.length);

        assertThat(tags).containsKey(TiffIfdParser.TAG_MAKE);
        assertThat(tags.get(TiffIfdParser.TAG_MAKE))
                .isInstanceOf(TiffIfdParser.TagValue.StringValue.class);
        assertThat(((TiffIfdParser.TagValue.StringValue) tags.get(TiffIfdParser.TAG_MAKE)).value())
                .isEqualTo("Canon");
    }

    @Test
    void parseLittleEndianIfd() throws IOException {
        byte[] tiffData = buildTiffData(ByteOrder.LITTLE_ENDIAN, new IfdEntry[]{
                new IfdEntry(TiffIfdParser.TAG_MAKE, 2, "Canon\0")
        });

        MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(
                new ByteArrayInputStream(tiffData));
        Map<Integer, TiffIfdParser.TagValue> tags =
                TiffIfdParser.parseIfd(input, 0, tiffData.length);

        assertThat(tags).containsKey(TiffIfdParser.TAG_MAKE);
    }

    @Test
    void parseShortTag() throws IOException {
        // Build a TIFF with a SHORT tag (ISO speed)
        byte[] tiffData = buildTiffWithShort(TiffIfdParser.TAG_ISO_SPEED, 400);

        MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(
                new ByteArrayInputStream(tiffData));
        Map<Integer, TiffIfdParser.TagValue> tags =
                TiffIfdParser.parseIfd(input, 0, tiffData.length);

        assertThat(tags).containsKey(TiffIfdParser.TAG_ISO_SPEED);
        if (tags.get(TiffIfdParser.TAG_ISO_SPEED) instanceof TiffIfdParser.TagValue.IntValue iv) {
            assertThat(iv.value()).isEqualTo(400);
        }
    }

    @Test
    void parseRationalTag() throws IOException {
        // Build a TIFF with a RATIONAL tag (focal length = 50/1)
        byte[] tiffData = buildTiffWithRational(TiffIfdParser.TAG_FOCAL_LENGTH, 50, 1);

        MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(
                new ByteArrayInputStream(tiffData));
        Map<Integer, TiffIfdParser.TagValue> tags =
                TiffIfdParser.parseIfd(input, 0, tiffData.length);

        assertThat(tags).containsKey(TiffIfdParser.TAG_FOCAL_LENGTH);
        if (tags.get(TiffIfdParser.TAG_FOCAL_LENGTH) instanceof TiffIfdParser.TagValue.RationalValue rv) {
            assertThat(rv.numerator()).isEqualTo(50);
            assertThat(rv.denominator()).isEqualTo(1);
            assertThat(rv.toDouble()).isEqualTo(50.0);
        }
    }

    @Test
    void rationalToDoubleWithZeroDenominator() {
        var rv = new TiffIfdParser.TagValue.RationalValue(50, 0);
        assertThat(rv.toDouble()).isEqualTo(0.0);
    }

    @Test
    void parseEmptyDataReturnsEmptyMap() throws IOException {
        byte[] data = new byte[4]; // too short for TIFF header
        MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(
                new ByteArrayInputStream(data));
        Map<Integer, TiffIfdParser.TagValue> tags = TiffIfdParser.parseIfd(input, 0, data.length);
        assertThat(tags).isEmpty();
    }

    @Test
    void parseInvalidByteOrderReturnsEmptyMap() throws IOException {
        byte[] data = new byte[]{0x00, 0x00, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08};
        MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(
                new ByteArrayInputStream(data));
        Map<Integer, TiffIfdParser.TagValue> tags = TiffIfdParser.parseIfd(input, 0, data.length);
        assertThat(tags).isEmpty();
    }

    @Test
    void parseInvalidMagicReturnsEmptyMap() throws IOException {
        byte[] data = new byte[]{
                'M', 'M', // big endian
                0x00, 0x43, // wrong magic (67 instead of 42)
                0x00, 0x00, 0x00, 0x08
        };
        MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(
                new ByteArrayInputStream(data));
        Map<Integer, TiffIfdParser.TagValue> tags = TiffIfdParser.parseIfd(input, 0, data.length);
        assertThat(tags).isEmpty();
    }

    @Test
    void parseSlongPreservesSign() throws IOException {
        // Build a TIFF with a SLONG tag containing a negative value
        byte[] tiffData = buildTiffWithSlong(0x9201, -5); // SLONG tag for ShutterSpeedValue

        MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(
                new ByteArrayInputStream(tiffData));
        Map<Integer, TiffIfdParser.TagValue> tags =
                TiffIfdParser.parseIfd(input, 0, tiffData.length);

        assertThat(tags).containsKey(0x9201);
        assertThat(tags.get(0x9201)).isInstanceOf(TiffIfdParser.TagValue.LongValue.class);
        TiffIfdParser.TagValue.LongValue lv = (TiffIfdParser.TagValue.LongValue) tags.get(0x9201);
        assertThat(lv.value()).isEqualTo(-5L);
    }

    @Test
    void parseMultiRationalGpsTimestamp() throws IOException {
        // GPS Timestamp = 3 RATIONALs: 14/1 (hours), 30/1 (minutes), 45/1 (seconds)
        byte[] tiffData = buildTiffWithMultiRational(
                TiffIfdParser.TAG_GPS_TIMESTAMP, 3,
                new int[]{14, 30, 45}, new int[]{1, 1, 1});

        MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(
                new ByteArrayInputStream(tiffData));
        Map<Integer, TiffIfdParser.TagValue> tags =
                TiffIfdParser.parseIfd(input, 0, tiffData.length);

        assertThat(tags).containsKey(TiffIfdParser.TAG_GPS_TIMESTAMP);
        TiffIfdParser.TagValue val = tags.get(TiffIfdParser.TAG_GPS_TIMESTAMP);
        assertThat(val).isInstanceOf(TiffIfdParser.TagValue.RationalArrayValue.class);
        TiffIfdParser.TagValue.RationalArrayValue rav = (TiffIfdParser.TagValue.RationalArrayValue) val;
        assertThat(rav.count()).isEqualTo(3);
        assertThat(rav.toDouble(0)).isEqualTo(14.0);
        assertThat(rav.toDouble(1)).isEqualTo(30.0);
        assertThat(rav.toDouble(2)).isEqualTo(45.0);
    }

    @Test
    void parseMultiRationalGpsCoordinateDms() throws IOException {
        // GPS Latitude = 3 RATIONALs: 48/1 (degrees), 51/1 (minutes), 24/1 (seconds)
        byte[] tiffData = buildTiffWithMultiRational(
                TiffIfdParser.TAG_GPS_LATITUDE, 3,
                new int[]{48, 51, 24}, new int[]{1, 1, 1});

        MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(
                new ByteArrayInputStream(tiffData));
        Map<Integer, TiffIfdParser.TagValue> tags =
                TiffIfdParser.parseIfd(input, 0, tiffData.length);

        assertThat(tags).containsKey(TiffIfdParser.TAG_GPS_LATITUDE);
        TiffIfdParser.TagValue val = tags.get(TiffIfdParser.TAG_GPS_LATITUDE);
        assertThat(val).isInstanceOf(TiffIfdParser.TagValue.RationalArrayValue.class);
        TiffIfdParser.TagValue.RationalArrayValue rav = (TiffIfdParser.TagValue.RationalArrayValue) val;
        assertThat(rav.count()).isEqualTo(3);
        // 48 + 51/60 + 24/3600 ≈ 48.856667
        assertThat(rav.toDouble(0)).isEqualTo(48.0);
        assertThat(rav.toDouble(1)).isEqualTo(51.0);
        assertThat(rav.toDouble(2)).isEqualTo(24.0);
    }

    @Test
    void rationalArrayValueBoundsCheck() {
        var rav = new TiffIfdParser.TagValue.RationalArrayValue(
                new int[]{1, 2}, new int[]{1, 1});
        assertThatThrownBy(() -> rav.toDouble(-1))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> rav.toDouble(2))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void rationalArrayValueWithZeroDenominator() {
        var rav = new TiffIfdParser.TagValue.RationalArrayValue(
                new int[]{10, 20}, new int[]{1, 0});
        assertThat(rav.toDouble(0)).isEqualTo(10.0);
        assertThat(rav.toDouble(1)).isEqualTo(0.0); // zero denominator → 0.0
    }

    // --- Helper methods for building synthetic TIFF data ---

    record IfdEntry(int tagId, int dataType, String stringValue) {}

    private static byte[] buildTiffData(ByteOrder order, IfdEntry[] entries) {
        // Calculate total size needed for string data
        int stringDataOffset = 8 + 2 + entries.length * 12 + 4; // TIFF header + IFD
        int stringDataSize = 0;
        for (IfdEntry e : entries) {
            if (e.stringValue != null && e.stringValue.length() > 4) {
                stringDataSize += e.stringValue.length();
            }
        }

        ByteBuffer buf = ByteBuffer.allocate(stringDataOffset + stringDataSize).order(order);

        // TIFF header
        if (order == ByteOrder.BIG_ENDIAN) {
            buf.put((byte) 'M');
            buf.put((byte) 'M');
        } else {
            buf.put((byte) 'I');
            buf.put((byte) 'I');
        }
        buf.putShort((short) 42);
        buf.putInt(8); // IFD0 offset

        // IFD
        buf.putShort((short) entries.length);
        int currentStringOffset = stringDataOffset;
        for (IfdEntry e : entries) {
            buf.putShort((short) e.tagId);
            buf.putShort((short) e.dataType);
            int count = e.stringValue != null ? e.stringValue.length() : 1;
            buf.putInt(count);
            if (e.stringValue != null && e.stringValue.length() > 4) {
                buf.putInt(currentStringOffset);
                currentStringOffset += e.stringValue.length();
            } else if (e.stringValue != null) {
                // Pack short string inline
                byte[] strBytes = e.stringValue.getBytes();
                byte[] padded = new byte[4];
                System.arraycopy(strBytes, 0, padded, 0, Math.min(strBytes.length, 4));
                buf.put(padded);
            } else {
                buf.putInt(0);
            }
        }
        buf.putInt(0); // next IFD offset (none)

        // String data
        for (IfdEntry e : entries) {
            if (e.stringValue != null && e.stringValue.length() > 4) {
                buf.put(e.stringValue.getBytes());
            }
        }

        return buf.array();
    }

    private static byte[] buildTiffWithShort(int tagId, int value) {
        ByteBuffer buf = ByteBuffer.allocate(8 + 2 + 12 + 4).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) 'M');
        buf.put((byte) 'M');
        buf.putShort((short) 42);
        buf.putInt(8);
        buf.putShort((short) 1);
        buf.putShort((short) tagId);
        buf.putShort((short) 3); // SHORT type
        buf.putInt(1);
        // For SHORT, value is in first 2 bytes of the 4-byte field (big endian)
        buf.putShort((short) value);
        buf.putShort((short) 0);
        buf.putInt(0);
        return buf.array();
    }

    private static byte[] buildTiffWithRational(int tagId, int num, int den) {
        int rationalDataOffset = 8 + 2 + 12 + 4; // after TIFF header + IFD
        ByteBuffer buf = ByteBuffer.allocate(rationalDataOffset + 8).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) 'M');
        buf.put((byte) 'M');
        buf.putShort((short) 42);
        buf.putInt(8);
        buf.putShort((short) 1);
        buf.putShort((short) tagId);
        buf.putShort((short) 5); // RATIONAL type
        buf.putInt(1);
        buf.putInt(rationalDataOffset); // offset to rational data
        buf.putInt(0); // next IFD
        buf.putInt(num);
        buf.putInt(den);
        return buf.array();
    }

    private static byte[] buildTiffWithSlong(int tagId, int value) {
        ByteBuffer buf = ByteBuffer.allocate(8 + 2 + 12 + 4).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) 'M');
        buf.put((byte) 'M');
        buf.putShort((short) 42);
        buf.putInt(8);
        buf.putShort((short) 1);
        buf.putShort((short) tagId);
        buf.putShort((short) 9); // SLONG type
        buf.putInt(1);
        buf.putInt(value); // signed 32-bit value
        buf.putInt(0);
        return buf.array();
    }

    /**
     * Builds a TIFF structure with a multi-component RATIONAL tag.
     *
     * @param tagId       the EXIF tag ID
     * @param count       number of RATIONAL components
     * @param numerators  array of numerators
     * @param denominators array of denominators
     * @return the raw TIFF bytes
     */
    private static byte[] buildTiffWithMultiRational(int tagId, int count,
                                                      int[] numerators, int[] denominators) {
        int rationalDataOffset = 8 + 2 + 12 + 4; // after TIFF header + IFD
        int rationalDataSize = count * 8; // each RATIONAL is 8 bytes
        ByteBuffer buf = ByteBuffer.allocate(rationalDataOffset + rationalDataSize)
                .order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) 'M');
        buf.put((byte) 'M');
        buf.putShort((short) 42);
        buf.putInt(8);
        buf.putShort((short) 1); // 1 IFD entry
        buf.putShort((short) tagId);
        buf.putShort((short) 5); // RATIONAL type
        buf.putInt(count);
        buf.putInt(rationalDataOffset); // offset to rational data
        buf.putInt(0); // next IFD
        // Write RATIONAL data
        for (int i = 0; i < count; i++) {
            buf.putInt(numerators[i]);
            buf.putInt(denominators[i]);
        }
        return buf.array();
    }
}
