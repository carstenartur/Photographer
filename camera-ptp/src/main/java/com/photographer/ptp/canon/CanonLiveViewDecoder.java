package com.photographer.ptp.canon;

import com.photographer.ptp.core.PtpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decodes Canon EOS LiveView data to extract JPEG frames.
 * Canon LiveView data contains a header followed by a JPEG image.
 */
public class CanonLiveViewDecoder {
    private static final Logger log = LoggerFactory.getLogger(CanonLiveViewDecoder.class);

    /** JPEG Start of Image marker */
    private static final byte[] JPEG_SOI = { (byte) 0xFF, (byte) 0xD8 };
    /** JPEG End of Image marker */
    private static final byte[] JPEG_EOI = { (byte) 0xFF, (byte) 0xD9 };

    private CanonLiveViewDecoder() {}

    /**
     * Extracts the JPEG image data from Canon LiveView raw data.
     * The LiveView data contains a variable-length header before the JPEG data.
     *
     * @param rawData The raw LiveView data from the camera
     * @return The JPEG image data
     * @throws PtpException if no JPEG data is found
     */
    public static byte[] extractJpeg(byte[] rawData) {
        if (rawData == null || rawData.length < 4) {
            throw new PtpException("LiveView data is null or too short");
        }

        // Find JPEG SOI marker
        int jpegStart = findMarker(rawData, JPEG_SOI, 0);
        if (jpegStart < 0) {
            throw new PtpException("No JPEG SOI marker found in LiveView data");
        }

        // Find JPEG EOI marker (search from the end for efficiency)
        int jpegEnd = findMarkerFromEnd(rawData, JPEG_EOI);
        if (jpegEnd < jpegStart) {
            throw new PtpException("No JPEG EOI marker found in LiveView data");
        }

        int jpegLength = jpegEnd + JPEG_EOI.length - jpegStart;
        byte[] jpeg = new byte[jpegLength];
        System.arraycopy(rawData, jpegStart, jpeg, 0, jpegLength);

        log.debug("Extracted JPEG from LiveView: offset={}, size={}", jpegStart, jpegLength);
        return jpeg;
    }

    private static int findMarker(byte[] data, byte[] marker, int fromIndex) {
        for (int i = fromIndex; i <= data.length - marker.length; i++) {
            if (data[i] == marker[0] && data[i + 1] == marker[1]) {
                return i;
            }
        }
        return -1;
    }

    private static int findMarkerFromEnd(byte[] data, byte[] marker) {
        for (int i = data.length - marker.length; i >= 0; i--) {
            if (data[i] == marker[0] && data[i + 1] == marker[1]) {
                return i;
            }
        }
        return -1;
    }
}
