package com.photographer.focusstack.ai.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.photographer.focusstack.ai.VisionFailureKind;
import com.photographer.focusstack.ai.VisionResult;
import com.photographer.focusstack.observer.events.BoundingBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/** Parses Gemini API responses into {@link VisionResult} objects. */
public final class GeminiResponseParser {

    private static final Logger log = LoggerFactory.getLogger(GeminiResponseParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MALFORMED_MESSAGE =
            "Provider response did not match the expected vision-result schema";

    private GeminiResponseParser() {}

    /**
     * Parses the full Gemini API response JSON into a {@link VisionResult}.
     * Malformed outer or inner JSON is a typed unsuccessful result and is never
     * converted into a successful {@code none} classification.
     */
    public static VisionResult parse(
            String json,
            String provider,
            String model,
            String taskType,
            long durationMs) {
        try {
            JsonNode root = MAPPER.readTree(json);
            String text = extractText(root);
            if (text == null || text.isBlank()) {
                return malformed(provider, model, taskType, durationMs);
            }
            return parseInnerJson(
                    text, provider, model, taskType, json, durationMs);
        } catch (Exception failure) {
            log.warn("Gemini response parsing failed ({})",
                    failure.getClass().getSimpleName());
            return malformed(provider, model, taskType, durationMs);
        }
    }

    private static String extractText(JsonNode root) {
        try {
            JsonNode candidates = root.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) {
                return null;
            }
            JsonNode parts = candidates.get(0).path("content").path("parts");
            if (!parts.isArray() || parts.isEmpty()) {
                return null;
            }
            return parts.get(0).path("text").asText(null);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static VisionResult parseInnerJson(
            String text,
            String provider,
            String model,
            String taskType,
            String rawResponse,
            long durationMs) {
        String cleaned = stripCodeFence(text);
        try {
            JsonNode inner = MAPPER.readTree(cleaned);
            JsonNode primaryNode = inner.get("primaryLabel");
            if (primaryNode == null || !primaryNode.isTextual()
                    || primaryNode.asText().isBlank()) {
                return malformed(provider, model, taskType, durationMs);
            }
            JsonNode confidenceNode = inner.get("confidence");
            if (confidenceNode == null || !confidenceNode.isNumber()) {
                return malformed(provider, model, taskType, durationMs);
            }
            double confidence = confidenceNode.asDouble();
            if (!Double.isFinite(confidence)
                    || confidence < 0.0 || confidence > 1.0) {
                return malformed(provider, model, taskType, durationMs);
            }

            List<String> alternatives = parseAlternativeLabels(inner);
            String summary = inner.path("summary").isTextual()
                    ? inner.path("summary").asText()
                    : null;
            List<BoundingBox> boundingBoxes = "object_detection".equals(taskType)
                    ? parseBoundingBoxes(inner)
                    : List.of();

            return new VisionResult(
                    provider,
                    model,
                    taskType,
                    primaryNode.asText(),
                    alternatives,
                    confidence,
                    summary,
                    boundingBoxes,
                    rawResponse,
                    durationMs,
                    true,
                    null);
        } catch (Exception failure) {
            log.warn("Gemini inner-result parsing failed ({})",
                    failure.getClass().getSimpleName());
            return malformed(provider, model, taskType, durationMs);
        }
    }

    private static String stripCodeFence(String text) {
        String cleaned = text.trim();
        if (!cleaned.startsWith("```")) {
            return cleaned;
        }
        int firstNewline = cleaned.indexOf('\n');
        int lastFence = cleaned.lastIndexOf("```");
        if (firstNewline >= 0 && lastFence > firstNewline) {
            return cleaned.substring(firstNewline + 1, lastFence).trim();
        }
        return cleaned;
    }

    private static List<String> parseAlternativeLabels(JsonNode inner) {
        JsonNode alternatives = inner.path("alternativeLabels");
        if (!alternatives.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode alternative : alternatives) {
            if (alternative.isTextual() && !alternative.asText().isBlank()) {
                values.add(alternative.asText());
            }
        }
        return List.copyOf(values);
    }

    private static VisionResult malformed(
            String provider,
            String model,
            String taskType,
            long durationMs) {
        return VisionResult.failure(
                provider,
                model,
                taskType,
                VisionFailureKind.MALFORMED_RESPONSE,
                false,
                null,
                MALFORMED_MESSAGE,
                durationMs);
    }

    /** Parses normalized object-detection boxes. Invalid entries are omitted. */
    private static List<BoundingBox> parseBoundingBoxes(JsonNode inner) {
        JsonNode objects = inner.path("objects");
        if (!objects.isArray() || objects.isEmpty()) {
            return List.of();
        }
        List<BoundingBox> boxes = new ArrayList<>();
        for (JsonNode object : objects) {
            String label = object.path("label").asText("unknown");
            double confidence = object.path("confidence").asDouble(0.0);
            double x = object.path("x").asDouble(0.0);
            double y = object.path("y").asDouble(0.0);
            double width = object.path("width").asDouble(0.0);
            double height = object.path("height").asDouble(0.0);
            if (!Double.isFinite(confidence)
                    || !Double.isFinite(x)
                    || !Double.isFinite(y)
                    || !Double.isFinite(width)
                    || !Double.isFinite(height)) {
                continue;
            }
            boxes.add(new BoundingBox(
                    x, y, width, height, label, confidence));
        }
        return List.copyOf(boxes);
    }
}
