package com.photographer.focusstack.common.model;

/** Status for an astrophotography capture session. */
public enum AstroSessionStatus {
    CREATED,
    PREPARING,
    FOCUS_SCANNING,
    CAPTURING,
    ANALYZING,
    STACKING,
    COMPLETED,
    FAILED,
    ABORTED
}
