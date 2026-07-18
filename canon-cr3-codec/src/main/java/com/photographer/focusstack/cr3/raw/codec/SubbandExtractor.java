package com.photographer.focusstack.cr3.raw.codec;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Lists and extracts CRX subband payloads for diagnostics and research. */
public final class SubbandExtractor {

    private SubbandExtractor() {}

    public record SubbandRef(
            int sequence,
            int tileSequence,
            int tileIndex,
            int planeIndex,
            int subbandIndex,
            int roundedBits,
            int quantValue,
            long dataOffset,
            long dataSize,
            int planeWidth,
            int planeHeight,
            int bitDepth,
            int waveletLevels,
            int encodingType,
            CrxHeader.CodecVersion codecVersion
    ) {}

    public static List<SubbandRef> list(File cr3) throws IOException, Cr3Exception {
        try (ImageInputStream in = ImageIO.createImageInputStream(cr3)) {
            if (in == null) throw new IOException("Cannot open " + cr3);
            Cr3Container container = Cr3Parser.parse(in);
            Cr3Track raw = container.tracks().stream()
                    .filter(track -> track.type() == Cr3Track.TrackType.CRX_BIG
                            || track.type() == Cr3Track.TrackType.CRX_RAW)
                    .findFirst()
                    .orElseThrow(() -> new Cr3Exception("No RAW track"));
            return list(in, raw, container);
        }
    }

    public static List<SubbandRef> list(ImageInputStream in, Cr3Track raw,
                                        Cr3Container container) throws IOException, Cr3Exception {
        byte[] data = readTrackBytes(in, raw);
        Cmp1Box cmp1 = raw.cmp1();
        if (cmp1 == null) {
            throw new Cr3Exception("RAW track has no CMP1 compression metadata");
        }

        int payloadBase = 0;
        try {
            int headerSize = CrxLosslessRawDecoder.resolveMdatHeaderSize(cmp1, data.length);
            if (headerSize > 0 && headerSize < data.length) payloadBase = headerSize;
        } catch (Cr3Exception ignored) {
            // The diagnostics layer can still try offset zero below.
        }

        String compressor = container.cncvBox() == null
                ? null : container.cncvBox().versionString();
        CrxHeader.CodecVersion version = CrxHeader.detectVersion(compressor);
        CrxHeader header;
        try {
            header = CrxHeader.parse(data, payloadBase, version);
        } catch (IOException first) {
            if (payloadBase == 0) throw first;
            payloadBase = 0;
            header = CrxHeader.parse(data, version);
        }

        int numTiles = Math.max(1, header.tiles().size());
        int planeWidth = (cmp1.imageWidth() + 1) / 2;
        int planeHeight = (cmp1.imageHeight() + 1) / 2;
        int nominalTilePlaneWidth = cmp1.tileWidth() > 0
                ? Math.max(1, (cmp1.tileWidth() + 1) / 2)
                : Math.max(1, (planeWidth + numTiles - 1) / numTiles);
        int tilePlaneHeight = cmp1.tileHeight() > 0
                ? Math.max(1, Math.min(planeHeight, (cmp1.tileHeight() + 1) / 2))
                : planeHeight;

        List<SubbandRef> result = new ArrayList<>();
        int sequence = 0;
        int tileSequence = 0;
        int tileX = 0;
        for (CrxHeader.Tile tile : header.tiles()) {
            int remaining = Math.max(0, planeWidth - tileX);
            int currentTileWidth = tileSequence == numTiles - 1
                    ? remaining
                    : Math.min(nominalTilePlaneWidth, remaining);
            if (currentTileWidth <= 0) currentTileWidth = nominalTilePlaneWidth;

            for (CrxHeader.Plane plane : tile.planes()) {
                for (CrxHeader.Subband subband : plane.subbands()) {
                    long offset = payloadBase + subband.dataOffset();
                    result.add(new SubbandRef(sequence++, tileSequence, tile.index(),
                            plane.index(), subband.index(), plane.roundedBits(),
                            subband.quantValue(), offset, subband.dataSize(),
                            currentTileWidth, tilePlaneHeight, cmp1.bitsPerSample(),
                            cmp1.waveletLevels(), cmp1.encodingType(), version));
                }
            }
            tileX += currentTileWidth;
            tileSequence++;
        }
        return List.copyOf(result);
    }

    public static byte[] read(File cr3, SubbandRef ref) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(cr3)) {
            if (in == null) throw new IOException("Cannot open " + cr3);
            if (ref.dataSize() > Integer.MAX_VALUE) {
                throw new IOException("Subband too large: " + ref.dataSize());
            }
            byte[] data = new byte[(int) ref.dataSize()];
            in.seek(ref.dataOffset());
            in.readFully(data);
            return data;
        }
    }

    public static void write(File cr3, SubbandRef ref, File output) throws IOException {
        Files.write(output.toPath(), read(cr3, ref));
    }

    private static byte[] readTrackBytes(ImageInputStream in, Cr3Track track) throws IOException {
        if (track.dataSize() > Integer.MAX_VALUE) {
            throw new IOException("RAW track too large: " + track.dataSize());
        }
        byte[] data = new byte[(int) track.dataSize()];
        in.seek(track.dataOffset());
        in.readFully(data);
        return data;
    }
}
