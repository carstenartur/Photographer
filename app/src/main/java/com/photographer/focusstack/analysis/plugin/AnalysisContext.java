package com.photographer.focusstack.analysis.plugin;

import java.util.Map;

/**
 * Context object passed to an {@link AnalysisPlugin} alongside the raw image data.
 *
 * <p>Carries optional parameters (e.g. block size, threshold) that plugins can use
 * to tailor their analysis without changing the plugin interface.
 */
public record AnalysisContext(Map<String, String> parameters) {

    /** Returns an empty context with no parameters. */
    public static AnalysisContext empty() {
        return new AnalysisContext(Map.of());
    }

    /**
     * Returns the value of {@code key} if present, otherwise {@code defaultValue}.
     */
    public String getOrDefault(String key, String defaultValue) {
        return parameters.getOrDefault(key, defaultValue);
    }
}
