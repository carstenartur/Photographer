package com.photographer.focusstack.common.mission;

/**
 * Spectrum describing how much agency the user delegates to the system on a
 * per-job basis.
 *
 * <p>Levels are <em>ordered</em> by increasing autonomy:</p>
 * <ol>
 *   <li>{@link #ADVISOR} — the system only suggests; the user applies every setting.</li>
 *   <li>{@link #ASSISTED} — recommendations are pre-filled in the UI and applied with one tap.</li>
 *   <li>{@link #AUTONOMOUS} — recommendations are applied automatically, but a user can intervene.</li>
 *   <li>{@link #FULL_PIPELINE} — the system runs the entire Observe → Decide → Act loop unattended.</li>
 * </ol>
 *
 * <p>Use {@link #atLeast(AutomationLevel)} to gate features: e.g. an automatic
 * focus-step adjustment is allowed when the configured level is at least
 * {@link #AUTONOMOUS}.</p>
 */
public enum AutomationLevel {
    /** System only proposes settings; user must apply each one manually. */
    ADVISOR,
    /** Recommendations are pre-filled in the UI and applied with explicit confirmation. */
    ASSISTED,
    /** System applies recommendations automatically; user can intervene at any time. */
    AUTONOMOUS,
    /** Fully unattended Observe → Decide → Act loop (long-running wildlife / astro sessions). */
    FULL_PIPELINE;

    /** Returns {@code true} when this level is at or above {@code other}. */
    public boolean atLeast(AutomationLevel other) {
        return this.ordinal() >= other.ordinal();
    }

    /**
     * Returns {@code true} when explicit user confirmation is required before
     * applying a recommendation. True for {@link #ADVISOR} and
     * {@link #ASSISTED}.
     */
    public boolean requiresUserConfirmation() {
        return this == ADVISOR || this == ASSISTED;
    }
}
