package com.photographer.focusstack.cr3.encode;

import javax.imageio.stream.ImageOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** Serializes the four-track still-image layout used by the built-in lossless CR3 profiles. */
final class Cr3ContainerWriter {
    private static final UUID CANON_METADATA = UUID.fromString("85c0b687-820f-11e0-8111-f4ce462b6a48");
    private static final UUID XMP = UUID.fromString("be7acfcb-97a9-42e8-9c71-999491e3afac");
    private static final UUID CANON_PREVIEW = UUID.fromString("eaf42b5e-1c98-4b88-b9fb-b7dc406e4d16");

    private static final int BOX_HEADER_SIZE = 8;
    private static final int TIMESCALE = 1;
    private static final int SAMPLE_DURATION = 1;
    private static final int TRACK_COUNT = 4;
    private static final int CTMD_RECORD_HEADER_SIZE = 12;
    private static final long UINT32_MAX = 0xffff_ffffL;
    private static final byte[] ZERO_BYTES = new byte[32];
    private static final byte[] EMPTY_XMP = emptyXmpPacket();

    private final ImageOutputStream output;
    private final long origin;

    private Cr3ContainerWriter(ImageOutputStream output) throws IOException {
        this.output = output;
        this.origin = output.getStreamPosition();
    }

    @FunctionalInterface
    interface Payload {
        void write(ImageOutputStream output) throws IOException;
    }

    static void write(ImageOutputStream output, Cr3CameraProfile profile,
                      byte[] cmt1, byte[] cmt2, byte[] cmt3, byte[] cmt4, byte[] ctmd,
                      Payload raw, Payload reducedRaw, Payload jpeg, Payload preview, Payload thumbnail)
            throws IOException {
        if (output.getBitOffset() != 0) {
            throw new IOException("A CR3 file must start at a byte boundary");
        }
        var writer = new Cr3ContainerWriter(output);
        ByteOrder savedOrder = output.getByteOrder();
        output.setByteOrder(ByteOrder.BIG_ENDIAN);
        try {
            writer.writeFileType();
            var movie = writer.writeMovie(profile, cmt1, cmt2, cmt3, cmt4, ctmd, thumbnail);
            var xmp = writer.writeXmp();
            var previewBox = writer.writePreview(profile, preview);
            var mdat = writer.writeMediaData(movie, raw, reducedRaw, jpeg, ctmd);
            writer.patchTopLevelReferences(movie.ctboEntries(), xmp, previewBox, mdat);
        } finally {
            output.setByteOrder(savedOrder);
        }
    }

    private void writeFileType() throws IOException {
        writeHeader("ftyp", 24);
        output.writeBytes("crx ");
        output.writeInt(1); // Minor version
        output.writeBytes("crx ");
        output.writeBytes("isom");
    }

    private MoviePatches writeMovie(Cr3CameraProfile profile, byte[] cmt1, byte[] cmt2,
                                    byte[] cmt3, byte[] cmt4, byte[] ctmd, Payload thumbnail)
            throws IOException {
        long moov = beginBox("moov");
        long ctboEntries = writeCanonMetadata(profile, cmt1, cmt2, cmt3, cmt4, thumbnail);
        writeMovieHeader();
        boolean explicitSize = profile.explicitImageSampleSizes();
        var jpeg = writeImageTrack(1, profile.jpegWidth(), profile.jpegHeight(), explicitSize, null, null);
        var reduced = profile.reducedRaw();
        var small = writeImageTrack(2, reduced.width(), reduced.height(), explicitSize,
                reduced.cmp1Payload(), reduced.iad1Payload());
        var full = profile.raw();
        var raw = writeImageTrack(3, full.width(), full.height(), explicitSize,
                full.cmp1Payload(), full.iad1Payload());
        var metadata = writeTrack(4, 0, 0, "meta", "CTMD", false, () -> writeTimedMetadataEntry(ctmd));
        endBox(moov);
        return new MoviePatches(ctboEntries, jpeg, small, raw, metadata);
    }

