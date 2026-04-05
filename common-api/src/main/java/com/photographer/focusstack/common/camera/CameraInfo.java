package com.photographer.focusstack.common.camera;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CameraInfo(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("model") String model,
        @JsonProperty("backendType") String backendType,
        @JsonProperty("host") String host,
        @JsonProperty("firmwareVersion") String firmwareVersion
) {
}
