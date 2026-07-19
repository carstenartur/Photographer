package com.photographer.focusstack.camera.discovery;

import java.net.URI;
import java.util.Objects;

/** Canon camera identity and CCAPI access URL resolved from its UPnP description. */
public record CcapiDiscoveredCamera(
        URI descriptionUrl,
        URI accessUrl,
        String udn,
        String friendlyName,
        String modelName,
        String serialNumber,
        String nickname,
        boolean serviceConnected
) {
    public CcapiDiscoveredCamera {
        descriptionUrl = Objects.requireNonNull(descriptionUrl, "descriptionUrl");
        accessUrl = Objects.requireNonNull(accessUrl, "accessUrl");
        udn = normalize(udn);
        friendlyName = normalize(friendlyName);
        modelName = normalize(modelName);
        serialNumber = normalize(serialNumber);
        nickname = normalize(nickname);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
