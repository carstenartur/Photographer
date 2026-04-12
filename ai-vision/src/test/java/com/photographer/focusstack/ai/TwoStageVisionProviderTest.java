package com.photographer.focusstack.ai;

import com.photographer.focusstack.ai.mock.MockVisionProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TwoStageVisionProviderTest {

    private static final byte[] IMAGE = new byte[]{1, 2, 3};

    /** Pre-filter returning "none" with low confidence → classifier must NOT be called. */
    @Test
    void preFilterNone_skipsExpensiveClassifier() {
        MockVisionProvider preFilter = new MockVisionProvider(
                VisionResult.error("mock", "m", "simulated-none"));
        // Override: return a "none" success result
        VisionResult noneResult = new VisionResult("mock", "m", "classification",
                "none", List.of(), 0.1, null, List.of(), null, 5L, true, null);
        MockVisionProvider preFilterNone = new MockVisionProvider(noneResult);

        MockVisionProvider classifier = new MockVisionProvider(
                new VisionResult("gemini", "g", "classification",
                        "Kohlmeise", List.of(), 0.95, null, List.of(), null, 100L, true, null));

        TwoStageVisionProvider two = new TwoStageVisionProvider(preFilterNone, classifier);
        VisionResult result = two.classifyImage(IMAGE, VisionContext.wildlife());

        // Classifier should NOT have been called
        assertEquals(0, classifier.getCallCount());
        assertEquals("none", result.primaryLabel());
    }

    /** Pre-filter returns a positive result → classifier SHOULD be called. */
    @Test
    void preFilterPositive_invokesClassifier() {
        VisionResult positivePreFilter = new VisionResult("mock", "m", "classification",
                "Vogel", List.of(), 0.7, null, List.of(), null, 5L, true, null);
        MockVisionProvider preFilter = new MockVisionProvider(positivePreFilter);

        VisionResult classifierResult = new VisionResult("gemini", "g", "classification",
                "Kohlmeise", List.of(), 0.95, "A great tit", List.of(), null, 100L, true, null);
        MockVisionProvider classifier = new MockVisionProvider(classifierResult);

        TwoStageVisionProvider two = new TwoStageVisionProvider(preFilter, classifier);
        VisionResult result = two.classifyImage(IMAGE, VisionContext.wildlife());

        assertEquals(1, classifier.getCallCount());
        assertEquals("Kohlmeise", result.primaryLabel());
        assertEquals(0.95, result.confidence(), 0.001);
    }

    /** Classifier result should be returned (not pre-filter result) when pre-filter is positive. */
    @Test
    void classifierResultIsReturned_notPreFilterResult() {
        VisionResult pre = new VisionResult("mock", "m", "classification",
                "Tier", List.of(), 0.6, null, List.of(), null, 0L, true, null);
        VisionResult classified = new VisionResult("gemini", "g", "classification",
                "Blaumeise", List.of(), 0.88, "Blue tit", List.of(), null, 80L, true, null);

        TwoStageVisionProvider two = new TwoStageVisionProvider(
                new MockVisionProvider(pre),
                new MockVisionProvider(classified));

        VisionResult result = two.classifyImage(IMAGE, VisionContext.wildlife());
        assertEquals("Blaumeise", result.primaryLabel());
        assertEquals("gemini", result.provider());
    }

    /** Low confidence in pre-filter (< 0.3) → classifier should be skipped. */
    @Test
    void preFilterLowConfidence_skipsClassifier() {
        VisionResult lowConf = new VisionResult("mock", "m", "classification",
                "Vogel", List.of(), 0.2, null, List.of(), null, 0L, true, null);
        MockVisionProvider preFilter = new MockVisionProvider(lowConf);
        MockVisionProvider classifier = new MockVisionProvider(
                new VisionResult("gemini", "g", "classification",
                        "Kohlmeise", List.of(), 0.95, null, List.of(), null, 100L, true, null));

        TwoStageVisionProvider two = new TwoStageVisionProvider(preFilter, classifier);
        two.classifyImage(IMAGE, VisionContext.wildlife());

        assertEquals(0, classifier.getCallCount());
    }

    @Test
    void name_includesBothProviderNames() {
        TwoStageVisionProvider two = new TwoStageVisionProvider(
                new MockVisionProvider(VisionResult.error("mock", "m", "x")),
                new MockVisionProvider(VisionResult.error("mock", "m", "x")));
        assertTrue(two.name().contains("mock"));
    }

    @Test
    void isAvailable_trueWhenBothAvailable() {
        VisionResult r = new VisionResult("mock", "m", "c", "Vogel",
                List.of(), 0.8, null, List.of(), null, 0L, true, null);
        TwoStageVisionProvider two = new TwoStageVisionProvider(
                new MockVisionProvider(r), new MockVisionProvider(r));
        assertTrue(two.isAvailable());
    }

    @Test
    void isAvailable_falseWhenPreFilterUnavailable() {
        TwoStageVisionProvider two = new TwoStageVisionProvider(
                new MockVisionProvider(List.of(), 0),
                new MockVisionProvider(VisionResult.error("mock", "m", "x")));
        assertFalse(two.isAvailable());
    }
}
