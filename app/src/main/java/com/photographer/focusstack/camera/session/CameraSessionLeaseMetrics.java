package com.photographer.focusstack.camera.session;

/** Aggregate metrics deliberately contain no camera or owner identity labels. */
public record CameraSessionLeaseMetrics(
        int activeLeases,
        long conflicts,
        long staleRecoveries) {
}
