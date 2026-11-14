package com.photographer.focusstack.cr3.raw.codec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parser for the CRX bitstream header structure found at the start of mdat track data.
 *
 * <p>The CRX bitstream is organized hierarchically:</p>
 * <ul>
 *   <li><b>Tile</b> (marker 0xff01/0xff11): One tile for small images,
 *       two tiles (left + right halves) for full-resolution images.</li>
 *   <li><b>Plane</b> (marker 0xff02/0xff12): Typically 4 planes (RGGB Bayer).</li>
 *   <li><b>Subband</b> (marker 0xff03/0xff13): 1 subband for lossless,
 *       10 subbands for lossy (3-level wavelet decomposition).</li>
 * </ul>
 *
 * <p>For native sensor tracks, CMP1 supplies the header length and the first subband starts
 * immediately after it. The parser also accepts a directory with four terminal zero bytes.</p>
 *
 * <p>This is a cleanroom implementation based on published ISO BMFF and
 * Canon CR3 format documentation. No proprietary source code was used.</p>
 */
public final class CrxHeader {

    /** Codec version determines marker bytes used in the bitstream. */
    public enum CodecVersion {
        /** CanonCR3_001: markers ff01, ff02, ff03. */
        V1(0xff01, 0xff02, 0xff03),
        /** CanonCR3_002+: markers ff11, ff12, ff13 (1DX Mark III, R5, etc.). */
        V2(0xff11, 0xff12, 0xff13);

        final int tileMarker;
        final int planeMarker;
        final int subbandMarker;

        CodecVersion(int tileMarker, int planeMarker, int subbandMarker) {
            this.tileMarker = tileMarker;
            this.planeMarker = planeMarker;
            this.subbandMarker = subbandMarker;
        }
    }

    /**
     * Describes a subband within a plane.
     *
     * @param index           subband index (0 for lossless LL; 0-9 for lossy wavelets)
     * @param dataSize        size of the encoded subband data in bytes
     * @param supportsPartial whether partial decoding is flagged
     * @param quantValue      quantization value (0 for lossless)
     * @param paddingBytes    number of terminal padding bytes declared in the subband record
     * @param dataOffset      absolute offset of the subband data within the CRX block
     */
    public record Subband(int index, long dataSize, boolean supportsPartial,
                          int quantValue, long dataOffset, int paddingBytes) {
        /** Compatibility constructor for callers describing subbands without terminal padding. */
        public Subband(int index, long dataSize, boolean supportsPartial, int quantValue, long dataOffset) {
            this(index, dataSize, supportsPartial, quantValue, dataOffset, 0);
        }
    }

    /**
     * Describes a colour plane within a tile.
     *
     * @param index           plane index (0-3 for RGGB)
     * @param dataSize        total encoded size for this plane
     * @param supportsPartial whether partial decoding is flagged
     * @param roundedBits     number of rounded bits
     * @param subbands        list of subbands in this plane
     */
    public record Plane(int index, long dataSize, boolean supportsPartial,
                        int roundedBits, List<Subband> subbands) {}

    /**
     * Describes a tile (spatial partition of the image).
     *
     * @param index    tile index (0 or 1; two tiles for big images)
     * @param dataSize total encoded size for this tile
     * @param planes   list of planes in this tile
     */
    public record Tile(int index, long dataSize, List<Plane> planes) {}

    private final List<Tile> tiles;
    private final int headerSize;

    private CrxHeader(List<Tile> tiles, int headerSize) {
        this.tiles = Collections.unmodifiableList(tiles);
        this.headerSize = headerSize;
    }

    /**
     * Returns the parsed tiles.
     *
     * @return the immutable parsed tile list
     */
    public List<Tile> tiles() {
        return tiles;
    }

    /**
     * Returns the total header size including any terminal padding supplied with the directory.
     *
     * @return the total header size in bytes
     */
    public int headerSize() {
        return headerSize;
    }

    /**
     * Parses a CRX header from the given data.
     *
     * @param data    the raw CRX track data (starting from the track's mdat offset)
     * @param version the codec version (determines marker bytes)
     * @return the parsed header
     * @throws IOException if the header is malformed
     */
    public static CrxHeader parse(byte[] data, CodecVersion version) throws IOException {
        return parse(data, 0, version);
    }

    /**
     * Parses a CRX header from the given data at the specified offset.
     *
     * @param data    the byte array containing the CRX data
     * @param offset  starting offset within the array
     * @param version the codec version
     * @return the parsed header
     * @throws IOException if the header is malformed
     */
    public static CrxHeader parse(byte[] data, int offset, CodecVersion version)
            throws IOException {
        return parse(new CrxTrackInput.Window(data, 0, data.length), offset, data.length, version);
    }

    /** Parses only CMP1's directory, without copying it or reading the adjacent entropy data. */
    static CrxHeader parseDirectory(byte[] track, int headerBytes, CodecVersion version) throws IOException {
        if (headerBytes < 0 || headerBytes > track.length) {
            throw new IOException("Invalid CMP1 directory length: " + headerBytes);
        }
        return parseDirectory(new CrxTrackInput.Window(track, 0, headerBytes), version);
    }

    static CrxHeader parseDirectory(CrxTrackInput.Window directory, CodecVersion version) throws IOException {
        int headerBytes = directory.length;
        CrxHeader header = parse(directory, 0, headerBytes, version);
        if (header.headerSize() != headerBytes) {
            throw new IOException("Incomplete CRX directory at byte " + header.headerSize());
        }
        return header;
    }

