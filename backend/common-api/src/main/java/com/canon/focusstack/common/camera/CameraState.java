package com.canon.focusstack.common.camera;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CameraState(
        @JsonProperty("connected") boolean connected,
        @JsonProperty("batteryLevel") int batteryLevel,
        @JsonProperty("storageAvailable") long storageAvailable,
        @JsonProperty("currentFocusPosition") int currentFocusPosition,
        @JsonProperty("shutterSpeed") String shutterSpeed,
        @JsonProperty("aperture") String aperture,
        @JsonProperty("iso") String iso,
        @JsonProperty("liveViewActive") boolean liveViewActive
) {
}
