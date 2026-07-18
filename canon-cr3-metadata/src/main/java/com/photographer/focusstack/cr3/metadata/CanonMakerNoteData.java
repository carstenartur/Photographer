package com.photographer.focusstack.cr3.metadata;

import java.util.Collections;
import java.util.Map;

/** Parsed data from a Canon MakerNote (CMT3 box in CR3 files). */
public record CanonMakerNoteData(
        double[] whiteBalanceMultipliers,
        int colorTemperature,
        Map<Integer, TiffIfdParser.TagValue> rawTags,
        Map<Integer, TiffIfdParser.TagValue> cameraSettings,
        Map<Integer, TiffIfdParser.TagValue> shotInfo
) {
    public static CanonMakerNoteData empty() {
        return new CanonMakerNoteData(null, 0,
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
    }

    public boolean hasWhiteBalance() {
        return whiteBalanceMultipliers != null && whiteBalanceMultipliers.length >= 3;
    }

    public boolean isEmpty() {
        return rawTags.isEmpty();
    }
}
