package com.photographer.focusstack.video;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MockVideoEncoderTest {

    private final MockVideoEncoder encoder = new MockVideoEncoder();

    @Test
    void encode_returnsNonEmpty() {
        byte[] frame1 = {1, 2, 3};
        byte[] frame2 = {4, 5, 6};

        byte[] result = encoder.encode(List.of(frame1, frame2), 24, 1920, 1080);

        assertNotNull(result);
        assertTrue(result.length > 0);
        String content = new String(result);
        assertTrue(content.contains("frames=2"));
        assertTrue(content.contains("fps=24"));
    }

    @Test
    void encode_emptyFrames_returnsEmpty() {
        byte[] result = encoder.encode(List.of(), 24, 1920, 1080);

        assertEquals(0, result.length);
    }

    @Test
    void encode_nullFrames_returnsEmpty() {
        byte[] result = encoder.encode(null, 24, 1920, 1080);

        assertEquals(0, result.length);
    }

    @Test
    void format_returnsMock() {
        assertEquals("mock", encoder.format());
    }

    @Test
    void name_returnsMockVideo() {
        assertEquals("mock-video", encoder.name());
    }
}
