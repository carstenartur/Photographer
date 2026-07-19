package com.photographer.focusstack.cr3.metadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cleanroom parser for Canon MakerNote data from CMT3 boxes in CR3 files.
 *
 * <p>Canon MakerNote uses standard TIFF IFD format at the box data offset.
 * Unlike normal EXIF IFDs, Canon MakerNote IFDs use tag IDs in the range
 * 0x0001–0x4000+ with Canon-specific semantics.</p>
 *
 * <p>This parser extracts known tags where semantics are publicly documented
 * or derivable from test data analysis. Unknown tags are preserved as raw values
 * for diagnostic access.</p>
 *
 * <h2>Tag semantics derived from</h2>
 * <ul>
 *   <li>Standard TIFF IFD encoding rules (TIFF 6.0 spec)</li>
 *   <li>Tag ID analysis of available test CR3 files</li>
 *   <li>Publicly documented EXIF MakerNote conventions</li>
 * </ul>
 *
 * <p>No proprietary parser code was used. No code from LibRaw, dcraw,
 * exiftool, or similar projects was ported or adapted.</p>
 */
public final class CanonMakerNoteParser {

    private static final Logger LOG = LoggerFactory.getLogger(CanonMakerNoteParser.class);

    /**
     * Canon MakerNote tag IDs — publicly known tag IDs.
     * Tag semantics are derived from public EXIF documentation and self-analysis
     * of test CR3 files.
     */
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
    /** Canon color balance tag — contains WB multipliers as RGGB SHORT array. */
    static final int TAG_COLOR_BALANCE = 0x4001;
    /** Canon color temperature tag. */
    static final int TAG_COLOR_TEMPERATURE = 0x00A0;
    /** Minimum expected value for a raw WB channel reading. */
    private static final int MIN_WB_RAW_VALUE = 100;
    /** Maximum expected value for a raw WB channel reading (16-bit unsigned). */
    private static final int MAX_WB_RAW_VALUE = 65535;

    private CanonMakerNoteParser() {}

    /**
     * Parses a Canon MakerNote from a CMT3 box.
     *
     * <p>The CMT3 box contains a TIFF IFD starting directly at the box data offset,
     * using the same byte order and base offset as the IFD header.</p>
     *
     * @param input         the image input stream
     * @param boxDataOffset absolute offset of the CMT3 box data
     * @param boxDataSize   size of the CMT3 box data
     * @return parsed MakerNote data (never null; may be empty on parse failure)
     */
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

        // Canon MakerNote in CMT3 uses standard TIFF IFD format
        Map<Integer, TiffIfdParser.TagValue> rawTags =
                TiffIfdParser.parseIfd(input, boxDataOffset, boxDataSize);

        if (rawTags.isEmpty()) {
            LOG.debug("No tags found in Canon MakerNote");
            return CanonMakerNoteData.empty();
        }

        LOG.debug("Parsed {} Canon MakerNote tags", rawTags.size());

        // Extract WB multipliers from tag 0x4001 (ColorBalance)
        double[] wbMultipliers = extractWhiteBalance(rawTags);

        // Extract color temperature if available
        int colorTemp = extractColorTemperature(rawTags);

        // Extract sub-IFDs (CameraSettings, ShotInfo) — stored as SHORT arrays
        Map<Integer, TiffIfdParser.TagValue> cameraSettings = extractSubArray(rawTags, TAG_CAMERA_SETTINGS);
        Map<Integer, TiffIfdParser.TagValue> shotInfo = extractSubArray(rawTags, TAG_SHOT_INFO);

        return new CanonMakerNoteData(wbMultipliers, colorTemp,
                Collections.unmodifiableMap(rawTags),
                Collections.unmodifiableMap(cameraSettings),
                Collections.unmodifiableMap(shotInfo));
    }

    /**
     * Extracts white balance multipliers from Canon's color balance tag (0x4001).
     *
     * <p>Canon stores WB as RGGB unsigned SHORT values in tag 0x4001. The exact
     * offset within the array varies by camera model. We look for a group of 4
     * values where the pattern suggests RGGB multipliers (non-zero, reasonable range).</p>
     *
     * @return [R, G, B] multipliers normalised to G=1.0, or null if not found
     */
    private static double[] extractWhiteBalance(Map<Integer, TiffIfdParser.TagValue> tags) {
        TiffIfdParser.TagValue colorBalance = tags.get(TAG_COLOR_BALANCE);
        if (colorBalance instanceof TiffIfdParser.TagValue.IntArrayValue arr) {
            int[] values = arr.values();
            // Canon ColorBalance tag 0x4001: look for RGGB group at known offsets.
            // Common offsets for WB_RGGBLevelsAsShot: index 63, 67, or near start.
            // We scan for 4 consecutive values that look like WB multipliers (>100, <65535).
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

    /**
     * Extracts the color temperature from MakerNote tags.
     */
    private static int extractColorTemperature(Map<Integer, TiffIfdParser.TagValue> tags) {
        // Color temperature may be stored in various tags depending on camera model
        // Check known locations
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

    /**
     * Extracts a Canon sub-IFD stored as a SHORT array into indexed tag values.
     * Canon stores CameraSettings and ShotInfo as SHORT arrays in top-level tags.
     */
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
