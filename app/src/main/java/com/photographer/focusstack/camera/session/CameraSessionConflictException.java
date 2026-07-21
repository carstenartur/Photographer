package com.photographer.focusstack.camera.session;

import com.photographer.focusstack.camera.exception.CameraException;

import java.time.Duration;

/** Conflict response containing only redacted, bounded diagnostic fields. */
public final class CameraSessionConflictException extends CameraException {
    public static final String CODE = "CAMERA_SESSION_CONFLICT";

    private final String ownerCorrelationId;
    private final String workflowType;
    private final Duration retryAfter;

    CameraSessionConflictException(
            String ownerCorrelationId,
            String workflowType,
            Duration retryAfter) {
        super("Camera is already controlled by another workflow");
        this.ownerCorrelationId = ownerCorrelationId;
        this.workflowType = workflowType;
        this.retryAfter = retryAfter;
    }

    public String code() {
        return CODE;
    }

    public String ownerCorrelationId() {
        return ownerCorrelationId;
    }

    public String workflowType() {
        return workflowType;
    }

    public boolean retryable() {
        return true;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
