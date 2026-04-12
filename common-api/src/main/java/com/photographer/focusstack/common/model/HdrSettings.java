package com.photographer.focusstack.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Configuration for an HDR Timelapse session. */
public record HdrSettings(
        @JsonProperty("intervalSeconds") int intervalSeconds,
        @JsonProperty("bracketCount") int bracketCount,
        @JsonProperty("evStep") double evStep,
        @JsonProperty("totalIntervals") int totalIntervals,
        @JsonProperty("saveRaw") boolean saveRaw,
        @JsonProperty("tonemapEnabled") boolean tonemapEnabled
) {
    public static HdrSettings defaults() {
        return new HdrSettings(10, 3, 1.0, 100, false, true);
    }
}
