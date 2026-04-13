package com.photographer.focusstack.cr3.bmff;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Parsed CNCV (Canon Compressor Version) box.
 * The payload is a 30-byte ASCII version string like "CanonCR3_001/00.09.00/00.00.00".
 */
public record CncvBox(String versionString) {

    public static CncvBox parse(ImageInputStream input, IsoBox box) throws IOException {
        if (!"CNCV".equals(box.type())) {
            throw new IllegalArgumentException("Expected CNCV box, got: " + box.type());
        }
        long dataSize = box.dataSize();
        if (dataSize < 0) {
            throw new IllegalArgumentException(
                    "CNCV box has unknown size; cannot parse version string");
        }
        input.seek(box.dataOffset());
        long len = Math.min(dataSize, 64);
        byte[] bytes = new byte[(int) len];
        input.readFully(bytes);
        int end = bytes.length;
        while (end > 0 && bytes[end - 1] == 0) end--;
        return new CncvBox(new String(bytes, 0, end, StandardCharsets.US_ASCII));
    }

    /** Returns true if this is a CR3 file (not HEIF). */
    public boolean isCraw() {
        return versionString.contains("CR3");
    }

    /** Returns true if this is a roll/burst mode file. */
    public boolean isRollBurst() {
        return versionString.contains("/01.");
    }
}
