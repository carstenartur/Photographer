package com.photographer.focusstack.hdr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Mock HDR processor for testing. Returns a placeholder image
 * derived from the first bracket image with a header marking it as HDR.
 */
public class MockHdrProcessor implements HdrProcessor {

    private static final Logger log = LoggerFactory.getLogger(MockHdrProcessor.class);

    @Override
    public byte[] merge(List<byte[]> bracketImages, List<Double> evOffsets) {
        if (bracketImages == null || bracketImages.isEmpty()) {
            return new byte[0];
        }
        log.debug("MockHdrProcessor: merging {} brackets", bracketImages.size());
        // Return the first image as the "merged" result for testing
        byte[] first = bracketImages.get(0);
        byte[] result = new byte[first.length];
        System.arraycopy(first, 0, result, 0, first.length);
        return result;
    }

    @Override
    public String name() {
        return "mock-hdr";
    }
}
