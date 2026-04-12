package com.photographer.focusstack.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.Map;

/** A single captured frame with metadata. */
public record CaptureFrame(
        @JsonProperty("frameId") @NotBlank String frameId,
        @JsonProperty("sessionId") @NotBlank String sessionId,
        @JsonProperty("fileName") String fileName,
        @JsonProperty("filePath") String filePath,
        @JsonProperty("capturedAt") Instant capturedAt,
        @JsonProperty("exposureValue") double exposureValue,
        @JsonProperty("sequenceIndex") int sequenceIndex,
        @JsonProperty("metadata") Map<String, String> metadata
) {
}
