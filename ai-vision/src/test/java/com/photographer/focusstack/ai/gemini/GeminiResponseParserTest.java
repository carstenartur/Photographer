package com.photographer.focusstack.ai.gemini;

import com.photographer.focusstack.ai.VisionResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GeminiResponseParserTest {

    @Test
    void parsesWellFormedResponse() {
        String json = """
                {
                  "candidates": [{
                    "content": {
                      "parts": [{
                        "text": "{\\"primaryLabel\\":\\"Kohlmeise\\",\\"alternativeLabels\\":[\\"Blaumeise\\"],\\"confidence\\":0.92,\\"summary\\":\\"Eine Kohlmeise sitzt auf einem Ast\\"}"
                      }]
                    }
                  }]
                }
                """;
        VisionResult result = GeminiResponseParser.parse(json, "gemini", "gemini-2.5-flash", "classification", 200L);
        assertTrue(result.successful());
        assertEquals("Kohlmeise", result.primaryLabel());
        assertEquals(0.92, result.confidence(), 0.001);
        assertEquals(1, result.alternativeLabels().size());
        assertEquals("Blaumeise", result.alternativeLabels().get(0));
        assertEquals(200L, result.durationMs());
    }

    @Test
    void handlesMarkdownCodeBlock() {
        String json = """
                {
                  "candidates": [{
                    "content": {
                      "parts": [{
                        "text": "```json\\n{\\"primaryLabel\\":\\"Honigbiene\\",\\"alternativeLabels\\":[],\\"confidence\\":0.85,\\"summary\\":\\"Eine Biene\\"}\\n```"
                      }]
                    }
                  }]
                }
                """;
        VisionResult result = GeminiResponseParser.parse(json, "gemini", "model", "classification", 100L);
        assertTrue(result.successful());
        assertEquals("Honigbiene", result.primaryLabel());
    }

    @Test
    void returnsErrorOnInvalidJson() {
        VisionResult result = GeminiResponseParser.parse("not-json", "gemini", "model", "classification", 0L);
        assertFalse(result.successful());
    }

    @Test
    void returnsErrorWhenNoCandidates() {
        String json = """
                { "candidates": [] }
                """;
        VisionResult result = GeminiResponseParser.parse(json, "gemini", "model", "classification", 0L);
        // Empty candidates produce null text → error
        assertFalse(result.successful());
    }

    @Test
    void handlesNoneLabel() {
        String json = """
                {
                  "candidates": [{
                    "content": {
                      "parts": [{
                        "text": "{\\"primaryLabel\\":\\"none\\",\\"alternativeLabels\\":[],\\"confidence\\":0.0,\\"summary\\":\\"Kein Tier erkannt\\"}"
                      }]
                    }
                  }]
                }
                """;
        VisionResult result = GeminiResponseParser.parse(json, "gemini", "model", "classification", 50L);
        assertTrue(result.successful());
        assertEquals("none", result.primaryLabel());
    }
}
