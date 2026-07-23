package com.photographer.focusstack.ai.gemini;

import com.photographer.focusstack.ai.VisionFailureKind;
import com.photographer.focusstack.ai.VisionResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiResponseParserTest {

    @Test
    void parsesWellFormedResponse() {
        VisionResult result = GeminiResponseParser.parse(
                outer("{\"primaryLabel\":\"Kohlmeise\","
                        + "\"alternativeLabels\":[\"Blaumeise\"],"
                        + "\"confidence\":0.92,"
                        + "\"summary\":\"Eine Kohlmeise sitzt auf einem Ast\"}"),
                "gemini", "gemini-2.5-flash", "classification", 200L);

        assertTrue(result.successful());
        assertEquals(VisionFailureKind.NONE, result.failureKind());
        assertEquals("Kohlmeise", result.primaryLabel());
        assertEquals(0.92, result.confidence(), 0.001);
        assertEquals(1, result.alternativeLabels().size());
        assertEquals("Blaumeise", result.alternativeLabels().get(0));
        assertEquals(200L, result.durationMs());
    }

    @Test
    void handlesMarkdownCodeBlock() {
        VisionResult result = GeminiResponseParser.parse(
                outer("```json\n{\"primaryLabel\":\"Honigbiene\","
                        + "\"alternativeLabels\":[],\"confidence\":0.85,"
                        + "\"summary\":\"Eine Biene\"}\n```"),
                "gemini", "model", "classification", 100L);

        assertTrue(result.successful());
        assertEquals("Honigbiene", result.primaryLabel());
    }

    @Test
    void returnsTypedFailureOnInvalidOuterOrInnerJson() {
        VisionResult outerFailure = GeminiResponseParser.parse(
                "not-json", "gemini", "model", "classification", 7L);
        VisionResult innerFailure = GeminiResponseParser.parse(
                outer("not-inner-json"),
                "gemini", "model", "classification", 9L);

        assertMalformed(outerFailure, 7L);
        assertMalformed(innerFailure, 9L);
    }

    @Test
    void rejectsMissingRequiredFieldsAndInvalidConfidence() {
        VisionResult missingLabel = GeminiResponseParser.parse(
                outer("{\"confidence\":0.5}"),
                "gemini", "model", "classification", 0L);
        VisionResult missingConfidence = GeminiResponseParser.parse(
                outer("{\"primaryLabel\":\"bird\"}"),
                "gemini", "model", "classification", 0L);
        VisionResult outOfRange = GeminiResponseParser.parse(
                outer("{\"primaryLabel\":\"bird\",\"confidence\":1.2}"),
                "gemini", "model", "classification", 0L);

        assertMalformed(missingLabel, 0L);
        assertMalformed(missingConfidence, 0L);
        assertMalformed(outOfRange, 0L);
    }

    @Test
    void returnsErrorWhenNoCandidates() {
        VisionResult result = GeminiResponseParser.parse(
                "{\"candidates\":[]}",
                "gemini", "model", "classification", 0L);

        assertMalformed(result, 0L);
    }

    @Test
    void successfulNoneLabelRemainsDistinctFromProviderFailure() {
        VisionResult result = GeminiResponseParser.parse(
                outer("{\"primaryLabel\":\"none\","
                        + "\"alternativeLabels\":[],\"confidence\":0.0,"
                        + "\"summary\":\"Kein Tier erkannt\"}"),
                "gemini", "model", "classification", 50L);

        assertTrue(result.successful());
        assertEquals(VisionFailureKind.NONE, result.failureKind());
        assertEquals("none", result.primaryLabel());
    }

    private static void assertMalformed(VisionResult result, long durationMs) {
        assertFalse(result.successful());
        assertEquals(VisionFailureKind.MALFORMED_RESPONSE, result.failureKind());
        assertEquals("unclassified", result.primaryLabel());
        assertFalse(result.retryable());
        assertNull(result.rawResponse());
        assertEquals(durationMs, result.durationMs());
    }

    private static String outer(String innerText) {
        String escaped = innerText
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
        return "{\"candidates\":[{\"content\":{\"parts\":[{"
                + "\"text\":\"" + escaped + "\"}]}}]}";
    }
}
