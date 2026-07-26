package com.photographer.focusstack.ai;

import com.photographer.focusstack.ai.mock.MockVisionProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwoStageVisionProviderTest {

    private static final byte[] IMAGE = new byte[]{1, 2, 3};

    @Test
    void successfulNoneSkipsExpensiveClassifier() {
        VisionResult noneResult = success("mock", "none", 0.1);
        MockVisionProvider preFilter = new MockVisionProvider(noneResult);
        MockVisionProvider classifier = new MockVisionProvider(
                success("gemini", "Kohlmeise", 0.95));

        VisionResult result = new TwoStageVisionProvider(
                preFilter, classifier).classifyImage(
                IMAGE, VisionContext.wildlife());

        assertEquals(0, classifier.getCallCount());
        assertTrue(result.successful());
        assertEquals("none", result.primaryLabel());
        assertEquals(VisionFailureKind.NONE, result.failureKind());
    }

    @Test
    void typedPreFilterFailureSkipsClassifierAndKeepsCategory() {
        VisionResult timeout = VisionResult.failure(
                "local", "model", "classification",
                VisionFailureKind.TIMEOUT, true, 3,
                "Local pre-filter timed out", 25L);
        MockVisionProvider classifier = new MockVisionProvider(
                success("gemini", "Kohlmeise", 0.95));
        TwoStageVisionProvider provider = new TwoStageVisionProvider(
                new MockVisionProvider(timeout), classifier);

        VisionResult classification = provider.classifyImage(
                IMAGE, VisionContext.wildlife());
        VisionResult description = provider.describeImage(
                IMAGE, VisionContext.wildlife());
        VisionResult detection = provider.detectObjects(
                IMAGE, VisionContext.wildlife());

        assertEquals(0, classifier.getCallCount());
        for (VisionResult result : List.of(
                classification, description, detection)) {
            assertFalse(result.successful());
            assertEquals("unclassified", result.primaryLabel());
            assertEquals(VisionFailureKind.TIMEOUT, result.failureKind());
            assertTrue(result.retryable());
            assertEquals(3, result.retryAfterSeconds());
            assertNull(result.rawResponse());
        }
        assertEquals("classification", classification.taskType());
        assertEquals("description", description.taskType());
        assertEquals("object_detection", detection.taskType());
    }

    @Test
    void positivePreFilterInvokesClassifier() {
        MockVisionProvider preFilter = new MockVisionProvider(
                success("mock", "Vogel", 0.7));
        MockVisionProvider classifier = new MockVisionProvider(
                success("gemini", "Kohlmeise", 0.95));

        VisionResult result = new TwoStageVisionProvider(
                preFilter, classifier).classifyImage(
                IMAGE, VisionContext.wildlife());

        assertEquals(1, classifier.getCallCount());
        assertEquals("Kohlmeise", result.primaryLabel());
        assertEquals(0.95, result.confidence(), 0.001);
    }

    @Test
    void classifierResultIsReturnedNotPreFilterResult() {
        VisionResult pre = success("mock", "Tier", 0.6);
        VisionResult classified = new VisionResult(
                "gemini", "g", "classification", "Blaumeise",
                List.of(), 0.88, "Blue tit", List.of(), null,
                80L, true, null);

        VisionResult result = new TwoStageVisionProvider(
                new MockVisionProvider(pre),
                new MockVisionProvider(classified))
                .classifyImage(IMAGE, VisionContext.wildlife());

        assertEquals("Blaumeise", result.primaryLabel());
        assertEquals("gemini", result.provider());
    }

    @Test
    void lowConfidencePreFilterSkipsClassifier() {
        MockVisionProvider preFilter = new MockVisionProvider(
                success("mock", "Vogel", 0.2));
        MockVisionProvider classifier = new MockVisionProvider(
                success("gemini", "Kohlmeise", 0.95));

        new TwoStageVisionProvider(preFilter, classifier)
                .classifyImage(IMAGE, VisionContext.wildlife());

        assertEquals(0, classifier.getCallCount());
    }

    @Test
    void nameIncludesBothProviderNames() {
        TwoStageVisionProvider provider = new TwoStageVisionProvider(
                new MockVisionProvider(VisionResult.error("mock", "m", "x")),
                new MockVisionProvider(VisionResult.error("mock", "m", "x")));

        assertTrue(provider.name().contains("mock"));
    }

    @Test
    void isAvailableTrueWhenBothAvailable() {
        VisionResult result = success("mock", "Vogel", 0.8);
        TwoStageVisionProvider provider = new TwoStageVisionProvider(
                new MockVisionProvider(result),
                new MockVisionProvider(result));

        assertTrue(provider.isAvailable());
    }

    @Test
    void isAvailableFalseWhenPreFilterUnavailable() {
        TwoStageVisionProvider provider = new TwoStageVisionProvider(
                new MockVisionProvider(List.of(), 0),
                new MockVisionProvider(VisionResult.error("mock", "m", "x")));

        assertFalse(provider.isAvailable());
    }

    private static VisionResult success(
            String provider,
            String label,
            double confidence) {
        return new VisionResult(
                provider, "model", "classification", label,
                List.of(), confidence, null, List.of(), null,
                0L, true, null);
    }
}
