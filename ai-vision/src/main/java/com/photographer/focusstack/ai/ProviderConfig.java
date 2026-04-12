package com.photographer.focusstack.ai;

/**
 * Per-provider configuration (API key, endpoint, model, limits).
 */
public record ProviderConfig(
        String apiKey,
        String endpoint,
        String model,
        int timeoutSeconds,
        int maxRetries,
        int rateLimitPerMinute,
        long maxImageBytes
) {

    /** Default Gemini configuration (no API key). */
    public static ProviderConfig geminiDefaults() {
        return new ProviderConfig(
                null,
                "https://generativelanguage.googleapis.com/v1beta/models",
                "gemini-2.5-flash",
                30, 3, 60, 4 * 1024 * 1024L
        );
    }
}
