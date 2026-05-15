package com.photographer.focusstack.common.device;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Snapshot of camera battery state.
 *
 * <p>Vendor-neutral wrapper around CCAPI {@code /devicestatus/battery} and the
 * equivalent PTP property. Use {@code -1} for unknown numeric fields and
 * {@code "unknown"} for unknown enum strings.
 */
public record BatteryInfo(
        @JsonProperty("level") String level,
        @JsonProperty("kind") String kind,
        @JsonProperty("quality") String quality,
        @JsonProperty("percent") int percent
) {
    public static BatteryInfo unknown() {
        return new BatteryInfo("unknown", "unknown", "unknown", -1);
    }

    public boolean isCritical() {
        return percent >= 0 && percent <= 5
                || "low".equalsIgnoreCase(level)
                || "veryLow".equalsIgnoreCase(level);
    }
}
