package com.photographer.focusstack.cr3.container;

import com.photographer.focusstack.cr3.Cr3FormatException;
import com.photographer.focusstack.cr3.bmff.CcdtEntry;
import com.photographer.focusstack.cr3.bmff.CctpBox;
import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.bmff.CncvBox;
import com.photographer.focusstack.cr3.bmff.CtboBox;
import com.photographer.focusstack.cr3.bmff.FtypBox;
import com.photographer.focusstack.cr3.bmff.Iad1Box;
import com.photographer.focusstack.cr3.bmff.IsoBox;
import com.photographer.focusstack.cr3.bmff.IsoBmffReader;
import com.photographer.focusstack.cr3.bmff.PrvwBox;
import com.photographer.focusstack.cr3.bmff.ThmbBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a Canon CR3 file into a {@link Cr3Container} structure.
 */
public final class Cr3Parser {

    private static final Logger LOG = LoggerFactory.getLogger(Cr3Parser.class);

    private Cr3Parser() {}

    public static Cr3Container parse(ImageInputStream input) throws Cr3FormatException, IOException {
        input.setByteOrder(ByteOrder.BIG_ENDIAN);

        List<IsoBox> boxes = IsoBmffReader.readBoxes(input);
        if (boxes.isEmpty()) {
            throw new Cr3FormatException("No boxes found in file");
        }

        IsoBox ftypIsoBox = findBox(boxes, "ftyp");
        if (ftypIsoBox == null) {
            throw new Cr3FormatException("Missing ftyp box — not a valid BMFF file");
        }

        FtypBox ftypBox = FtypBox.parse(input, ftypIsoBox);
        if (!ftypBox.isCr3()) {
            throw new Cr3FormatException(
                    "Not a CR3 file: major brand is '%s'".formatted(ftypBox.majorBrand()));
        }

        IsoBox moovBox = findBox(boxes, "moov");
        if (moovBox == null) {
            throw new Cr3FormatException("Missing moov box");
        }

        IsoBox mdatBox = findBox(boxes, "mdat");
        if (mdatBox == null) {
            throw new Cr3FormatException("Missing mdat box");
        }

        // Parse Canon-specific boxes from moov
        CncvBox cncvBox = null;
        CctpBox cctpBox = null;
        CtboBox ctboBox = null;
        ThmbBox thmbBox = null;
        PrvwBox prvwBox = null;

        IsoBox cncvIso = moovBox.findDescendant("CNCV");
        if (cncvIso != null) {
            try { cncvBox = CncvBox.parse(input, cncvIso); } catch (IOException e) {
                LOG.warn("Failed to parse CNCV box", e);
            }
        }

        IsoBox cctpIso = moovBox.findDescendant("CCTP");
        if (cctpIso != null) {
            try { cctpBox = CctpBox.parse(input, cctpIso); } catch (IOException e) {
                LOG.warn("Failed to parse CCTP box", e);
            }
        }

        IsoBox ctboIso = moovBox.findDescendant("CTBO");
        if (ctboIso != null) {
            try { ctboBox = CtboBox.parse(input, ctboIso); } catch (IOException e) {
                LOG.warn("Failed to parse CTBO box", e);
            }
        }

        IsoBox thmbIso = moovBox.findDescendant("THMB");
        if (thmbIso != null) {
            try { thmbBox = ThmbBox.parse(input, thmbIso); } catch (IOException e) {
                LOG.warn("Failed to parse THMB box", e);
            }
        }

        // Parse PRVW from top-level uuid boxes
        for (IsoBox box : boxes) {
            if ("uuid".equals(box.type())) {
                IsoBox prvwChild = box.findChild("PRVW");
                if (prvwChild != null) {
                    try { prvwBox = PrvwBox.parse(input, prvwChild); } catch (IOException e) {
                        LOG.warn("Failed to parse PRVW box", e);
                    }
                }
            }
        }

        List<Cr3Track> tracks = parseTracks(input, moovBox, mdatBox, cctpBox);

        return new Cr3Container(ftypBox, moovBox, mdatBox, boxes, tracks,
                cncvBox, cctpBox, ctboBox, thmbBox, prvwBox);
    }

