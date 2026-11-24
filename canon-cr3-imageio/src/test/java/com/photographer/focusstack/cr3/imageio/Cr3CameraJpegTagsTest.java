package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.bmff.IsoBox;
import com.photographer.focusstack.cr3.container.Cr3Container;
import org.junit.jupiter.api.Test;

import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Cr3CameraJpegTagsTest {
    private static final byte[] BARE = HexFormat.of().parseHex("ffd8ffd9");

    @Test
    void usesCmtDeclarationsOnBareCanonStreamsAndSkipsUnknownSpaces() throws IOException {
        assertEquals(new Cr3CameraJpegTags(6, "CMT2_sRGB", true), read(6, 1, BARE));
        assertEquals(new Cr3CameraJpegTags(8, "CMT2_uncalibrated", false), read(8, 65535, BARE));
        assertFalse(read(1, 2, BARE).comparable());
        assertFalse(read(1, -1, BARE).comparable());
        assertEquals(9, read(9, 1, BARE).orientation());
    }

    @Test
    void anIccChunkOverridesContainerSrgbWhileAnAdobeTransformMarkerDoesNotIdentifyAProfile()
            throws IOException {
        byte[] icc = "ICC_PROFILE\0\1\1".getBytes(StandardCharsets.US_ASCII);
        assertEquals(new Cr3CameraJpegTags(1, "JPEG_ICC", false), read(1, 1, jpeg(0xe2, icc)));
        assertTrue(read(1, 1, jpeg(0xee, "Adobe".getBytes(StandardCharsets.US_ASCII))).comparable());
    }

    @Test
    void readsJpegExifOrientationAndNestedColorSpaceInBothByteOrders() throws IOException {
        for (ByteOrder order : List.of(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN)) {
            ByteBuffer exif = ByteBuffer.allocate(62).order(order);
            exif.put("Exif\0\0".getBytes(StandardCharsets.US_ASCII));
            tiffHeader(exif);
            exif.putShort((short) 2);
            shortEntry(exif, 0x0112, 1);
            exif.putShort((short) 0x8769).putShort((short) 4).putInt(1).putInt(38);
            exif.putInt(0).putShort((short) 1);
            shortEntry(exif, 0xa001, 1);
            exif.putInt(0);
            byte[] jpeg = jpeg(0xe1, exif.array());
            assertEquals(new Cr3CameraJpegTags(1, "EXIF_sRGB", true), read(6, -1, jpeg));
            assertEquals(new Cr3CameraJpegTags(1, "conflicting_color_spaces", false), read(6, 65535, jpeg));
        }
    }

    @Test
    void rejectsTruncatedSegmentsAndExifWithAnOutOfBoundsDirectory() throws IOException {
        assertThrows(IOException.class, () -> read(1, 1, HexFormat.of().parseHex("ffd8ffe20010ff")));
        byte[] badExif = HexFormat.of().parseHex("4578696600004d4d002a7fffffff");
        assertThrows(IOException.class, () -> read(1, 1, jpeg(0xe1, badExif)));
    }

    private static Cr3CameraJpegTags read(int orientation, int colorSpace, byte[] jpeg) throws IOException {
        // The box objects point to two tiny TIFF headers in a shared stream, as in a real moov.
        ByteBuffer bytes = ByteBuffer.allocate(68).order(ByteOrder.LITTLE_ENDIAN);
        bytes.position(8);
        oneTag(bytes, 0x0112, orientation);
        bytes.position(42);
        oneTag(bytes, 0xa001, colorSpace);
        var orient = new IsoBox("CMT1", 34, 0);
        var space = new IsoBox("CMT2", 34, 34);
        var moov = new IsoBox("moov", 68, 0, colorSpace < 0 ? List.of(orient) : List.of(orient, space));
        var container = new Cr3Container(null, moov, null, List.of(moov), List.of());
        try (var in = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes.array()))) {
            return Cr3CameraJpegTags.read(in, container, jpeg);
        }
    }

    private static void oneTag(ByteBuffer buf, int tag, int value) {
        tiffHeader(buf);
        buf.putShort((short) 1);
        shortEntry(buf, tag, value);
        buf.putInt(0);
    }

    private static void tiffHeader(ByteBuffer buf) {
        buf.putShort((short) (buf.order() == ByteOrder.LITTLE_ENDIAN ? 0x4949 : 0x4d4d));
        buf.putShort((short) 42).putInt(8);
    }

    private static void shortEntry(ByteBuffer buf, int tag, int value) {
        buf.putShort((short) tag).putShort((short) 3).putInt(1).putShort((short) value).putShort((short) 0);
    }

    private static byte[] jpeg(int marker, byte[] payload) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeShort(0xffd8);
            out.writeShort(0xff00 | marker);
            out.writeShort(payload.length + 2);
            out.write(payload);
            out.writeShort(0xffd9);
        }
        return bytes.toByteArray();
    }
}
