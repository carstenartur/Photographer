package com.photographer.focusstack.common.media;

/** Tracks whether a media item has been (or should be) deleted on the camera. */
public enum DeleteState {
    /** Still present on the camera. */
    PRESENT,
    /** Deletion requested but not yet confirmed by the camera. */
    DELETE_PENDING,
    /** Camera confirmed deletion. */
    DELETED,
    /** Camera refused deletion (e.g. file protected). */
    DELETE_FAILED,
    /** Policy explicitly forbids deleting this item. */
    KEEP
}
