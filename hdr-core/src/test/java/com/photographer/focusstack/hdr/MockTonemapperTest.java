package com.photographer.focusstack.hdr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MockTonemapperTest {

    private final MockTonemapper tonemapper = new MockTonemapper();

    @Test
    void tonemap_returnsNonEmpty() {
        byte[] hdr = {1, 2, 3, 4};

        byte[] result = tonemapper.tonemap(hdr, 2.2);

        assertNotNull(result);
        assertArrayEquals(hdr, result);
    }

    @Test
    void tonemap_nullInput_returnsEmpty() {
        byte[] result = tonemapper.tonemap(null, 2.2);

        assertEquals(0, result.length);
    }

    @Test
    void name_returnsMockTonemap() {
        assertEquals("mock-tonemap", tonemapper.name());
    }
}
