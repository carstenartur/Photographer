package com.photographer.focusstack.cr3.container;

import com.photographer.focusstack.cr3.Cr3FormatException;
import com.photographer.focusstack.cr3.bmff.FtypBox;
import com.photographer.focusstack.cr3.bmff.IsoBox;
import com.photographer.focusstack.cr3.bmff.IsoBmffReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a Canon CR3 file into a {@link Cr3Container} structure.
 *
 * <p>The parser reads the ISO BMFF box hierarchy, validates the ftyp box,
 * and extracts track information from the moov box.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * try (ImageInputStream input = ImageIO.createImageInputStream(new File("photo.cr3"))) {
 *     Cr3Container container = Cr3Parser.parse(input);
 *     System.out.println("Tracks: " + container.trackCount());
 * }
 * }</pre>
 */
public final class Cr3Parser {

    private static final Logger LOG = LoggerFactory.getLogger(Cr3Parser.class);

    private Cr3Parser() {
        // utility class
    }

    /**
     * Parses a CR3 file from the given input stream.
     *
     * @param input the image input stream positioned at the start of the file
     * @return the parsed CR3 container
     * @throws Cr3FormatException if the file is not a valid CR3 file
     * @throws IOException        if an I/O error occurs
     */
    public static Cr3Container parse(ImageInputStream input) throws Cr3FormatException, IOException {
        input.setByteOrder(ByteOrder.BIG_ENDIAN);

        // Read all top-level boxes
        List<IsoBox> boxes = IsoBmffReader.readBoxes(input);
        if (boxes.isEmpty()) {
            throw new Cr3FormatException("No boxes found in file");
        }

        LOG.info("Parsed {} top-level boxes", boxes.size());
        for (IsoBox box : boxes) {
            LOG.debug("  {}", box);
        }

        // Validate ftyp box
        IsoBox ftypIsoBox = findBox(boxes, "ftyp");
        if (ftypIsoBox == null) {
            throw new Cr3FormatException("Missing ftyp box — not a valid BMFF file");
        }

        FtypBox ftypBox = FtypBox.parse(input, ftypIsoBox);
        if (!ftypBox.isCr3()) {
            throw new Cr3FormatException(
                    "Not a CR3 file: major brand is '%s'".formatted(ftypBox.majorBrand()));
        }
        LOG.info("CR3 file identified: brand={}, compatible={}", ftypBox.majorBrand(), ftypBox.compatibleBrands());

        // Find required boxes
        IsoBox moovBox = findBox(boxes, "moov");
        if (moovBox == null) {
            throw new Cr3FormatException("Missing moov box");
        }

        IsoBox mdatBox = findBox(boxes, "mdat");
        if (mdatBox == null) {
            throw new Cr3FormatException("Missing mdat box");
        }

        // Parse tracks from moov
        List<Cr3Track> tracks = parseTracks(input, moovBox, mdatBox);
        LOG.info("Found {} tracks", tracks.size());
        for (Cr3Track track : tracks) {
            LOG.debug("  {}", track);
        }

        return new Cr3Container(ftypBox, moovBox, mdatBox, boxes, tracks);
    }

    /**
     * Checks whether the given input stream starts with a valid CR3 file signature.
     *
     * <p>Parses the full ftyp box to check both the major brand and compatible brands
     * for "crx ", matching the detection behavior of {@link FtypBox#isCr3()}.</p>
     *
     * @param input the image input stream
     * @return {@code true} if the file appears to be a CR3 file
     * @throws IOException if an I/O error occurs
     */
    public static boolean isCr3(ImageInputStream input) throws IOException {
        input.setByteOrder(ByteOrder.BIG_ENDIAN);
        input.seek(0);

        // A CR3 file must start with an ftyp box
        if (input.length() >= 0 && input.length() < 12) {
            return false;
        }

        try {
            long size = Integer.toUnsignedLong(input.readInt());
            byte[] type = new byte[4];
            input.readFully(type);
            String typeStr = new String(type, java.nio.charset.StandardCharsets.US_ASCII);

            if (!"ftyp".equals(typeStr)) {
                return false;
            }

            // Parse the full ftyp box to check major brand + compatible brands
            IsoBox ftypIsoBox = new IsoBox("ftyp", size, 0);
            FtypBox ftypBox = FtypBox.parse(input, ftypIsoBox);
            return ftypBox.isCr3();
        } catch (java.io.EOFException e) {
            return false;
        } finally {
            input.seek(0);
        }
    }

    private static List<Cr3Track> parseTracks(ImageInputStream input, IsoBox moovBox, IsoBox mdatBox)
            throws IOException {
        List<Cr3Track> tracks = new ArrayList<>();
        int trackIndex = 0;

        for (IsoBox child : moovBox.children()) {
            if ("trak".equals(child.type())) {
                Cr3Track track = parseTrack(input, child, trackIndex, mdatBox);
                if (track != null) {
                    tracks.add(track);
                }
                trackIndex++;
            }
        }

        return tracks;
    }

