package com.photographer.focusstack.common.camera;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record CaptureResult(
        @JsonProperty("imageId") String imageId,
        @JsonProperty("fileName") String fileName,
        @JsonProperty("filePath") String filePath,
        @JsonProperty("capturedAt") Instant capturedAt,
        @JsonProperty("focusPosition") int focusPosition,
        @JsonProperty("fileSize") long fileSize
) {
}
