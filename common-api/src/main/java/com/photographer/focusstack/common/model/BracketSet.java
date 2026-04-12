package com.photographer.focusstack.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;

/** A group of bracketed exposures captured at one interval. */
public record BracketSet(
        @JsonProperty("bracketSetId") @NotBlank String bracketSetId,
        @JsonProperty("sessionId") @NotBlank String sessionId,
        @JsonProperty("capturedAt") Instant capturedAt,
        @JsonProperty("frames") List<CaptureFrame> frames,
        @JsonProperty("hdrResultPath") String hdrResultPath
) {
}
