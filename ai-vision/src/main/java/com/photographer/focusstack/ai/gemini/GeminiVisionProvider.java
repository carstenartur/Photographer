package com.photographer.focusstack.ai.gemini;

import com.photographer.focusstack.ai.ProviderCapabilities;
import com.photographer.focusstack.ai.ProviderConfig;
import com.photographer.focusstack.ai.VisionContext;
import com.photographer.focusstack.ai.VisionProvider;
import com.photographer.focusstack.ai.VisionResult;
import com.photographer.focusstack.ai.prompts.PromptTemplates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * {@link VisionProvider} implementation for the Google Gemini API.
 *
 * <p>Uses Java's built-in {@link HttpClient}; no external HTTP library required.</p>
 *
 * <p>Set {@code GEMINI_API_KEY} in the environment or pass it via {@link ProviderConfig}.</p>
 */
public class GeminiVisionProvider implements VisionProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiVisionProvider.class);

    private final ProviderConfig config;
    private final HttpClient httpClient;

    public GeminiVisionProvider(ProviderConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.timeoutSeconds()))
                .build();
    }

    @Override
    public String name() {
        return "gemini";
    }

    @Override
    public VisionResult classifyImage(byte[] imageData, VisionContext context) {
        String prompt = context.promptTemplate() != null
                ? context.promptTemplate()
                : PromptTemplates.forMode(context.mode());
        return doRequest(imageData, prompt, "classification");
    }

    @Override
    public VisionResult describeImage(byte[] imageData, VisionContext context) {
        String prompt = context.promptTemplate() != null
                ? context.promptTemplate()
                : "Beschreibe das Bild ausführlich als JSON: {\"summary\": \"...\", \"primaryLabel\": \"...\", \"confidence\": 0.8, \"alternativeLabels\": []}";
        return doRequest(imageData, prompt, "description");
    }

    @Override
    public VisionResult detectObjects(byte[] imageData, VisionContext context) {
        String prompt = context.promptTemplate() != null
                ? context.promptTemplate()
                : PromptTemplates.DETECT_OBJECTS;
        return doRequest(imageData, prompt, "object_detection");
    }

    @Override
    public VisionResult rankBestImage(List<byte[]> images, VisionContext context) {
        if (images == null || images.isEmpty()) {
            return VisionResult.error(name(), config.model(), "No images provided");
        }
        if (images.size() > 1) {
            return VisionResult.error(
                    name(),
                    config.model(),
                    "Multi-image ranking is not supported by the Gemini provider"
            );
        }
        String prompt = PromptTemplates.RANK_BEST.formatted(images.size());
        return doRequest(images.get(0), prompt, "ranking");
    }

    @Override
    public boolean isAvailable() {
        String key = getApiKey();
        return key != null && !key.isBlank();
    }

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(
                true, true, true, false,
                (int) config.maxImageBytes(),
                List.of("jpeg", "png", "webp")
        );
    }

    private VisionResult doRequest(byte[] imageData, String prompt, String taskType) {
        if (!isAvailable()) {
            return VisionResult.error(name(), config.model(), "Gemini API key not configured");
        }
        String apiKey = getApiKey();
        String base64Image = Base64.getEncoder().encodeToString(imageData);
        String requestBody = buildRequestBody(prompt, base64Image);

        String url = "%s/%s:generateContent?key=%s".formatted(
                config.endpoint(), config.model(), apiKey);

        Instant start = Instant.now();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            long durationMs = Duration.between(start, Instant.now()).toMillis();

            if (response.statusCode() != 200) {
                log.warn("Gemini API returned HTTP {}: {}", response.statusCode(), response.body());
                return VisionResult.error(name(), config.model(),
                        "HTTP " + response.statusCode() + ": " + response.body());
            }

            return GeminiResponseParser.parse(response.body(), name(), config.model(),
                    taskType, durationMs);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return VisionResult.error(name(), config.model(), "Request interrupted");
        } catch (Exception e) {
            log.error("Gemini API request failed", e);
            return VisionResult.error(name(), config.model(), e.getMessage());
        }
    }

    private String buildRequestBody(String prompt, String base64Image) {
        return """
                {
                  "contents": [{
                    "parts": [
                      { "text": %s },
                      { "inlineData": { "mimeType": "image/jpeg", "data": "%s" } }
                    ]
                  }]
                }
                """.formatted(jsonString(prompt), base64Image);
    }

    private String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }

    private String getApiKey() {
        if (config.apiKey() != null && !config.apiKey().isBlank()) {
            return config.apiKey();
        }
        return System.getenv("GEMINI_API_KEY");
    }
}