    private long writeCanonMetadata(Cr3CameraProfile profile, byte[] cmt1, byte[] cmt2,
                                    byte[] cmt3, byte[] cmt4, Payload thumbnail) throws IOException {
        long metadata = beginUuid(CANON_METADATA);
        byte[] version = profile.compressorVersion().getBytes(StandardCharsets.US_ASCII);
        writeHeader("CNCV", BOX_HEADER_SIZE + (long) version.length);
        output.write(version);
        writeTrackDescriptors();
        long ctboEntries = writeReferenceTable(profile.ctboEntryCount());
        writeAlignmentBox(0);
        writeTiffBox("CMT1", cmt1);
        writeTiffBox("CMT2", cmt2);
        writeTiffBox("CMT3", cmt3);
        writeTiffBox("CMT4", cmt4);
        writeThumbnail(profile, thumbnail);
        endBox(metadata);
        return ctboEntries;
    }

    private void writeTrackDescriptors() throws IOException {
        long cctp = beginBox("CCTP");
        // Canon prefix measured in the supported four-track layouts.
        output.writeInt(0);
        output.writeInt(1);
        output.writeInt(3);
        writeTrackDescriptor(16, 1); // Full-size JPEG
        writeTrackDescriptor(1, 2); // Reduced CRX
        writeTrackDescriptor(0, 3); // Full CRX
        endBox(cctp);
    }

    private void writeTrackDescriptor(long imageType, int trackId) throws IOException {
        writeHeader("CCDT", 24);
        output.writeLong(imageType);
        output.writeInt(0); // No dual-pixel stream
        output.writeInt(trackId);
    }

    private long writeReferenceTable(int entryCount) throws IOException {
        long ctbo = beginBox("CTBO");
        output.writeInt(entryCount);
        long entries = output.getStreamPosition();
        for (int i = 1; i <= entryCount; i++) {
            output.writeInt(i);
            output.writeLong(0);
            output.writeLong(0);
        }
        endBox(ctbo);
        return entries;
    }

    private void writeTiffBox(String type, byte[] tiff) throws IOException {
        int padding = alignmentPadding(tiff.length);
        writeHeader(type, BOX_HEADER_SIZE + (long) tiff.length + padding);
        output.write(tiff);
        writeZeros(padding);
    }

    private void writeThumbnail(Cr3CameraProfile profile, Payload thumbnail) throws IOException {
        long box = beginBox("THMB");
        output.writeInt(0); // Version 0, flags 0
        output.writeShort(profile.thumbnailWidth());
        output.writeShort(profile.thumbnailHeight());
        long sizeField = output.getStreamPosition();
        output.writeInt(0);
        output.writeShort(profile.thumbnailFlag());
        output.writeShort(0);
        long length = writePayload(thumbnail);
        writeZeros(alignmentPadding(length));
        patchInt(sizeField, length);
        endBox(box);
    }

    private void writeMovieHeader() throws IOException {
        long box = beginBox("mvhd");
        output.writeInt(0); // Version and flags
        writeZeros(8); // Creation and modification timestamps
        output.writeInt(TIMESCALE);
        output.writeInt(SAMPLE_DURATION);
        output.writeInt(1 << 16); // Playback rate, 16.16
        output.writeShort(1 << 8); // Volume, 8.8
        writeZeros(10); // Reserved
        writeIdentityMatrix();
        writeZeros(24); // Predefined
        output.writeInt(TRACK_COUNT + 1);
        endBox(box);
    }

    private SamplePatch writeImageTrack(int trackId, int width, int height, boolean explicitSize,
                                        byte[] cmp1, byte[] iad1) throws IOException {
        return writeTrack(trackId, width, height, "vide", "CRAW", explicitSize,
                () -> writeVisualEntry(width, height, cmp1, iad1));
    }

