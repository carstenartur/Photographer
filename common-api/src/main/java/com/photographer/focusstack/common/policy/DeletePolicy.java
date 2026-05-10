package com.photographer.focusstack.common.policy;

/**
 * When (if ever) media should be deleted from the camera after import.
 *
 * <p>The {@code MediaIngestService} consults this policy together with the
 * {@code TransferResult} for each item; deletion is performed only when the
 * combined check yields {@code true}.  See
 * {@code docs/generic-camera-architecture.md} for the full decision matrix.
 */
public enum DeletePolicy {
    /** Never delete from the camera. Safe default. */
    NEVER,
    /** Mark for deletion but require an explicit user confirmation. */
    MANUAL_CONFIRM,
    /**
     * Delete automatically after a successful download, regardless of
     * whether verification (size match / checksum) was requested or passed.
     * Use {@link #AUTO_IF_VERIFIED} for the safer variant.
     */
    AUTO_AFTER_IMPORT,
    /** Auto-delete JPEG/HEIF only; keep RAW masters on the card. Requires verification to pass. */
    AUTO_JPEG_ONLY,
    /** Auto-delete only when the import succeeded <em>and</em> the verification checks passed. */
    AUTO_IF_VERIFIED
}
