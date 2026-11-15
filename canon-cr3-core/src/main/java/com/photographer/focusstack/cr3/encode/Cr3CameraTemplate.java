package com.photographer.focusstack.cr3.encode;

import com.photographer.focusstack.cr3.bmff.CctpBox;
import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.bmff.CncvBox;
import com.photographer.focusstack.cr3.bmff.CtboBox;
import com.photographer.focusstack.cr3.bmff.FtypBox;
import com.photographer.focusstack.cr3.bmff.Iad1Box;
import com.photographer.focusstack.cr3.bmff.IsoBox;
import com.photographer.focusstack.cr3.bmff.IsoBmffReader;
import com.photographer.focusstack.cr3.container.CompressionMode;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.container.Cr3Track.TrackType;
import com.photographer.focusstack.cr3.raw.codec.CrxHeader;

import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Relocates the measured four-track camera container around newly encoded image data. */
final class Cr3CameraTemplate {

    private static final UUID CANON_METADATA = UUID.fromString("85c0b687-820f-11e0-8111-f4ce462b6a48");
    private static final UUID XMP = UUID.fromString("be7acfcb-97a9-42e8-9c71-999491e3afac");
    private static final UUID CANON_PREVIEW = UUID.fromString("eaf42b5e-1c98-4b88-b9fb-b7dc406e4d16");
    private static final long UINT32_MAX = 0xffff_ffffL;

    private final ImageInputStream source;
    private final long sourceSize;
    private final EmbeddedJpeg thumbnail;
    private final EmbeddedJpeg preview;
    private final List<SampleTable> tracks;
    private final List<IsoBox> resizedBoxes;
    private final List<BoxReference> ctboReferences;

    private Cr3CameraTemplate(ImageInputStream source, long sourceSize,
                              EmbeddedJpeg thumbnail, EmbeddedJpeg preview,
                              List<SampleTable> tracks, List<IsoBox> resizedBoxes,
                              List<BoxReference> ctboReferences) {
        this.source = source;
        this.sourceSize = sourceSize;
        this.thumbnail = thumbnail;
        this.preview = preview;
        this.tracks = tracks;
        this.resizedBoxes = resizedBoxes;
        this.ctboReferences = ctboReferences;
    }

    static Cr3CameraTemplate read(ImageInputStream source) throws IOException {
        long position = source.getStreamPosition();
        int bitOffset = source.getBitOffset();
        ByteOrder byteOrder = source.getByteOrder();
        try {
            return readTemplate(source);
        } finally {
            source.setByteOrder(byteOrder);
            source.seek(position);
            source.setBitOffset(bitOffset);
        }
    }

