package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.bmff.Iad1Box;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.metadata.TiffIfdParser;
import java.io.IOException;
import java.nio.ByteOrder;
import javax.imageio.stream.ImageInputStream;

/** Geometry of the full RAW track and per-exposure calibration recorded by the camera. */
public record Cr3SensorCalibration(Cmp1Box compression, Iad1Box geometry, ColorData color) {

    /** Canon ColorData values, in native sensor units and RGGB channel order. */
    public record ColorData(int redGain, int greenRedGain, int greenBlueGain, int blueGain,
                            int redBlack, int greenRedBlack, int greenBlueBlack, int blueBlack, int white) {}

    static Cr3SensorCalibration read(ImageInputStream input, Cr3Container container) throws IOException {
        Cr3Track raw = null;
        Cr3Track ctmd = null;
        for (Cr3Track track : container.tracks()) {
            if (raw == null && (track.type() == Cr3Track.TrackType.CRX_BIG || track.type() == Cr3Track.TrackType.CRX_RAW)) {
                raw = track;
            }
            if ("CTMD".equals(track.codecType())) {
                ctmd = track;
            }
        }
        if (raw == null || raw.cmp1() == null) {
            return null;
        }
        ColorData data = ctmd == null ? null : readColorData(input, ctmd);
        return new Cr3SensorCalibration(raw.cmp1(), raw.iad1(), data);
    }

    // CTMD records and their nested blocks have little-endian lengths including their headers.
    // Type 8 contains block 0x927c (EXIF MakerNote), which carries a self-contained TIFF.
    private static ColorData readColorData(ImageInputStream input, Cr3Track track) throws IOException {
        long pos = track.dataOffset();
        if (pos < 0 || track.dataSize() < 12 || pos > Long.MAX_VALUE - track.dataSize()) {
            return null;
        }
        long end = pos + track.dataSize();
        long originalPosition = input.getStreamPosition();
        ByteOrder originalOrder = input.getByteOrder();
        try {
            input.setByteOrder(ByteOrder.LITTLE_ENDIAN);
            while (end - pos >= 12) {
                input.seek(pos);
                long length = input.readUnsignedInt();
                int type = input.readUnsignedShort();
                if (length < 12 || length > end - pos) return null;
                if (type == 8) return readMakerNote(input, pos + 12, pos + length);
                pos += length;
            }
            return null;
        } finally {
            input.setByteOrder(originalOrder);
            input.seek(originalPosition);
        }
    }

    private static ColorData readMakerNote(ImageInputStream input, long pos, long end) throws IOException {
        while (end - pos >= 8) {
            input.seek(pos);
            long length = input.readUnsignedInt();
            long tag = input.readUnsignedInt();
            if (length < 8 || length > end - pos) return null;
            if (tag == 0x927c) {
                var tags = TiffIfdParser.parseIfd(input, pos + 8, length - 8);
                if (tags.get(0x4001) instanceof TiffIfdParser.TagValue.IntArrayValue field) {
                    int[] words = field.values();
                    // Verified with independent per-file metadata on the EOS M50, R, RP and G5 X II.
                    if (words.length > 797 && words[0] >= 16 && words[0] <= 19) {
                        return new ColorData(words[71], words[72], words[73], words[74],
                                words[792], words[793], words[794], words[795], words[797]);
                    }
                }
                return null;
            }
            pos += length;
        }
        return null;
    }
}
