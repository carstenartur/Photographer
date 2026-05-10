package com.photographer.focusstack.common.ai;

import java.util.List;

/**
 * Result of an automated quality check on a captured image.
 *
 * <p>Used by post-capture workflows to decide whether to keep, redo or flag
 * a frame. Issue strings are free-form and should be human-readable.
 */
public record QualityCheckResult(
        boolean passed,
        double sharpness,
        double exposureScore,
        List<String> issues
) {
    public QualityCheckResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public static QualityCheckResult passed(double sharpness, double exposureScore) {
        return new QualityCheckResult(true, sharpness, exposureScore, List.of());
    }
}
