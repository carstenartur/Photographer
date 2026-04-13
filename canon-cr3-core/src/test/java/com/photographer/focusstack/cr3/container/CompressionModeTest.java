package com.photographer.focusstack.cr3.container;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CompressionMode}.
 */
class CompressionModeTest {

    @Test
    @DisplayName("RAW_LOSSLESS should be supported")
    void rawLosslessSupported() {
        assertThat(CompressionMode.RAW_LOSSLESS.isSupported()).isTrue();
    }

    @Test
    @DisplayName("CRAW_LOSSY should not yet be supported")
    void crawLossyNotSupported() {
        assertThat(CompressionMode.CRAW_LOSSY.isSupported()).isFalse();
    }

    @Test
    @DisplayName("UNKNOWN should not be supported")
    void unknownNotSupported() {
        assertThat(CompressionMode.UNKNOWN.isSupported()).isFalse();
    }

    @Test
    @DisplayName("All enum values should be present")
    void allValuesPresent() {
        assertThat(CompressionMode.values())
                .containsExactly(CompressionMode.RAW_LOSSLESS, CompressionMode.CRAW_LOSSY, CompressionMode.UNKNOWN);
    }
}