    private static CrxHeader parse(CrxTrackInput.Window data, int offset, int limit, CodecVersion version) throws IOException {
        if (offset < 0 || offset > limit - 12) {
            throw new IOException("CRX data too short for header at offset " + offset);
        }

        List<Tile> tiles = new ArrayList<>();
        int pos = offset;

        // Parse tiles
        while (pos <= limit - 12) {
            int marker = data.unsignedShort(pos);

            // Check for tile marker
            if (marker != version.tileMarker) {
                break;
            }

            int end = recordEnd(data, pos, limit);
            long tileDataSize = Integer.toUnsignedLong(data.word(pos + 4));
            int tileIndex = (data.unsignedByte(pos + 8) >> 4) & 0xF;
            pos = end;

            // Parse planes within this tile
            List<Plane> planes = new ArrayList<>();
            while (pos <= limit - 12) {
                int planeMarker = data.unsignedShort(pos);
                if (planeMarker != version.planeMarker) {
                    break;
                }

                int planeEnd = recordEnd(data, pos, limit);
                long planeDataSize = Integer.toUnsignedLong(data.word(pos + 4));
                int packedPlane = data.unsignedByte(pos + 8);
                int planeIndex = (packedPlane >> 4) & 0xF;
                boolean planePartial = (packedPlane & 0x08) != 0;
                int roundedBits = (packedPlane >> 1) & 0x3;
                pos = planeEnd;

                // Parse subbands within this plane
                List<Subband> subbands = new ArrayList<>();
                while (pos <= limit - 12) {
                    int subMarker = data.unsignedShort(pos);
                    if (subMarker != version.subbandMarker) {
                        break;
                    }

                    int subbandEnd = recordEnd(data, pos, limit);
                    long subDataSize = Integer.toUnsignedLong(data.word(pos + 4));
                    int packedSub = data.unsignedByte(pos + 8);
                    int subIndex = (packedSub >> 4) & 0xF;
                    boolean subPartial = (packedSub & 0x08) != 0;
                    // quantValue: 3 lower bits of byte 8 + 5 upper bits of byte 9
                    int quantValue = ((packedSub & 0x07) << 5) | (data.unsignedByte(pos + 9) >> 3);
                    int paddingBytes = data.unsignedShort(pos + 10);
                    pos = subbandEnd;

                    subbands.add(new Subband(subIndex, subDataSize, subPartial, quantValue, 0, paddingBytes));
                }

                planes.add(new Plane(planeIndex, planeDataSize, planePartial, roundedBits,
                        Collections.unmodifiableList(subbands)));
            }

            tiles.add(new Tile(tileIndex, tileDataSize, Collections.unmodifiableList(planes)));
        }

        // Some callers supply a directory with four terminal zero bytes.
        if (pos <= limit - 4 && data.word(pos) == 0) {
            pos += 4;
        }

        int headerTotalSize = pos - offset;

        // Second pass: calculate data offsets for each subband
        List<Tile> resolvedTiles = resolveDataOffsets(tiles, headerTotalSize);

        return new CrxHeader(resolvedTiles, headerTotalSize);
    }

    /**
     * Determines the codec version from the CNCV version string.
     *
     * @param cncvVersion the CNCV version string (for example, "CanonCR3_001/00.09.00/00.00.00")
     * @return the codec version
     */
    public static CodecVersion detectVersion(String cncvVersion) {
        if (cncvVersion != null && cncvVersion.contains("CR3_002")) {
            return CodecVersion.V2;
        }
        if (cncvVersion != null && cncvVersion.contains("CR3_003")) {
            return CodecVersion.V2;
        }
        return CodecVersion.V1;
    }

    private static List<Tile> resolveDataOffsets(List<Tile> tiles, int headerSize) {
        List<Tile> resolved = new ArrayList<>();
        long currentOffset = headerSize;

        for (Tile tile : tiles) {
            List<Plane> resolvedPlanes = new ArrayList<>();
            for (Plane plane : tile.planes()) {
                List<Subband> resolvedSubbands = new ArrayList<>();
                for (Subband sub : plane.subbands()) {
                    resolvedSubbands.add(new Subband(sub.index(), sub.dataSize(),
                            sub.supportsPartial(), sub.quantValue(), currentOffset, sub.paddingBytes()));
                    currentOffset += sub.dataSize();
                }
                resolvedPlanes.add(new Plane(plane.index(), plane.dataSize(),
                        plane.supportsPartial(), plane.roundedBits(),
                        Collections.unmodifiableList(resolvedSubbands)));
            }
            resolved.add(new Tile(tile.index(), tile.dataSize(),
                    Collections.unmodifiableList(resolvedPlanes)));
        }

        return resolved;
    }

    private static int recordEnd(CrxTrackInput.Window data, int pos, int limit) throws IOException {
        int bodyBytes = data.unsignedShort(pos + 2);
        if (bodyBytes < 8 || bodyBytes > limit - pos - 4) {
            throw new IOException("Invalid CRX record length " + bodyBytes + " at byte " + pos);
        }
        return pos + 4 + bodyBytes;
    }

    @Override
    public String toString() {
        return "CrxHeader[tiles=%d, headerSize=%d]".formatted(tiles.size(), headerSize);
    }
}
