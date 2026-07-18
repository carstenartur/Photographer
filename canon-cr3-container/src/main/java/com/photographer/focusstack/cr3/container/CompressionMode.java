package com.photographer.focusstack.cr3.container;

/** Distinguishes between Canon CRX compression modes in a CR3 file. */
public enum CompressionMode {

    /** Lossless RAW — primary supported CRX mode. */
    RAW_LOSSLESS,

    /** Lossy C-RAW — wavelet, quantization and entropy coding. */
    CRAW_LOSSY,

    /** Compression mode is unknown or not recognized. */
    UNKNOWN;

    /** Returns whether this compression mode is supported by the current decoder. */
    public boolean isSupported() {
        return this == RAW_LOSSLESS;
    }
}
