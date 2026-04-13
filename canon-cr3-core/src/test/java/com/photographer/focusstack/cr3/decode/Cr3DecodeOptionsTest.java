package com.photographer.focusstack.cr3.decode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link Cr3DecodeOptions}.
 */
class Cr3DecodeOptionsTest {

    @Test
    @DisplayName("Default options should be preview mode with 8-bit output")
    void defaultOptions() {
        Cr3DecodeOptions opts = Cr3DecodeOptions.defaults();
        assertThat(opts.outputMode()).isEqualTo(Cr3DecodeOptions.OutputMode.PREVIEW);
        assertThat(opts.bitDepth()).isEqualTo(Cr3DecodeOptions.BitDepth.EIGHT);
        assertThat(opts.applyBlackLevel()).isTrue();
        assertThat(opts.applyWhiteBalance()).isTrue();
        assertThat(opts.applyColorMatrix()).isTrue();
        assertThat(opts.applySrgbConversion()).isTrue();
        assertThat(opts.cropToActiveArea()).isTrue();
    }

    @Test
    @DisplayName("rawProcessed factory should set RAW_PROCESSED mode")
    void rawProcessedOptions() {
        Cr3DecodeOptions opts = Cr3DecodeOptions.rawProcessed();
        assertThat(opts.outputMode()).isEqualTo(Cr3DecodeOptions.OutputMode.RAW_PROCESSED);
    }

    @Test
    @DisplayName("rawLinear factory should disable color processing")
    void rawLinearOptions() {
        Cr3DecodeOptions opts = Cr3DecodeOptions.rawLinear();
        assertThat(opts.outputMode()).isEqualTo(Cr3DecodeOptions.OutputMode.RAW_LINEAR);
        assertThat(opts.applyBlackLevel()).isTrue();
        assertThat(opts.applyWhiteBalance()).isFalse();
        assertThat(opts.applyColorMatrix()).isFalse();
        assertThat(opts.applySrgbConversion()).isFalse();
    }

    @Test
    @DisplayName("Fluent builder should allow chaining")
    void fluentBuilder() {
        Cr3DecodeOptions opts = new Cr3DecodeOptions()
                .outputMode(Cr3DecodeOptions.OutputMode.THUMBNAIL)
                .bitDepth(Cr3DecodeOptions.BitDepth.SIXTEEN)
                .applyBlackLevel(false);

        assertThat(opts.outputMode()).isEqualTo(Cr3DecodeOptions.OutputMode.THUMBNAIL);
        assertThat(opts.bitDepth()).isEqualTo(Cr3DecodeOptions.BitDepth.SIXTEEN);
        assertThat(opts.applyBlackLevel()).isFalse();
    }
}
