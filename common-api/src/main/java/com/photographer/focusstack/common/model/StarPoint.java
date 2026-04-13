package com.photographer.focusstack.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A detected star position within an astrophotography frame. */
public record StarPoint(
        @JsonProperty("x") double x,
        @JsonProperty("y") double y,
        @JsonProperty("brightness") double brightness,
        @JsonProperty("radius") Double radius
) {
}
