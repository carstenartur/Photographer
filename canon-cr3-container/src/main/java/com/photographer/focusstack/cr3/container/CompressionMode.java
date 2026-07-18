package com.photographer.focusstack.cr3.container;

/**
 * Distinguishes between different Canon CRX compression modes in a CR3 file.
 *
 * <p>This is a critical architectural distinction derived from the CR3 format
 * specification. Canon CR3 files can contain either lossless RAW or lossy C-RAW
 * data, and these require fundamentally different decoding paths.</p>
 *
 * <p>V1 of this decoder targets {@link #RAW_LOSSLESS} only. {@link #CRAW_LOSSY}
 * support is deferred to a later milestone.</p>
 */
public enum CompressionMode {

    /**
     * Lossless RAW — full wavelet + entropy coding with no quantization.
     * This is the primary target for V1 decoding.
     */
    RAW_LOSSLESS,

    /**
     * Lossy C-RAW — wavelet + quantization + entropy coding.
     * Results in smaller files with some quality loss.
     * Deferred to V2.
     */
    CRAW_LOSSY,

    /**
     * Compression mode could not be determined or is not yet recognized.
     */
    UNKNOWN;

    /**
     * Returns whether this compression mode is supported by the current decoder.
     *
     * @return {@code true} if decoding is implemented for this mode
     */
    public boolean isSupported() {
        return this == RAW_LOSSLESS;
    }
}
