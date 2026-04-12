package com.photographer.focusstack.hdr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mock tonemapper for testing. Returns the input unchanged.
 */
public class MockTonemapper implements Tonemapper {

    private static final Logger log = LoggerFactory.getLogger(MockTonemapper.class);

    @Override
    public byte[] tonemap(byte[] hdrImage, double gamma) {
        if (hdrImage == null) {
            return new byte[0];
        }
        log.debug("MockTonemapper: tonemapping with gamma={}", gamma);
        // Return the input unchanged for testing
        byte[] result = new byte[hdrImage.length];
        System.arraycopy(hdrImage, 0, result, 0, hdrImage.length);
        return result;
    }

    @Override
    public String name() {
        return "mock-tonemap";
    }
}
