package com.canon.focusstack.common.camera;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Positive;

public record FocusCommand(
        @JsonProperty("direction") Direction direction,
        @JsonProperty("steps") @Positive int steps
) {

    public enum Direction {
        NEAR,
        FAR
    }
}
