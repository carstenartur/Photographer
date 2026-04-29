package com.photographer.focusstack.common.mission;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Snapshot of the contextual conditions surrounding a capture mission.
 *
 * <p>{@code MissionContext} aggregates the optional environmental signals that
 * help the system make smarter decisions (or surface them to the user as
 * advice). Every field except {@link #populatedSources} is nullable to
 * acknowledge that not every context source will always be available — for
 * example a wildlife camera in a forest has no GPS fix but still has a clock.</p>
 *
 * <p>The {@link #populatedSources} set is an authoritative record of which
 * sources actually contributed to this snapshot, so consumers can distinguish
 * "weather not available" from "weather is overcast".</p>
 *
 * @param instant         wall-clock time the snapshot was taken (never {@code null})
 * @param latitude        decimal degrees, or {@code null} if no GPS fix
 * @param longitude       decimal degrees, or {@code null} if no GPS fix
 * @param altitudeMeters  metres above sea level, or {@code null}
 * @param sunAltitudeDeg  sun altitude in degrees above horizon (negative = below), or {@code null}
 * @param moonPhase       0.0 (new) to 1.0 (full); {@code null} if unknown
 * @param weather         short weather descriptor (e.g. {@code "clear"}, {@code "overcast"}), or {@code null}
 * @param windKph         wind speed in km/h, or {@code null}
 * @param ambientLightLux ambient light in lux, or {@code null}
 * @param populatedSources set of {@link ContextSource} that actually populated the snapshot
 */
public record MissionContext(
        Instant instant,
        Double latitude,
        Double longitude,
        Double altitudeMeters,
        Double sunAltitudeDeg,
        Double moonPhase,
        String weather,
        Double windKph,
        Double ambientLightLux,
        Set<ContextSource> populatedSources
) {

    public MissionContext {
        Objects.requireNonNull(instant, "instant");
        Objects.requireNonNull(populatedSources, "populatedSources");
        if (moonPhase != null && (moonPhase < 0.0 || moonPhase > 1.0)) {
            throw new IllegalArgumentException("moonPhase must be in [0.0, 1.0]; got " + moonPhase);
        }
        // Defensive copy + immutable view
        populatedSources = Collections.unmodifiableSet(EnumSet.copyOf(
                populatedSources.isEmpty() ? EnumSet.noneOf(ContextSource.class)
                        : EnumSet.copyOf(populatedSources)));
    }

    /** Returns a minimal {@code MissionContext} containing only the wall-clock time. */
    public static MissionContext nowOnly() {
        return new MissionContext(Instant.now(), null, null, null, null, null, null, null, null,
                EnumSet.of(ContextSource.CLOCK));
    }

    /** Convenience: does this snapshot include data for the given source? */
    public boolean has(ContextSource source) {
        return populatedSources.contains(source);
    }

    /** Returns a builder seeded with the current instant. */
    public static Builder builder() {
        return new Builder(Instant.now());
    }

    /** Returns a builder seeded with the given instant. */
    public static Builder builder(Instant instant) {
        return new Builder(instant);
    }

    /** Mutable builder for {@link MissionContext}. */
    public static final class Builder {
        private final Instant instant;
        private Double latitude;
        private Double longitude;
        private Double altitudeMeters;
        private Double sunAltitudeDeg;
        private Double moonPhase;
        private String weather;
        private Double windKph;
        private Double ambientLightLux;
        private final EnumSet<ContextSource> sources = EnumSet.of(ContextSource.CLOCK);

        private Builder(Instant instant) {
            this.instant = Objects.requireNonNull(instant, "instant");
        }

        public Builder gps(double lat, double lon, Double altMeters) {
            this.latitude = lat;
            this.longitude = lon;
            this.altitudeMeters = altMeters;
            sources.add(ContextSource.GPS);
            return this;
        }

        public Builder sunAltitudeDeg(double deg) {
            this.sunAltitudeDeg = deg;
            sources.add(ContextSource.SUN);
            return this;
        }

        public Builder moonPhase(double phase) {
            this.moonPhase = phase;
            sources.add(ContextSource.MOON);
            return this;
        }

        public Builder weather(String descriptor) {
            this.weather = descriptor;
            sources.add(ContextSource.WEATHER);
            return this;
        }

        public Builder windKph(double kph) {
            this.windKph = kph;
            sources.add(ContextSource.WIND);
            return this;
        }

        public Builder ambientLightLux(double lux) {
            this.ambientLightLux = lux;
            sources.add(ContextSource.AMBIENT_LIGHT);
            return this;
        }

        public MissionContext build() {
            return new MissionContext(instant, latitude, longitude, altitudeMeters,
                    sunAltitudeDeg, moonPhase, weather, windKph, ambientLightLux, sources);
        }
    }
}
