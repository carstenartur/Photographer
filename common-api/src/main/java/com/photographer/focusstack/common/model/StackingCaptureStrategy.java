package com.photographer.focusstack.common.model;

/** Explicit capture strategy requested for a stacking job. */
public enum StackingCaptureStrategy {
    /** Preserve the existing application-controlled focus-drive loop. */
    MANUAL,
    /** Require a complete camera-owned native focus-bracketing capability. */
    NATIVE,
    /** Prefer native execution and fall back to manual when capability is incomplete. */
    AUTO
}
