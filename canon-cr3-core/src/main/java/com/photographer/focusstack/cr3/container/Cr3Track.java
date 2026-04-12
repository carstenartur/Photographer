package com.photographer.focusstack.cr3.container;

/**
 * Represents a single track within a CR3 file.
 *
 * <p>CR3 files typically contain 3-4 tracks:</p>
 * <ul>
 *   <li>Track 1 (JPEG thumbnail): small preview, ~160×120 pixels</li>
 *   <li>Track 2 (JPEG preview): main preview, ~1620×1080 pixels</li>
 *   <li>Track 3 (CRX RAW): lossless RAW sensor data, full resolution</li>
 *   <li>Track 4 (CRX HDR): optional HDR RAW data</li>
 * </ul>
 *
 * @param index       zero-based track index
 * @param type        the track type
 * @param dataOffset  absolute offset of the track's data within the file
 * @param dataSize    size of the track's data in bytes
 * @param width       image width in pixels (0 if unknown)
 * @param height      image height in pixels (0 if unknown)
 * @param codecType   codec four-character code (e.g. "CRAW")
 */
public record Cr3Track(
        int index,
        TrackType type,
        long dataOffset,
        long dataSize,
        int width,
        int height,
        String codecType
) {

    /**
     * Enumeration of CR3 track types.
     */
    public enum TrackType {
        /** Small JPEG thumbnail. */
        JPEG_THUMBNAIL,
        /** Main JPEG preview image. */
        JPEG_PREVIEW,
        /** Lossless CRX RAW data. */
        CRX_RAW,
        /** Lossless CRX HDR RAW data. */
        CRX_HDR_RAW,
        /** Unknown track type. */
        UNKNOWN
    }

    @Override
    public String toString() {
        return "Cr3Track[index=%d, type=%s, offset=%d, size=%d, %dx%d, codec=%s]"
                .formatted(index, type, dataOffset, dataSize, width, height, codecType);
    }
}
