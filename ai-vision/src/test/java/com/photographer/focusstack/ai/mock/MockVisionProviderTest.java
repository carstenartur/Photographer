package com.photographer.focusstack.ai.mock;

import com.photographer.focusstack.ai.ProviderCapabilities;
import com.photographer.focusstack.ai.VisionContext;
import com.photographer.focusstack.ai.VisionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MockVisionProviderTest {

    private static final byte[] DUMMY_IMAGE = new byte[]{1, 2, 3};

    @Test
    void returnsScheduledResult() {
        VisionResult expected = new VisionResult("mock", "mock-model", "classification",
                "Kohlmeise", List.of("Blaumeise"), 0.9, "Kleine Meise", List.of(), "{}", 50L, true, null);
        MockVisionProvider provider = new MockVisionProvider(expected);
        VisionResult result = provider.classifyImage(DUMMY_IMAGE, VisionContext.wildlife());
        assertEquals("Kohlmeise", result.primaryLabel());
        assertTrue(result.successful());
    }

    @Test
    void cyclesThroughMultipleResults() {
        VisionResult r1 = new VisionResult("mock", "m", "classification", "Kohlmeise",
                List.of(), 0.9, null, List.of(), null, 10L, true, null);
        VisionResult r2 = new VisionResult("mock", "m", "classification", "Blaumeise",
                List.of(), 0.8, null, List.of(), null, 10L, true, null);
        MockVisionProvider provider = new MockVisionProvider(List.of(r1, r2), 0);

        assertEquals("Kohlmeise", provider.classifyImage(DUMMY_IMAGE, VisionContext.wildlife()).primaryLabel());
        assertEquals("Blaumeise", provider.classifyImage(DUMMY_IMAGE, VisionContext.wildlife()).primaryLabel());
        assertEquals("Kohlmeise", provider.classifyImage(DUMMY_IMAGE, VisionContext.wildlife()).primaryLabel());
    }

    @Test
    void isAvailableWhenResultsConfigured() {
        MockVisionProvider provider = new MockVisionProvider(
                VisionResult.error("mock", "m", "err"));
        assertTrue(provider.isAvailable());
    }

    @Test
    void isNotAvailableWhenNoResults() {
        MockVisionProvider provider = new MockVisionProvider(List.of(), 0);
        assertFalse(provider.isAvailable());
    }

    @Test
    void trackCallCount() {
        MockVisionProvider provider = new MockVisionProvider(
                VisionResult.error("mock", "m", "err"));
        provider.classifyImage(DUMMY_IMAGE, VisionContext.wildlife());
        provider.describeImage(DUMMY_IMAGE, VisionContext.wildlife());
        assertEquals(2, provider.getCallCount());
        provider.reset();
        assertEquals(0, provider.getCallCount());
    }

    @Test
    void returnsErrorOnEmptyResults() {
        MockVisionProvider provider = new MockVisionProvider(List.of(), 0);
        VisionResult result = provider.classifyImage(DUMMY_IMAGE, VisionContext.wildlife());
        assertFalse(result.successful());
    }

    @Test
    void detectObjects_returnsMockBoundingBox() {
        VisionResult r = new VisionResult("mock", "m", "classification",
                "Honigbiene", List.of(), 0.85, null, List.of(), null, 5L, true, null);
        MockVisionProvider provider = new MockVisionProvider(r);
        VisionResult detected = provider.detectObjects(DUMMY_IMAGE, VisionContext.wildlife());
        assertEquals("object_detection", detected.taskType());
        assertEquals("Honigbiene", detected.primaryLabel());
        assertFalse(detected.boundingBoxes().isEmpty());
    }

    @Test
    void capabilities_objectDetectionIsTrue() {
        MockVisionProvider provider = new MockVisionProvider(
                VisionResult.error("mock", "m", "x"));
        assertTrue(provider.capabilities().objectDetection());
        assertTrue(provider.capabilities().classification());
    }
}
