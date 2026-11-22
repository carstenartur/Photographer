package com.photographer.focusstack.cr3.raw.codec;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.bmff.Cmp1Box;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Reader for the measured single-tile, three-level CRX V2 C-RAW profile. */
final class CrxAdaptiveLossyDecoder {
    private static final int PLANE_COUNT = 4;
    private static final int[] GAINS = {0, 0, 0, 0, 4, 4, 8, 8, 8, 16};
    private static final int TILE_HEADER_BYTES = 20;
    private static final int PLANE_HEADER_BYTES = 12;
    private static final int BAND_HEADER_BYTES = 20;
    private static final int DIRECTORY_BYTES =
            TILE_HEADER_BYTES + PLANE_COUNT * (PLANE_HEADER_BYTES + GAINS.length * BAND_HEADER_BYTES);

    record Directory(CrxHeader header, int mapLength) {}

    private CrxAdaptiveLossyDecoder() {}

    static CrxSensorDecoder prepare(CrxTrackInput track, int width, int height, int depth, Cmp1Box cmp)
            throws IOException, Cr3Exception {
        if (cmp.version() != 0x200 || cmp.bitsPerSample() != 14 || depth != 14 || cmp.numPlanes() != PLANE_COUNT
                || cmp.waveletLevels() != 3 || cmp.encodingType() != 0 || cmp.cfaLayout() < 0
                || cmp.cfaLayout() > 3 || cmp.hasExtendedHeader() || cmp.multipleHTiles()
                || width < 16 || height < 16 || (width & 1) != 0 || (height & 1) != 0
                || width != cmp.imageWidth() || height != cmp.imageHeight()
                || width != cmp.tileWidth() || height != cmp.tileHeight()
                || (long) width * height > Integer.MAX_VALUE) {
            throw new Cr3Exception("Unsupported adaptive C-RAW profile");
        }
        var directory = readDirectory(track, cmp.mdatHeaderSize());
        CrxHeader header = directory.header();
        var map = CrxAdaptiveQuantization.read(new BitReader(track.window(header.headerSize(), directory.mapLength())),
                width, height);
        return new CrxTileDecoder(header, width, height, width, (tile, plane, columns, rows, consumer) ->
                decodePlane(track, map, plane, columns, height / 2, rows, consumer));
    }

    static Directory readDirectory(CrxTrackInput track, int headerBytes) throws IOException, Cr3Exception {
        if (track == null || (headerBytes != DIRECTORY_BYTES && headerBytes != DIRECTORY_BYTES + Integer.BYTES)
                || track.length <= headerBytes) {
            throw new Cr3Exception("Invalid adaptive C-RAW directory extent");
        }
        var directory = track.window(0, headerBytes);
        requireRecord(directory, 0, 0xff11, TILE_HEADER_BYTES);
        if (directory.word(4) != track.length - headerBytes || directory.word(8) != 0x4000
                || directory.unsignedShort(18) != 0
                || (headerBytes != DIRECTORY_BYTES && directory.word(DIRECTORY_BYTES) != 0)) {
            throw new Cr3Exception("Invalid adaptive C-RAW tile record");
        }
        int mapLength = directory.word(12);
        int mapPadding = directory.unsignedShort(16);
        if (mapLength < Integer.BYTES || mapPadding > 7 || mapLength > track.length - headerBytes - mapPadding) {
            throw new Cr3Exception("Invalid adaptive C-RAW quantization map extent");
        }
        int firstBand = headerBytes + mapLength + mapPadding;
        List<CrxHeader.Plane> planes = readPlanes(directory, firstBand, track.length);
        var header = new CrxHeader(List.of(new CrxHeader.Tile(0, track.length - headerBytes, planes)), headerBytes);
        return new Directory(header, mapLength);
    }

