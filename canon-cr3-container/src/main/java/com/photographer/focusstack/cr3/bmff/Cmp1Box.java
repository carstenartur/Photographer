package com.photographer.focusstack.cr3.bmff;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;

/**
 * Parsed CMP1 (Compression Parameters 1) box — critical for CRX codec.
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
        input.readShort();
        input.readShort();
        short version = input.readShort();
        input.readShort();
        int imageWidth = input.readInt();
        int imageHeight = input.readInt();
        int tileWidth = input.readInt();
        int tileHeight = input.readInt();
        int bitsPerSample = input.readByte() & 0xFF;
        int packedPlanesAndCfa = input.readByte() & 0xFF;
        int numPlanes = (packedPlanesAndCfa >> 4) & 0xF;
        int cfaLayout = packedPlanesAndCfa & 0xF;
        int packedEncodingAndWavelet = input.readByte() & 0xFF;
        int encodingType = (packedEncodingAndWavelet >> 4) & 0xF;
        int waveletLevels = packedEncodingAndWavelet & 0xF;
        int tileFlags = input.readByte() & 0xFF;
        boolean multipleHTiles = (tileFlags & 0x80) != 0;
        int mdatHeaderSize = input.readInt();
        int extHeaderByte = input.readByte() & 0xFF;
        boolean hasExtendedHeader = (extHeaderByte & 0x80) != 0;

        int extendedHeaderSize = 0;
        int medianBitDepth = 0;
        if (hasExtendedHeader && box.dataSize() > 52) {
            input.readByte();
            input.readShort();
            input.skipBytes(16);
            extendedHeaderSize = input.readInt();
            if (extendedHeaderSize > 0 && box.dataSize() > 56) {
                int extFlags = input.readByte() & 0xFF;
                boolean hasMedianBitDepth = (extFlags & MEDIAN_BIT_DEPTH_FLAG) != 0;
                input.readByte();
                input.readShort();
                if (box.dataSize() > 84) {
                    input.skipBytes(24);
                    if (hasMedianBitDepth && box.dataSize() > 84) {
                        medianBitDepth = input.readByte() & 0xFF;
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
