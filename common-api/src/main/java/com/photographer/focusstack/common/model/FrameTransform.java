package com.photographer.focusstack.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Geometric transform applied to align a frame to the reference frame. */
public record FrameTransform(
        @JsonProperty("rotationDeg") double rotationDeg,
        @JsonProperty("translationX") double translationX,
        @JsonProperty("translationY") double translationY,
        @JsonProperty("scale") Double scale
) {
    public static FrameTransform identity() {
        return new FrameTransform(0.0, 0.0, 0.0, null);
    }
}
