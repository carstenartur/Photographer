package com.photographer.focusstack.cr3.raw.codec;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.raw.Cr3RawImage;

import java.io.IOException;

/** Native 14-bit CRX V1 lossless tiles with four positional Bayer planes. */
final class CrxNativeLosslessImage {
    private CrxNativeLosslessImage() {}

    static Cr3RawImage decode(byte[] track, int width, int height, int depth,
                              CrxHeader.CodecVersion version, Cmp1Box cmp,
                              Cr3RawImage.BayerPattern pattern) throws Cr3Exception {
        try {
            return CrxPlaneAssembler.decodeImage(prepare(track == null ? null : CrxTrackInput.fromArray(track), width, height, depth, version, cmp),
                    width, height, depth, pattern);
        } catch (IOException e) {
            throw invalidPayload(e);
        }
    }

    static CrxSensorDecoder prepare(CrxTrackInput track, int width, int height, int depth,
                                    CrxHeader.CodecVersion version, Cmp1Box cmp) throws Cr3Exception {
        if (cmp == null || version != CrxHeader.CodecVersion.V1 || !cmp.isLossless()
                || cmp.encodingType() != 0 || cmp.numPlanes() != 4 || depth != 14
                || cmp.bitsPerSample() != depth || cmp.cfaLayout() < 0 || cmp.cfaLayout() > 3
                || cmp.hasExtendedHeader()) {
            throw new Cr3Exception("Unsupported native lossless CRX layout: " + cmp);
        }
        int tileWidth = cmp.tileWidth();
        if (width <= 0 || height <= 0 || width != cmp.imageWidth() || height != cmp.imageHeight()
                || (width & 1) != 0 || (height & 1) != 0 || (long) width * height > Integer.MAX_VALUE
                || tileWidth <= 0 || tileWidth > width || (tileWidth & 1) != 0 || cmp.tileHeight() != height) {
            throw new Cr3Exception("Unsupported lossless CRX tile geometry");
        }
        int headerBytes = cmp.mdatHeaderSize();
        if (track == null || headerBytes < 12 || headerBytes >= track.length) {
            throw new Cr3Exception("Invalid CMP1 lossless header length: " + headerBytes);
        }
        // CMP1 sizes the marker records. The zero bytes that follow belong to the first plane.
        CrxHeader header;
        try {
            header = CrxHeader.parseDirectory(track.window(0, headerBytes), version);
        } catch (IOException e) {
            throw invalidPayload(e);
        }
        if (header.tiles().size() != Math.ceilDiv(width, tileWidth)) {
            throw new Cr3Exception("Lossless CRX tile directory disagrees with CMP1");
        }
        long end = headerBytes;
        for (var tile : header.tiles()) {
            if (tile.planes().size() != 4) {
                throw new Cr3Exception("Expected four lossless Bayer planes per tile");
            }
            long tileStart = end;
            int mask = 0;
            for (var plane : tile.planes()) {
                int index = plane.index();
                if (index < 0 || index > 3 || (mask & (1 << index)) != 0 || plane.roundedBits() != 0
                        || !plane.supportsPartial() || plane.subbands().size() != 1) {
                    throw new Cr3Exception("Unsupported or duplicate lossless CRX plane " + index);
                }
                mask |= 1 << index;
                var band = plane.subbands().getFirst();
                if (band.index() != 0 || band.dataSize() < 8 || band.dataSize() != plane.dataSize()
                        || band.dataOffset() != end || band.dataSize() > track.length - end) {
                    throw new Cr3Exception("Invalid lossless CRX subband extent for plane " + index);
                }
                end += band.dataSize();
            }
            if (end - tileStart != tile.dataSize()) {
                throw new Cr3Exception("Lossless CRX tile size disagrees with plane sizes");
            }
        }
        if (end != track.length) {
            throw new Cr3Exception("Lossless CRX tile sizes disagree with track length");
        }
        return consumer -> {
            try {
                decodeTiles(track, width, height, tileWidth, header, consumer);
            } catch (IOException e) {
                throw invalidPayload(e);
            }
        };
    }

    private static void decodeTiles(CrxTrackInput track, int width, int height, int tileWidth,
                                    CrxHeader header, CrxSensorDecoder.RowConsumer consumer) throws IOException {
        int tileX = 0;
        for (var tile : header.tiles()) {
            int extent = Math.min(tileWidth, width - tileX);
            for (var plane : tile.planes()) {
                var band = plane.subbands().getFirst();
                int x = tileX + (plane.index() & 1);
                int y = plane.index() / 2;
                var bits = new BitReader(track.window((int) band.dataOffset(), (int) band.dataSize()));
                CrxNativeLosslessPlane.decodeSensor(bits,
                        extent / 2, height / 2, x, y, consumer);
            }
            tileX += extent;
        }
    }

    private static Cr3Exception invalidPayload(IOException cause) {
        return new Cr3Exception("Invalid lossless CRX payload: " + cause.getMessage(), cause);
    }
}
