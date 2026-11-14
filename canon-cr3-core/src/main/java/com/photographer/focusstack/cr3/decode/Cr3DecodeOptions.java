package com.photographer.focusstack.cr3.decode;

/**
 * Options controlling how a CR3 file is decoded.
 *
 * <p>Allows callers to choose between preview extraction (fast) and full RAW
 * decoding, and to control output format and processing steps.</p>
 *
 * <p><strong>API status:</strong> preview and thumbnail selection are intended
 * for external use. Full-RAW and processing-pipeline controls remain experimental
 * until the CRX decoder is fully verified and may evolve between pre-1.0 releases.
 * The explicit reference-matching pedestal strategy and related accessors are
 * <strong>research-only</strong>; they are not a supported production contract
 * and may be changed or removed without notice.</p>
 */
public final class Cr3DecodeOptions {

    /**
     * Output mode for the decoder.
     */
    public enum OutputMode {
        /** Return the embedded JPEG preview (fastest). */
        PREVIEW,
        /** Return the embedded JPEG thumbnail. */
        THUMBNAIL,
        /** Decode the full RAW image with demosaicing and color processing. Experimental before 1.0. */
        RAW_PROCESSED,
        /** Decode the RAW image as a linear Bayer pattern (no demosaicing). Experimental before 1.0. */
        RAW_LINEAR,
        /** Full sensor mosaic in original camera sample units, including optical black margins. */
        RAW_SENSOR
    }

    /**
     * Output bit depth.
     */
    public enum BitDepth {
        /** 8 bits per channel (standard sRGB). */
        EIGHT,
        /** 16 bits per channel (for HDR / wide-gamut workflows). */
        SIXTEEN
    }

    /** Strategy for resolving pedestal / black-level subtraction. */
    public enum PedestalStrategy {
        /** Use the decoder's default resolver (OB -> metadata -> fallback). */
        DEFAULT,
        /**
         * Use an explicit constant pedestal chosen for reference matching experiments.
         *
         * <p><strong>Research-only — not supported production API.</strong>
         * This value exists solely for fixture/reference comparison and may change or be
         * removed without notice.</p>
         */
        EXPERIMENTAL_REFERENCE_MATCH
    }

    private OutputMode outputMode = OutputMode.PREVIEW;
    private BitDepth bitDepth = BitDepth.EIGHT;
    private boolean applyBlackLevel = true;
    private boolean applyWhiteBalance = true;
    private boolean applyColorMatrix = true;
    private boolean applySrgbConversion = true;
    private boolean cropToActiveArea = true;
    private PedestalStrategy pedestalStrategy = PedestalStrategy.DEFAULT;
    private int experimentalPedestalLevel = 0;

    /** Returns the output mode. Default is {@link OutputMode#PREVIEW}. */
    public OutputMode outputMode() { return outputMode; }

    /** Sets the output mode. */
    public Cr3DecodeOptions outputMode(OutputMode mode) {
        this.outputMode = mode;
        return this;
    }

    /** Returns the output bit depth. Default is {@link BitDepth#EIGHT}. */
    public BitDepth bitDepth() { return bitDepth; }

    /** Sets the output bit depth. */
    public Cr3DecodeOptions bitDepth(BitDepth depth) {
        this.bitDepth = depth;
        return this;
    }

    /** Whether to subtract black level from RAW data. Default is {@code true}. */
    public boolean applyBlackLevel() { return applyBlackLevel; }

    /** Sets whether to apply black level subtraction. */
    public Cr3DecodeOptions applyBlackLevel(boolean apply) {
        this.applyBlackLevel = apply;
        return this;
    }

    /** Whether to apply white balance. Default is {@code true}. */
    public boolean applyWhiteBalance() { return applyWhiteBalance; }

    /** Sets whether to apply white balance. */
    public Cr3DecodeOptions applyWhiteBalance(boolean apply) {
        this.applyWhiteBalance = apply;
        return this;
    }

    /** Whether to apply the camera color matrix. Default is {@code true}. */
    public boolean applyColorMatrix() { return applyColorMatrix; }

    /** Sets whether to apply the color matrix. */
    public Cr3DecodeOptions applyColorMatrix(boolean apply) {
        this.applyColorMatrix = apply;
        return this;
    }

    /** Whether to convert to sRGB. Default is {@code true}. */
    public boolean applySrgbConversion() { return applySrgbConversion; }

    /** Sets whether to apply sRGB conversion. */
    public Cr3DecodeOptions applySrgbConversion(boolean apply) {
        this.applySrgbConversion = apply;
        return this;
    }

    /** Whether to crop to the active sensor area. Default is {@code true}. */
    public boolean cropToActiveArea() { return cropToActiveArea; }

    /** Sets whether to crop to the active area. */
    public Cr3DecodeOptions cropToActiveArea(boolean crop) {
        this.cropToActiveArea = crop;
        return this;
    }

    /**
     * Returns the pedestal resolution strategy.
     * The reference-matching value is research-only.
     */
    public PedestalStrategy pedestalStrategy() { return pedestalStrategy; }

    /**
     * Sets the pedestal resolution strategy.
     * The reference-matching value is research-only and must not be used in production code.
     */
    public Cr3DecodeOptions pedestalStrategy(PedestalStrategy strategy) {
        this.pedestalStrategy = strategy;
        return this;
    }

    /**
     * Enables the research-only reference-matching pedestal strategy with
     * a fixed global pedestal level.
     *
     * <p><strong>Research-only — not supported production API.</strong>
     * This method exists solely for fixture/reference comparison and may change or be
     * removed without notice. Do not use in production code.</p>
     */
    public Cr3DecodeOptions experimentalPedestal(int level) {
        this.pedestalStrategy = PedestalStrategy.EXPERIMENTAL_REFERENCE_MATCH;
        this.experimentalPedestalLevel = level;
        return this;
    }

    /** Returns the fixed pedestal level used by the research-only reference-matching strategy. */
    public int experimentalPedestalLevel() { return experimentalPedestalLevel; }

    /** Creates default options (preview mode, 8-bit). */
    public static Cr3DecodeOptions defaults() {
        return new Cr3DecodeOptions();
    }

    /** Creates options for full RAW processing. */
    public static Cr3DecodeOptions rawProcessed() {
        return new Cr3DecodeOptions().outputMode(OutputMode.RAW_PROCESSED);
    }

    /**
     * Returns full-sensor Bayer codes in an unsigned-short, single-band raster.
     * The RAW_SENSOR mode always preserves the original extent and sample units.
     * Crop and processing settings apply only to the other RAW modes.
     */
    public static Cr3DecodeOptions rawSensor() {
        return rawLinear().outputMode(OutputMode.RAW_SENSOR).cropToActiveArea(false).applyBlackLevel(false);
    }

    /** Creates options for linear RAW output (no demosaicing). */
    public static Cr3DecodeOptions rawLinear() {
        return new Cr3DecodeOptions()
                .outputMode(OutputMode.RAW_LINEAR)
                .applyBlackLevel(true)
                .applyWhiteBalance(false)
                .applyColorMatrix(false)
                .applySrgbConversion(false);
    }

    @Override
    public String toString() {
        return "Cr3DecodeOptions[mode=%s, bits=%s, blackLevel=%s, wb=%s, colorMatrix=%s, srgb=%s, crop=%s, pedestalStrategy=%s, experimentalPedestal=%s]"
                .formatted(outputMode, bitDepth, applyBlackLevel, applyWhiteBalance,
                        applyColorMatrix, applySrgbConversion, cropToActiveArea,
                        pedestalStrategy, experimentalPedestalLevel);
    }
}
