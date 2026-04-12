package com.photographer.focusstack.video;

import java.util.List;

/**
 * Interface for encoding a sequence of images into a video file.
 * Implementations must not use external command-line tools or process calls.
 * Concrete library dependencies are encapsulated in adapter classes.
 */
public interface VideoEncoder {

    /**
     * Encodes a sequence of images into a video.
     *
     * @param frames     ordered list of image byte arrays
     * @param fps        frames per second
     * @param width      video width in pixels
     * @param height     video height in pixels
     * @return the encoded video bytes
     */
    byte[] encode(List<byte[]> frames, int fps, int width, int height);

    /**
     * Returns the output format (e.g. "mp4", "avi", "mjpeg").
     */
    String format();

    /**
     * Returns the name of this encoder implementation.
     */
    String name();
}
