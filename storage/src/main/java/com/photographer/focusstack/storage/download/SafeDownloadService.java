package com.photographer.focusstack.storage.download;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;

/**
 * Orchestrates the safe "download from camera" workflow described in the
 * Sprint 3 brief of issue #54.
 *
 * <p>The service is deliberately decoupled from any concrete camera backend:
 * callers pass in lambdas describing how to download / validate / delete the
 * file. This keeps the {@code storage} module free of plugin-api or backend
 * dependencies while still enforcing the safety rules in one central place.</p>
 *
 * <p>Workflow (per call to {@link #download}):</p>
 * <ol>
 *   <li>If {@link DownloadOptions#deleteAfterDownload} is requested but the
 *       backend does not support it, the file is downloaded and kept on the
 *       camera (status {@link SafeDownloadResult.Status#DELETE_UNSUPPORTED}).</li>
 *   <li>The download step is invoked. Any exception or empty payload is
 *       reported as {@link SafeDownloadResult.Status#DOWNLOAD_FAILED} and the
 *       camera file is left untouched.</li>
 *   <li>If validation is enabled, the validator is called with the downloaded
 *       bytes. A return of {@code false} or a thrown exception aborts the
 *       workflow with {@link SafeDownloadResult.Status#VALIDATION_FAILED}.</li>
 *   <li>The delete step is invoked with the configured
 *       {@link RawJpegDeletionPolicy}. A failure here is reported as
 *       {@link SafeDownloadResult.Status#DELETE_FAILED}; the bytes are still
 *       returned because they are already on local disk.</li>
 * </ol>
 *
 * <p>This service is stateless and safe to share across threads.</p>
 */
public class SafeDownloadService {

    private static final Logger log = LoggerFactory.getLogger(SafeDownloadService.class);

    /** Performs the actual transfer from camera to a local byte[]. */
    @FunctionalInterface
    public interface DownloadStep {
        /**
         * @param fileName the file identifier on the camera
         * @return the downloaded bytes; the {@link SafeDownloadService} treats
         *         a {@code null} or empty array as
         *         {@link SafeDownloadResult.Status#DOWNLOAD_FAILED}
         */
        byte[] download(String fileName) throws IOException;
    }

    /** Validates the downloaded bytes (e.g. checksum, JPEG marker, byte count). */
    @FunctionalInterface
    public interface DownloadValidator {
        /** @return {@code true} when the bytes look correct, {@code false} otherwise. */
        boolean validate(String fileName, byte[] bytes);
    }

    /** Deletes the file (and possibly its RAW/JPEG sibling) from the camera. */
    @FunctionalInterface
    public interface DeleteStep {
        /**
         * @param fileName the file identifier on the camera
         * @param policy   how to handle the RAW + JPEG pair
         * @return {@code true} when the sibling was also deleted (matters when
         *         policy is {@link RawJpegDeletionPolicy#DELETE_BOTH_PAIR})
         */
        boolean delete(String fileName, RawJpegDeletionPolicy policy) throws IOException;
    }

    /**
     * Runs the safe download workflow.
     *
     * @param fileName             camera file identifier (e.g. {@code "100EOS_R/IMG_0001.JPG"})
     * @param options              download options; never {@code null}
     * @param backendSupportsDelete whether the backend advertises
     *                             {@code supportsDeleteAfterDownload}
     * @param downloadStep         actual download implementation
     * @param validator            validator callback; ignored when
     *                             {@link DownloadOptions#validateAfterDownload} is {@code false},
     *                             may be {@code null} in that case
     * @param deleteStep           delete implementation; may be {@code null} when deletion is
     *                             not requested or not supported
     * @return the terminal {@link SafeDownloadResult}
     */
    public SafeDownloadResult download(
            String fileName,
            DownloadOptions options,
            boolean backendSupportsDelete,
            DownloadStep downloadStep,
            DownloadValidator validator,
            DeleteStep deleteStep) {

        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(downloadStep, "downloadStep");

        // 1. Download
        byte[] bytes;
        try {
            bytes = downloadStep.download(fileName);
        } catch (IOException | RuntimeException e) {
            log.warn("Safe download: download step failed for {}: {}", fileName, e.getMessage());
            return new SafeDownloadResult(
                    SafeDownloadResult.Status.DOWNLOAD_FAILED, null, false,
                    "Download failed: " + e.getMessage());
        }
        if (bytes == null || bytes.length == 0) {
            return new SafeDownloadResult(
                    SafeDownloadResult.Status.DOWNLOAD_FAILED, null, false,
                    bytes == null ? "Download returned null bytes"
                            : "Download returned empty payload");
        }

        // 2. Validation (optional)
        if (options.validateAfterDownload()) {
            if (validator == null) {
                return new SafeDownloadResult(
                        SafeDownloadResult.Status.VALIDATION_FAILED, bytes, false,
                        "No validator provided");
            }
            boolean ok;
            try {
                ok = validator.validate(fileName, bytes);
            } catch (RuntimeException e) {
                log.warn("Safe download: validator threw for {}: {}", fileName, e.getMessage());
                return new SafeDownloadResult(
                        SafeDownloadResult.Status.VALIDATION_FAILED, bytes, false,
                        "Validation threw: " + e.getMessage());
            }
            if (!ok) {
                return new SafeDownloadResult(
                        SafeDownloadResult.Status.VALIDATION_FAILED, bytes, false,
                        "Validation reported failure");
            }
        }

        // 3. Delete (optional, gated by backend capability)
        if (!options.deleteAfterDownload()) {
            return new SafeDownloadResult(
                    SafeDownloadResult.Status.DOWNLOADED_KEPT, bytes, false, null);
        }
        if (!backendSupportsDelete) {
            log.info("Safe download: backend does not support delete-after-download; keeping {}",
                    fileName);
            return new SafeDownloadResult(
                    SafeDownloadResult.Status.DELETE_UNSUPPORTED, bytes, false, null);
        }
        if (deleteStep == null) {
            return new SafeDownloadResult(
                    SafeDownloadResult.Status.DELETE_UNSUPPORTED, bytes, false,
                    "No delete step provided");
        }
        boolean pairDeleted;
        try {
            pairDeleted = deleteStep.delete(fileName, options.deletionPolicy());
        } catch (IOException | RuntimeException e) {
            log.warn("Safe download: delete step failed for {}: {}", fileName, e.getMessage());
            return new SafeDownloadResult(
                    SafeDownloadResult.Status.DELETE_FAILED, bytes, false,
                    "Delete failed: " + e.getMessage());
        }
        return new SafeDownloadResult(
                SafeDownloadResult.Status.DOWNLOADED_DELETED, bytes, pairDeleted, null);
    }
}
