package com.photographer.focusstack.storage.download;

/**
 * Policy for handling RAW + JPEG file pairs when deleting from the camera
 * after a successful download.
 *
 * <p>Cameras like the Canon R-series can record RAW and JPEG simultaneously.
 * When the user has only downloaded one of the two files, the safe default is
 * to keep the other file on the camera so that the original RAW (which often
 * captures the moment irreplaceably) is not lost.</p>
 */
public enum RawJpegDeletionPolicy {

    /**
     * Delete <em>only</em> the file that was actually downloaded. If a paired
     * file (RAW or JPEG) exists on the camera, leave it in place. This is the
     * safe default and prevents accidental loss of the unloaded RAW companion.
     */
    DELETE_DOWNLOADED_ONLY,

    /**
     * Delete both files of the RAW + JPEG pair, even when only one has been
     * downloaded. Use only when the user explicitly opts in (e.g. a "save
     * camera storage" toggle).
     */
    DELETE_BOTH_PAIR,

    /**
     * Delete the JPEG sibling but always keep the RAW on the camera. Useful
     * when the user wants to preview JPEGs on the desktop and then re-import
     * the RAW later for full processing.
     */
    KEEP_RAW;

    /** Returns the safe default policy. */
    public static RawJpegDeletionPolicy safeDefault() {
        return DELETE_DOWNLOADED_ONLY;
    }
}
