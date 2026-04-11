package com.photographer.ptp.canon;

import com.photographer.ptp.core.PtpException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CanonLiveViewDecoderTest {

    @Test
    void extractJpegFromLiveViewData() {
        // Build mock LiveView data: header + JPEG
        byte[] data = new byte[100];
        // 32 bytes header (zeros)
        // Then JPEG SOI
        data[32] = (byte) 0xFF;
        data[33] = (byte) 0xD8;
        // JPEG content
        data[34] = (byte) 0xFF;
        data[35] = (byte) 0xE0;
        // JPEG EOI at end
        data[98] = (byte) 0xFF;
        data[99] = (byte) 0xD9;

        byte[] jpeg = CanonLiveViewDecoder.extractJpeg(data);

        assertNotNull(jpeg);
        assertTrue(jpeg.length > 0);
        // Check JPEG markers
        assertEquals((byte) 0xFF, jpeg[0]);
        assertEquals((byte) 0xD8, jpeg[1]);
        assertEquals((byte) 0xFF, jpeg[jpeg.length - 2]);
        assertEquals((byte) 0xD9, jpeg[jpeg.length - 1]);
    }

    @Test
    void extractJpegFromNullThrows() {
        assertThrows(PtpException.class, () -> CanonLiveViewDecoder.extractJpeg(null));
    }

    @Test
    void extractJpegFromEmptyThrows() {
        assertThrows(PtpException.class, () -> CanonLiveViewDecoder.extractJpeg(new byte[2]));
    }

    @Test
    void extractJpegNoSoiThrows() {
        byte[] data = new byte[50];
        // No JPEG SOI marker
        data[48] = (byte) 0xFF;
        data[49] = (byte) 0xD9; // only EOI
        assertThrows(PtpException.class, () -> CanonLiveViewDecoder.extractJpeg(data));
    }
}