    private static List<CrxHeader.Plane> readPlanes(CrxTrackInput.Window directory, int firstBand, int trackLength)
            throws IOException, Cr3Exception {
        int pos = TILE_HEADER_BYTES;
        int bandOffset = firstBand;
        int seen = 0;
        var planes = new ArrayList<CrxHeader.Plane>(PLANE_COUNT);
        for (int i = 0; i < PLANE_COUNT; i++) {
            requireRecord(directory, pos, 0xff12, PLANE_HEADER_BYTES);
            int planeLength = directory.word(pos + 4);
            int planeFlags = directory.word(pos + 8);
            int plane = planeFlags >>> 28;
            if (plane >= PLANE_COUNT || (seen & (1 << plane)) != 0 || (planeFlags & 0x0fff_ffff) != 0x0800_0000) {
                throw new Cr3Exception("Invalid adaptive C-RAW plane record");
            }
            seen |= 1 << plane;
            pos += PLANE_HEADER_BYTES;
            int beforePlane = bandOffset;
            var bands = new ArrayList<CrxHeader.Subband>(GAINS.length);
            for (int band = 0; band < GAINS.length; band++) {
                requireRecord(directory, pos, 0xff13, BAND_HEADER_BYTES);
                int bandLength = directory.word(pos + 4);
                int flags = directory.unsignedShort(pos + 8);
                int gain = directory.unsignedShort(pos + 10);
                int base = directory.word(pos + 12);
                int padding = directory.unsignedShort(pos + 16);
                if (flags != band << 12 || gain != GAINS[band] || base != (gain == 0 ? 1 : 0)
                        || directory.unsignedShort(pos + 18) != 0 || padding > 7 || bandLength <= padding
                        || bandLength > trackLength - bandOffset) {
                    throw new Cr3Exception("Unsupported or inconsistent adaptive C-RAW subband " + band);
                }
                bands.add(new CrxHeader.Subband(band, bandLength, false, 0, bandOffset, padding));
                bandOffset += bandLength;
                pos += BAND_HEADER_BYTES;
            }
            if (bandOffset - beforePlane != planeLength) {
                throw new Cr3Exception("Adaptive C-RAW subbands disagree with their plane extent");
            }
            planes.add(new CrxHeader.Plane(plane, planeLength, true, 0, List.copyOf(bands)));
        }
        if (bandOffset != trackLength) {
            throw new Cr3Exception("Adaptive C-RAW tile does not fill its track extent");
        }
        return List.copyOf(planes);
    }

    private static void requireRecord(CrxTrackInput.Window directory, int offset, int marker, int bytes)
            throws IOException, Cr3Exception {
        if (directory.unsignedShort(offset) != marker
                || directory.unsignedShort(offset + Short.BYTES) != bytes - Integer.BYTES) {
            throw new Cr3Exception("Invalid adaptive C-RAW directory record at byte " + offset);
        }
    }

    private static void decodePlane(CrxTrackInput track, CrxAdaptiveQuantization map, CrxHeader.Plane plane,
                                    int columns, int height, int rows, CrxSensorDecoder.RowConsumer consumer)
            throws IOException, Cr3Exception {
        var shapes = CrxWaveletTile.shapes(columns, height, false, false);
        var bands = new CrxWaveletTile.Band[shapes.length];
        for (int index = 0; index < shapes.length; index++) {
            var band = plane.subbands().get(index);
            int count = Math.toIntExact(band.dataSize()) - band.paddingBytes();
            var bits = new BitReader(track.window(Math.toIntExact(band.dataOffset()), count,
                    CrxLossyDecoder.SUBBAND_READ_AHEAD));
            var shape = shapes[index];
            bands[index] = index == 0
                    ? CrxNativeLosslessPlane.approximation(bits, shape.width(), shape.height())
                    : map.expand(CrxWaveletDetail.band(bits, shape.width(), shape.height(), 1), index, GAINS[index]);
        }
        CrxLossyDecoder.writePlane(bands, columns, height, rows, plane.index() & 1, plane.index() / 2,
                false, false, map.boundedCoefficients(), consumer);
    }
}
