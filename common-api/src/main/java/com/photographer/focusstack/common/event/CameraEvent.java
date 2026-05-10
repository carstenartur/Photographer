package com.photographer.focusstack.common.event;

import com.photographer.focusstack.common.media.MediaItem;

import java.time.Instant;

/**
 * Vendor-neutral domain events emitted by the camera/transport layer.
 *
 * <p>The event hierarchy is intentionally sealed so that consumers (UI,
 * workflow services, audit logging) can pattern-match exhaustively.  Adding
 * a new event type is a single edit in this file and forces every consumer
 * to handle it.
 *
 * <p>Backends translate transport-specific signals (CCAPI event polling,
 * PTP {@code GetEvent}, WLAN reachability ping) into these records — UI code
 * never sees the originating wire format.
 */
public sealed interface CameraEvent {

    String cameraId();
    Instant timestamp();

    record ConnectionLost(String cameraId, Instant timestamp, String reason)            implements CameraEvent {}
    record DeviceBusy(String cameraId, Instant timestamp, String reason)                implements CameraEvent {}
    record StorageChanged(String cameraId, Instant timestamp)                           implements CameraEvent {}
    record NewMediaDetected(String cameraId, Instant timestamp, MediaItem item)         implements CameraEvent {}
    record CaptureCompleted(String cameraId, Instant timestamp, String captureId)       implements CameraEvent {}
    record ImportSkipped(String cameraId, Instant timestamp, MediaItem item, String reason) implements CameraEvent {}
    record ImportCompleted(String cameraId, Instant timestamp, MediaItem item, boolean verified) implements CameraEvent {}
    record DeleteCompleted(String cameraId, Instant timestamp, MediaItem item, boolean success)   implements CameraEvent {}
    record BatteryCritical(String cameraId, Instant timestamp, int percent)             implements CameraEvent {}
    record TemperatureWarning(String cameraId, Instant timestamp, String level)         implements CameraEvent {}
}
