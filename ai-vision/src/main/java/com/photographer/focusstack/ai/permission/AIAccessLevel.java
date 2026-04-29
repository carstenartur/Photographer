package com.photographer.focusstack.ai.permission;

/**
 * Access-level spectrum that controls what the AI subsystem is allowed to do.
 *
 * <p>The levels are ordered from most restrictive ({@link #DISABLED}) to least
 * restrictive ({@link #AUTONOMOUS}). Each level is a strict superset of all
 * lower levels.</p>
 *
 * <p>This enum is designed to be GDPR-friendly: the default for new users / jobs
 * should be {@link #READ_ONLY} unless the operator explicitly elevates it.</p>
 */
public enum AIAccessLevel {

    /**
     * AI is completely switched off; no image data or metadata leaves the
     * application for AI processing.
     */
    DISABLED,

    /**
     * AI may read and analyse images but must not write, annotate, or apply
     * any suggestion automatically. Results are returned to the caller only.
     */
    READ_ONLY,

    /**
     * AI may analyse images and produce recommendations, but every suggested
     * action must be explicitly approved by the user before it is applied.
     */
    RECOMMEND,

    /**
     * AI may analyse images and apply suggestions automatically, but the user
     * must confirm each action (e.g. via a UI prompt) before the pipeline proceeds.
     */
    APPLY_WITH_CONFIRMATION,

    /**
     * AI operates fully autonomously; it may analyse images and act on its
     * suggestions without any user confirmation.
     *
     * <p><strong>Warning:</strong> only enable this level in controlled,
     * non-critical environments or with additional safety guards in place.</p>
     */
    AUTONOMOUS;

    /**
     * Returns {@code true} when this level is at least as permissive as
     * {@code required}.
     *
     * @param required the minimum required access level
     * @return {@code true} iff {@code this.ordinal() >= required.ordinal()}
     */
    public boolean allows(AIAccessLevel required) {
        return this.ordinal() >= required.ordinal();
    }
}
