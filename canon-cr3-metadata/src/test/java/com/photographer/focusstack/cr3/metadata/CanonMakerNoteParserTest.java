package com.photographer.focusstack.cr3.metadata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CanonMakerNoteParser}.
 */
class CanonMakerNoteParserTest {

    @Test
    @DisplayName("Empty MakerNote data returns empty result")
    void emptyData() {
        CanonMakerNoteData result = CanonMakerNoteData.empty();
        assertThat(result.isEmpty()).isTrue();
        assertThat(result.hasWhiteBalance()).isFalse();
        assertThat(result.colorTemperature()).isZero();
        assertThat(result.rawTags()).isEmpty();
        assertThat(result.cameraSettings()).isEmpty();
        assertThat(result.shotInfo()).isEmpty();
    }

    @Test
    @DisplayName("MakerNote with WB multipliers reports hasWhiteBalance")
    void withWhiteBalance() {
        double[] wb = {1.8, 1.0, 1.4};
        CanonMakerNoteData result = new CanonMakerNoteData(
                wb, 5500, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
        assertThat(result.hasWhiteBalance()).isTrue();
        assertThat(result.whiteBalanceMultipliers()).containsExactly(1.8, 1.0, 1.4);
        assertThat(result.colorTemperature()).isEqualTo(5500);
        assertThat(result.isEmpty()).isTrue(); // rawTags is empty
    }

    @Test
    @DisplayName("MakerNote with raw tags reports non-empty")
    void withRawTags() {
        Map<Integer, TiffIfdParser.TagValue> tags = new LinkedHashMap<>();
        tags.put(0x0001, new TiffIfdParser.TagValue.IntArrayValue(new int[]{1, 2, 3}));
        tags.put(0x0010, new TiffIfdParser.TagValue.LongValue(0x12345678));
        CanonMakerNoteData result = new CanonMakerNoteData(
                null, 0, tags, Collections.emptyMap(), Collections.emptyMap());
        assertThat(result.isEmpty()).isFalse();
        assertThat(result.rawTags()).hasSize(2);
    }

    @Test
    @DisplayName("WB multipliers with insufficient length returns false for hasWhiteBalance")
    void wbTooShort() {
        double[] wb = {1.0, 1.0};
        CanonMakerNoteData result = new CanonMakerNoteData(
                wb, 0, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
        assertThat(result.hasWhiteBalance()).isFalse();
    }
}
