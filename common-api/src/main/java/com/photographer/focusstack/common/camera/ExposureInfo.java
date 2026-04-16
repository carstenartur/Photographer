package com.photographer.focusstack.common.camera;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Snapshot of the current camera exposure settings.
 *
 * <p>Values are provided as human-readable strings (e.g. {@code "f/5.6"},
 * {@code "1/250"}, {@code "400"}) <em>and</em> as raw integer codes
 * (the vendor-specific property values) so that callers can both display
 * and re-apply them.
 *
 * @param aperture       human-readable aperture (e.g. "f/5.6")
 * @param apertureRaw    raw vendor property value (0 if unknown)
 * @param shutterSpeed   human-readable shutter speed (e.g. "1/250")
 * @param shutterSpeedRaw raw vendor property value (0 if unknown)
 * @param iso            human-readable ISO (e.g. "400" or "Auto")
 * @param isoRaw         raw vendor property value (0 if unknown)
 * @param exposureCompensation human-readable EV (e.g. "+1.0" or "0")
 * @param exposureCompensationRaw raw vendor property value (0 if unknown)
 */
public record ExposureInfo(
        @JsonProperty("aperture") String aperture,
        @JsonProperty("apertureRaw") int apertureRaw,
        @JsonProperty("shutterSpeed") String shutterSpeed,
        @JsonProperty("shutterSpeedRaw") int shutterSpeedRaw,
        @JsonProperty("iso") String iso,
        @JsonProperty("isoRaw") int isoRaw,
        @JsonProperty("exposureCompensation") String exposureCompensation,
        @JsonProperty("exposureCompensationRaw") int exposureCompensationRaw
) {
    /** Convenience factory for backends that only have string values. */
    public static ExposureInfo ofStrings(String aperture, String shutterSpeed, String iso) {
        return new ExposureInfo(aperture, 0, shutterSpeed, 0, iso, 0, "0", 0);
    }
}