    private static Cr3CameraTemplate readTemplate(ImageInputStream source) throws IOException {
        List<IsoBox> boxes = IsoBmffReader.readBoxes(source);
        require(boxes.stream().map(IsoBox::type).toList()
                        .equals(List.of("ftyp", "moov", "uuid", "uuid", "mdat")),
                "expected ftyp, moov, XMP UUID, preview UUID and mdat");
        IsoBox ftyp = boxes.get(0);
        IsoBox moov = boxes.get(1);
        IsoBox xmp = boxes.get(2);
        IsoBox previewUuid = boxes.get(3);
        IsoBox mdat = boxes.get(4);
        long sourceSize = end(mdat);
        verifySequence(source, boxes, 0, sourceSize, Set.of("ftyp", "moov", "uuid", "mdat"));
        if (source.length() >= 0) {
            require(source.length() == sourceSize, "invalid file extent");
        } else {
            source.seek(sourceSize - 1);
            require(source.read() >= 0 && source.read() < 0, "invalid file extent");
        }

        require(ftyp.dataSize() == 16 && FtypBox.parse(source, ftyp).isCr3(), "invalid Canon brand");
        verifyUuid(source, xmp, XMP);
        verifyUuid(source, previewUuid, CANON_PREVIEW);
        verifyChildren(source, previewUuid, 24, Set.of("PRVW"));
        IsoBox previewBox = onlyChild(previewUuid, "PRVW");

        verifyChildren(source, moov, 0, Set.of("uuid", "mvhd", "trak"));
        IsoBox metadata = onlyChild(moov, "uuid");
        onlyChild(moov, "mvhd");
        verifyUuid(source, metadata, CANON_METADATA);
        verifyChildren(source, metadata, 16,
                Set.of("CNCV", "CCTP", "CTBO", "CMT1", "CMT2", "CMT3", "CMT4", "THMB", "free"));
        IsoBox version = onlyChild(metadata, "CNCV");
        require(version.dataSize() == 30 && CncvBox.parse(source, version).versionString().startsWith("CanonCR3_001/"),
                "expected Canon CRX V1");
        for (String type : List.of("CMT1", "CMT2", "CMT3", "CMT4")) {
            onlyChild(metadata, type);
        }
        verifyTrackRoles(source, onlyChild(metadata, "CCTP"));
        List<BoxReference> ctboReferences = readCtbo(source, onlyChild(metadata, "CTBO"),
                List.of(xmp, previewUuid, mdat));
        IsoBox thumbBox = onlyChild(metadata, "THMB");
        EmbeddedJpeg thumbnail = readEmbeddedJpeg(source, thumbBox, true);
        EmbeddedJpeg preview = readEmbeddedJpeg(source, previewBox, false);

        List<IsoBox> trackBoxes = moov.children().stream().filter(box -> "trak".equals(box.type())).toList();
        List<TrackType> roles = List.of(TrackType.JPEG_BIG, TrackType.CRX_SMALL, TrackType.CRX_BIG, TrackType.UNKNOWN);
        require(trackBoxes.size() == roles.size(), "expected four camera tracks");
        List<SampleTable> tracks = new ArrayList<>(roles.size());
        long position = mdat.dataOffset();
        for (int i = 0; i < roles.size(); i++) {
            SampleTable table = readTrack(source, trackBoxes.get(i), i, roles.get(i), position, end(mdat));
            require(table.sample().start() == position, "unexpected camera sample padding");
            tracks.add(table);
            position = table.sample().end();
            if (i == 0) {
                position += alignmentPadding(table.sample().size());
            }
        }
        verifyJpeg(source, tracks.getFirst().sample());
        verifyRaw(source, tracks.get(1).track(), 1, 112);
        verifyRaw(source, tracks.get(2).track(), 2, 216);

        return new Cr3CameraTemplate(source, sourceSize, thumbnail, preview, List.copyOf(tracks),
                List.of(thumbBox, metadata, moov, previewBox, previewUuid, mdat), ctboReferences);
    }

    private static void verifyTrackRoles(ImageInputStream source, IsoBox cctp) throws IOException {
        verifyChildren(source, cctp, 12, Set.of("CCDT"));
        require(cctp.children().size() == 3 && cctp.children().stream().allMatch(box -> box.dataSize() == 16),
                "expected three Canon image track descriptors");
        var entries = CctpBox.parse(source, cctp).entries();
        long jpegType = entries.getFirst().imageType();
        require(jpegType == 2 || jpegType == 16, "expected a full-size JPEG track");
        long[] imageTypes = {jpegType, 1, 0};
        for (int i = 0; i < imageTypes.length; i++) {
            var entry = entries.get(i);
            require(entry.imageType() == imageTypes[i] && entry.trakIndex() == i + 1 && entry.dualPixelFlag() == 0,
                    "unsupported Canon track descriptor");
        }
    }