    private SamplePatch writeTrack(int trackId, int width, int height, String handler, String entryType,
                                   boolean explicitSize, BoxBody entry) throws IOException {
        long trak = beginBox("trak");
        writeTrackHeader(trackId, width, height);
        long mdia = beginBox("mdia");
        writeMediaHeader();
        writeHandler(handler);
        long minf = beginBox("minf");
        if ("vide".equals(handler)) {
            writeHeader("vmhd", 20);
            output.writeInt(1);
            writeZeros(8); // Graphics mode and opcolor
        } else {
            writeHeader("nmhd", 12);
            output.writeInt(0);
        }
        writeDataReference();

        long stbl = beginBox("stbl");
        long stsd = beginBox("stsd");
        output.writeInt(0); // Version and flags
        output.writeInt(1); // Single sample entry
        long sampleEntry = beginBox(entryType);
        writeZeros(6); // Reserved
        output.writeShort(1); // Data reference index
        entry.write();
        endBox(sampleEntry);
        endBox(stsd);

        writeHeader("stts", 24);
        output.writeInt(0); // Version and flags
        output.writeInt(1); // Entry count
        output.writeInt(1); // Sample count
        output.writeInt(SAMPLE_DURATION);
        writeHeader("stsc", 28);
        output.writeInt(0); // Version and flags
        output.writeInt(1); // Entry count
        output.writeInt(1); // First chunk
        output.writeInt(1); // Samples per chunk
        output.writeInt(1); // Sample description index

        long sizeField = writeSampleSizeTable(explicitSize);
        writeAlignmentBox(0);
        writeHeader("co64", 24);
        output.writeInt(0); // Version and flags
        output.writeInt(1); // Chunk count
        long offsetField = output.getStreamPosition();
        output.writeLong(0);

        endBox(stbl);
        endBox(minf);
        endBox(mdia);
        endBox(trak);
        return new SamplePatch(sizeField, offsetField);
    }

    private void writeTrackHeader(int trackId, int width, int height) throws IOException {
        long box = beginBox("tkhd");
        output.writeInt(7); // Enabled, in movie and in preview
        writeZeros(8); // Creation and modification timestamps
        output.writeInt(trackId);
        writeZeros(4); // Reserved
        output.writeInt(SAMPLE_DURATION);
        writeZeros(8); // Reserved
        writeZeros(8); // Layer, alternate group, volume and reserved
        writeIdentityMatrix();
        output.writeInt(width << 16);
        output.writeInt(height << 16);
        endBox(box);
    }

    private void writeIdentityMatrix() throws IOException {
        output.writeInt(1 << 16);
        output.writeLong(0);
        output.writeInt(0);
        output.writeInt(1 << 16);
        output.writeInt(0);
        output.writeLong(0);
        output.writeInt(1 << 30);
    }

    private void writeMediaHeader() throws IOException {
        long box = beginBox("mdhd");
        output.writeInt(0); // Version and flags
        writeZeros(8); // Creation and modification timestamps
        output.writeInt(TIMESCALE);
        output.writeInt(SAMPLE_DURATION);
        output.writeShort(0x15c7); // ISO 639-2 "eng"
        output.writeShort(0); // Predefined
        endBox(box);
    }

    private void writeHandler(String type) throws IOException {
        writeHeader("hdlr", 33);
        writeZeros(8); // Version, flags and predefined
        output.writeBytes(type);
        writeZeros(12); // Reserved
        output.writeByte(0); // Empty, null-terminated name
    }

    private void writeDataReference() throws IOException {
        writeHeader("dinf", 36);
        writeHeader("dref", 28);
        output.writeInt(0); // Version and flags
        output.writeInt(1); // Entry count
        writeHeader("url ", 12);
        output.writeInt(1); // Self-contained media data
    }

