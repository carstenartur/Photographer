package com.photographer.focusstack.common.camera;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record LiveViewFrame(
        @JsonProperty("data") byte[] data,
        @JsonProperty("width") int width,
        @JsonProperty("height") int height,
        @JsonProperty("timestamp") Instant timestamp,
        @JsonProperty("contentType") String contentType
) {
}
