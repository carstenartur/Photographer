package com.photographer.focusstack.dng.imageio;

import java.util.Map;
import javax.imageio.plugins.tiff.BaselineTIFFTagSet;
import javax.imageio.plugins.tiff.TIFFDirectory;
import javax.imageio.plugins.tiff.TIFFField;
import javax.imageio.plugins.tiff.TIFFTag;
import javax.imageio.plugins.tiff.TIFFTagSet;
import static javax.imageio.plugins.tiff.TIFFTag.*;
import static java.util.Map.entry;

/** Fields from the Adobe Digital Negative Specification (tag numbers are shared with TIFF/EP). */
final class DngTags {
    private static final BaselineTIFFTagSet BASELINE = BaselineTIFFTagSet.getInstance();
    private static final Map<Integer, String> NAMES = Map.ofEntries(
            entry(33421, "CFARepeatPatternDim"), entry(33422, "CFAPattern"),
            entry(50706, "DNGVersion"), entry(50707, "DNGBackwardVersion"),
            entry(50708, "UniqueCameraModel"), entry(50710, "CFAPlaneColor"), entry(50711, "CFALayout"),
            entry(50713, "BlackLevelRepeatDim"), entry(50714, "BlackLevel"), entry(50717, "WhiteLevel"),
            entry(50718, "DefaultScale"), entry(50719, "DefaultCropOrigin"), entry(50720, "DefaultCropSize"),
            entry(50721, "ColorMatrix1"), entry(50722, "ColorMatrix2"), entry(50728, "AsShotNeutral"),
            entry(50778, "CalibrationIlluminant1"), entry(50779, "CalibrationIlluminant2"),
            entry(50829, "ActiveArea"), entry(50830, "MaskedAreas"));

    private DngTags() {}

    static TIFFDirectory emptyDirectory() {
        return new TIFFDirectory(new TIFFTagSet[]{BASELINE}, null);
    }

    static TIFFDirectory bayerDirectory(int width, int height, String model, int[] pattern, int whiteLevel) {
        TIFFDirectory dir = emptyDirectory();
        dir.addTIFFField(longs(254, 0));
        dir.addTIFFField(shorts(274, 1));
        dir.addTIFFField(longs(256, width));
        dir.addTIFFField(longs(257, height));
        dir.addTIFFField(shorts(258, 16));
        dir.addTIFFField(shorts(259, 1));
        dir.addTIFFField(shorts(262, 32803)); // TIFF/EP Color Filter Array
        dir.addTIFFField(shorts(277, 1));
        dir.addTIFFField(shorts(339, 1));
        dir.addTIFFField(shorts(33421, 2, 2));
        dir.addTIFFField(bytes(33422, pattern));
        dir.addTIFFField(bytes(50706, 1, 1, 0, 0));
        dir.addTIFFField(bytes(50707, 1, 1, 0, 0));
        dir.addTIFFField(ascii(50708, model));
        dir.addTIFFField(longs(50717, whiteLevel));
        return dir;
    }

    private static TIFFField field(int number, int type, int count, Object data) {
        TIFFTag tag = BASELINE.getTag(number);
        if (tag == null) {
            String name = NAMES.get(number);
            if (name == null) {
                throw new IllegalArgumentException("Unknown DNG field " + number);
            }
            tag = new TIFFTag(name, number, 1 << type);
        }
        return new TIFFField(tag, type, count, data);
    }

    static TIFFField shorts(int id, int... values) {
        char[] data = new char[values.length];
        for (int i = 0; i < values.length; i++) {
            data[i] = (char) values[i];
        }
        return field(id, TIFF_SHORT, data.length, data);
    }

    static TIFFField longs(int id, long... values) {
        return field(id, TIFF_LONG, values.length, values);
    }

    static TIFFField bytes(int id, int... values) {
        byte[] data = new byte[values.length];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) values[i];
        }
        return field(id, TIFF_BYTE, data.length, data);
    }

    static TIFFField ascii(int id, String value) {
        return field(id, TIFF_ASCII, 1, new String[]{value});
    }

    static TIFFField rationals(int id, long[][] values) {
        return field(id, TIFF_RATIONAL, values.length, values);
    }

    static TIFFField signedRationals(int id, int[][] values) {
        return field(id, TIFF_SRATIONAL, values.length, values);
    }
}
