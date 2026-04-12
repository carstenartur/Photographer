package com.photographer.focusstack.observer.events;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Immutable record representing a single wildlife observation event.
 * Built incrementally via {@link ObservationEvent.Builder}.
 */
public record ObservationEvent(
        String id,
        Instant timestamp,
        Instant completedAt,
        EventState state,
        String mode,
        String cameraId,
        String triggerReason,
        RegionOfInterest roi,
        List<CapturedImage> images,
        String bestImageId,
        VisionResultData aiResult,
        Map<String, String> metadata
) {

    /** Returns a new builder pre-populated with values from this event. */
    public Builder toBuilder() {
        return new Builder()
                .id(id).timestamp(timestamp).completedAt(completedAt)
                .state(state).mode(mode).cameraId(cameraId)
                .triggerReason(triggerReason).roi(roi)
                .images(images).bestImageId(bestImageId)
                .aiResult(aiResult).metadata(metadata);
    }

    /** Fluent builder for {@link ObservationEvent}. */
    public static final class Builder {
        private String id;
        private Instant timestamp;
        private Instant completedAt;
        private EventState state = EventState.IDLE;
        private String mode;
        private String cameraId;
        private String triggerReason;
        private RegionOfInterest roi;
        private List<CapturedImage> images = List.of();
        private String bestImageId;
        private VisionResultData aiResult;
        private Map<String, String> metadata = Map.of();

        public Builder id(String id)                       { this.id = id; return this; }
        public Builder timestamp(Instant ts)               { this.timestamp = ts; return this; }
        public Builder completedAt(Instant ts)             { this.completedAt = ts; return this; }
        public Builder state(EventState s)                 { this.state = s; return this; }
        public Builder mode(String mode)                   { this.mode = mode; return this; }
        public Builder cameraId(String cameraId)           { this.cameraId = cameraId; return this; }
        public Builder triggerReason(String reason)        { this.triggerReason = reason; return this; }
        public Builder roi(RegionOfInterest roi)           { this.roi = roi; return this; }
        public Builder images(List<CapturedImage> images)  { this.images = images != null ? List.copyOf(images) : List.of(); return this; }
        public Builder bestImageId(String id)              { this.bestImageId = id; return this; }
        public Builder aiResult(VisionResultData result)   { this.aiResult = result; return this; }
        public Builder metadata(Map<String, String> meta)  { this.metadata = meta != null ? Map.copyOf(meta) : Map.of(); return this; }

        public ObservationEvent build() {
            return new ObservationEvent(id, timestamp, completedAt, state, mode, cameraId,
                    triggerReason, roi, images, bestImageId, aiResult, metadata);
        }
    }
}
