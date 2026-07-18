package com.photographer.focusstack.cr3.metadata;

import java.util.Collections;
import java.util.Map;

/**
 * Parsed data from a Canon MakerNote (CMT3 box in CR3 files).
 *
 * <p>Canon MakerNote uses a TIFF IFD structure with Canon-proprietary tag IDs.
 * This record holds the parsed semantic fields and raw tag data for unknown tags.</p>
 *
 * <p>Cleanroom implementation based on publicly documented TIFF IFD structure
 * and self-derived tag analysis from test files. No external parser code was used.</p>
 *
 * @param whiteBalanceMultipliers WB multipliers [R, G, B] if found, or {@code null}
 * @param colorTemperature       color temperature in Kelvin if found, or 0
 * @param rawTags                all IFD tags (tag ID → raw value) for advanced access
 * @param cameraSettings         Canon CameraSettings sub-IFD tags, or empty map
 * @param shotInfo               Canon ShotInfo sub-IFD tags, or empty map
 */
public record CanonMakerNoteData(
        double[] whiteBalanceMultipliers,
        int colorTemperature,
        Map<Integer, TiffIfdParser.TagValue> rawTags,
        Map<Integer, TiffIfdParser.TagValue> cameraSettings,
        Map<Integer, TiffIfdParser.TagValue> shotInfo
) {
    /** Creates an empty (no data parsed) MakerNote result. */
    public static CanonMakerNoteData empty() {
        return new CanonMakerNoteData(null, 0,
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
    }

    /** Returns {@code true} if white balance multipliers were extracted. */
    public boolean hasWhiteBalance() {
        return whiteBalanceMultipliers != null && whiteBalanceMultipliers.length >= 3;
    }

    /** Returns {@code true} if any MakerNote data was parsed. */
    public boolean isEmpty() {
        return rawTags.isEmpty();
    }
}
