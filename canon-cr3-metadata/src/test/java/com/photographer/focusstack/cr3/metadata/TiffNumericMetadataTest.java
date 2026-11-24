package com.photographer.focusstack.cr3.metadata;

import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.metadata.TiffIfdParser.TagValue;
import com.photographer.focusstack.cr3.testutil.Cr3TestData;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TiffNumericMetadataTest {
    static Stream<ByteOrder> byteOrders() {
        return Stream.of(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN);
    }

    @ParameterizedTest
    @MethodSource("byteOrders")
    void exposesUnsignedExposureAsSecondsAndAsTheRecordedFraction(ByteOrder order) throws Exception {
        byte[] tiff = rationalIfd(order, TiffIfdParser.TAG_EXPOSURE_TIME, 5,
                3_000_000_000L, 100_000_000L);
        var metadata = readMetadata("CMT2", tiff);
        assertThat(metadata.exposureTime()).isEqualTo(30.0);
        assertThat(metadata.shutterSpeed()).isEqualTo("3000000000/100000000");
    }

    @ParameterizedTest
    @MethodSource("byteOrders")
    void widensBothWordsOfUnsignedRationalArrays(ByteOrder order) throws IOException {
        byte[] tiff = rationalIfd(order, TiffIfdParser.TAG_GPS_TIMESTAMP, 5,
                3_000_000_000L, 100_000_000L, 1_500_000_000L, 3_000_000_000L);
        var value = (TagValue.RationalArrayValue) readTag(tiff, TiffIfdParser.TAG_GPS_TIMESTAMP);
        assertThat(value.toDouble(0)).isEqualTo(30.0);
        assertThat(value.toDouble(1)).isEqualTo(0.5);
    }

    @ParameterizedTest
    @MethodSource("byteOrders")
    void preservesSignedRationalScalarsAndArrays(ByteOrder order) throws IOException {
        int tag = TiffIfdParser.TAG_SHUTTER_SPEED_VALUE;
        var scalar = (TagValue.RationalValue) readTag(rationalIfd(order, tag, 10, -3, 2), tag);
        assertThat(scalar.toDouble()).isEqualTo(-1.5);
        var array = (TagValue.RationalArrayValue) readTag(rationalIfd(order, tag, 10, -3, 2, 3, -4), tag);
        assertThat(array.toDouble(0)).isEqualTo(-1.5);
        assertThat(array.toDouble(1)).isEqualTo(-0.75);
    }

    @ParameterizedTest
    @MethodSource("byteOrders")
    void readsTheFirstInlineByteAsTheBelowSeaLevelAltitudeReference(ByteOrder order) throws Exception {
        // TIFF header, two IFD entries and next-IFD pointer occupy the first 38 bytes.
        var tiff = startIfd(order, 2, 46);
        tiff.putShort((short) TiffIfdParser.TAG_GPS_ALTITUDE_REF).putShort((short) 1).putInt(1);
        tiff.put((byte) 1).put(new byte[3]);
        tiff.putShort((short) TiffIfdParser.TAG_GPS_ALTITUDE).putShort((short) 5).putInt(1).putInt(38);
        tiff.putInt(0).putInt(30).putInt(1);
        assertThat(readMetadata("CMT4", tiff.array()).gpsAltitude()).isEqualTo(-30.0);
    }

    @ParameterizedTest
    @MethodSource("byteOrders")
    void readsOddAndEvenUnsignedShortArrays(ByteOrder order) throws IOException {
        int tag = 0x4001;
        var single = (TagValue.IntValue) readTag(shortArrayIfd(order, tag, new int[]{0xffff}), tag);
        assertThat(single.value()).isEqualTo(0xffff);
        for (int count : new int[]{2, 3, 4, 1023, 1024, 1025, 8192}) {
            int[] words = IntStream.range(0, count).map(i -> (0xffff - i * 32767) & 0xffff).toArray();
            var value = (TagValue.IntArrayValue) readTag(shortArrayIfd(order, tag, words), tag);
            assertThat(value.values()).containsExactly(words);
        }
    }

    @ParameterizedTest
    @MethodSource("byteOrders")
    void rejectsShortArraysTruncatedInsideAWordOrBeforeTheOddTail(ByteOrder order) throws IOException {
        byte[] complete = shortArrayIfd(order, 0x4001, new int[]{0xffff, 0x8000, 0x1234, 0x4321, 0x7fff});
        for (int missing : new int[]{1, 2, 3}) {
            byte[] truncated = Arrays.copyOf(complete, complete.length - missing);
            try (var input = Cr3TestData.toImageInputStream(truncated)) {
                assertThatThrownBy(() -> TiffIfdParser.parseIfd(input, 0, complete.length))
                        .isInstanceOf(EOFException.class);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("byteOrders")
    void readsCompleteDescriptorsInsideTheBoxAfterFollowingAnExternalValue(ByteOrder order) throws IOException {
        // The string occupies the gap between the TIFF header and the IFD.
        var data = ByteBuffer.allocate(64).order(order);
        data.putShort(order == ByteOrder.BIG_ENDIAN ? (short) 0x4d4d : (short) 0x4949);
        data.putShort((short) 42).putInt(16);
        data.put(new byte[]{'C', 'a', 'n', 'o', 'n', 0});
        data.position(16);
        data.putShort((short) 3);
        data.putShort((short) 1).putShort((short) 2).putInt(6).putInt(8);
        data.putShort((short) 2).putShort((short) 4).putInt(1).putInt(42);
        data.putShort((short) 3).putShort((short) 4).putInt(1).putInt(99);
        int endOfSecondDescriptor = 16 + 2 + 2 * 12;
        try (var input = Cr3TestData.toImageInputStream(data.array())) {
            input.setByteOrder(ByteOrder.LITTLE_ENDIAN);
            var tags = TiffIfdParser.parseIfd(input, 0, endOfSecondDescriptor + 11);
            assertThat(tags).containsOnlyKeys(1, 2);
            assertThat(tags.get(1)).isEqualTo(new TagValue.StringValue("Canon"));
            assertThat(tags.get(2)).isEqualTo(new TagValue.LongValue(42));
            assertThat(input.getStreamPosition()).isEqualTo(endOfSecondDescriptor);
            assertThat(input.getByteOrder()).isEqualTo(ByteOrder.LITTLE_ENDIAN);
        }
        try (var input = Cr3TestData.toImageInputStream(Arrays.copyOf(data.array(), endOfSecondDescriptor + 11))) {
            input.setByteOrder(ByteOrder.LITTLE_ENDIAN);
            assertThatThrownBy(() -> TiffIfdParser.parseIfd(input, 0, data.capacity()))
                    .isInstanceOf(EOFException.class);
            assertThat(input.getByteOrder()).isEqualTo(ByteOrder.LITTLE_ENDIAN);
        }
    }

    private static byte[] shortArrayIfd(ByteOrder order, int tag, int[] words) {
        int offset = 26; // TIFF header, directory entry and next-IFD pointer
        boolean inline = words.length <= 2;
        var tiff = startIfd(order, 1, offset + (inline ? 0 : words.length * Short.BYTES));
        tiff.putShort((short) tag).putShort((short) 3).putInt(words.length);
        if (!inline) {
            tiff.putInt(offset).putInt(0);
        }
        for (int word : words) {
            tiff.putShort((short) word);
        }
        return tiff.array();
    }

    private static TagValue readTag(byte[] tiff, int tag) throws IOException {
        try (var input = Cr3TestData.toImageInputStream(tiff)) {
            return TiffIfdParser.parseIfd(input, 0, tiff.length).get(tag);
        }
    }

    private static Cr3Metadata readMetadata(String box, byte[] tiff) throws Exception {
        byte[] data = Cr3TestData.concat(Cr3TestData.buildCr3FtypBox(),
                Cr3TestData.buildBox("moov", Cr3TestData.buildBox(box, tiff)),
                Cr3TestData.buildBox("mdat", new byte[0]));
        try (var input = Cr3TestData.toImageInputStream(data)) {
            return Cr3MetadataReader.read(input, Cr3Parser.parse(input));
        }
    }

    private static byte[] rationalIfd(ByteOrder order, int tag, int type, long... words) {
        // TIFF header, one IFD entry and next-IFD pointer precede the rational words.
        int offset = 26;
        var tiff = startIfd(order, 1, offset + words.length * Integer.BYTES);
        tiff.putShort((short) tag).putShort((short) type).putInt(words.length / 2).putInt(offset);
        tiff.putInt(0);
        for (long word : words) {
            tiff.putInt((int) word);
        }
        return tiff.array();
    }

    private static ByteBuffer startIfd(ByteOrder order, int entries, int size) {
        var tiff = ByteBuffer.allocate(size).order(order);
        tiff.putShort(order == ByteOrder.BIG_ENDIAN ? (short) 0x4d4d : (short) 0x4949);
        return tiff.putShort((short) 42).putInt(8).putShort((short) entries);
    }
}
