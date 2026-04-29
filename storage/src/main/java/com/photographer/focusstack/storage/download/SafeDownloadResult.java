package com.photographer.focusstack.storage.download;

/**
 * Outcome of a {@link SafeDownloadService#download} operation.
 *
 * <p>The status describes <em>why</em> the workflow stopped where it did so
 * that callers (REST endpoints, UI) can render a precise message instead of
 * relying on substring-matching on error strings.</p>
 *
 * @param status        terminal status of the workflow
 * @param bytes         downloaded bytes; {@code null} when the download itself failed
 * @param pairDeleted   {@code true} when the RAW + JPEG sibling was also deleted from the camera
 * @param errorMessage  short human-readable explanation; {@code null} on success
 */
public record SafeDownloadResult(
        Status status,
        byte[] bytes,
        boolean pairDeleted,
        String errorMessage
) {

    /** Terminal outcome categories of the safe-download workflow. */
    public enum Status {
        /** Download succeeded; deletion did not occur (either not requested or unsupported). */
        DOWNLOADED_KEPT,
        /** Download succeeded and the file was deleted from the camera per policy. */
        DOWNLOADED_DELETED,
        /** Backend does not advertise {@code supportsDeleteAfterDownload}; nothing was deleted. */
        DELETE_UNSUPPORTED,
        /** The download step failed; nothing was deleted. */
        DOWNLOAD_FAILED,
        /** Validation reported failure; nothing was deleted. */
        VALIDATION_FAILED,
        /** Download and validation succeeded but the deletion call itself failed. */
        DELETE_FAILED;

        /** Returns {@code true} when the file was successfully transferred. */
        public boolean isDownloaded() {
            return this == DOWNLOADED_KEPT
                    || this == DOWNLOADED_DELETED
                    || this == DELETE_UNSUPPORTED
                    || this == DELETE_FAILED;
        }
    }

    /** Convenience: did the file successfully reach local storage? */
    public boolean isDownloaded() {
        return status.isDownloaded();
    }

    /** Convenience: was the file deleted from the camera? */
    public boolean isDeleted() {
        return status == Status.DOWNLOADED_DELETED;
    }
}
