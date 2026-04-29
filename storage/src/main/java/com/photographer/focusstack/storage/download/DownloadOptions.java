package com.photographer.focusstack.storage.download;

import java.util.Objects;

/**
 * Options controlling a safe "download from camera" workflow.
 *
 * <p>The matching {@link SafeDownloadService} enforces the following ordering
 * before any file is deleted from the camera:</p>
 * <ol>
 *   <li>The backend must advertise that it supports delete-after-download.</li>
 *   <li>The download must succeed (no I/O exception, non-empty bytes).</li>
 *   <li>If {@link #validateAfterDownload} is set, the validation callback
 *       must report success.</li>
 *   <li>RAW + JPEG pairs are handled per the configured
 *       {@link RawJpegDeletionPolicy}.</li>
 * </ol>
 *
 * @param deleteAfterDownload   when {@code true}, attempt to delete the file
 *                              from the camera after a successful download
 * @param validateAfterDownload when {@code true}, run the validation callback
 *                              before considering the download complete
 * @param deletionPolicy        how to treat RAW + JPEG pairs (never {@code null})
 */
public record DownloadOptions(
        boolean deleteAfterDownload,
        boolean validateAfterDownload,
        RawJpegDeletionPolicy deletionPolicy
) {

    public DownloadOptions {
        Objects.requireNonNull(deletionPolicy, "deletionPolicy");
    }

    /**
     * Returns the safe default: <em>no</em> deletion, <em>no</em> extra
     * validation, and the safe deletion policy ({@link RawJpegDeletionPolicy#safeDefault()}).
     */
    public static DownloadOptions safeDefault() {
        return new DownloadOptions(false, false, RawJpegDeletionPolicy.safeDefault());
    }

    /**
     * Returns options requesting download + automatic deletion of the
     * downloaded file only, with validation enabled.
     */
    public static DownloadOptions deleteWithValidation() {
        return new DownloadOptions(true, true, RawJpegDeletionPolicy.DELETE_DOWNLOADED_ONLY);
    }

    /** Returns a copy with {@code deleteAfterDownload} set to the given value. */
    public DownloadOptions withDeleteAfterDownload(boolean v) {
        return new DownloadOptions(v, this.validateAfterDownload, this.deletionPolicy);
    }

    /** Returns a copy with {@code validateAfterDownload} set to the given value. */
    public DownloadOptions withValidateAfterDownload(boolean v) {
        return new DownloadOptions(this.deleteAfterDownload, v, this.deletionPolicy);
    }

    /** Returns a copy with the given deletion policy. */
    public DownloadOptions withDeletionPolicy(RawJpegDeletionPolicy policy) {
        return new DownloadOptions(this.deleteAfterDownload, this.validateAfterDownload, policy);
    }
}
