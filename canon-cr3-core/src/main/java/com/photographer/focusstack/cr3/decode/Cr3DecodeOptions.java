package com.photographer.focusstack.cr3.decode;

import java.util.Objects;

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
     * Copies all current settings so each set can be changed independently.
     *
     * @return a new mutable options object
     */
    public Cr3DecodeOptions copy() {
        var copy = new Cr3DecodeOptions();
        copy.outputMode = outputMode;
        copy.bitDepth = bitDepth;
        copy.explicitBitDepth = explicitBitDepth;
        copy.applyBlackLevel = applyBlackLevel;
        copy.applyWhiteBalance = applyWhiteBalance;
        copy.applyColorMatrix = applyColorMatrix;
        copy.applySrgbConversion = applySrgbConversion;
        copy.applyLensCorrection = applyLensCorrection;
        copy.lensShadingStrength = lensShadingStrength;
        copy.applyCameraTone = applyCameraTone;
        copy.cropToActiveArea = cropToActiveArea;
        copy.pedestalStrategy = pedestalStrategy;
        copy.experimentalPedestalLevel = experimentalPedestalLevel;
        copy.maxWorkers = maxWorkers;
        copy.memoryMode = memoryMode;
        copy.postDecodeMemoryBytes = postDecodeMemoryBytes;
        return copy;
    }

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
     * Requested depth for developed RGB. Both Bayer output modes use
     * unsigned-short storage and keep their own sample scaling rules.
     */
    public enum BitDepth {
        /** Default request for eight bits per channel. */
        EIGHT,
        /** Unsigned 16-bit RGB channels, quantized after demosaicing and color processing. */
        SIXTEEN
    }

    /** Memory/speed trade-off for RAW reads; image resolution and sample precision are unchanged. */
    public enum MemoryMode {
        /** Select compact processing and adapt the worker limit when estimated heap headroom is tight. */
        AUTO,
        /** Always use compact processing, adapt the worker limit to estimated headroom and report it to stderr. */
        LOW_MEMORY,
        /** Retain normal parallel processing without automatic memory adaptation. */
        FAST
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
    private boolean explicitBitDepth;
    private boolean applyBlackLevel = true;
    private boolean applyWhiteBalance = true;
    private boolean applyColorMatrix = true;
    private boolean applySrgbConversion = true;
    private boolean applyLensCorrection = true;
    private double lensShadingStrength = 1;
    private boolean applyCameraTone = true;
    private boolean cropToActiveArea = true;
    private PedestalStrategy pedestalStrategy = PedestalStrategy.DEFAULT;
    private int experimentalPedestalLevel = 0;
    private int maxWorkers;
    private MemoryMode memoryMode = MemoryMode.AUTO;
    private long postDecodeMemoryBytes;

    /** Returns the RAW memory policy, automatic by default. */
    public MemoryMode memoryMode() { return memoryMode; }

    /**
     * Selects the RAW memory policy for this read. Low-memory processing avoids full-frame
     * orientation copies for owned RGB images and selects a worker limit within the
     * caller's CPU cap and estimated heap budget. When the one-worker estimate exceeds
     * the budget, the decoder still attempts the read with one worker.
     * Automatic selection is based on a heap snapshot; concurrent allocations and the
     * unavoidable final image storage can still exhaust memory. Preview reads are unaffected.
     *
     * @param mode memory policy (not null)
     * @return these options
     */
    public Cr3DecodeOptions memoryMode(MemoryMode mode) {
        memoryMode = java.util.Objects.requireNonNull(mode, "Memory mode must not be null");
        return this;
    }

    /** Additional bytes expected to coexist with the returned RAW image, zero by default. */
    public long postDecodeMemoryBytes() { return postDecodeMemoryBytes; }

    /**
     * Estimates additional heap the caller will allocate while retaining the returned RAW image.
     * For example, an adapter may copy samples to a differently sized destination after decoding.
     * AUTO and LOW_MEMORY compare this later peak with the working peak during decoding.
     * Existing caller-owned buffers are already reflected in the heap snapshot.
     *
     * @param bytes nonnegative estimated additional allocation after decoding
     * @return these options
     * @throws IllegalArgumentException if bytes is negative
     */
    public Cr3DecodeOptions postDecodeMemoryBytes(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("postDecodeMemoryBytes must be non-negative");
        }
        postDecodeMemoryBytes = bytes;
        return this;
    }

    /** Maximum workers per RAW stage, or zero to use the processor count available to the JVM. */
    public int maxWorkers() { return maxWorkers; }

    /**
     * Limits the workers for this RAW import within the shared CPU budget.
     * Zero selects automatic sizing; one requests sequential processing.
     * @throws IllegalArgumentException if the limit is negative
     */
    public Cr3DecodeOptions maxWorkers(int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("maxWorkers must be non-negative");
        }
        maxWorkers = limit;
        return this;
    }


    /** Returns the output mode. Default is {@link OutputMode#PREVIEW}. */
    public OutputMode outputMode() { return outputMode; }

    /** Sets the output mode. */
    public Cr3DecodeOptions outputMode(OutputMode mode) {
        this.outputMode = mode;
        return this;
    }

    /** Returns the requested depth or the default supplied by the caller of the factory. */
    public BitDepth bitDepth() { return bitDepth; }

    /** Whether a caller selected a depth that should take precedence over an ImageIO destination. */
    public boolean hasExplicitBitDepth() { return explicitBitDepth; }

    /**
     * Records the requested RGB output depth. See {@link BitDepth} for the
     * effective depth of the current output modes.
     *
     * @param depth requested RGB depth
     * @return these options
     */
    public Cr3DecodeOptions bitDepth(BitDepth depth) {
        this.bitDepth = depth;
        explicitBitDepth = true;
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

    /** Whether to use the matched optical profile; enabled by default for processed RAW. */
    public boolean applyLensCorrection() { return applyLensCorrection; }

    /**
     * Corrects supported lenses for vignetting, distortion and lateral chromatic aberration
     * on a recognized full field of view. The optical transform precedes any requested
     * source region or subsampling and keeps the developed image dimensions. Unknown lenses,
     * unsupported capture geometries and full-sensor reads retain their native geometry.
     *
     * @param apply whether to request the optical correction
     * @return these options
     */
    public Cr3DecodeOptions applyLensCorrection(boolean apply) {
        applyLensCorrection = apply;
        return this;
    }

    /** Fraction of the calibrated vignetting gain to use, one by default. */
    public double lensShadingStrength() { return lensShadingStrength; }

    /**
     * Sets the amount of vignetting correction used with {@link #applyLensCorrection(boolean)}.
     * Zero keeps the original peripheral brightness. One applies the full calibrated gain.
     * Intermediate values blend the gains in linear light. All strengths use the same
     * geometric mapping, lateral chromatic correction and valid field of view.
     *
     * @param strength finite value from zero to one, inclusive
     * @return these options
     * @throws IllegalArgumentException if strength is outside the supported range
     */
    public Cr3DecodeOptions lensShadingStrength(double strength) {
        if (!(strength >= 0 && strength <= 1)) {
            throw new IllegalArgumentException("lensShadingStrength must be between 0 and 1");
        }
        lensShadingStrength = strength;
        return this;
    }

    /** Whether to use a camera tone curve for display sRGB; enabled by default for processed RAW. */
    public boolean applyCameraTone() { return applyCameraTone; }

    /**
     * Selects the calibrated tone curve for a supported camera and Canon picture style.
     * The curve operates on developed RAW colors and does not require a JPEG decode.
     * Linear RGB and Bayer data retain their original tonal response.
     *
     * @param apply whether to request the camera tone curve
     * @return these options
     */
    public Cr3DecodeOptions applyCameraTone(boolean apply) {
        applyCameraTone = apply;
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

    /** Creates eight-bit RAW processing options with available lens and camera tone profiles enabled. */
    public static Cr3DecodeOptions rawProcessed() {
        return rawProcessed(BitDepth.EIGHT);
    }

    /**
     * Creates RAW processing options with available lens and camera tone profiles enabled
     * and a default depth that a destination may override. Disable either stage independently
     * with {@link #applyLensCorrection(boolean)} or {@link #applyCameraTone(boolean)}.
     * Unsupported camera, lens and picture-style combinations retain their established fallback.
     */
    public static Cr3DecodeOptions rawProcessed(BitDepth defaultDepth) {
        var options = new Cr3DecodeOptions().outputMode(OutputMode.RAW_PROCESSED);
        options.bitDepth = Objects.requireNonNull(defaultDepth, "defaultDepth");
        return options;
    }

    /**
     * Requests the normal developed RGB16 output with the available lens profile and camera tone curve.
     * This is equivalent to {@code rawProcessed(BitDepth.SIXTEEN)} and the ImageIO default.
     * Both adjustments are independent and may be disabled or enabled individually.
     * The pixels are developed from RAW sensor data.
     *
     * @return mutable options for camera-rendered RAW output
     */
    public static Cr3DecodeOptions cameraRendered() {
        return rawProcessed(BitDepth.SIXTEEN);
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
                .applyLensCorrection(false)
                .applyCameraTone(false)
                .applySrgbConversion(false);
    }

    @Override
    public String toString() {
        return "Cr3DecodeOptions[mode=%s, bits=%s, blackLevel=%s, wb=%s, colorMatrix=%s, srgb=%s, lens=%s, lensShadingStrength=%s, cameraTone=%s, crop=%s, pedestalStrategy=%s, experimentalPedestal=%s, maxWorkers=%s]"
                .formatted(outputMode, bitDepth, applyBlackLevel, applyWhiteBalance,
                        applyColorMatrix, applySrgbConversion, applyLensCorrection, lensShadingStrength, applyCameraTone, cropToActiveArea,
                        pedestalStrategy, experimentalPedestalLevel, maxWorkers);
    }
}
