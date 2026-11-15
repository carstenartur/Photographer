package com.photographer.focusstack.cr3.encode;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Generates self-contained TIFF and timed metadata for a profile's lossless RAW mode. */
final class Cr3ProfileMetadata {
    private static final int SHORT = 3;
    private static final int COLOR_WORDS = 798;
    private static final int NEUTRAL_GAIN = 1024;

    private Cr3ProfileMetadata() {}

    static byte[] camera(Cr3CameraProfile profile) {
        return tiff(ascii(0x010f, "Canon"),
                ascii(0x0110, profile.cameraModel()),
                new Field(0x0112, SHORT, 1, new byte[]{1, 0}),
                ascii(0x0131, "Photographer"));
    }

    static byte[] exif() {
        // ExifVersion (UNDEFINED) is present even when no exposure metadata is supplied.
        return tiff(new Field(0x9000, 7, 4, "0230".getBytes(StandardCharsets.US_ASCII)));
    }

    static byte[] makerNote(Cr3CameraProfile profile) {
        // CameraSettings word 3 selects lossless RAW. Word 0 stores the array's byte length.
        int count = profile.cameraSettingsWords();
        var settings = words(count);
        settings.putShort(0, (short) settings.capacity());
        putWord(settings, 3, 4);
        return tiff(new Field(0x0001, SHORT, count, settings.array()));
    }

    static byte[] gps() {
        // GPSVersionID identifies the TIFF schema without recording a location.
        return tiff(new Field(0x0000, 1, 4, new byte[]{2, 3, 0, 0}));
    }

    static byte[] timed(Cr3CameraProfile profile) {
        var color = profile.color();
        var values = words(COLOR_WORDS);
        putWord(values, 0, color.version());
        for (int site = 0; site < 4; site++) {
            putWord(values, 71 + site, NEUTRAL_GAIN);
            putWord(values, 329 + site, color.black());
            putWord(values, 792 + site, color.black());
        }
        putWord(values, 797, color.white());
        byte[] makerNote = tiff(new Field(0x4001, SHORT, COLOR_WORDS, values.array()));

        int blockLength = 8 + makerNote.length;
        int recordLength = 12 + blockLength;
        return ByteBuffer.allocate(recordLength).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(recordLength).putShort((short) 8)
                .putShort((short) 0x0101).putShort((short) 0x0100).putShort((short) 0xffff)
                .putInt(blockLength).putInt(0x927c).put(makerNote).array();
    }

    private static ByteBuffer words(int count) {
        return ByteBuffer.allocate(count * Short.BYTES).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void putWord(ByteBuffer words, int index, int value) {
        words.putShort(index * Short.BYTES, (short) value);
    }

    private static Field ascii(int tag, String value) {
        byte[] bytes = (value + '\0').getBytes(StandardCharsets.US_ASCII);
        return new Field(tag, 2, bytes.length, bytes);
    }

    private static byte[] tiff(Field... fields) {
        int dataOffset = 8 + Short.BYTES + fields.length * 12 + Integer.BYTES;
        int size = dataOffset;
        for (Field field : fields) {
            size += field.extraBytes();
        }
        var result = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        result.putShort((short) 0x4949).putShort((short) 42).putInt(8);
        result.putShort((short) fields.length);
        for (Field field : fields) {
            result.putShort((short) field.tag()).putShort((short) field.type()).putInt(field.count());
            if (field.extraBytes() == 0) {
                result.put(field.data());
                result.position(result.position() + Integer.BYTES - field.data().length);
            } else {
                result.putInt(dataOffset);
                result.put(dataOffset, field.data());
                dataOffset += field.extraBytes();
            }
        }
        result.putInt(0); // No next IFD.
        return result.array();
    }

    private record Field(int tag, int type, int count, byte[] data) {
        int extraBytes() {
            return data.length <= Integer.BYTES ? 0 : (data.length + 1) & ~1;
        }
    }
}
