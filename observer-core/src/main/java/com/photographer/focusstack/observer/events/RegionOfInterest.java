package com.photographer.focusstack.observer.events;

/**
 * Region of interest within a camera frame. All values are relative (0.0–1.0).
 */
public record RegionOfInterest(
        double x,
        double y,
        double width,
        double height,
        String label
) {
    /** Full-frame region. */
    public static RegionOfInterest fullFrame() {
        return new RegionOfInterest(0.0, 0.0, 1.0, 1.0, "full");
    }

    /** Validate that values are within bounds. */
    public RegionOfInterest {
        if (x < 0 || x > 1 || y < 0 || y > 1 || width <= 0 || width > 1 || height <= 0 || height > 1) {
            throw new IllegalArgumentException(
                    "ROI values must be in [0,1] with width/height > 0: x=%s y=%s w=%s h=%s"
                            .formatted(x, y, width, height));
        }
    }
}