    public static boolean isCr3(ImageInputStream input) throws IOException {
        input.setByteOrder(ByteOrder.BIG_ENDIAN);
        input.seek(0);

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

            IsoBox ftypIsoBox = new IsoBox("ftyp", size, 0);
            FtypBox ftypBox = FtypBox.parse(input, ftypIsoBox);
            return ftypBox.isCr3();
        } catch (java.io.EOFException e) {
            return false;
        } finally {
            input.seek(0);
        }
    }

    private static List<Cr3Track> parseTracks(ImageInputStream input, IsoBox moovBox, IsoBox mdatBox,
                                               CctpBox cctpBox) throws IOException {
        List<Cr3Track> tracks = new ArrayList<>();
        int trackIndex = 0;

        for (IsoBox child : moovBox.children()) {
            if ("trak".equals(child.type())) {
                Cr3Track track = parseTrack(input, child, trackIndex, mdatBox, cctpBox);
                if (track != null) {
                    tracks.add(track);
                }
                trackIndex++;
            }
        }

        return tracks;
    }

    private static Cr3Track parseTrack(ImageInputStream input, IsoBox trakBox, int index,
                                        IsoBox mdatBox, CctpBox cctpBox) throws IOException {
        IsoBox mdiaBox = trakBox.findChild("mdia");
        if (mdiaBox == null) { LOG.warn("Track {} has no mdia box", index); return null; }

        IsoBox minfBox = mdiaBox.findChild("minf");
        if (minfBox == null) { LOG.warn("Track {} has no minf box", index); return null; }

        IsoBox stblBox = minfBox.findChild("stbl");
        if (stblBox == null) { LOG.warn("Track {} has no stbl box", index); return null; }

        IsoBox stsdBox = stblBox.findChild("stsd");
        int width = 0;
        int height = 0;
        String codecType = "";
        Cmp1Box cmp1Box = null;
        Iad1Box iad1Box = null;

        if (stsdBox != null) {
            // stsd: FullBox (4 bytes version/flags) + 4 bytes entry count before entries
            long stsdDataOffset = stsdBox.dataOffset() + 8;
            input.seek(stsdDataOffset);

            if (stsdBox.dataSize() > 16) {
                int entrySize = input.readInt();
                byte[] entryType = new byte[4];
                input.readFully(entryType);
                codecType = new String(entryType, java.nio.charset.StandardCharsets.US_ASCII);

                input.skipBytes(8); // 6 reserved + 2 data ref index

                if ("CRAW".equals(codecType) || codecType.startsWith("jpeg") || codecType.startsWith("JPEG")) {
                    input.skipBytes(16);
                    width = input.readUnsignedShort();
                    height = input.readUnsignedShort();
                }

                if ("CRAW".equals(codecType)) {
                    IsoBox crawBox = stsdBox.findChild("CRAW");
                    if (crawBox != null) {
                        IsoBox cmp1Iso = crawBox.findChild("CMP1");
                        if (cmp1Iso != null) {
                            try { cmp1Box = Cmp1Box.parse(input, cmp1Iso); } catch (IOException e) {
                                LOG.warn("Failed to parse CMP1 box in track {}", index, e);
                            }
                        }
                        IsoBox cdi1Box = crawBox.findChild("CDI1");
                        if (cdi1Box != null) {
                            IsoBox iad1Iso = cdi1Box.findChild("IAD1");
                            if (iad1Iso != null) {
                                try { iad1Box = Iad1Box.parse(input, iad1Iso); } catch (IOException e) {
                                    LOG.warn("Failed to parse IAD1 box in track {}", index, e);
                                }
                            }
                        }
                    }
                }
            }
        }

        // Chunk offset
        long dataOffset = 0;
        IsoBox stcoBox = stblBox.findChild("stco");
        IsoBox co64Box = stblBox.findChild("co64");
        if (co64Box != null) {
            input.seek(co64Box.dataOffset() + 4);
            int entryCount = input.readInt();
            if (entryCount > 0) dataOffset = input.readLong();
        } else if (stcoBox != null) {
            input.seek(stcoBox.dataOffset() + 4);
            int entryCount = input.readInt();
            if (entryCount > 0) dataOffset = Integer.toUnsignedLong(input.readInt());
        }

        // Sample size
        long dataSize = 0;
        IsoBox stszBox = stblBox.findChild("stsz");
        if (stszBox != null) {
            input.seek(stszBox.dataOffset() + 4);
            long sampleSize = Integer.toUnsignedLong(input.readInt());
            int sampleCount = input.readInt();
            if (sampleSize > 0) {
                dataSize = sampleSize;
            } else if (sampleCount > 0) {
                dataSize = Integer.toUnsignedLong(input.readInt());
            }
        }

        Cr3Track.TrackType type = determineTrackType(index, codecType, cctpBox);

        CompressionMode compressionMode;
        if (cmp1Box != null) {
            compressionMode = cmp1Box.isLossless() ? CompressionMode.RAW_LOSSLESS : CompressionMode.CRAW_LOSSY;
        } else {
            compressionMode = determineCompressionMode(codecType);
        }

        if (dataOffset > 0 && dataSize > 0 && mdatBox.size() > 0) {
            long mdatStart = mdatBox.dataOffset();
            long mdatEnd = mdatBox.offset() + mdatBox.size();
            long trackEnd = dataOffset + dataSize;
            if (dataOffset < mdatStart || trackEnd > mdatEnd) {
                LOG.warn("Track {} data range [{}, {}) falls outside mdat [{}, {})",
                        index, dataOffset, trackEnd, mdatStart, mdatEnd);
            }
        }

        return new Cr3Track(index, type, dataOffset, dataSize, width, height, codecType,
                compressionMode, cmp1Box, iad1Box);
    }

    private static Cr3Track.TrackType determineTrackType(int index, String codecType, CctpBox cctpBox) {
        // Use CCDT if available (1-based trakIndex, 0-based index)
        if (cctpBox != null) {
            for (CcdtEntry entry : cctpBox.entries()) {
                if (entry.trakIndex() == index + 1) {
                    return switch ((int) entry.imageType()) {
                        case 0 -> Cr3Track.TrackType.CRX_BIG;
                        case 1 -> Cr3Track.TrackType.CRX_SMALL;
                        case 2 -> Cr3Track.TrackType.JPEG_BIG;
                        case 4 -> Cr3Track.TrackType.CRX_DUAL_PIXEL;
                        default -> Cr3Track.TrackType.UNKNOWN;
                    };
                }
            }
        }

        // Fallback: heuristic based on index and codec
        if ("CRAW".equals(codecType)) {
            return index >= 3 ? Cr3Track.TrackType.CRX_HDR_RAW : Cr3Track.TrackType.CRX_RAW;
        }
        return switch (index) {
            case 0 -> Cr3Track.TrackType.JPEG_THUMBNAIL;
            case 1 -> Cr3Track.TrackType.JPEG_PREVIEW;
            default -> Cr3Track.TrackType.UNKNOWN;
        };
    }

    private static CompressionMode determineCompressionMode(String codecType) {
        if (!"CRAW".equals(codecType)) {
            return CompressionMode.UNKNOWN;
        }
        return CompressionMode.RAW_LOSSLESS;
    }

    private static IsoBox findBox(List<IsoBox> boxes, String type) {
        for (IsoBox box : boxes) {
            if (box.type().equals(type)) return box;
        }
        return null;
    }
}
