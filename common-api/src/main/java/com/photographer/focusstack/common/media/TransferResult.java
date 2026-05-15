package com.photographer.focusstack.common.media;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.file.Path;

/**
 * Outcome of a single media transfer.
 *
 * <p>{@link #verified()} is {@code true} only when the local bytes have been
 * checked against the camera-reported size (and, when available, a checksum).
 * The auto-delete workflow refuses to delete unless this flag is set.
 *
 * <p>{@link #localPath()} is {@code null} on failure and may also be
 * {@code null} for backends that stream bytes to a caller-supplied sink
 * without materialising a file path themselves; callers that need the
 * on-disk path should rely on the {@code Path} they handed to
 * {@code downloadMedia(...)}.
 */
public record TransferResult(
        @JsonProperty("item") MediaItem item,
        @JsonProperty("localPath") Path localPath,
        @JsonProperty("bytesTransferred") long bytesTransferred,
        @JsonProperty("verified") boolean verified,
        @JsonProperty("success") boolean success,
        @JsonProperty("errorMessage") String errorMessage
) {

    public static TransferResult ok(MediaItem item, Path localPath, long bytes, boolean verified) {
        return new TransferResult(item, localPath, bytes, verified, true, null);
    }

    public static TransferResult failed(MediaItem item, String error) {
        return new TransferResult(item, null, 0, false, false, error);
    }
}
