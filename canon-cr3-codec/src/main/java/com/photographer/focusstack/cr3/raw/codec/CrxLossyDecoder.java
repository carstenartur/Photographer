package com.photographer.focusstack.cr3.raw.codec;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.raw.Cr3RawImage;

import java.io.IOException;

/** Native decoder for three-level, 14-bit Canon CRX V1 C-RAW sensor tracks. */
public final class CrxLossyDecoder {
    // All ten subbands of a plane can have a live entropy window during synthesis.
    private static final int SUBBAND_READ_AHEAD = 16 * 1024;

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
        return CrxPlaneAssembler.decodeImage(prepare(data == null ? null : CrxTrackInput.fromArray(data), width, height, bitDepth, version, cmp),
                width, height, bitDepth, CrxLosslessRawDecoder.resolveBayerPattern(cmp));
    }

    static CrxSensorDecoder prepare(CrxTrackInput data, int width, int height, int bitDepth,
                                    CrxHeader.CodecVersion version, Cmp1Box cmp) throws Cr3Exception, IOException {
        if (cmp == null) {
            throw new Cr3Exception("Cannot decode C-RAW: CMP1 box is missing");
        }
        if (cmp.isLossless()) {
            throw new Cr3Exception("Cannot decode C-RAW: CMP1 indicates lossless");
        }
        if (version != CrxHeader.CodecVersion.V1 || cmp.waveletLevels() != 3 || cmp.numPlanes() != 4
                || cmp.encodingType() != 0 || bitDepth != 14 || cmp.bitsPerSample() != bitDepth
                || cmp.cfaLayout() < 0 || cmp.cfaLayout() > 3 || cmp.hasExtendedHeader()) {
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
        CrxHeader header;
        try {
            header = CrxHeader.parseDirectory(data.window(0, headerSize), version);
        } catch (IOException e) {
            throw new Cr3Exception("Invalid C-RAW tile directory: " + e.getMessage(), e);
        }
        if (header.tiles().size() != 2) {
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
                    if (b == 0 && INVERSE_QUANTIZATION[band.quantValue()] != 1) {
                        throw new Cr3Exception("Unsupported low-frequency C-RAW quantizer " + band.quantValue());
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
        return new CrxTileDecoder(header, width, height, tileWidth, (tile, plane, columns, rows, consumer) ->
                decodePlane(data, tile, plane, columns, height / 2, rows, consumer));
    }

    private static void decodePlane(CrxTrackInput data, int tile, CrxHeader.Plane plane, int cols, int rows,
                                    int rowsToRead,
                                    CrxSensorDecoder.RowConsumer consumer) throws IOException, Cr3Exception {
        boolean left = tile > 0, right = tile == 0;
        var shapes = CrxWaveletTile.shapes(cols, rows, left, right);
        var bands = new CrxWaveletTile.Band[shapes.length];
        boolean boundedCoefficients = true;
        for (int b = 0; b < shapes.length; b++) {
            var band = plane.subbands().get(b);
            int start = Math.toIntExact(band.dataOffset());
            int count = Math.toIntExact(band.dataSize()) - band.paddingBytes();
            int bw = shapes[b].width(), bh = shapes[b].height();
            int factor = INVERSE_QUANTIZATION[band.quantValue()];
            var bits = new BitReader(data.window(start, count, SUBBAND_READ_AHEAD));
            if (b == 0) {
                bands[b] = CrxNativeLosslessPlane.approximation(bits, bw, bh);
            } else {
                boundedCoefficients &= factor <= CrxWaveletTile.MAX_BOUNDED_DETAIL_FACTOR;
                bands[b] = CrxWaveletDetail.band(bits, bw, bh, factor);
            }
        }
        int x = tile * (2 * cols) + (plane.index() & 1);
        int y = plane.index() / 2;
        try {
            var planeRows = boundedCoefficients
                    ? CrxWaveletTile.synthesizeNative(bands, cols, rows, left, right)
                    : CrxWaveletTile.synthesize(bands, cols, rows, left, right);
            int[] row = new int[cols];
            for (int rowIndex = 0; rowIndex < rowsToRead; rowIndex++) {
                planeRows.rows().read(row);
                for (int i = 0; i < row.length; i++) {
                    row[i] = Math.clamp(row[i], 0, 16383);
                }
                consumer.write(x, y + 2 * rowIndex, row);
            }
        } catch (ArithmeticException e) {
            throw new Cr3Exception("C-RAW coefficient overflow during wavelet reconstruction", e);
        }
    }
}
