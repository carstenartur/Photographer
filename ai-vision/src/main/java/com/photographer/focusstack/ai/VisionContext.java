package com.photographer.focusstack.ai;

import java.util.Map;

/**
 * Context passed to a {@link VisionProvider} to configure the analysis task.
 */
public record VisionContext(
        String mode,
        String promptTemplate,
        String language,
        int maxTokens,
        String responseFormat,
        Map<String, String> extra
) {

    /** Context for standard wildlife classification. */
    public static VisionContext wildlife() {
        return new VisionContext("wildlife", null, "de", 256, "json", Map.of());
    }

    /** Context for macro pollinator classification. */
    public static VisionContext macroPollinator() {
        return new VisionContext("macro_pollinator", null, "de", 256, "json", Map.of());
    }

    /** Returns a context appropriate for the given mode. */
    public static VisionContext forMode(String mode) {
        return "macro_pollinator".equals(mode) ? macroPollinator() : wildlife();
    }
}
