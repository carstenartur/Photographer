package com.photographer.focusstack.common.mission;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * High-level capture-mission categories surfaced to the UI.
 *
 * <p>Each scenario advertises the {@link ContextSource}s that are most
 * relevant to it; the frontend uses this to highlight the inputs the user
 * should provide (or that the backend should fetch) for a given session type.</p>
 */
public enum MissionScenario {

    /** Bird-house / feeder observation. */
    BIRDHOUSE(EnumSet.of(
            ContextSource.CLOCK, ContextSource.SUN, ContextSource.WEATHER,
            ContextSource.WIND, ContextSource.AMBIENT_LIGHT)),

    /** General wildlife observation in the field. */
    WILDLIFE(EnumSet.of(
            ContextSource.GPS, ContextSource.CLOCK, ContextSource.SUN,
            ContextSource.WEATHER, ContextSource.WIND, ContextSource.AMBIENT_LIGHT)),

    /** Deep-sky / nightscape astrophotography. */
    ASTRO(EnumSet.of(
            ContextSource.GPS, ContextSource.CLOCK, ContextSource.SUN,
            ContextSource.MOON, ContextSource.WEATHER, ContextSource.AMBIENT_LIGHT)),

    /** HDR timelapse over hours or a full day-night cycle. */
    HDR_TIMELAPSE(EnumSet.of(
            ContextSource.CLOCK, ContextSource.SUN, ContextSource.WEATHER,
            ContextSource.AMBIENT_LIGHT)),

    /** Macro / focus-stacking session in a controlled environment. */
    MACRO_FOCUS_STACK(EnumSet.of(
            ContextSource.CLOCK, ContextSource.AMBIENT_LIGHT));

    private final Set<ContextSource> relevant;

    MissionScenario(EnumSet<ContextSource> relevant) {
        this.relevant = Collections.unmodifiableSet(relevant);
    }

    /** Returns the context sources that the UI should highlight for this scenario. */
    public Set<ContextSource> relevantContextSources() {
        return relevant;
    }
}
