package com.photographer.focusstack.ai;

import com.photographer.focusstack.observer.events.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisionResultFailureTest {

    @Test
    void typedFailureCannotMasqueradeAsNegativeClassification() {
        VisionResult result = VisionResult.failure(
                "provider", "model", "classification",
                VisionFailureKind.RATE_LIMITED, true, 12,
                "Provider rate limit was reached", 25L);

        assertFalse(result.successful());
        assertEquals("unclassified", result.primaryLabel());
        assertEquals(0.0, result.confidence());
        assertEquals(VisionFailureKind.RATE_LIMITED, result.failureKind());
        assertTrue(result.retryable());
        assertEquals(12, result.retryAfterSeconds());
        assertNull(result.rawResponse());
        assertTrue(result.boundingBoxes().isEmpty());
    }

    @Test
    void unsuccessfulLegacyConstructorNormalizesToUnknownAndDropsRawPayload() {
        VisionResult result = new VisionResult(
                "provider", "model", "error", "none", List.of(), 0.7,
                "summary", List.of(new BoundingBox(0, 0, 1, 1, "bird", 1.0)),
                "private provider payload", 5L, false, "legacy error");

        assertEquals(VisionFailureKind.UNKNOWN, result.failureKind());
        assertEquals("unclassified", result.primaryLabel());
        assertFalse(result.retryable());
        assertNull(result.rawResponse());
        assertTrue(result.boundingBoxes().isEmpty());
    }

    @Test
    void successfulResultCannotCarryFailureMetadata() {
        VisionResult result = new VisionResult(
                "provider", "model", "classification", "none", List.of(), 0.0,
                "No subject", List.of(), "safe success response", 4L, true,
                VisionFailureKind.TIMEOUT, true, 10, "should be removed");

        assertTrue(result.successful());
        assertEquals(VisionFailureKind.NONE, result.failureKind());
        assertFalse(result.retryable());
        assertNull(result.retryAfterSeconds());
        assertNull(result.errorMessage());
        assertEquals("none", result.primaryLabel());
    }

    @Test
    void rejectsInvalidDurationsAndRetryHints() {
        assertThrows(IllegalArgumentException.class, () -> VisionResult.failure(
                "provider", "model", "classification",
                VisionFailureKind.TIMEOUT, true, null, "timeout", -1L));
        assertThrows(IllegalArgumentException.class, () -> new VisionResult(
                "provider", "model", "classification", "unclassified",
                List.of(), 0.0, null, List.of(), null, 0L, false,
                VisionFailureKind.RATE_LIMITED, true, -1, "limited"));
    }
}
