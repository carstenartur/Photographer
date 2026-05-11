package com.photographer.focusstack.common.capability;

/**
 * Vendor- and transport-neutral camera capability identifiers.
 *
 * <p>Used by the UI and workflow layer to decide which buttons / actions to
 * expose without ever inspecting the underlying transport (CCAPI key, PTP
 * opcode, etc.). A backend reports a {@link CapabilitySet} and the UI binds
 * widgets to capabilities, not to API keys.
 *
 * <p>Adding a new capability is a pure additive change — existing backends
 * simply do not list it.
 */
public enum Capability {

    // --- Identity & status ----------------------------------------------------
    DEVICE_INFO,
    STORAGE_INFO,
    BATTERY_INFO,
    LENS_INFO,
    EVENT_MONITORING,

    // --- Imaging --------------------------------------------------------------
    LIVE_VIEW,
    CAPTURE_STILL,
    CAPTURE_BURST,
    VIDEO_RECORD,

    // --- Control --------------------------------------------------------------
    FOCUS_DRIVE,
    ZOOM_CONTROL,
    SHOOTING_MODE_READ,
    SHOOTING_MODE_WRITE,
    EXPOSURE_READ,
    EXPOSURE_WRITE,

    // --- Media ----------------------------------------------------------------
    LIST_MEDIA,
    DOWNLOAD_MEDIA,
    DELETE_MEDIA,
    THUMBNAIL,
    PREVIEW,

    // --- Workflows ------------------------------------------------------------
    AUTO_DELETE_AFTER_IMPORT,
    BULK_DELETE
}
