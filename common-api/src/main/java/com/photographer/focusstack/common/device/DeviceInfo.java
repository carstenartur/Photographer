package com.photographer.focusstack.common.device;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Transport-independent identity of a physical camera.
 *
 * <p>Designed to be stable across reconnects and across different transports
 * (CCAPI/WLAN today, USB/PTP tomorrow). The {@link #cameraId()} is the
 * canonical key used by the application; it is typically derived from
 * {@code model + serialNumber} so the same body keeps the same id whether it
 * is reached via WLAN or USB.
 *
 * <p>The Canon CCAPI {@code /deviceinformation} endpoint supplies
 * {@code productname}, {@code serialnumber} and {@code firmwareversion}; the
 * UPnP device description additionally exposes the access URL.
 */
public record DeviceInfo(
        @JsonProperty("cameraId") String cameraId,
        @JsonProperty("model") String model,
        @JsonProperty("serialNumber") String serialNumber,
        @JsonProperty("firmwareVersion") String firmwareVersion,
        @JsonProperty("manufacturer") String manufacturer,
        @JsonProperty("displayName") String displayName,
        @JsonProperty("lastTransport") String lastTransport,
        @JsonProperty("accessUrl") String accessUrl
) {

    /** Convenience factory when serial/firmware are not yet known. */
    public static DeviceInfo minimal(String cameraId, String model, String transport) {
        return new DeviceInfo(cameraId, model, null, null, null, model, transport, null);
    }
}
