package com.photographer.focusstack.hdr;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MockHdrProcessorTest {

    private final MockHdrProcessor processor = new MockHdrProcessor();

    @Test
    void merge_returnsNonEmpty() {
        byte[] img1 = {1, 2, 3};
        byte[] img2 = {4, 5, 6};
        byte[] img3 = {7, 8, 9};

        byte[] result = processor.merge(
                List.of(img1, img2, img3),
                List.of(-1.0, 0.0, 1.0));

        assertNotNull(result);
        assertTrue(result.length > 0);
    }

    @Test
    void merge_emptyInput_returnsEmpty() {
        byte[] result = processor.merge(List.of(), List.of());

        assertEquals(0, result.length);
    }

    @Test
    void merge_nullInput_returnsEmpty() {
        byte[] result = processor.merge(null, null);

        assertEquals(0, result.length);
    }

    @Test
    void name_returnsMockHdr() {
        assertEquals("mock-hdr", processor.name());
    }
}
