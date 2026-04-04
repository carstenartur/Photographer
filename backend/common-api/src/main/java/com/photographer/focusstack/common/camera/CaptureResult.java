package com.photographer.focusstack.common.camera;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record CaptureResult(
        @JsonProperty("imageId") String imageId,
        @JsonProperty("fileName") String fileName,
        @JsonProperty("filePath") String filePath,
        @JsonProperty("capturedAt") Instant capturedAt,
        @JsonProperty("focusPosition") int focusPosition,
        @JsonProperty("fileSize") long fileSize,
        /**
         * Raw image bytes for in-process analysis. May be {@code null} if the backend
         * does not provide image data directly (e.g. the CCAPI backend stores images on
         * the camera card and requires a separate download step).
         */
        @JsonIgnore byte[] imageData
) {
    /** Convenience factory that leaves {@code imageData} null (backward-compatible). */
    public static CaptureResult of(String imageId, String fileName, String filePath,
                                   Instant capturedAt, int focusPosition, long fileSize) {
        return new CaptureResult(imageId, fileName, filePath, capturedAt, focusPosition, fileSize, null);
    }
}