    private void writeVisualEntry(int width, int height, byte[] cmp1, byte[] iad1) throws IOException {
        writeZeros(16); // Predefined and reserved
        output.writeShort(width);
        output.writeShort(height);
        output.writeInt(72 << 16); // Horizontal resolution, 16.16
        output.writeInt(72 << 16); // Vertical resolution, 16.16
        output.writeInt(0); // Reserved
        output.writeShort(1); // Frame count
        writeZeros(32); // Compressor name
        output.writeShort(24); // Depth
        output.writeShort(-1); // Predefined

        boolean jpeg = cmp1 == null;
        output.writeShort(jpeg ? 3 : 1); // Canon visual entry extension
        output.writeShort(jpeg ? 0 : 1);
        if (jpeg) {
            writeHeader("JPEG", 12);
            output.writeInt(0);
        } else {
            writeHeader("CMP1", BOX_HEADER_SIZE + (long) cmp1.length);
            output.write(cmp1);
            long cdi1 = beginBox("CDI1");
            output.writeInt(0);
            writeHeader("IAD1", BOX_HEADER_SIZE + (long) iad1.length);
            output.write(iad1);
            endBox(cdi1);
        }
        writeFreeBox(2);
    }

    private void writeTimedMetadataEntry(byte[] ctmd) throws IOException {
        long countField = output.getStreamPosition();
        output.writeInt(0);
        var records = ByteBuffer.wrap(ctmd).order(ByteOrder.LITTLE_ENDIAN);
        int count = 0;
        while (records.hasRemaining()) {
            int start = records.position();
            int available = records.remaining();
            if (available < CTMD_RECORD_HEADER_SIZE) {
                throw new IOException("Truncated CR3 timed metadata record");
            }
            int size = records.getInt();
            int type = Short.toUnsignedInt(records.getShort());
            if (size < CTMD_RECORD_HEADER_SIZE || size > available) {
                throw new IOException("Invalid CR3 timed metadata record length");
            }
            output.writeInt(type);
            output.writeInt(size);
            records.position(start + size);
            count++;
        }
        patchInt(countField, count);
    }

    private long writeSampleSizeTable(boolean explicitSize) throws IOException {
        if (explicitSize) {
            // Align the first per-sample size field after its 20-byte box prefix.
            writeAlignmentBox(20);
        }
        long stsz = beginBox("stsz");
        output.writeInt(0); // Version and flags
        long sizeField = output.getStreamPosition();
        output.writeInt(0); // Fixed size, or zero when sizes follow the count
        output.writeInt(1); // Sample count
        if (explicitSize) {
            sizeField = output.getStreamPosition();
            output.writeInt(0);
        }
        endBox(stsz);
        return sizeField;
    }

    private BoxRange writeXmp() throws IOException {
        long box = beginUuid(XMP);
        output.write(EMPTY_XMP);
        return new BoxRange(box - origin, endBox(box));
    }

    private static byte[] emptyXmpPacket() {
        // Reserve the same packet space as the measured camera layout for subsequent metadata edits.
        byte[] packet = new byte[64 * 1024];
        Arrays.fill(packet, (byte) ' ');
        byte[] start = """
                <?xpacket begin="\uFEFF" id="W5M0MpCehiHzreSzNTczkc9d"?>
                <x:xmpmeta xmlns:x="adobe:ns:meta/">
                  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                    <rdf:Description rdf:about=""/>
                  </rdf:RDF>
                </x:xmpmeta>
                """.getBytes(StandardCharsets.UTF_8);
        byte[] end = "\n<?xpacket end=\"w\"?>".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(start, 0, packet, 0, start.length);
        System.arraycopy(end, 0, packet, packet.length - end.length, end.length);
        return packet;
    }

    private BoxRange writePreview(Cr3CameraProfile profile, Payload preview) throws IOException {
        long uuid = beginUuid(CANON_PREVIEW);
        output.writeInt(0);
        output.writeInt(1);
        long box = beginBox("PRVW");
        output.writeInt(0);
        output.writeShort(1);
        output.writeShort(profile.previewWidth());
        output.writeShort(profile.previewHeight());
        output.writeShort(profile.previewFlag());
        long sizeField = output.getStreamPosition();
        output.writeInt(0);
        patchInt(sizeField, writePayload(preview));
        endBox(box);
        return new BoxRange(uuid - origin, endBox(uuid));
    }

