package com.photographer.focusstack.cr3.container;

import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.bmff.Iad1Box;

/** Represents a single track within a CR3 file. */
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
        JPEG_THUMBNAIL,
        JPEG_PREVIEW,
        JPEG_BIG,
        CRX_SMALL,
        CRX_BIG,
        CRX_DUAL_PIXEL,
        METADATA,
        HEVC_HDR,
        CRX_RAW,
        CRX_HDR_RAW,
        UNKNOWN
    }

    @Override
    public String toString() {
        return "Cr3Track[index=%d, type=%s, offset=%d, size=%d, %dx%d, codec=%s, compression=%s]"
                .formatted(index, type, dataOffset, dataSize, width, height, codecType, compressionMode);
    }
}
