package com.photographer.focusstack.observer.profile;

import com.photographer.focusstack.observer.events.RegionOfInterest;

/**
 * Configuration record for a Wildlife Observer operating mode.
 *
 * <p>Pre-defined profiles can be obtained via the static factory methods
 * (e.g. {@link #birdhouse()}, {@link #birdbath()}, {@link #macroFlower()}).</p>
 */
public record ObserverProfile(
        String name,
        String mode,
        // Monitoring
        int previewIntervalMs,
        double motionThreshold,
        double minObjectSize,
        RegionOfInterest defaultRoi,
        // Capture
        int burstCount,
        boolean focusBracketing,
        int focusBracketingSteps,
        // Rules
        int cooldownSeconds,
        String activeTimeStart,
        String activeTimeEnd,
        int maxEventsPerHour,
        // AI
        String aiPromptTemplate,
        String preferredAiProvider,
        // Camera
        String focusMode,
        String imageQuality
) {

    // ---- Pre-defined profiles ----

    public static ObserverProfile birdhouse() {
        return new ObserverProfile(
                "birdhouse", "wildlife",
                500, 0.15, 0.05, RegionOfInterest.fullFrame(),
                3, false, 0,
                30, "06:00", "21:00", 120,
                null, "gemini",
                "auto", "jpeg"
        );
    }

    public static ObserverProfile birdbath() {
        return new ObserverProfile(
                "birdbath", "wildlife",
                500, 0.10, 0.03, RegionOfInterest.fullFrame(),
                3, false, 0,
                20, "06:00", "21:00", 180,
                null, "gemini",
                "auto", "jpeg"
        );
    }

    public static ObserverProfile macroFlower() {
        return new ObserverProfile(
                "macro_flower", "macro_pollinator",
                200, 0.08, 0.02, RegionOfInterest.fullFrame(),
                5, true, 3,
                10, "08:00", "19:00", 300,
                null, "gemini",
                "manual", "raw+jpeg"
        );
    }

    public static ObserverProfile insectHotel() {
        return new ObserverProfile(
                "insect_hotel", "macro_pollinator",
                250, 0.08, 0.01, RegionOfInterest.fullFrame(),
                4, true, 3,
                10, "08:00", "20:00", 360,
                null, "gemini",
                "auto", "jpeg"
        );
    }

    public static ObserverProfile gardenWildlife() {
        return new ObserverProfile(
                "garden_wildlife", "wildlife",
                600, 0.12, 0.04, RegionOfInterest.fullFrame(),
                2, false, 0,
                45, "05:00", "22:00", 80,
                null, "gemini",
                "auto", "jpeg"
        );
    }
}
