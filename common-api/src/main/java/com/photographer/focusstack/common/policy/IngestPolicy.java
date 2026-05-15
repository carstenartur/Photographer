package com.photographer.focusstack.common.policy;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.photographer.focusstack.common.media.MediaType;

import java.util.Set;

/**
 * Controls how new media is detected, downloaded and verified, and which
 * {@link DeletePolicy} applies after a successful import.
 *
 * <p>This is the single object passed to {@code MediaIngestService.ingest(...)}
 * — there are no global "auto-delete" flags hidden in service classes.
 *
 * @param verifyChecksum if {@code true}, compute a checksum after download and
 *                       require it to match a server-side hash (when available)
 *                       before the item is marked verified.
 * @param requireSizeMatch fail the transfer if the local size doesn't match
 *                         the camera-reported size.
 * @param mediaTypes which media types to ingest; {@code null}/empty = all.
 * @param deletePolicy what to do after a successful import.
 */
public record IngestPolicy(
        @JsonProperty("verifyChecksum") boolean verifyChecksum,
        @JsonProperty("requireSizeMatch") boolean requireSizeMatch,
        @JsonProperty("mediaTypes") Set<MediaType> mediaTypes,
        @JsonProperty("deletePolicy") DeletePolicy deletePolicy
) {

    public IngestPolicy {
        if (deletePolicy == null) deletePolicy = DeletePolicy.NEVER;
        mediaTypes = (mediaTypes == null || mediaTypes.isEmpty()) ? Set.of() : Set.copyOf(mediaTypes);
    }

    /** Conservative default: verify size, never delete. */
    public static IngestPolicy safeDefault() {
        return new IngestPolicy(false, true, Set.of(), DeletePolicy.NEVER);
    }

    public boolean acceptsType(MediaType type) {
        return mediaTypes.isEmpty() || mediaTypes.contains(type);
    }
}
