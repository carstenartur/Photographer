package com.photographer.focusstack.cr3.raw.codec;

import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reverse-engineering helper that extracts the raw entropy-coded bytes of a single
 * CRX subband from a CR3 file, together with structural metadata needed to interpret
 * those bytes (pixel grid, q-param, codec version, etc.).
 *
 * <p>This class is intentionally side-effect-free with respect to the production
 * decoder — it only reads the existing CRX header and slices bytes out of the
 * RAW track. It exists to support cleanroom investigation of the CRX entropy
 * codec, which is not described by any public textual specification.</p>
 *
 * <p>Typical usage:</p>
 * <pre>
 *   var subbands = SubbandExtractor.list(new File("sample.cr3"));
 *   var bytes = SubbandExtractor.read(new File("sample.cr3"), subbands.get(0));
 * </pre>
 */
public final class SubbandExtractor {

    private SubbandExtractor() {
        // utility class
    }

    /**
     * Description of one subband within a CR3 file's RAW track, with everything
     * a research harness needs to reason about its bitstream.
     *
     * @param tileIndex          index of the parent tile in the CRX header
     * @param tileSequence       0-based ordinal of the tile (distinguishes tiles when {@code tileIndex} is reused)
     * @param planeIndex         index of the parent plane (0..3 for RGGB)
     * @param subbandIndex       index of the subband (0 for lossless LL; 0..9 for lossy wavelets)
     * @param dataOffsetInFile   absolute byte offset of the subband data within the .cr3 file
     * @param dataSize           size in bytes of the subband data
     * @param quantValue         the {@code quantValue} field from the subband header
     * @param supportsPartial    the partial-decode flag from the subband header
     * @param roundedBits        the {@code roundedBits} field from the parent plane header
     * @param trackWidth         width in pixels of the RAW track
     * @param trackHeight        height in pixels of the RAW track
     * @param bitDepth           bit depth declared by CMP1 (typically 14)
     * @param codecVersion       which CRX codec marker variant the file uses
     * @param tileWidth          tile width in pixels (from CMP1; 0 if unknown)
     * @param tileHeight         tile height in pixels (from CMP1; 0 if unknown)
     * @param planeWidth         width in pixels of one plane within one tile (estimated from track and CMP1 data)
     * @param planeHeight        height in pixels of one plane within one tile (estimated from track and CMP1 data)
     * @param waveletLevels      number of wavelet decomposition levels declared by CMP1 (0 for lossless)
     * @param encodingType       {@code encodingType} field from CMP1
     * @param cncvVersion        full CNCV version string from the CR3 file (or {@code null})
     */
    public record SubbandRef(
            int tileIndex,
            int tileSequence,
            int planeIndex,
            int subbandIndex,
            long dataOffsetInFile,
            long dataSize,
            int quantValue,
            boolean supportsPartial,
            int roundedBits,
            int trackWidth,
            int trackHeight,
            int bitDepth,
            CrxHeader.CodecVersion codecVersion,
            int tileWidth,
            int tileHeight,
            int planeWidth,
            int planeHeight,
            int waveletLevels,
            int encodingType,
            String cncvVersion) {
    }

    /**
     * Lists every subband in the RAW (CRX_BIG or CRX_RAW) track of the given CR3 file.
     *
     * @param cr3File the CR3 file to inspect
     * @return an immutable list of subband descriptors in the order they appear in the bitstream
     * @throws IOException if the file cannot be read or parsed
     */
    public static List<SubbandRef> list(File cr3File) throws IOException, com.photographer.focusstack.cr3.Cr3Exception {
        try (ImageInputStream in = ImageIO.createImageInputStream(cr3File)) {
            if (in == null) {
                throw new IOException("Cannot open file: " + cr3File);
            }
            return list(in);
        }
    }

