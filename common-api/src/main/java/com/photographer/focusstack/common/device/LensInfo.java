package com.photographer.focusstack.common.device;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Vendor-neutral lens information snapshot.
 *
 * <p>Populated from CCAPI {@code /devicestatus/lens} or the corresponding
 * PTP/USB property when available. Fields are nullable / {@code -1} for
 * unknown values.
 */
public record LensInfo(
        @JsonProperty("name") String name,
        @JsonProperty("focalLengthMm") double focalLengthMm,
        @JsonProperty("minFocalLengthMm") double minFocalLengthMm,
        @JsonProperty("maxFocalLengthMm") double maxFocalLengthMm,
        @JsonProperty("hasImageStabilizer") Boolean hasImageStabilizer,
        @JsonProperty("attached") boolean attached
) {
    public static LensInfo none() {
        return new LensInfo(null, -1, -1, -1, null, false);
    }
}
