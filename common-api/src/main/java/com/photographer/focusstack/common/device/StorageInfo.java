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

    /**
     * Returns the total free space summed across every volume with a known value.
     *
     * @return {@code -1} if no volume reports free space, or
     *         {@link Long#MAX_VALUE} if the mathematical sum exceeds a signed
     *         64-bit byte count
     */
    public long totalFreeBytes() {
        long sum = 0L;
        boolean anyKnown = false;
        for (Volume volume : volumes) {
            if (volume.freeBytes() >= 0L) {
                anyKnown = true;
                try {
                    sum = Math.addExact(sum, volume.freeBytes());
                } catch (ArithmeticException overflow) {
                    return Long.MAX_VALUE;
                }
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
            if (totalBytes < -1L) totalBytes = -1L;
            if (freeBytes < -1L) freeBytes = -1L;
            if (contentCount < -1) contentCount = -1;
        }
    }
}
