package com.photographer.focusstack.observer.events;

/**
 * An axis-aligned bounding box from AI object detection.
 * All coordinate values are relative to the frame (0.0–1.0).
 */
public record BoundingBox(
        double x,
        double y,
        double width,
        double height,
        String label,
        double confidence
) {}
