package com.photographer.focusstack.observer.capture;

import com.photographer.focusstack.observer.events.CapturedImage;

import java.util.List;

/**
 * Result of a capture series: all captured images and the selected best image.
 */
public record CaptureSeriesResult(
        List<CapturedImage> allImages,
        CapturedImage bestImage,
        String selectionReason
) {}
