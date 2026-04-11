package com.photographer.ptp.session;

/**
 * Represents an image/object stored on the camera.
 */
public record StorageEntry(
    int objectHandle,
    String filename,
    int objectFormat,
    long compressedSize,
    int imageWidth,
    int imageHeight,
    String captureDate
) {}
