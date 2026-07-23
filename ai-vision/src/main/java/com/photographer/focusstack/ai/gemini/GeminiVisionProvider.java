package com.photographer.focusstack.ai.gemini;

import com.photographer.focusstack.ai.ProviderCapabilities;
import com.photographer.focusstack.ai.ProviderConfig;
import com.photographer.focusstack.ai.VisionContext;
import com.photographer.focusstack.ai.VisionFailureKind;
import com.photographer.focusstack.ai.VisionProvider;
import com.photographer.focusstack.ai.VisionResult;
import com.photographer.focusstack.ai.prompts.PromptTemplates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * {@link VisionProvider} implementation for the Google Gemini API.
 *
 * <p>Failures are mapped to provider-neutral categories. Only transient results
 * are retried, and every attempt retains the same bounded request timeout.</p>
 */
public class GeminiVisionProvider implements VisionProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiVisionProvider.class);
    private static final int MAX_CONFIGURED_RETRIES = 5;
    private static final int MAX_RETRY_AFTER_SECONDS = 60;
    private static final Duration BASE_BACKOFF = Duration.ofMillis(100);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(2);

    private final ProviderConfig config;
    private final GeminiTransport transport;
    private final GeminiTransport.Sleeper sleeper;
    private final Duration requestTimeout;

    public GeminiVisionProvider(ProviderConfig config) {
        this(config,
                GeminiTransport.jdk(Duration.ofSeconds(
                        Math.max(1, config.timeoutSeconds()))),
                GeminiTransport.Sleeper.threadSleep());
    }

    GeminiVisionProvider(
            ProviderConfig config,
            GeminiTransport transport,
            GeminiTransport.Sleeper sleeper) {
        this.config = Objects.requireNonNull(config, "config");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.requestTimeout = Duration.ofSeconds(
                Math.max(1, config.timeoutSeconds()));
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
                : "Beschreibe das Bild ausführlich als JSON: "
                + "{\"summary\": \"...\", \"primaryLabel\": \"...\", "
                + "\"confidence\": 0.8, \"alternativeLabels\": []}";
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
            return failure("ranking", VisionFailureKind.INVALID_REQUEST,
                    false, null, "No images were provided", 0L);
        }
        if (images.size() > 1) {
            return failure("ranking", VisionFailureKind.INVALID_REQUEST,
                    false, null,
                    "Multi-image ranking is not supported by this provider", 0L);
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
                List.of("jpeg", "png", "webp"));
    }

    private VisionResult doRequest(
            byte[] imageData,
            String prompt,
            String taskType) {
        if (!isAvailable()) {
            return failure(taskType, VisionFailureKind.NOT_CONFIGURED,
                    false, null, "AI provider credentials are not configured", 0L);
        }
        if (imageData == null || imageData.length == 0) {
            return failure(taskType, VisionFailureKind.INVALID_REQUEST,
                    false, null, "Image data is required", 0L);
        }
        if (config.maxImageBytes() > 0
                && imageData.length > config.maxImageBytes()) {
            return failure(taskType, VisionFailureKind.INVALID_REQUEST,
                    false, null, "Image exceeds the configured provider limit", 0L);
        }

        HttpRequest request;
        try {
            request = request(imageData, prompt);
        } catch (RuntimeException failure) {
            log.warn("Gemini request configuration is invalid ({})",
                    failure.getClass().getSimpleName());
            return failure(taskType, VisionFailureKind.INVALID_REQUEST,
                    false, null, "AI provider request configuration is invalid", 0L);
        }

        int maximumAttempts = 1 + Math.max(
                0, Math.min(config.maxRetries(), MAX_CONFIGURED_RETRIES));
        long startNanos = System.nanoTime();
        VisionResult last = null;
        for (int attempt = 1; attempt <= maximumAttempts; attempt++) {
            last = sendOnce(request, taskType, startNanos);
            if (last.successful() || !last.retryable()
                    || attempt == maximumAttempts) {
                return last;
            }
            Duration delay = retryDelay(last, attempt);
            log.info("Retrying Gemini {} after typed {} failure (attempt {}/{})",
                    taskType, last.failureKind(), attempt + 1, maximumAttempts);
            try {
                sleeper.sleep(delay);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return failure(taskType, VisionFailureKind.INTERRUPTED,
                        false, null, "AI provider request was interrupted",
                        elapsedMillis(startNanos));
            }
        }
        return last;
    }

    private VisionResult sendOnce(
            HttpRequest request,
            String taskType,
            long startNanos) {
        try {
            GeminiTransport.Response response = transport.send(request);
            long durationMs = elapsedMillis(startNanos);
            if (response.statusCode() == 200) {
                return GeminiResponseParser.parse(
                        response.body(), name(), config.model(), taskType, durationMs);
            }
            VisionResult mapped = mapHttpFailure(
                    taskType, response.statusCode(), response.firstHeader("Retry-After"),
                    durationMs);
            log.warn("Gemini {} failed with HTTP {} ({})",
                    taskType, response.statusCode(), mapped.failureKind());
            return mapped;
        } catch (HttpTimeoutException timeout) {
            return failure(taskType, VisionFailureKind.TIMEOUT,
                    true, null, "AI provider request timed out",
                    elapsedMillis(startNanos));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failure(taskType, VisionFailureKind.INTERRUPTED,
                    false, null, "AI provider request was interrupted",
                    elapsedMillis(startNanos));
        } catch (IOException unavailable) {
            log.warn("Gemini transport failed ({})",
                    unavailable.getClass().getSimpleName());
            return failure(taskType, VisionFailureKind.TEMPORARY_UNAVAILABLE,
                    true, null, "AI provider is temporarily unavailable",
                    elapsedMillis(startNanos));
        } catch (RuntimeException failure) {
            log.error("Gemini request failed ({})",
                    failure.getClass().getSimpleName());
            return failure(taskType, VisionFailureKind.UNKNOWN,
                    false, null, "AI provider request failed",
                    elapsedMillis(startNanos));
        }
    }

    private VisionResult mapHttpFailure(
            String taskType,
            int status,
            String retryAfter,
            long durationMs) {
        if (status == 401 || status == 403) {
            return failure(taskType, VisionFailureKind.AUTHENTICATION,
                    false, null, "AI provider authentication was rejected", durationMs);
        }
        if (status == 429) {
            Integer retryAfterSeconds = parseRetryAfter(retryAfter);
            return failure(taskType, VisionFailureKind.RATE_LIMITED,
                    true, retryAfterSeconds, "AI provider rate limit was reached",
                    durationMs);
        }
        if (status == 408 || status == 504) {
            return failure(taskType, VisionFailureKind.TIMEOUT,
                    true, null, "AI provider request timed out", durationMs);
        }
        if (status >= 500 && status <= 599) {
            return failure(taskType, VisionFailureKind.TEMPORARY_UNAVAILABLE,
                    true, null, "AI provider is temporarily unavailable", durationMs);
        }
        if (status >= 400 && status <= 499) {
            return failure(taskType, VisionFailureKind.INVALID_REQUEST,
                    false, null, "AI provider rejected the request", durationMs);
        }
        return failure(taskType, VisionFailureKind.PERMANENT_PROVIDER_ERROR,
                false, null, "AI provider returned an unsupported response status",
                durationMs);
    }

    private HttpRequest request(byte[] imageData, String prompt) {
        String apiKey = getApiKey();
        String base64Image = Base64.getEncoder().encodeToString(imageData);
        String requestBody = buildRequestBody(prompt, base64Image);
        URI uri = URI.create("%s/%s:generateContent?key=%s".formatted(
                config.endpoint(), config.model(), apiKey));
        return HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(requestTimeout)
                .build();
    }

    private Duration retryDelay(VisionResult result, int completedAttempt) {
        if (result.retryAfterSeconds() != null) {
            return Duration.ofSeconds(Math.min(
                    result.retryAfterSeconds(), MAX_RETRY_AFTER_SECONDS));
        }
        long multiplier = 1L << Math.min(completedAttempt - 1, 4);
        Duration calculated = BASE_BACKOFF.multipliedBy(multiplier);
        return calculated.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : calculated;
    }

    private static Integer parseRetryAfter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            if (seconds < 0) {
                return null;
            }
            return (int) Math.min(seconds, MAX_RETRY_AFTER_SECONDS);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private VisionResult failure(
            String taskType,
            VisionFailureKind kind,
            boolean retryable,
            Integer retryAfterSeconds,
            String message,
            long durationMs) {
        return VisionResult.failure(
                name(), config.model(), taskType, kind, retryable,
                retryAfterSeconds, message, durationMs);
    }

    private static long elapsedMillis(long startNanos) {
        return Math.max(0L,
                Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
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

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\")
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