    private BoxRange writeMediaData(MoviePatches movie, Payload raw, Payload reducedRaw, Payload jpeg, byte[] ctmd)
            throws IOException {
        long start = output.getStreamPosition();
        writeHeader("mdat", 1); // Extended-size sentinel
        long sizeField = output.getStreamPosition();
        output.writeLong(0);
        writeSample(movie.jpeg(), jpeg);
        writeSample(movie.reducedRaw(), reducedRaw);
        writeSample(movie.raw(), raw);
        writeSample(movie.metadata(), stream -> stream.write(ctmd));
        long size = output.getStreamPosition() - start;
        patchLong(sizeField, size);
        return new BoxRange(start - origin, size);
    }

    private void writeSample(SamplePatch patch, Payload payload) throws IOException {
        long offset = output.getStreamPosition() - origin;
        long length = writePayload(payload);
        writeZeros(alignmentPadding(length));
        patchInt(patch.sizeField(), length);
        patchLong(patch.offsetField(), offset);
    }

    private long writePayload(Payload payload) throws IOException {
        long start = output.getStreamPosition();
        payload.write(output);
        output.setByteOrder(ByteOrder.BIG_ENDIAN);
        long length = output.getStreamPosition() - start;
        if (length <= 0 || length > UINT32_MAX || output.getBitOffset() != 0) {
            throw new IOException("Invalid CR3 payload size or byte alignment");
        }
        return length;
    }

    private void patchTopLevelReferences(long entries, BoxRange xmp, BoxRange preview, BoxRange mdat)
            throws IOException {
        long end = output.getStreamPosition();
        output.seek(entries);
        writeReference(1, xmp);
        writeReference(2, preview);
        writeReference(3, mdat);
        output.seek(end);
    }

    private void writeReference(int index, BoxRange box) throws IOException {
        output.writeInt(index);
        output.writeLong(box.offset());
        output.writeLong(box.size());
    }

    private long beginUuid(UUID uuid) throws IOException {
        long start = beginBox("uuid");
        output.writeLong(uuid.getMostSignificantBits());
        output.writeLong(uuid.getLeastSignificantBits());
        return start;
    }

    private long beginBox(String type) throws IOException {
        long start = output.getStreamPosition();
        writeHeader(type, 0);
        return start;
    }

    private long endBox(long start) throws IOException {
        long size = output.getStreamPosition() - start;
        patchInt(start, size);
        return size;
    }

    private void writeHeader(String type, long size) throws IOException {
        writeUint32(size);
        output.writeBytes(type);
    }

    private void writeAlignmentBox(int bytesAfterBox) throws IOException {
        long offset = output.getStreamPosition() - origin + BOX_HEADER_SIZE + bytesAfterBox;
        writeFreeBox(alignmentPadding(offset));
    }

    private void writeFreeBox(int padding) throws IOException {
        writeHeader("free", BOX_HEADER_SIZE + padding);
        writeZeros(padding);
    }

    private static int alignmentPadding(long size) {
        return (int) (-size & (Long.BYTES - 1));
    }

    private void writeZeros(int length) throws IOException {
        output.write(ZERO_BYTES, 0, length);
    }

    private void patchInt(long position, long value) throws IOException {
        long end = output.getStreamPosition();
        output.seek(position);
        writeUint32(value);
        output.seek(end);
    }

    private void patchLong(long position, long value) throws IOException {
        long end = output.getStreamPosition();
        output.seek(position);
        output.writeLong(value);
        output.seek(end);
    }

    private void writeUint32(long value) throws IOException {
        if (value < 0 || value > UINT32_MAX) {
            throw new IOException("CR3 field exceeds its unsigned 32-bit range");
        }
        output.writeInt((int) value);
    }

    @FunctionalInterface
    private interface BoxBody {
        void write() throws IOException;
    }

    private record BoxRange(long offset, long size) {
    }

    private record SamplePatch(long sizeField, long offsetField) {
    }

    private record MoviePatches(long ctboEntries, SamplePatch jpeg, SamplePatch reducedRaw,
                                SamplePatch raw, SamplePatch metadata) {
    }
}
