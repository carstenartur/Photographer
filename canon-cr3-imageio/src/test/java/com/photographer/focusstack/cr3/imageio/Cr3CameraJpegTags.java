package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.metadata.TiffIfdParser;

import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Orientation and color declaration for the same-capture camera JPEG comparison. */
record Cr3CameraJpegTags(int orientation, String profile, boolean comparable) {
    private static final int ORIENTATION = 0x0112;
    private static final int EXIF_IFD = 0x8769;
    private static final int COLOR_SPACE = 0xa001;
    private static final byte[] EXIF = "Exif\0\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ICC = "ICC_PROFILE\0".getBytes(StandardCharsets.US_ASCII);

    static Cr3CameraJpegTags read(ImageInputStream in, Cr3Container container, byte[] jpeg) throws IOException {
        int outerOrientation = containerTag(in, container, "CMT1", ORIENTATION);
        int outerSpace = containerTag(in, container, "CMT2", COLOR_SPACE);
        int orientation = outerOrientation == -1 ? 1 : outerOrientation;
        int jpegSpace = -1;
        boolean hasIcc = false;
        boolean hasExif = false;
        try (var data = new DataInputStream(new ByteArrayInputStream(jpeg))) {
            if (data.readUnsignedShort() != 0xffd8) throw new IOException("Expected JPEG SOI");
            while (true) {
                if (data.readUnsignedByte() != 0xff) throw new IOException("Invalid JPEG marker");
                int marker;
                do {
                    marker = data.readUnsignedByte();
                } while (marker == 0xff);
                if (marker == 0xda || marker == 0xd9) break; // SOS or EOI ends the metadata header.
                if (marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) continue;
                int size = data.readUnsignedShort() - 2;
                if (size < 0) throw new IOException("Invalid JPEG segment length");
                if (marker != 0xe1 && marker != 0xe2) {
                    data.skipNBytes(size);
                    continue;
                }
                byte[] segment = new byte[size];
                data.readFully(segment);
                if (marker == 0xe2 && startsWith(segment, ICC)) hasIcc = true;
                if (marker == 0xe1 && startsWith(segment, EXIF)) {
                    if (hasExif) throw new IOException("Multiple EXIF declarations in camera JPEG");
                    hasExif = true;
                    ByteBuffer tiff = ByteBuffer.wrap(segment, EXIF.length, size - EXIF.length).slice();
                    if (tiff.remaining() < 8) throw new IOException("Short JPEG EXIF header");
                    int endian = tiff.getShort(0);
                    if (endian == 0x4949) tiff.order(ByteOrder.LITTLE_ENDIAN);
                    else if (endian != 0x4d4d) throw new IOException("Invalid JPEG EXIF byte order");
                    if (tiff.getShort(2) != 42) throw new IOException("Invalid JPEG EXIF header");
                    int ifd = tiff.getInt(4);
                    int jpegOrientation = scalar(tiff, ifd, ORIENTATION);
                    if (jpegOrientation != -1) orientation = jpegOrientation;
                    int exifIfd = scalar(tiff, ifd, EXIF_IFD);
                    if (exifIfd != -1) jpegSpace = scalar(tiff, exifIfd, COLOR_SPACE);
                }
            }
        }
        // ImageIO's implicit JPEG conversions vary with reader and profile. Only compare
        // declared sRGB samples; an ICC chunk (even if incomplete) takes precedence.
        if (hasIcc) return new Cr3CameraJpegTags(orientation, "JPEG_ICC", false);
        if (jpegSpace != -1 && outerSpace != -1 && jpegSpace != outerSpace)
            return new Cr3CameraJpegTags(orientation, "conflicting_color_spaces", false);
        int space = jpegSpace != -1 ? jpegSpace : outerSpace;
        String source = jpegSpace != -1 ? "EXIF_" : "CMT2_";
        return switch (space) {
            case 1 -> new Cr3CameraJpegTags(orientation, source + "sRGB", true);
            case -1 -> new Cr3CameraJpegTags(orientation, "unknown", false);
            case 65535 -> new Cr3CameraJpegTags(orientation, source + "uncalibrated", false);
            default -> new Cr3CameraJpegTags(orientation, source + space, false);
        };
    }

    private static int containerTag(ImageInputStream in, Cr3Container container, String type, int tag)
            throws IOException {
        var moov = container.moovBox();
        var box = moov == null ? null : moov.findDescendant(type);
        if (box == null) return -1;
        var value = TiffIfdParser.parseIfd(in, box.dataOffset(), box.dataSize()).get(tag);
        return value instanceof TiffIfdParser.TagValue.IntValue scalar ? scalar.value() : -1;
    }

    private static boolean startsWith(byte[] segment, byte[] prefix) {
        return segment.length >= prefix.length && Arrays.equals(segment, 0, prefix.length, prefix, 0, prefix.length);
    }

    /** Only the inline SHORT values and the single LONG pointer to the EXIF subdirectory are needed. */
    private static int scalar(ByteBuffer tiff, int offset, int tag) throws IOException {
        if (offset < 8 || offset > tiff.limit() - 2) throw new IOException("Invalid JPEG EXIF IFD offset");
        int count = Short.toUnsignedInt(tiff.getShort(offset));
        int start = offset + 2;
        if (count > (tiff.limit() - start) / 12) throw new IOException("Truncated JPEG EXIF directory");
        for (int i = 0; i < count; i++) {
            int entry = start + i * 12;
            if (Short.toUnsignedInt(tiff.getShort(entry)) != tag) continue;
            boolean pointer = tag == EXIF_IFD;
            if (tiff.getInt(entry + 4) != 1 || tiff.getShort(entry + 2) != (pointer ? 4 : 3))
                throw new IOException("Invalid JPEG EXIF tag " + tag);
            if (!pointer) return Short.toUnsignedInt(tiff.getShort(entry + 8));
            int address = tiff.getInt(entry + 8);
            if (address < 8) throw new IOException("Invalid JPEG EXIF subdirectory offset");
            return address;
        }
        return -1;
    }
}
