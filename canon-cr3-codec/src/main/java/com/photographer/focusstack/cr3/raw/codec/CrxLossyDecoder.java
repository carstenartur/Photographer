package com.photographer.focusstack.cr3.raw.codec;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.raw.Cr3RawImage;

import java.io.IOException;
import java.util.Arrays;

/** Native decoder for three-level, 14-bit Canon CRX V1 C-RAW sensor tracks. */
public final class CrxLossyDecoder {
    // Measured with isolated signed coefficient pulses and pinned full-sensor references.
    private static final int[] INVERSE_QUANTIZATION = {
            0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3,
            4, 4, 5, 5, 6, 7, 8, 9, 10, 11, 12, 14, 16, 18, 20, 22,
            25, 28, 32, 36, 40, 45, 51, 57, 64
    };

    private CrxLossyDecoder() {}

    public static boolean isLossy(Cmp1Box cmp1) {
        return cmp1 != null && !cmp1.isLossless();
    }

    /** Reconstructs the four signed transform planes and clips only after the final inverse lift. */
    public static Cr3RawImage decode(byte[] data, int width, int height, int bitDepth,
                                     CrxHeader.CodecVersion version, Cmp1Box cmp) throws Cr3Exception, IOException {
        if (cmp == null) {
            throw new Cr3Exception("Cannot decode C-RAW: CMP1 box is missing");
        }
        if (cmp.isLossless()) {
            throw new Cr3Exception("Cannot decode C-RAW: CMP1 indicates lossless");
        }
        if (version != CrxHeader.CodecVersion.V1 || cmp.waveletLevels() != 3 || cmp.numPlanes() != 4
                || cmp.encodingType() != 0 || bitDepth != 14 || cmp.bitsPerSample() != bitDepth
                || cmp.cfaLayout() > 3 || cmp.hasExtendedHeader()) {
            throw new Cr3Exception("Unsupported C-RAW profile. File has %d wavelet levels, %d planes."
                    .formatted(cmp.waveletLevels(), cmp.numPlanes()));
        }
        int tileWidth = cmp.tileWidth();
        if (width != cmp.imageWidth() || height != cmp.imageHeight() || tileWidth < 16
                || height < 16 || (width & 1) != 0 || (height & 1) != 0 || (tileWidth & 1) != 0
                || width != 2L * tileWidth || height != cmp.tileHeight() || (long) width * height > Integer.MAX_VALUE) {
            throw new Cr3Exception("Unsupported C-RAW sensor or horizontal tile dimensions");
        }
        int headerSize = cmp.mdatHeaderSize();
        if (data == null || headerSize < 12 || headerSize >= data.length) {
            throw new Cr3Exception("Invalid CMP1 C-RAW header size");
        }
        CrxHeader header = CrxHeader.parse(Arrays.copyOf(data, headerSize), version);
        if (header.headerSize() != headerSize || header.tiles().size() != 2) {
            throw new Cr3Exception("C-RAW tile directory disagrees with CMP1");
        }
        long tileEnd = headerSize;
        for (var tile : header.tiles()) {
            long tileStart = tileEnd;
            int seenPlanes = 0;
            for (var plane : tile.planes()) {
                if (plane.index() > 3 || (seenPlanes & (1 << plane.index())) != 0
                        || plane.roundedBits() != 0 || plane.subbands().size() != 10) {
                    throw new Cr3Exception("Invalid C-RAW wavelet plane directory");
                }
                seenPlanes |= 1 << plane.index();
                long first = tileEnd;
                for (int b = 0; b < 10; b++) {
                    var band = plane.subbands().get(b);
                    if (band.index() != b || band.dataOffset() != tileEnd || band.supportsPartial()
                            || band.dataSize() <= band.paddingBytes() || band.dataSize() > data.length - tileEnd
                            || band.quantValue() >= INVERSE_QUANTIZATION.length) {
                        throw new Cr3Exception("Unsupported or inconsistent C-RAW subband " + b);
                    }
                    tileEnd += band.dataSize();
                }
                if (tileEnd - first != plane.dataSize()) {
                    throw new Cr3Exception("C-RAW subbands disagree with the plane extent");
                }
            }
            if (seenPlanes != 15 || tileEnd - tileStart != tile.dataSize()) {
                throw new Cr3Exception("C-RAW planes disagree with the tile extent");
            }
        }
        if (tileEnd != data.length) {
            throw new Cr3Exception("C-RAW tiles disagree with the track extent");
        }
        int[] mosaic = new int[width * height];
        for (int tile = 0; tile < 2; tile++) {
            boolean left = tile > 0, right = tile == 0;
            int cols = tileWidth / 2, rows = height / 2;
            var shapes = CrxWaveletTile.shapes(cols, rows, left, right);
            for (var plane : header.tiles().get(tile).planes()) {
                var bands = new CrxWaveletTile.Band[shapes.length];
                for (int b = 0; b < shapes.length; b++) {
                    var band = plane.subbands().get(b);
                    int start = Math.toIntExact(band.dataOffset());
                    int count = Math.toIntExact(band.dataSize()) - band.paddingBytes();
                    int bw = shapes[b].width(), bh = shapes[b].height();
                    int factor = INVERSE_QUANTIZATION[band.quantValue()];
                    int[] values;
                    if (b == 0) {
                        if (factor != 1) {
                            throw new Cr3Exception("Unsupported low-frequency C-RAW quantizer " + band.quantValue());
                        }
                        values = CrxNativeLosslessPlane.decodeCoefficients(data, start, count, bw, bh);
                    } else {
                        values = CrxWaveletDetail.decode(data, start, count, bw, bh, factor);
                    }
                    bands[b] = new CrxWaveletTile.Band(values, bw, bh);
                }
                int[] pixels;
                try {
                    pixels = CrxWaveletTile.synthesize(bands, cols, rows, left, right);
                } catch (ArithmeticException e) {
                    throw new Cr3Exception("C-RAW coefficient overflow during wavelet reconstruction", e);
                }
                int row0 = plane.index() / 2, col0 = tile * tileWidth + (plane.index() & 1);
                for (int y = 0; y < rows; y++) {
                    int dest = (2 * y + row0) * width + col0;
                    for (int x = 0; x < cols; x++) {
                        mosaic[dest + x * 2] = Math.clamp(pixels[y * cols + x], 0, 16383);
                    }
                }
            }
        }
        return new Cr3RawImage(width, height, mosaic, bitDepth, CrxLosslessRawDecoder.resolveBayerPattern(cmp));
    }
}
