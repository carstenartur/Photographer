package com.photographer.focusstack.common.ai;

import java.util.List;
import java.util.Map;

/**
 * Result of a scene-analysis pass.
 *
 * <p>Intentionally generic — vendor-specific tags live in {@link #attributes()}.
 * Future AI providers can extend by adding more attributes without breaking
 * downstream consumers.
 */
public record SceneAnalysisResult(
        String sceneType,
        List<String> detectedSubjects,
        double confidence,
        Map<String, Object> attributes
) {
    public SceneAnalysisResult {
        detectedSubjects = detectedSubjects == null ? List.of() : List.copyOf(detectedSubjects);
        attributes       = attributes == null      ? Map.of()  : Map.copyOf(attributes);
    }
}
