package com.photographer.focusstack.cr3.raw.codec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Parser for the CRX tile/plane/subband header tree. */
public final class CrxHeader {

    public enum CodecVersion {
        V1(0xff01, 0xff02, 0xff03),
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

    public record Subband(int index, long dataSize, boolean supportsPartial,
                          int quantValue, long dataOffset) {}
    public record Plane(int index, long dataSize, boolean supportsPartial,
                        int roundedBits, List<Subband> subbands) {}
    public record Tile(int index, long dataSize, List<Plane> planes) {}

    private final List<Tile> tiles;
    private final int headerSize;

    private CrxHeader(List<Tile> tiles, int headerSize) {
        this.tiles = Collections.unmodifiableList(tiles);
        this.headerSize = headerSize;
    }

    public List<Tile> tiles() { return tiles; }
    public int headerSize() { return headerSize; }

    public static CrxHeader parse(byte[] data, CodecVersion version) throws IOException {
        return parse(data, 0, version);
    }

    public static CrxHeader parse(byte[] data, int offset, CodecVersion version)
            throws IOException {
        if (data.length - offset < 12) {
            throw new IOException("CRX data too short for header: " + (data.length - offset));
        }

        List<Tile> tiles = new ArrayList<>();
        int position = offset;

        while (position + 12 <= data.length) {
            int marker = readUnsignedShort(data, position);
            if (marker != version.tileMarker) break;

            int headerLength = readUnsignedShort(data, position + 2);
            long tileDataSize = readUnsignedInt(data, position + 4);
            int tileIndex = (data[position + 8] >> 4) & 0xF;
            position += 4 + headerLength;

            List<Plane> planes = new ArrayList<>();
            while (position + 12 <= data.length) {
                int planeMarker = readUnsignedShort(data, position);
                if (planeMarker != version.planeMarker) break;

                int planeHeaderLength = readUnsignedShort(data, position + 2);
                long planeDataSize = readUnsignedInt(data, position + 4);
                int packedPlane = data[position + 8] & 0xFF;
                int planeIndex = (packedPlane >> 4) & 0xF;
                boolean planePartial = (packedPlane & 0x08) != 0;
                int roundedBits = (packedPlane >> 1) & 0x3;
                position += 4 + planeHeaderLength;

                List<Subband> subbands = new ArrayList<>();
                while (position + 12 <= data.length) {
                    int subbandMarker = readUnsignedShort(data, position);
                    if (subbandMarker != version.subbandMarker) break;

                    int subbandHeaderLength = readUnsignedShort(data, position + 2);
                    long subbandDataSize = readUnsignedInt(data, position + 4);
                    int packedSubband = data[position + 8] & 0xFF;
                    int subbandIndex = (packedSubband >> 4) & 0xF;
                    boolean subbandPartial = (packedSubband & 0x08) != 0;
                    int quantValue = ((packedSubband & 0x07) << 5)
                            | ((data[position + 9] & 0xFF) >> 3);
                    position += 4 + subbandHeaderLength;

                    subbands.add(new Subband(subbandIndex, subbandDataSize,
                            subbandPartial, quantValue, 0));
                }

                planes.add(new Plane(planeIndex, planeDataSize, planePartial,
                        roundedBits, Collections.unmodifiableList(subbands)));
            }

            tiles.add(new Tile(tileIndex, tileDataSize,
                    Collections.unmodifiableList(planes)));
        }

        if (position + 4 <= data.length
                && data[position] == 0 && data[position + 1] == 0
                && data[position + 2] == 0 && data[position + 3] == 0) {
            position += 4;
        }

        int totalHeaderSize = position - offset;
        return new CrxHeader(resolveDataOffsets(tiles, totalHeaderSize), totalHeaderSize);
    }

    public static CodecVersion detectVersion(String cncvVersion) {
        if (cncvVersion != null
                && (cncvVersion.contains("CR3_002") || cncvVersion.contains("CR3_003"))) {
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
                for (Subband subband : plane.subbands()) {
                    resolvedSubbands.add(new Subband(subband.index(), subband.dataSize(),
                            subband.supportsPartial(), subband.quantValue(), currentOffset));
                    currentOffset += subband.dataSize();
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

    private static int readUnsignedShort(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static long readUnsignedInt(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF) << 24)
                | ((long) (data[offset + 1] & 0xFF) << 16)
                | ((long) (data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    @Override
    public String toString() {
        return "CrxHeader[tiles=%d, headerSize=%d]".formatted(tiles.size(), headerSize);
    }
}
