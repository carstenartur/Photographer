package com.photographer.focusstack.video;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Mock video encoder for testing. Returns a placeholder byte array
 * with metadata about the encoding parameters.
 */
public class MockVideoEncoder implements VideoEncoder {

    private static final Logger log = LoggerFactory.getLogger(MockVideoEncoder.class);

    @Override
    public byte[] encode(List<byte[]> frames, int fps, int width, int height) {
        if (frames == null || frames.isEmpty()) {
            return new byte[0];
        }
        log.debug("MockVideoEncoder: encoding {} frames at {}fps ({}x{})",
                frames.size(), fps, width, height);
        // Return a placeholder with metadata
        String placeholder = String.format("MOCK_VIDEO:frames=%d,fps=%d,size=%dx%d",
                frames.size(), fps, width, height);
        return placeholder.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String format() {
        return "mock";
    }

    @Override
    public String name() {
        return "mock-video";
    }
}
