package com.photographer.ptp.session;

/**
 * Result of downloading an image from the camera.
 */
public record ImageDownload(
    String objectId,
    String fileName,
    byte[] data
) {
    public int size() {
        return data != null ? data.length : 0;
    }
}
