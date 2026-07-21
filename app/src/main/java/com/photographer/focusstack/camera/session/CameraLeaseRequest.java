package com.photographer.focusstack.camera.session;

import java.time.Duration;
import java.util.Objects;

/** Logical ownership request for one camera. */
public record CameraLeaseRequest(
        CameraLeaseKey cameraKey,
        String workflowType,
        String workflowId,
        String ownerToken,
        Duration requestedLifetime) {

    public CameraLeaseRequest {
        Objects.requireNonNull(cameraKey, "cameraKey");
        workflowType = requireText(workflowType, "workflowType");
        workflowId = requireText(workflowId, "workflowId");
        ownerToken = requireText(ownerToken, "ownerToken");
        Objects.requireNonNull(requestedLifetime, "requestedLifetime");
        if (requestedLifetime.isZero() || requestedLifetime.isNegative()) {
            throw new IllegalArgumentException("requestedLifetime must be positive");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > 160) {
            throw new IllegalArgumentException(name + " is too long");
        }
        return normalized;
    }
}