    private static List<BoxReference> readCtbo(ImageInputStream source, IsoBox ctbo, List<IsoBox> targets)
            throws IOException {
        require(ctbo.dataSize() == 84 || ctbo.dataSize() == 104, "unsupported CTBO size");
        source.seek(ctbo.dataOffset());
        require(ctbo.dataSize() == 4 + 20L * source.readInt(), "invalid CTBO entry count");
        var entries = CtboBox.parse(source, ctbo).entries();
        List<BoxReference> references = new ArrayList<>(targets.size());
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            require(entry.index() == i + 1, "unexpected CTBO entry index");
            if (i < targets.size()) {
                IsoBox target = targets.get(i);
                require(entry.offset() == target.offset() && entry.size() == target.size(),
                        "CTBO entry does not match its top-level box");
                references.add(new BoxReference(ctbo.dataOffset() + 8 + 20L * i, target));
            } else {
                require(entry.offset() == 0 && entry.size() == 0, "unsupported CTBO reference");
            }
        }
        return List.copyOf(references);
    }

    private static SampleTable readTrack(ImageInputStream source, IsoBox trak, int index, TrackType role,
                                        long lowerBound, long upperBound) throws IOException {
        boolean metadata = role == TrackType.UNKNOWN;
        verifyChildren(source, trak, 0, Set.of("tkhd", "mdia"));
        onlyChild(trak, "tkhd");
        IsoBox mdia = onlyChild(trak, "mdia");
        verifyChildren(source, mdia, 0, Set.of("mdhd", "hdlr", "minf"));
        onlyChild(mdia, "mdhd");
        onlyChild(mdia, "hdlr");
        IsoBox minf = onlyChild(mdia, "minf");
        String mediaHeader = metadata ? "nmhd" : "vmhd";
        verifyChildren(source, minf, 0, Set.of(mediaHeader, "dinf", "stbl"));
        onlyChild(minf, mediaHeader);
        verifyDataReference(source, onlyChild(minf, "dinf"));
        IsoBox stbl = onlyChild(minf, "stbl");
        verifyChildren(source, stbl, 0, Set.of("stsd", "stts", "stsc", "stsz", "stco", "co64", "free"));

        IsoBox stsd = onlyChild(stbl, "stsd");
        String codec = metadata ? "CTMD" : "CRAW";
        verifyChildren(source, stsd, 8, Set.of(codec));
        source.seek(stsd.dataOffset());
        require(source.readInt() == 0 && source.readInt() == 1, "expected one sample description per track");
        IsoBox sampleEntry = onlyChild(stsd, codec);
        require(!sampleEntry.extendedSize() && sampleEntry.dataSize() >= (metadata ? 8 : 82),
                "unsupported sample description");
        source.seek(sampleEntry.dataOffset() + 6);
        require(source.readUnsignedShort() == 1, "expected a self-contained track sample");

        IsoBox stts = onlyChild(stbl, "stts");
        require(stts.dataSize() == 16, "invalid stts size");
        source.seek(stts.dataOffset());
        require(source.readInt() == 0 && source.readInt() == 1 && source.readInt() == 1
                && source.readInt() != 0, "expected timing for one sample");

        IsoBox stsc = onlyChild(stbl, "stsc");
        require(stsc.dataSize() == 20, "invalid stsc size");
        source.seek(stsc.dataOffset());
        require(source.readInt() == 0 && source.readInt() == 1 && source.readInt() == 1
                && source.readInt() == 1 && source.readInt() == 1, "expected one sample per chunk");

        IsoBox stsz = onlyChild(stbl, "stsz");
        require(stsz.dataSize() == 12 || stsz.dataSize() == 16, "invalid stsz size");
        source.seek(stsz.dataOffset());
        require(source.readInt() == 0, "unsupported stsz flags");
        long size = Integer.toUnsignedLong(source.readInt());
        require(source.readInt() == 1, "expected one track sample");
        long sizeField = stsz.dataOffset() + 4;
        if (size == 0) {
            require(stsz.dataSize() == 16, "missing explicit sample size");
            sizeField = source.getStreamPosition();
            size = Integer.toUnsignedLong(source.readInt());
        } else {
            require(stsz.dataSize() == 12, "invalid fixed sample size table");
        }

        List<IsoBox> chunkTables = stbl.children().stream()
                .filter(box -> "co64".equals(box.type()) || "stco".equals(box.type())).toList();
        require(chunkTables.size() == 1, "expected one chunk offset table per track");
        IsoBox offsetBox = chunkTables.getFirst();
        boolean wideOffset = "co64".equals(offsetBox.type());
        require(offsetBox.dataSize() == (wideOffset ? 16 : 12), "invalid chunk offset table size");
        source.seek(offsetBox.dataOffset());
        require(source.readInt() == 0 && source.readInt() == 1, "expected one chunk per track");
        long offsetField = source.getStreamPosition();
        long start = wideOffset ? source.readLong() : Integer.toUnsignedLong(source.readInt());
        require(size > 0 && start >= lowerBound && start <= upperBound && size <= upperBound - start,
                "invalid or overlapping camera sample extent");

        int width = 0;
        int height = 0;
        Cmp1Box cmp1 = null;
        Iad1Box iad1 = null;
        if (!metadata) {
            source.seek(sampleEntry.dataOffset() + 24);
            width = source.readUnsignedShort();
            height = source.readUnsignedShort();
            require(width > 0 && height > 0, "invalid camera track dimensions");
            if (role == TrackType.JPEG_BIG) {
                verifyChildren(source, sampleEntry, 82, Set.of("JPEG", "free"));
                onlyChild(sampleEntry, "JPEG");
            } else {
                verifyChildren(source, sampleEntry, 82, Set.of("CMP1", "CDI1", "free"));
                IsoBox compression = onlyChild(sampleEntry, "CMP1");
                require(compression.dataSize() == 52, "unsupported CMP1 size");
                cmp1 = Cmp1Box.parse(source, compression);
                IsoBox cdi1 = onlyChild(sampleEntry, "CDI1");
                verifyChildren(source, cdi1, 4, Set.of("IAD1"));
                IsoBox geometry = onlyChild(cdi1, "IAD1");
                require(geometry.dataSize() == (role == TrackType.CRX_BIG ? 48 : 32), "unsupported IAD1 size");
                iad1 = Iad1Box.parse(source, geometry);
            }
        }
        Cr3Track track = new Cr3Track(index, role, start, size, width, height, codec,
                metadata ? CompressionMode.UNKNOWN : CompressionMode.RAW_LOSSLESS, cmp1, iad1);
        return new SampleTable(track, offsetField, wideOffset, sizeField);
    }

    private static void verifyDataReference(ImageInputStream source, IsoBox dinf) throws IOException {
        verifyChildren(source, dinf, 0, Set.of("dref"));
        IsoBox dref = onlyChild(dinf, "dref");
        require(dref.dataSize() == 20, "unsupported data reference");
        source.seek(dref.dataOffset());
        require(source.readInt() == 0 && source.readInt() == 1 && source.readInt() == 12
                && source.readInt() == 0x75726c20 && source.readInt() == 1,
                "expected a self-contained URL data reference");
    }

    private static void verifyRaw(ImageInputStream source, Cr3Track track, int tileCount, int directorySize)
            throws IOException {
        Cmp1Box cmp = track.cmp1();
        require(cmp.version() == 0x100 && cmp.bitsPerSample() == 14 && cmp.numPlanes() == 4
                && cmp.cfaLayout() >= 0 && cmp.cfaLayout() < 4 && cmp.encodingType() == 0
                && cmp.waveletLevels() == 0 && !cmp.hasExtendedHeader(),
                "expected unrounded 14-bit V1 lossless CRX");
        require(track.width() == cmp.imageWidth() && track.height() == cmp.imageHeight()
                && (cmp.tileWidth() & 1) == 0 && (track.height() & 1) == 0
                && (long) cmp.tileWidth() * tileCount == track.width() && cmp.tileHeight() == track.height()
                && (long) track.width() * track.height() <= Integer.MAX_VALUE
                && track.width() == track.iad1().imageWidth() && track.height() == track.iad1().imageHeight()
                && track.iad1().isBigImage() == (tileCount == 2),
                "unsupported CRX tile or sensor geometry");
        require(cmp.mdatHeaderSize() == directorySize && track.dataSize() > directorySize,
                "unsupported CRX directory size");
        byte[] directory = new byte[directorySize];
        source.seek(track.dataOffset());
        source.readFully(directory);
        CrxHeader header = CrxHeader.parse(directory, CrxHeader.CodecVersion.V1);
        require(header.headerSize() == directorySize && header.tiles().size() == tileCount,
                "invalid V1 CRX tile directory");

        long sampleSize = directorySize;
        for (var tile : header.tiles()) {
            require(tile.planes().size() == 4, "expected four Bayer planes per tile");
            int planes = 0;
            long tileSize = 0;
            for (var plane : tile.planes()) {
                require(plane.index() < 4 && (planes & (1 << plane.index())) == 0
                        && plane.roundedBits() == 0 && plane.supportsPartial() && plane.subbands().size() == 1,
                        "unsupported CRX plane");
                planes |= 1 << plane.index();
                var band = plane.subbands().getFirst();
                require(band.index() == 0 && band.dataSize() >= 8 && band.dataSize() == plane.dataSize()
                        && alignmentPadding(band.dataSize()) == 0
                        && band.paddingBytes() <= band.dataSize() - 8, "invalid CRX band extent");
                tileSize += band.dataSize();
            }
            require(tileSize == tile.dataSize(), "invalid CRX tile extent");
            sampleSize += tileSize;
        }
        require(sampleSize == track.dataSize(), "CRX tile extents disagree with the camera sample size");
    }

    private static EmbeddedJpeg readEmbeddedJpeg(ImageInputStream source, IsoBox box, boolean thumbnail)
            throws IOException {
        require(box.dataSize() >= 20, "invalid embedded JPEG box extent");
        source.seek(box.dataOffset());
        if (thumbnail) {
            require(source.readUnsignedByte() == 0, "expected THMB version 0");
        }
        source.seek(box.dataOffset() + (thumbnail ? 4 : 6));
        int width = source.readUnsignedShort();
        int height = source.readUnsignedShort();
        long sizeField = box.dataOffset() + (thumbnail ? 8 : 12);
        source.seek(sizeField);
        long size = Integer.toUnsignedLong(source.readInt());
        require(width > 0 && height > 0 && size <= box.dataSize() - 16, "invalid embedded JPEG extent");
        Range sample = new Range(box.dataOffset() + 16, size);
        verifyJpeg(source, sample);
        long padding = end(box) - sample.end();
        require(padding == (thumbnail ? alignmentPadding(size) : 0), "unexpected embedded JPEG padding");
        return new EmbeddedJpeg(new Range(sample.start(), size + padding), sizeField, width, height);
    }

    private static void verifyJpeg(ImageInputStream source, Range range) throws IOException {
        require(range.size() >= 4, "camera JPEG is too short");
        source.seek(range.start());
        require(source.readUnsignedShort() == 0xffd8, "invalid camera JPEG start marker");
        source.seek(range.end() - 2);
        require(source.readUnsignedShort() == 0xffd9, "invalid camera JPEG end marker");
    }

    private static void verifyUuid(ImageInputStream source, IsoBox box, UUID expected) throws IOException {
        require(box.dataSize() >= 16, "truncated UUID box");
        source.seek(box.dataOffset());
        require(expected.equals(new UUID(source.readLong(), source.readLong())), "unexpected camera UUID");
    }

    private static void verifyChildren(ImageInputStream source, IsoBox parent, long prefix, Set<String> allowed)
            throws IOException {
        verifySequence(source, parent.children(), parent.dataOffset() + prefix, end(parent), allowed);
    }

    private static void verifySequence(ImageInputStream source, List<IsoBox> boxes, long start, long end,
                                       Set<String> allowed) throws IOException {
        long position = start;
        for (IsoBox box : boxes) {
            require(allowed.contains(box.type()) && box.offset() == position && end(box) <= end,
                    "unexpected or unbounded BMFF box");
            source.seek(box.offset());
            require(source.readInt() != 0, "unsupported implicit BMFF box size");
            position = end(box);
        }
        require(position == end, "incomplete BMFF box sequence");
    }

    private static IsoBox onlyChild(IsoBox parent, String type) throws IOException {
        List<IsoBox> matches = parent.children().stream().filter(box -> type.equals(box.type())).toList();
        require(matches.size() == 1, "expected one " + type + " box in " + parent.type());
        return matches.getFirst();
    }

    private static long end(IsoBox box) {
        return box.offset() + box.size();
    }

    private static int alignmentPadding(long size) {
        return (int) (-size & (Long.BYTES - 1));
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) {
            throw new IOException("Unsupported CR3 camera template: " + message);
        }
    }

    Cr3Track raw() { return tracks.get(2).track(); }
    Cr3Track reducedRaw() { return tracks.get(1).track(); }
    Cr3Track jpeg() { return tracks.getFirst().track(); }
    int previewWidth() { return preview.width(); }
    int previewHeight() { return preview.height(); }
    int thumbnailWidth() { return thumbnail.width(); }
    int thumbnailHeight() { return thumbnail.height(); }

    void write(ImageOutputStream output, Payload raw, Payload reducedRaw, Payload jpeg,
               Payload preview, Payload thumbnail) throws IOException {
        if (source == output || output.getBitOffset() != 0) {
            throw new IOException("CR3 output requires a separate stream at a byte boundary");
        }
        long sourcePosition = source.getStreamPosition();
        int sourceBitOffset = source.getBitOffset();
        long base = output.getStreamPosition();
        ByteOrder byteOrder = output.getByteOrder();
        output.setByteOrder(ByteOrder.BIG_ENDIAN);
        try {
            Range jpegSample = tracks.getFirst().sample();
            Range alignedJpeg = new Range(jpegSample.start(), jpegSample.size() + alignmentPadding(jpegSample.size()));
            List<Range> slots = List.of(this.thumbnail.slot(), this.preview.slot(), alignedJpeg,
                    tracks.get(1).sample(), tracks.get(2).sample());
            List<Payload> payloads = List.of(thumbnail, preview, jpeg, reducedRaw, raw);
            List<Replacement> replacements = new ArrayList<>(slots.size());
            byte[] buffer = new byte[64 * 1024];
            long position = 0;
            for (int i = 0; i < slots.size(); i++) {
                Range slot = slots.get(i);
                copy(output, position, slot.start(), buffer);
                long payloadStart = output.getStreamPosition();
                payloads.get(i).write(output);
                long payloadSize = output.getStreamPosition() - payloadStart;
                if (payloadSize <= 0 || payloadSize > UINT32_MAX || output.getBitOffset() != 0) {
                    throw new IOException("Unsupported CR3 replacement payload size or alignment");
                }
                int padding = i == 0 || i == 2 ? alignmentPadding(payloadSize) : 0;
                if (padding > 0) {
                    output.write(new byte[padding]);
                }
                replacements.add(new Replacement(slot, payloadSize, padding));
                position = slot.end();
            }
            copy(output, position, sourceSize, buffer);
            long outputEnd = output.getStreamPosition();

            // The stream can contain a prefix, while the file's offsets are always CR3-relative.
            output.setByteOrder(ByteOrder.BIG_ENDIAN);
            for (IsoBox box : resizedBoxes) {
                long sizeField = box.offset() + (box.extendedSize() ? 8 : 0);
                writeField(output, base, sizeField, box.extendedSize(), newBoxSize(box, replacements), replacements);
            }
            writeField(output, base, this.thumbnail.sizeField(), false, replacements.get(0).payloadSize(), replacements);
            writeField(output, base, this.preview.sizeField(), false, replacements.get(1).payloadSize(), replacements);
            for (int i = 0; i < tracks.size(); i++) {
                SampleTable table = tracks.get(i);
                long size = i < 3 ? replacements.get(i + 2).payloadSize() : table.track().dataSize();
                writeField(output, base, table.sizeField(), false, size, replacements);
                long offset = relocate(table.track().dataOffset(), replacements);
                writeField(output, base, table.offsetField(), table.wideOffset(), offset, replacements);
            }
            for (BoxReference reference : ctboReferences) {
                IsoBox target = reference.target();
                writeField(output, base, reference.offsetField(), true, relocate(target.offset(), replacements),
                        replacements);
                writeField(output, base, reference.offsetField() + 8, true, newBoxSize(target, replacements),
                        replacements);
            }
            output.seek(outputEnd);
        } finally {
            output.setByteOrder(byteOrder);
            source.seek(sourcePosition);
            source.setBitOffset(sourceBitOffset);
        }
    }

    private void copy(ImageOutputStream output, long start, long end, byte[] buffer) throws IOException {
        source.seek(start);
        while (start < end) {
            int count = (int) Math.min(end - start, buffer.length);
            source.readFully(buffer, 0, count);
            output.write(buffer, 0, count);
            start += count;
        }
    }

    private static long newBoxSize(IsoBox box, List<Replacement> replacements) {
        return relocate(end(box), replacements) - relocate(box.offset(), replacements);
    }

    private static long relocate(long position, List<Replacement> replacements) {
        long updated = position;
        for (Replacement replacement : replacements) {
            if (position < replacement.original().end()) {
                break;
            }
            updated += replacement.size() - replacement.original().size();
        }
        return updated;
    }

    private static void writeField(ImageOutputStream output, long base, long field, boolean wide, long value,
                                   List<Replacement> replacements) throws IOException {
        if (value < 0 || (!wide && value > UINT32_MAX)) {
            throw new IOException("CR3 output exceeds a template field's capacity");
        }
        output.seek(base + relocate(field, replacements));
        if (wide) {
            output.writeLong(value);
        } else {
            output.writeInt((int) value);
        }
    }

    @FunctionalInterface
    interface Payload {
        void write(ImageOutputStream output) throws IOException;
    }

    private record Range(long start, long size) {
        long end() { return start + size; }
    }

    private record EmbeddedJpeg(Range slot, long sizeField, int width, int height) {}
    private record BoxReference(long offsetField, IsoBox target) {}
    private record Replacement(Range original, long payloadSize, int padding) {
        long size() { return payloadSize + padding; }
    }
    private record SampleTable(Cr3Track track, long offsetField, boolean wideOffset, long sizeField) {
        Range sample() { return new Range(track.dataOffset(), track.dataSize()); }
    }
}
