package com.photographer.focusstack.common.mission;

import java.util.Objects;

/**
 * A single recommendation produced by an "advisor" — a service that proposes
 * a setting change but does not apply it on its own.
 *
 * <p>In the {@link AutomationLevel#ADVISOR} and {@link AutomationLevel#ASSISTED}
 * modes the UI presents these recommendations to the user, who decides whether
 * to apply them. In the higher automation levels they may be applied
 * automatically subject to the configured policy.</p>
 *
 * @param setting         human-readable name of the affected setting (e.g. {@code "shutterSpeed"})
 * @param suggestedValue  the proposed value, expressed as a string for display
 * @param reason          short justification (e.g. {@code "Sun set 30 min ago, ambient light dropped 4 EV"})
 * @param confidenceScore advisor confidence in [0.0, 1.0]
 * @param severity        importance hint for the UI (advisory / strong / critical)
 */
public record AdvisorRecommendation(
        String setting,
        String suggestedValue,
        String reason,
        double confidenceScore,
        Severity severity
) {

    /** UI hint describing how strongly the user should consider acting on the recommendation. */
    public enum Severity {
        /** Optional improvement; safe to ignore. */
        ADVISORY,
        /** Likely required to get the desired outcome. */
        STRONG,
        /** Failing to apply may cause data loss or hardware damage. */
        CRITICAL
    }

    public AdvisorRecommendation {
        Objects.requireNonNull(setting, "setting");
        Objects.requireNonNull(suggestedValue, "suggestedValue");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(severity, "severity");
        if (confidenceScore < 0.0 || confidenceScore > 1.0) {
            throw new IllegalArgumentException(
                    "confidenceScore must be in [0.0, 1.0]; got " + confidenceScore);
        }
    }

    /** Convenience factory for an {@link Severity#ADVISORY} recommendation. */
    public static AdvisorRecommendation advisory(
            String setting, String suggestedValue, String reason, double confidence) {
        return new AdvisorRecommendation(setting, suggestedValue, reason, confidence, Severity.ADVISORY);
    }
}
