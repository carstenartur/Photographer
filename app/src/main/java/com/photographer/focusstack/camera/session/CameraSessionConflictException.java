package com.photographer.focusstack.camera.session;

import com.photographer.focusstack.camera.exception.CameraException;

import java.time.Duration;
import java.util.Objects;

/** Conflict response containing only redacted, bounded diagnostic fields. */
public final class CameraSessionConflictException extends CameraException {
    public static final String CODE = "CAMERA_SESSION_CONFLICT";
    private static final Duration MAXIMUM_RETRY_AFTER = Duration.ofSeconds(30);

    private final String ownerCorrelationId;
    private final CameraWorkflowType workflowType;
    private final Duration retryAfter;

    CameraSessionConflictException(
            String ownerCorrelationId,
            CameraWorkflowType workflowType,
            Duration retryAfter) {
        super("Camera is already controlled by another workflow");
        if (ownerCorrelationId == null
                || !ownerCorrelationId.matches("[A-Za-z0-9_-]{8,64}")) {
            throw new IllegalArgumentException("ownerCorrelationId is invalid");
        }
        this.ownerCorrelationId = ownerCorrelationId;
        this.workflowType = Objects.requireNonNull(workflowType, "workflowType");
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
        if (retryAfter.isNegative() || retryAfter.compareTo(MAXIMUM_RETRY_AFTER) > 0) {
            throw new IllegalArgumentException("retryAfter must be between zero and 30 seconds");
        }
    }

    public String code() {
        return CODE;
    }

    public String ownerCorrelationId() {
        return ownerCorrelationId;
    }

    public CameraWorkflowType workflowType() {
        return workflowType;
    }

    public boolean retryable() {
        return true;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
