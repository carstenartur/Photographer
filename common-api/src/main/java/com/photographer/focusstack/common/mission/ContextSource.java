package com.photographer.focusstack.common.mission;

/**
 * Identifies a category of contextual information that can enrich a capture
 * mission (Wildlife observer, Astro session, HDR timelapse, …).
 *
 * <p>Each value corresponds to one field of {@link MissionContext}. UI layers
 * use the set of relevant sources for a {@link MissionScenario} to render
 * "Recommended context sources" hints — e.g. an astro session prominently
 * surfaces moon phase and ambient light, while a bird watcher sees wind and
 * weather.</p>
 */
public enum ContextSource {
    /** Geographic location (latitude / longitude / altitude). */
    GPS,
    /** Wall-clock time of the capture. */
    CLOCK,
    /** Sun altitude above the horizon (positive = above). */
    SUN,
    /** Lunar illumination phase, 0.0 (new) to 1.0 (full). */
    MOON,
    /** Weather conditions (clear, cloudy, rain, …). */
    WEATHER,
    /** Wind speed and gusts. */
    WIND,
    /** Ambient light level (lux, EV). */
    AMBIENT_LIGHT
}
