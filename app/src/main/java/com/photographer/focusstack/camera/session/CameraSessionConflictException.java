package com.photographer.focusstack.camera.session;

import com.photographer.focusstack.camera.exception.CameraException;

import java.time.Duration;
import java.util.Objects;

/** Conflict response containing only redacted, bounded diagnostic fields. */
public final class CameraSessionConflictException extends CameraException {
    public static final String CODE = "CAMERA_SESSION_CONFLICT";

    private final String ownerCorrelationId;
    private final CameraWorkflowType workflowType;
    private final Duration retryAfter;

    CameraSessionConflictException(
            String ownerCorrelationId,
            CameraWorkflowType workflowType,
            Duration retryAfter) {
        super("Camera is already controlled by another workflow");
        this.ownerCorrelationId = Objects.requireNonNull(
                ownerCorrelationId, "ownerCorrelationId");
        this.workflowType = Objects.requireNonNull(workflowType, "workflowType");
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
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
