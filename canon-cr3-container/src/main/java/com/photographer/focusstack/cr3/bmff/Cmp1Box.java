package com.photographer.focusstack.cr3.bmff;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;

/**
 * CRX compression parameters from the CMP1 child of a CRAW sample entry.
 *
 * <p>Image and tile dimensions count sites in the Bayer mosaic. A four-plane tile
 * has a grid at half the tile width and height for each position in the CFA pattern.
 * Native decoding checks the measured profile against these fields before reading
 * its entropy streams.</p>
 *
 * @param version version field stored in CMP1
 * @param imageWidth full mosaic width in sensor sites
 * @param imageHeight full mosaic height in sensor sites
 * @param tileWidth nominal tile width in sensor sites
 * @param tileHeight nominal tile height in sensor sites
 * @param bitsPerSample native sensor sample depth
 * @param numPlanes number of encoded planes per tile
 * @param cfaLayout code for the color at each position in the 2 by 2 CFA grid
 * @param encodingType codec encoding type
 * @param waveletLevels number of wavelet levels, zero for the lossless layout
 * @param multipleHTiles multiple-horizontal-tiles flag
 * @param mdatHeaderSize byte count from the start of the CRX sample to the first entropy stream
 * @param hasExtendedHeader whether the CMP1 extension flag is set
 * @param extendedHeaderSize declared extension size when read
 * @param medianBitDepth median bit depth from the extension when read
 */
public record Cmp1Box(
        short version,
        int imageWidth,
        int imageHeight,
        int tileWidth,
        int tileHeight,
        int bitsPerSample,
        int numPlanes,
        int cfaLayout,
        int encodingType,
        int waveletLevels,
        boolean multipleHTiles,
        int mdatHeaderSize,
        boolean hasExtendedHeader,
        int extendedHeaderSize,
        int medianBitDepth
) {
    /** Extended header flag bit indicating median bit depth is present. */
    private static final int MEDIAN_BIT_DEPTH_FLAG = 0x40;
    /** Backwards-compatible constructor without extended header details. */
    public Cmp1Box(short version, int imageWidth, int imageHeight, int tileWidth,
                   int tileHeight, int bitsPerSample, int numPlanes, int cfaLayout,
                   int encodingType, int waveletLevels, boolean multipleHTiles,
                   int mdatHeaderSize, boolean hasExtendedHeader) {
        this(version, imageWidth, imageHeight, tileWidth, tileHeight,
                bitsPerSample, numPlanes, cfaLayout, encodingType, waveletLevels,
                multipleHTiles, mdatHeaderSize, hasExtendedHeader, 0, 0);
    }

    public static Cmp1Box parse(ImageInputStream input, IsoBox box) throws IOException {
        if (!"CMP1".equals(box.type())) {
            throw new IllegalArgumentException("Expected CMP1 box, got: " + box.type());
        }
        input.seek(box.dataOffset());
        input.readShort();                               // +0: unknown
        input.readShort();                               // +2: headerSize
        short version = input.readShort();               // +4
        input.readShort();                               // +6: padding
        int imageWidth = input.readInt();                // +8
        int imageHeight = input.readInt();               // +12
        int tileWidth = input.readInt();                 // +16
        int tileHeight = input.readInt();                // +20
        int bitsPerSample = input.readByte() & 0xFF;    // +24
        int packedPlanesAndCfa = input.readByte() & 0xFF; // +25
        int numPlanes = (packedPlanesAndCfa >> 4) & 0xF;
        int cfaLayout = packedPlanesAndCfa & 0xF;
        int packedEncodingAndWavelet = input.readByte() & 0xFF; // +26
        int encodingType = (packedEncodingAndWavelet >> 4) & 0xF;
        int waveletLevels = packedEncodingAndWavelet & 0xF;
        int tileFlags = input.readByte() & 0xFF;        // +27
        boolean multipleHTiles = (tileFlags & 0x80) != 0;
        int mdatHeaderSize = input.readInt();            // +28
        int extHeaderByte = input.readByte() & 0xFF;    // +32
        boolean hasExtendedHeader = (extHeaderByte & 0x80) != 0;

        // Parse extended header if present
        int extendedHeaderSize = 0;
        int medianBitDepth = 0;
        if (hasExtendedHeader && box.dataSize() > 52) {
            // Skip bytes 33-35 (3 bytes padding/unknown in base header)
            input.readByte(); // +33
            input.readShort(); // +34-35
            // Skip plane-count entries at +36..+51 (4 × 4 bytes)
            input.skipBytes(16);
            // +52: extended header size
            extendedHeaderSize = input.readInt();
            if (extendedHeaderSize > 0 && box.dataSize() > 56) {
                int extFlags = input.readByte() & 0xFF; // +56
                // Bit 6 (0x40) indicates median bit depth is present in the extended header
                boolean hasMedianBitDepth = (extFlags & MEDIAN_BIT_DEPTH_FLAG) != 0;
                // Skip remaining extended header fields
                input.readByte(); // +57
                input.readShort(); // +58-59
                if (box.dataSize() > 84) {
                    input.skipBytes(24); // 6 × int at +60..+83
                    if (hasMedianBitDepth && box.dataSize() > 84) {
                        medianBitDepth = input.readByte() & 0xFF; // +84
                    }
                }
            }
        }

        return new Cmp1Box(version, imageWidth, imageHeight, tileWidth, tileHeight,
                bitsPerSample, numPlanes, cfaLayout, encodingType, waveletLevels,
                multipleHTiles, mdatHeaderSize, hasExtendedHeader,
                extendedHeaderSize, medianBitDepth);
    }

    /** Returns true if the image is lossless RAW (waveletLevels == 0). */
    public boolean isLossless() {
        return waveletLevels == 0;
    }

    /** Returns the CFA layout name. */
    public String cfaLayoutName() {
        return switch (cfaLayout) {
            case 0 -> "RGGB";
            case 1 -> "GRBG";
            case 2 -> "GBRG";
            case 3 -> "BGGR";
            default -> "UNKNOWN";
        };
    }
}
