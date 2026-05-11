package com.photographer.focusstack.common.media;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Vendor-neutral description of a single media item known to the application.
 *
 * <p>The same record is used regardless of whether the bytes were sourced
 * via Canon CCAPI, USB/PTP or a folder backend. Workflow code (import,
 * delete, preview) and the UI both bind to this model — never to backend
 * specifics.
 *
 * <p>Identity is the application-side {@link #id()} (typically a UUID or a
 * hash of {@code cameraId + sourcePath}); {@link #sourcePath()} is the
 * camera-side locator (e.g. {@code /storage1/100CANON/IMG_0001.JPG}).
 *
 * <p>All state fields default to safe values via {@link #newDiscovered}.
 */
public record MediaItem(
        @JsonProperty("id") String id,
        @JsonProperty("cameraId") String cameraId,
        @JsonProperty("storageName") String storageName,
        @JsonProperty("directoryName") String directoryName,
        @JsonProperty("filename") String filename,
        @JsonProperty("sourcePath") String sourcePath,
        @JsonProperty("sourceUrl") String sourceUrl,
        @JsonProperty("mediaType") MediaType mediaType,
        @JsonProperty("createdAt") Instant createdAt,
        @JsonProperty("size") long size,
        @JsonProperty("checksum") String checksum,
        @JsonProperty("transferState") TransferState transferState,
        @JsonProperty("deleteState") DeleteState deleteState,
        @JsonProperty("previewState") PreviewState previewState
) {

    public MediaItem {
        if (transferState == null) transferState = TransferState.DISCOVERED;
        if (deleteState   == null) deleteState   = DeleteState.PRESENT;
        if (previewState  == null) previewState  = PreviewState.UNAVAILABLE;
        if (mediaType     == null) mediaType     = MediaType.fromFilename(filename);
        if (size < -1) size = -1;
    }

    /** Convenience factory for newly discovered camera-side items. */
    public static MediaItem newDiscovered(String id, String cameraId, String storageName,
                                          String directoryName, String filename,
                                          String sourcePath, String sourceUrl, long size) {
        return new MediaItem(id, cameraId, storageName, directoryName, filename,
                sourcePath, sourceUrl, MediaType.fromFilename(filename),
                null, size, null,
                TransferState.DISCOVERED, DeleteState.PRESENT, PreviewState.UNAVAILABLE);
    }

    public MediaItem withTransferState(TransferState s)   { return new MediaItem(id, cameraId, storageName, directoryName, filename, sourcePath, sourceUrl, mediaType, createdAt, size, checksum, s, deleteState, previewState); }
    public MediaItem withDeleteState(DeleteState s)       { return new MediaItem(id, cameraId, storageName, directoryName, filename, sourcePath, sourceUrl, mediaType, createdAt, size, checksum, transferState, s, previewState); }
    public MediaItem withPreviewState(PreviewState s)     { return new MediaItem(id, cameraId, storageName, directoryName, filename, sourcePath, sourceUrl, mediaType, createdAt, size, checksum, transferState, deleteState, s); }
    public MediaItem withChecksum(String c)               { return new MediaItem(id, cameraId, storageName, directoryName, filename, sourcePath, sourceUrl, mediaType, createdAt, size, c, transferState, deleteState, previewState); }
}
