package com.photographer.focusstack.ai.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.photographer.focusstack.ai.VisionResult;
import com.photographer.focusstack.observer.events.BoundingBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses Gemini API responses into {@link VisionResult} objects.
 */
public class GeminiResponseParser {

    private static final Logger log = LoggerFactory.getLogger(GeminiResponseParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GeminiResponseParser() {}

    /**
     * Parses the full Gemini API response JSON into a {@link VisionResult}.
     *
     * @param json       raw Gemini response JSON
     * @param provider   provider name
     * @param model      model name
     * @param taskType   task type string
     * @param durationMs time taken for the API call
     * @return parsed result; on outer JSON parse error returns {@code VisionResult.error(...)},
     *         on inner text parse error returns a successful result with primaryLabel="none"
     */
    public static VisionResult parse(String json, String provider, String model,
                                     String taskType, long durationMs) {
        try {
            JsonNode root = MAPPER.readTree(json);
            // Extract text from candidates[0].content.parts[0].text
            String text = extractText(root);
            if (text == null || text.isBlank()) {
                return VisionResult.error(provider, model, "Empty response from Gemini");
            }
            return parseInnerJson(text, provider, model, taskType, json, durationMs);
        } catch (Exception e) {
            log.warn("Failed to parse Gemini response: {}", e.getMessage());
            return VisionResult.error(provider, model, "Parse error: " + e.getMessage());
        }
    }

    private static String extractText(JsonNode root) {
        try {
            return root.path("candidates").get(0)
                    .path("content").path("parts").get(0)
                    .path("text").asText();
        } catch (Exception e) {
            return null;
        }
    }

    private static VisionResult parseInnerJson(String text, String provider, String model,
                                               String taskType, String rawResponse, long durationMs) {
        // Strip markdown code blocks if present
        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            int lastFence = cleaned.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                cleaned = cleaned.substring(firstNewline + 1, lastFence).trim();
            }
        }

        try {
            JsonNode inner = MAPPER.readTree(cleaned);
            String primaryLabel = inner.path("primaryLabel").asText("none");
            List<String> altLabels = new ArrayList<>();
            JsonNode alts = inner.path("alternativeLabels");
            if (alts.isArray()) {
                for (JsonNode alt : alts) {
                    altLabels.add(alt.asText());
                }
            }
            double confidence = inner.path("confidence").asDouble(0.0);
            String summary = inner.path("summary").asText(null);

            return new VisionResult(
                    provider, model, taskType,
                    primaryLabel, altLabels, confidence, summary,
                    List.of(), rawResponse, durationMs,
                    true, null
            );
        } catch (Exception e) {
            log.warn("Failed to parse inner JSON from Gemini text: {}", e.getMessage());
            return new VisionResult(provider, model, taskType,
                    "none", List.of(), 0.0, text, List.of(), rawResponse, durationMs,
                    true, null);
        }
    }
}