    private static Cr3Track parseTrack(ImageInputStream input, IsoBox trakBox, int index, IsoBox mdatBox)
            throws IOException {
        // Navigate: trak -> mdia -> minf -> stbl
        IsoBox mdiaBox = trakBox.findChild("mdia");
        if (mdiaBox == null) {
            LOG.warn("Track {} has no mdia box", index);
            return null;
        }

        IsoBox minfBox = mdiaBox.findChild("minf");
        if (minfBox == null) {
            LOG.warn("Track {} has no minf box", index);
            return null;
        }

        IsoBox stblBox = minfBox.findChild("stbl");
        if (stblBox == null) {
            LOG.warn("Track {} has no stbl box", index);
            return null;
        }

        // Get sample description from stsd box
        IsoBox stsdBox = stblBox.findChild("stsd");
        int width = 0;
        int height = 0;
        String codecType = "";

        if (stsdBox != null) {
            // stsd is a full box: 4 bytes version/flags + 4 bytes entry count
            long stsdDataOffset = stsdBox.dataOffset() + 8;
            input.seek(stsdDataOffset);

            // Read the first sample entry header
            if (stsdBox.dataSize() > 16) {
                int entrySize = input.readInt();
                byte[] entryType = new byte[4];
                input.readFully(entryType);
                codecType = new String(entryType, java.nio.charset.StandardCharsets.US_ASCII);

                // Skip 6 reserved bytes + 2 bytes data ref index
                input.skipBytes(8);

                // For visual tracks (CRAW, JPEG), next fields are version, revision, vendor, etc.
                // Skip to width/height: 2 version + 2 revision + 12 vendor/temporal/spatial = 16 bytes
                if ("CRAW".equals(codecType) || codecType.startsWith("jpeg") || codecType.startsWith("JPEG")) {
                    input.skipBytes(16);
                    width = input.readUnsignedShort();
                    height = input.readUnsignedShort();
                }
            }
        }

        // Get data offset and size from stsz (sample size) and stco/co64 (chunk offset)
        long dataOffset = 0;
        long dataSize = 0;

        // Try stco first, then co64
        IsoBox stcoBox = stblBox.findChild("stco");
        IsoBox co64Box = stblBox.findChild("co64");
        if (co64Box != null) {
            // co64: full box (4 bytes version/flags) + 4 bytes entry count + 8 bytes per offset
            input.seek(co64Box.dataOffset() + 4); // skip version/flags
            int entryCount = input.readInt();
            if (entryCount > 0) {
                dataOffset = input.readLong();
            }
        } else if (stcoBox != null) {
            // stco: full box (4 bytes version/flags) + 4 bytes entry count + 4 bytes per offset
            input.seek(stcoBox.dataOffset() + 4);
            int entryCount = input.readInt();
            if (entryCount > 0) {
                dataOffset = Integer.toUnsignedLong(input.readInt());
            }
        }

        // Get sample size from stsz
        IsoBox stszBox = stblBox.findChild("stsz");
        if (stszBox != null) {
            // stsz: full box (4 bytes version/flags) + 4 bytes sample size + 4 bytes count
            input.seek(stszBox.dataOffset() + 4);
            long sampleSize = Integer.toUnsignedLong(input.readInt());
            int sampleCount = input.readInt();
            if (sampleSize > 0) {
                dataSize = sampleSize;
            } else if (sampleCount > 0) {
                // Per-sample sizes — read first
                dataSize = Integer.toUnsignedLong(input.readInt());
            }
        }

        // Determine track type based on index and codec
        Cr3Track.TrackType type = determineTrackType(index, codecType);
        CompressionMode compressionMode = determineCompressionMode(codecType, type);

        // Validate track data offsets against mdat box bounds
        if (dataOffset > 0 && dataSize > 0 && mdatBox.size() > 0) {
            long mdatStart = mdatBox.dataOffset();
            long mdatEnd = mdatBox.offset() + mdatBox.size();
            long trackEnd = dataOffset + dataSize;
            if (dataOffset < mdatStart || trackEnd > mdatEnd) {
                LOG.warn("Track {} data range [{}, {}) falls outside mdat [{}, {})",
                        index, dataOffset, trackEnd, mdatStart, mdatEnd);
            }
        }

        LOG.debug("Track {}: type={}, codec='{}', {}x{}, offset={}, size={}, compression={}",
                index, type, codecType, width, height, dataOffset, dataSize, compressionMode);

        return new Cr3Track(index, type, dataOffset, dataSize, width, height, codecType, compressionMode);
    }

    private static Cr3Track.TrackType determineTrackType(int index, String codecType) {
        if ("CRAW".equals(codecType)) {
            // Track 3 is typically HDR RAW for some models
            return index >= 3 ? Cr3Track.TrackType.CRX_HDR_RAW : Cr3Track.TrackType.CRX_RAW;
        }
        // JPEG tracks: first is thumbnail, second is main preview
        return switch (index) {
            case 0 -> Cr3Track.TrackType.JPEG_THUMBNAIL;
            case 1 -> Cr3Track.TrackType.JPEG_PREVIEW;
            default -> Cr3Track.TrackType.UNKNOWN;
        };
    }

    /**
     * Determines the compression mode based on codec type.
     * TODO: Read CNCV box to distinguish between RAW_LOSSLESS and CRAW_LOSSY more reliably
     */
    private static CompressionMode determineCompressionMode(String codecType, Cr3Track.TrackType trackType) {
        if (!"CRAW".equals(codecType)) {
            // Non-CRX tracks (JPEG) don't have a CRX compression mode
            return CompressionMode.UNKNOWN;
        }
        // Default to RAW_LOSSLESS for CRX tracks — actual mode should be
        // determined from CNCV compressor version string once parsing is implemented
        return CompressionMode.RAW_LOSSLESS;
    }

    private static IsoBox findBox(List<IsoBox> boxes, String type) {
        for (IsoBox box : boxes) {
            if (box.type().equals(type)) {
                return box;
            }
        }
        return null;
    }
}
