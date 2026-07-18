package com.photographer.focusstack.cr3.metadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Cleanroom parser for Canon MakerNote data from CMT3 boxes in CR3 files. */
public final class CanonMakerNoteParser {

    private static final Logger LOG = LoggerFactory.getLogger(CanonMakerNoteParser.class);

    static final int TAG_CAMERA_SETTINGS = 0x0001;
    static final int TAG_FOCAL_LENGTH_TAG = 0x0002;
    static final int TAG_SHOT_INFO = 0x0004;
    static final int TAG_PANORAMA = 0x0005;
    static final int TAG_IMAGE_TYPE = 0x0006;
    static final int TAG_FIRMWARE_VERSION = 0x0007;
    static final int TAG_FILE_NUMBER = 0x0008;
    static final int TAG_OWNER_NAME = 0x0009;
    static final int TAG_SERIAL_NUMBER = 0x000C;
    static final int TAG_CAMERA_INFO = 0x000D;
    static final int TAG_MODEL_ID = 0x0010;
    static final int TAG_THUMBNAIL_IMAGE_VALID_AREA = 0x0013;
    static final int TAG_SERIAL_NUMBER_FORMAT = 0x0015;
    static final int TAG_AF_INFO = 0x0026;
    static final int TAG_COLOR_BALANCE = 0x4001;
    static final int TAG_COLOR_TEMPERATURE = 0x00A0;
    private static final int MIN_WB_RAW_VALUE = 100;
    private static final int MAX_WB_RAW_VALUE = 65535;

    private CanonMakerNoteParser() {}

    public static CanonMakerNoteData parse(ImageInputStream input,
                                            long boxDataOffset,
                                            long boxDataSize) {
        try {
            return doParse(input, boxDataOffset, boxDataSize);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Failed to parse Canon MakerNote: {}", e.getMessage());
            return CanonMakerNoteData.empty();
        }
    }

    private static CanonMakerNoteData doParse(ImageInputStream input,
                                               long boxDataOffset,
                                               long boxDataSize) throws IOException {
        if (boxDataSize < 8) {
            LOG.debug("CMT3 box too small for MakerNote: {} bytes", boxDataSize);
            return CanonMakerNoteData.empty();
        }

        Map<Integer, TiffIfdParser.TagValue> rawTags =
                TiffIfdParser.parseIfd(input, boxDataOffset, boxDataSize);

        if (rawTags.isEmpty()) {
            LOG.debug("No tags found in Canon MakerNote");
            return CanonMakerNoteData.empty();
        }

        LOG.debug("Parsed {} Canon MakerNote tags", rawTags.size());

        double[] wbMultipliers = extractWhiteBalance(rawTags);
        int colorTemp = extractColorTemperature(rawTags);
        Map<Integer, TiffIfdParser.TagValue> cameraSettings = extractSubArray(rawTags, TAG_CAMERA_SETTINGS);
        Map<Integer, TiffIfdParser.TagValue> shotInfo = extractSubArray(rawTags, TAG_SHOT_INFO);

        return new CanonMakerNoteData(wbMultipliers, colorTemp,
                Collections.unmodifiableMap(rawTags),
                Collections.unmodifiableMap(cameraSettings),
                Collections.unmodifiableMap(shotInfo));
    }

    private static double[] extractWhiteBalance(Map<Integer, TiffIfdParser.TagValue> tags) {
        TiffIfdParser.TagValue colorBalance = tags.get(TAG_COLOR_BALANCE);
        if (colorBalance instanceof TiffIfdParser.TagValue.IntArrayValue arr) {
            int[] values = arr.values();
            int[] candidateOffsets = {63, 67, 1, 0};
            for (int offset : candidateOffsets) {
                if (offset + 3 < values.length
                        && values[offset] > MIN_WB_RAW_VALUE && values[offset] < MAX_WB_RAW_VALUE
                        && values[offset + 1] > MIN_WB_RAW_VALUE && values[offset + 1] < MAX_WB_RAW_VALUE
                        && values[offset + 2] > MIN_WB_RAW_VALUE && values[offset + 2] < MAX_WB_RAW_VALUE
                        && values[offset + 3] > MIN_WB_RAW_VALUE && values[offset + 3] < MAX_WB_RAW_VALUE) {
                    int r = values[offset];
                    int g1 = values[offset + 1];
                    int g2 = values[offset + 2];
                    int b = values[offset + 3];
                    double gAvg = (g1 + g2) / 2.0;
                    if (gAvg > 0) {
                        double[] multipliers = {r / gAvg, 1.0, b / gAvg};
                        LOG.debug("Extracted WB multipliers from tag 0x4001 offset {}: R={}, G1={}, G2={}, B={} → [{}, 1.0, {}]",
                                offset, r, g1, g2, b, multipliers[0], multipliers[2]);
                        return multipliers;
                    }
                }
            }
            LOG.debug("ColorBalance tag 0x4001 found ({} values) but WB pattern not matched", values.length);
        }
        return null;
    }

    private static int extractColorTemperature(Map<Integer, TiffIfdParser.TagValue> tags) {
        for (int tagId : new int[]{TAG_COLOR_TEMPERATURE, 0x00A0}) {
            TiffIfdParser.TagValue val = tags.get(tagId);
            if (val instanceof TiffIfdParser.TagValue.IntValue iv && iv.value() > 0) {
                return iv.value();
            }
            if (val instanceof TiffIfdParser.TagValue.LongValue lv && lv.value() > 0 && lv.value() < 100_000) {
                return (int) lv.value();
            }
        }
        return 0;
    }

    private static Map<Integer, TiffIfdParser.TagValue> extractSubArray(
            Map<Integer, TiffIfdParser.TagValue> tags, int tagId) {
        TiffIfdParser.TagValue val = tags.get(tagId);
        if (val instanceof TiffIfdParser.TagValue.IntArrayValue arr) {
            Map<Integer, TiffIfdParser.TagValue> result = new LinkedHashMap<>();
            int[] values = arr.values();
            for (int i = 0; i < values.length; i++) {
                result.put(i, new TiffIfdParser.TagValue.IntValue(values[i]));
            }
            return result;
        }
        return Collections.emptyMap();
    }
}
