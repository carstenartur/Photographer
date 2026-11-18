package com.photographer.focusstack.cr3.container;

import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.bmff.Iad1Box;

/**
 * Describes a CR3 track and the first sample range projected from its BMFF tables.
 * For the supported single-sample layout, the range covers the entire sample.
 * A CRX codec resolves its directory and subband offsets relative to this range.
 *
 * @param index zero-based track index in the movie
 * @param type track role resolved by the container parser
 * @param dataOffset absolute position of the first chunk in the input stream, or zero if unavailable
 * @param dataSize sample length in bytes from the fixed or first sample size, or zero if unavailable
 * @param width visual sample entry width in pixels, or zero if unavailable
 * @param height visual sample entry height in pixels, or zero if unavailable
 * @param codecType sample entry format identifier
 * @param compressionMode compression classification
 * @param cmp1 CRX compression parameters when present
 * @param iad1 sensor geometry when present
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

