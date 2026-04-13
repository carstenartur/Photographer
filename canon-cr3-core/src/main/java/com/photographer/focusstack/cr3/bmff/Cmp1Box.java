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
        boolean hasExtendedHeader
) {
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

        return new Cmp1Box(version, imageWidth, imageHeight, tileWidth, tileHeight,
                bitsPerSample, numPlanes, cfaLayout, encodingType, waveletLevels,
                multipleHTiles, mdatHeaderSize, hasExtendedHeader);
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