    /**
     * Lists every subband in the RAW track of the CR3 data exposed by the given input.
     * The input must remain open for the lifetime of the call. The returned offsets
     * are absolute byte positions within the underlying file/stream.
     *
     * @param input an opened ImageInputStream positioned anywhere in a CR3 file
     * @return an immutable list of subband descriptors
     * @throws IOException if parsing fails
     * @throws com.photographer.focusstack.cr3.Cr3Exception if the file is not a valid CR3
     */
    public static List<SubbandRef> list(ImageInputStream input) throws IOException, com.photographer.focusstack.cr3.Cr3Exception {
        Cr3Container container = Cr3Parser.parse(input);
        Cr3Track raw = findRawTrack(container);
        if (raw == null) {
            return Collections.emptyList();
        }

        CrxHeader.CodecVersion version = CrxHeader.CodecVersion.V1;
        if (container.cncvBox() != null) {
            version = CrxHeader.detectVersion(container.cncvBox().versionString());
        }
        String cncvVersion = container.cncvBox() == null ? null : container.cncvBox().versionString();

        byte[] crxBytes = new byte[(int) raw.dataSize()];
        input.seek(raw.dataOffset());
        input.readFully(crxBytes);

        CrxHeader header = CrxHeader.parse(crxBytes, version);

        Cmp1Box cmp1 = raw.cmp1();
        int bitDepth = (cmp1 != null && cmp1.bitsPerSample() > 0) ? cmp1.bitsPerSample() : 14;
        int tileW = (cmp1 != null && cmp1.tileWidth() > 0) ? cmp1.tileWidth() : 0;
        int tileH = (cmp1 != null && cmp1.tileHeight() > 0) ? cmp1.tileHeight() : 0;
        int waveletLevels = cmp1 == null ? 0 : cmp1.waveletLevels();
        int encodingType = cmp1 == null ? 0 : cmp1.encodingType();
        int trackWidth = raw.width();
        int trackHeight = raw.height();
        if (cmp1 != null && cmp1.imageWidth() > 0) {
            trackWidth = cmp1.imageWidth();
        }
        if (cmp1 != null && cmp1.imageHeight() > 0) {
            trackHeight = cmp1.imageHeight();
        }

        List<SubbandRef> result = new ArrayList<>();
        int tileSeq = 0;
        for (CrxHeader.Tile tile : header.tiles()) {
            int planeW = (tileW > 0) ? tileW / 2 : trackWidth / Math.max(1, header.tiles().size()) / 2;
            int planeH = (tileH > 0) ? tileH / 2 : trackHeight / 2;
            // For lossy with wavelet decomposition, the LL subband has dimensions
            // planeW >> waveletLevels × planeH >> waveletLevels, etc. We report
            // the un-decomposed plane grid here; harness code can refine.
            for (CrxHeader.Plane plane : tile.planes()) {
                for (CrxHeader.Subband sub : plane.subbands()) {
                    long absOffset = raw.dataOffset() + sub.dataOffset();
                    result.add(new SubbandRef(
                            tile.index(),
                            tileSeq,
                            plane.index(),
                            sub.index(),
                            absOffset,
                            sub.dataSize(),
                            sub.quantValue(),
                            sub.supportsPartial(),
                            plane.roundedBits(),
                            trackWidth,
                            trackHeight,
                            bitDepth,
                            version,
                            tileW,
                            tileH,
                            planeW,
                            planeH,
                            waveletLevels,
                            encodingType,
                            cncvVersion));
                }
            }
            tileSeq++;
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Reads the raw entropy-coded byte payload for a single subband.
     *
     * @param cr3File the CR3 file to read from
     * @param ref     a subband descriptor obtained from {@link #list(File)} for the same file
     * @return a freshly allocated byte array of length {@code ref.dataSize()}
     * @throws IOException if the file cannot be read
     */
    public static byte[] read(File cr3File, SubbandRef ref) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(cr3File)) {
            if (in == null) {
                throw new IOException("Cannot open file: " + cr3File);
            }
            return read(in, ref);
        }
    }

    /**
     * Reads the raw entropy-coded byte payload for a single subband from an open input.
     *
     * @param input an opened input that contains the same CR3 file the {@code ref} came from
     * @param ref   the subband descriptor
     * @return the subband bytes
     * @throws IOException on read failure
     */
    public static byte[] read(ImageInputStream input, SubbandRef ref) throws IOException {
        if (ref.dataSize() < 0 || ref.dataSize() > Integer.MAX_VALUE) {
            throw new IOException("Subband size out of range: " + ref.dataSize());
        }
        byte[] out = new byte[(int) ref.dataSize()];
        input.seek(ref.dataOffsetInFile());
        input.readFully(out);
        return out;
    }

    private static Cr3Track findRawTrack(Cr3Container container) {
        for (Cr3Track t : container.tracks()) {
            if (t.type() == Cr3Track.TrackType.CRX_BIG || t.type() == Cr3Track.TrackType.CRX_RAW) {
                return t;
            }
        }
        return null;
    }
}
