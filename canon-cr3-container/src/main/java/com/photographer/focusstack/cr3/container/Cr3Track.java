package com.photographer.focusstack.cr3.container;

import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.bmff.Iad1Box;

/**
 * Represents a single track within a CR3 file.
 */
public record Cr3Track(
        int index,
        TrackType type,
        long dataOffset,
        long dataSize,
        int width,
        int height,
        String codecType,
        CompressionMode compressionMode,
        Cmp1Box cmp1,
        Iad1Box iad1
) {

    /** Backwards-compatible constructor without cmp1/iad1 (defaults to null). */
    public Cr3Track(int index, TrackType type, long dataOffset, long dataSize,
                    int width, int height, String codecType, CompressionMode compressionMode) {
        this(index, type, dataOffset, dataSize, width, height, codecType, compressionMode, null, null);
    }

    /** Backwards-compatible constructor without compression mode (defaults to UNKNOWN). */
    public Cr3Track(int index, TrackType type, long dataOffset, long dataSize,
                    int width, int height, String codecType) {
        this(index, type, dataOffset, dataSize, width, height, codecType, CompressionMode.UNKNOWN, null, null);
    }

    public enum TrackType {
        /** Small JPEG thumbnail. */
        JPEG_THUMBNAIL,
        /** Main JPEG preview image. */
        JPEG_PREVIEW,
        /** Full-size JPEG track (main JPEG image). */
        JPEG_BIG,
        /** Small CRX RAW preview track. */
        CRX_SMALL,
        /** Full-resolution CRX RAW track. */
        CRX_BIG,
        /** Dual-pixel CRX RAW delta track. */
        CRX_DUAL_PIXEL,
        /** Canon Timed Metadata track. */
        METADATA,
        /** HEVC/HDR track (HDR CR3 files). */
        HEVC_HDR,
        /** Lossless CRX RAW data. */
        CRX_RAW,
        /** Lossless CRX HDR RAW data. */
        CRX_HDR_RAW,
        /** Unknown track type. */
        UNKNOWN
    }

    @Override
    public String toString() {
        return "Cr3Track[index=%d, type=%s, offset=%d, size=%d, %dx%d, codec=%s, compression=%s]"
                .formatted(index, type, dataOffset, dataSize, width, height, codecType, compressionMode);
    }
}

