package com.photographer.focusstack.camera.discovery;

import java.util.Objects;

/** Non-fatal discovery problem suitable for diagnostics without packet or credential data. */
public record CcapiDiscoveryWarning(String stage, String source, String message) {
    public CcapiDiscoveryWarning {
        stage = Objects.requireNonNull(stage, "stage").trim();
        source = source == null || source.isBlank() ? null : source.trim();
        message = Objects.requireNonNull(message, "message").trim();
    }
}
