package com.photographer.focusstack.observer.bird;

import com.photographer.focusstack.observer.events.RegionOfInterest;

/** Configuration for local, generic bird detection. Areas are relative to the selected region. */
public record BirdTriggerSettings(RegionOfInterest roi, double minConfidence, double minArea,
                                  int intervalMs, int repeatSeconds, int absenceSeconds) {
    public BirdTriggerSettings {
        if (roi == null || !Double.isFinite(roi.x()) || !Double.isFinite(roi.y())
                || !Double.isFinite(roi.width()) || !Double.isFinite(roi.height())
                || roi.x() < 0 || roi.y() < 0 || roi.width() <= 0 || roi.height() <= 0
                || roi.x() + roi.width() > 1.000000001 || roi.y() + roi.height() > 1.000000001) {
            throw new IllegalArgumentException("The observation region must be a finite rectangle inside the image");
        }
        if (!Double.isFinite(minConfidence) || minConfidence < .1 || minConfidence > 1) {
            throw new IllegalArgumentException("Minimum confidence must be between 0.1 and 1");
        }
        if (!Double.isFinite(minArea) || minArea < 0 || minArea > 1) {
            throw new IllegalArgumentException("Minimum area must be between 0 and 1");
        }
        if (intervalMs < 200 || intervalMs > 5000 || repeatSeconds < 0 || repeatSeconds > 3600
                || absenceSeconds < 1 || absenceSeconds > 60) {
            throw new IllegalArgumentException("Invalid analysis, repeat or absence interval");
        }
    }

    public static BirdTriggerSettings defaults() {
        return new BirdTriggerSettings(new RegionOfInterest(0, 0, 1, 1, "observation"),
                .5, .0025, 500, 5, 3);
    }
}
