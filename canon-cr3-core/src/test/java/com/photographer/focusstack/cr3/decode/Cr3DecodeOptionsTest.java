package com.photographer.focusstack.cr3.decode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for {@link Cr3DecodeOptions}.
 */
class Cr3DecodeOptionsTest {

    @Test
    void rawImportsUseAutomaticParallelismUntilExplicitlyLimited() {
        var options = Cr3DecodeOptions.rawProcessed();
        assertThat(options.maxWorkers()).isZero();
        assertThat(options.maxWorkers(2).maxWorkers()).isEqualTo(2);
        assertThat(options.maxWorkers(1).maxWorkers()).isOne();
        assertThat(options.maxWorkers(0).maxWorkers()).isZero();
        assertThatIllegalArgumentException().isThrownBy(() -> options.maxWorkers(-1));
    }

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
        assertThat(opts.pedestalStrategy()).isEqualTo(Cr3DecodeOptions.PedestalStrategy.DEFAULT);
        assertThat(opts.experimentalPedestalLevel()).isZero();
    }

    @Test
    @DisplayName("rawProcessed includes the supported camera development stages")
    void rawProcessedOptions() {
        Cr3DecodeOptions opts = Cr3DecodeOptions.rawProcessed();
        assertThat(opts.outputMode()).isEqualTo(Cr3DecodeOptions.OutputMode.RAW_PROCESSED);
        assertThat(opts.applyLensCorrection()).isTrue();
        assertThat(opts.applyCameraTone()).isTrue();
    }

    @Test
    void selectingProcessedRawOnDefaultOptionsKeepsTheNormalDevelopmentStages() {
        var options = Cr3DecodeOptions.defaults().outputMode(Cr3DecodeOptions.OutputMode.RAW_PROCESSED);
        assertThat(options.applyLensCorrection()).isTrue();
        assertThat(options.applyCameraTone()).isTrue();
    }

    @Test
    void cameraRenderingOffersIndependentProfileSettingsAndCopiesBoth() {
        var camera = Cr3DecodeOptions.cameraRendered();
        var lensOnly = camera.copy().applyCameraTone(false);
        var toneOnly = camera.copy().applyLensCorrection(false);
        assertThat(camera.outputMode()).isEqualTo(Cr3DecodeOptions.OutputMode.RAW_PROCESSED);
        assertThat(camera.bitDepth()).isEqualTo(Cr3DecodeOptions.BitDepth.SIXTEEN);
        assertThat(camera.applyCameraTone()).isTrue();
        assertThat(camera.applyLensCorrection()).isTrue();
        assertThat(lensOnly.applyCameraTone()).isFalse();
        assertThat(lensOnly.applyLensCorrection()).isTrue();
        assertThat(toneOnly.applyCameraTone()).isTrue();
        assertThat(toneOnly.applyLensCorrection()).isFalse();
    }

    @Test
    void shadingAmountDoesNotSelectTheLensAndRejectsInvalidValues() {
        var neutral = Cr3DecodeOptions.rawProcessed().applyLensCorrection(false).applyCameraTone(false);
        assertThat(neutral.lensShadingStrength()).isEqualTo(1);
        var copy = neutral.lensShadingStrength(0.4).copy();
        assertThat(copy.lensShadingStrength()).isEqualTo(0.4);
        assertThat(copy.applyLensCorrection()).isFalse();
        copy.lensShadingStrength(0);
        assertThat(neutral.lensShadingStrength()).isEqualTo(0.4);
        for (double invalid : new double[]{-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThatIllegalArgumentException().isThrownBy(() -> copy.lensShadingStrength(invalid));
        }
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
        assertThat(opts.applyLensCorrection()).isFalse();
        assertThat(opts.applyCameraTone()).isFalse();
    }

    @Test
    @DisplayName("Fluent builder should allow chaining")
    void fluentBuilder() {
        Cr3DecodeOptions opts = new Cr3DecodeOptions()
                .outputMode(Cr3DecodeOptions.OutputMode.THUMBNAIL)
                .bitDepth(Cr3DecodeOptions.BitDepth.SIXTEEN)
                .applyBlackLevel(false)
                .pedestalStrategy(Cr3DecodeOptions.PedestalStrategy.DEFAULT);

        assertThat(opts.outputMode()).isEqualTo(Cr3DecodeOptions.OutputMode.THUMBNAIL);
        assertThat(opts.bitDepth()).isEqualTo(Cr3DecodeOptions.BitDepth.SIXTEEN);
        assertThat(opts.applyBlackLevel()).isFalse();
    }

    @Test
    @DisplayName("experimentalPedestal should enable experimental reference-matching strategy")
    void experimentalPedestal() {
        Cr3DecodeOptions opts = new Cr3DecodeOptions().experimentalPedestal(2048);

        assertThat(opts.pedestalStrategy()).isEqualTo(Cr3DecodeOptions.PedestalStrategy.EXPERIMENTAL_REFERENCE_MATCH);
        assertThat(opts.experimentalPedestalLevel()).isEqualTo(2048);
    }
}
