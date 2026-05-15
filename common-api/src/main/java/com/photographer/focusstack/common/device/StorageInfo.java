package com.photographer.focusstack.common.device;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Snapshot of every storage medium currently visible on the camera.
 *
 * <p>For Canon CCAPI this maps to {@code /devicestatus/storage}; for PTP/USB
 * cameras it is derived from the {@code GetStorageIDs} +
 * {@code GetStorageInfo} sequence. The model is intentionally vendor-neutral
 * so the UI can render storage status uniformly regardless of transport.
 */
public record StorageInfo(@JsonProperty("volumes") List<Volume> volumes) {

    public StorageInfo {
        volumes = volumes == null ? List.of() : List.copyOf(volumes);
    }

    public static StorageInfo empty() {
        return new StorageInfo(List.of());
    }

    /** Returns the total free space (bytes) summed across all volumes, or -1 if unknown. */
    public long totalFreeBytes() {
        long sum = 0;
        boolean anyKnown = false;
        for (Volume v : volumes) {
            if (v.freeBytes() >= 0) {
                sum += v.freeBytes();
                anyKnown = true;
            }
        }
        return anyKnown ? sum : -1L;
    }

    public record Volume(
            @JsonProperty("name") String name,
            @JsonProperty("path") String path,
            @JsonProperty("totalBytes") long totalBytes,
            @JsonProperty("freeBytes") long freeBytes,
            @JsonProperty("contentCount") int contentCount,
            @JsonProperty("accessibility") String accessibility
    ) {
        /** Use {@code -1} for any unknown numeric field. */
        public Volume {
            if (totalBytes < -1) totalBytes = -1;
            if (freeBytes  < -1) freeBytes  = -1;
            if (contentCount < -1) contentCount = -1;
        }
    }
}
