package com.photographer.focusstack.common.media;

/** Local-side state of a media item in the ingest pipeline. */
public enum TransferState {
    /** Discovered on the camera but not yet downloaded. */
    DISCOVERED,
    /** Local download in progress. */
    DOWNLOADING,
    /** Bytes are on disk but not yet verified. */
    DOWNLOADED,
    /** Bytes verified (size and/or checksum match). */
    VERIFIED,
    /** Download attempt failed; safe to retry. */
    FAILED
}
