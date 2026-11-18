package com.photographer.focusstack.cr3.metadata;

import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.metadata.TiffIfdParser.TagValue;
import com.photographer.focusstack.cr3.testutil.Cr3TestData;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

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
