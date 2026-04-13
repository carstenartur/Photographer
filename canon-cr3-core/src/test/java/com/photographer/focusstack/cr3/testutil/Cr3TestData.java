package com.photographer.focusstack.cr3.testutil;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Utility for building synthetic ISO-BMFF / CR3 byte streams in tests.
 *
 * <p>Provides helpers to construct valid and invalid box structures,
 * ftyp boxes, and minimal CR3 containers with embedded JPEG data.</p>
 */
public final class Cr3TestData {

    private Cr3TestData() {
        // utility class
    }

    /** Builds a standard 8-byte-header ISO BMFF box. */
    public static byte[] buildBox(String type, byte[] payload) {
        int size = 8 + payload.length;
        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(size);
        buf.put(type.getBytes());
        buf.put(payload);
        return buf.array();
    }

    /** Builds a box with extended 64-bit size header (size field = 1). */
    public static byte[] buildExtendedSizeBox(String type, byte[] payload) {
        long totalSize = 16L + payload.length;
        ByteBuffer buf = ByteBuffer.allocate((int) totalSize).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(1); // signals 64-bit extended size
        buf.put(type.getBytes());
        buf.putLong(totalSize);
        buf.put(payload);
        return buf.array();
    }

    /** Builds a box with size=0 (extends to end of file). */
    public static byte[] buildSizeZeroBox(String type, byte[] payload) {
        int total = 8 + payload.length;
        ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(0); // size = 0 means extends to EOF
        buf.put(type.getBytes());
        buf.put(payload);
        return buf.array();
    }

    /** Builds an ftyp box with the given major brand and no compatible brands. */
    public static byte[] buildFtypBox(String brand) {
        ByteBuffer payload = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        payload.put(brand.getBytes());
        payload.putInt(0); // minor version
        return buildBox("ftyp", payload.array());
    }

    /** Builds an ftyp box with the given major brand and compatible brands. */
    public static byte[] buildFtypBox(String brand, int minorVersion, String... compatibleBrands) {
        int payloadSize = 8 + compatibleBrands.length * 4;
        ByteBuffer payload = ByteBuffer.allocate(payloadSize).order(ByteOrder.BIG_ENDIAN);
        payload.put(brand.getBytes());
        payload.putInt(minorVersion);
        for (String cb : compatibleBrands) {
            payload.put(cb.getBytes());
        }
        return buildBox("ftyp", payload.array());
    }

    /** Builds a CR3 ftyp box (brand "crx ", compatible "crx ", "isom"). */
    public static byte[] buildCr3FtypBox() {
        return buildFtypBox("crx ", 1, "crx ", "isom");
    }

    /**
     * Creates a minimal 2×2 JPEG image as a byte array.
     *
     * <p>Pixels: top-left red, top-right green, bottom-left blue, bottom-right white.</p>
     *
     * @return JPEG bytes
     */
    public static byte[] createMinimalJpeg() throws IOException {
        BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, 0xFF0000); // red pixel
        img.setRGB(1, 0, 0x00FF00); // green pixel
        img.setRGB(0, 1, 0x0000FF); // blue pixel
        img.setRGB(1, 1, 0xFFFFFF); // white pixel
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "JPEG", baos);
        return baos.toByteArray();
    }

    /**
     * Builds a minimal CR3-like file structure with embedded JPEG preview and thumbnail.
     *
     * <p>Structure:</p>
     * <pre>
     * ftyp (crx)
     * moov
     *   trak[0] (JPEG thumbnail) -> stbl -> stco/stsz pointing into mdat
     *   trak[1] (JPEG preview)   -> stbl -> stco/stsz pointing into mdat
     * mdat
     *   [thumbnail JPEG bytes] [preview JPEG bytes]
     * </pre>
     *
     * @return byte array of the synthetic CR3 file
     */
    public static byte[] buildMinimalCr3WithJpeg() throws IOException {
        byte[] jpegData = createMinimalJpeg();
        return buildMinimalCr3WithJpeg(jpegData, jpegData);
    }

    /**
     * Builds a minimal CR3-like file with the given thumbnail and preview JPEG data.
     */
    public static byte[] buildMinimalCr3WithJpeg(byte[] thumbnailJpeg, byte[] previewJpeg)
            throws IOException {
        // We need to know the final offsets, so calculate sizes first.
        // Strategy: build ftyp, then moov (with placeholder offsets), then mdat.
        // We calculate moov size first, then fix offsets.

        byte[] ftyp = buildCr3FtypBox();

        // Build stbl for each track: stsd + stsz + stco
        // stsd: full box (version/flags=4) + entry_count=4 + entry(size=4, type=4, reserved=6, dataRefIdx=2, codecFields=16, w=2, h=2)
        // stsz: full box (version/flags=4) + sample_size=4 + count=4 (+per-sample if sample_size==0)
        // stco: full box (version/flags=4) + count=4 + offset=4

        // For simplicity, we'll use a "jpeg" stsd entry with width/height

        // Placeholder offset for mdat content - we'll fix after calculating moov size
        // Track 0: thumbnail at mdat_start+8
        // Track 1: preview at mdat_start+8+thumbnailJpeg.length

        // Build track boxes bottom-up
        byte[] stsdThumb = buildStsd("jpeg", 2, 2);
        byte[] stszThumb = buildStsz(thumbnailJpeg.length);
        byte[] stcoThumbPlaceholder = buildStco(0); // placeholder, fix later

        byte[] stblThumbPayload = concat(stsdThumb, stszThumb, stcoThumbPlaceholder);
        byte[] stblThumb = buildBox("stbl", stblThumbPayload);
        byte[] minfThumb = buildBox("minf", stblThumb);
        byte[] mdiaThumb = buildBox("mdia", minfThumb);
        byte[] trakThumb = buildBox("trak", mdiaThumb);

        byte[] stsdPreview = buildStsd("jpeg", 2, 2);
        byte[] stszPreview = buildStsz(previewJpeg.length);
        byte[] stcoPreviewPlaceholder = buildStco(0); // placeholder, fix later

        byte[] stblPreviewPayload = concat(stsdPreview, stszPreview, stcoPreviewPlaceholder);
        byte[] stblPreview = buildBox("stbl", stblPreviewPayload);
        byte[] minfPreview = buildBox("minf", stblPreview);
        byte[] mdiaPreview = buildBox("mdia", minfPreview);
        byte[] trakPreview = buildBox("trak", mdiaPreview);

        byte[] moovPayload = concat(trakThumb, trakPreview);
        byte[] moov = buildBox("moov", moovPayload);

        // mdat payload = thumbnail + preview
        byte[] mdatPayload = concat(thumbnailJpeg, previewJpeg);
        byte[] mdat = buildBox("mdat", mdatPayload);

        // Now calculate actual offsets
        int mdatOffset = ftyp.length + moov.length;
        int mdatDataStart = mdatOffset + 8; // mdat header is 8 bytes
        int thumbDataOffset = mdatDataStart;
        int previewDataOffset = mdatDataStart + thumbnailJpeg.length;

        // Fix stco offsets in the moov box
        // We need to find the stco boxes and patch the offset values
        byte[] fullFile = concat(ftyp, moov, mdat);
        patchStcoOffset(fullFile, ftyp.length, 0, thumbDataOffset);
        patchStcoOffset(fullFile, ftyp.length, 1, previewDataOffset);

        return fullFile;
    }

    /** Builds a stsd full box for a visual track with the given codec and dimensions. */
    private static byte[] buildStsd(String codec, int width, int height) {
        // stsd: full box header (version/flags) + entry_count + entry
        // entry: size(4) + type(4) + reserved(6) + dataRefIdx(2) + pre-defined(16) + width(2) + height(2)
        int entryPayloadSize = 6 + 2 + 16 + 2 + 2; // = 28
        int entrySize = 8 + entryPayloadSize; // = 36

        ByteBuffer entry = ByteBuffer.allocate(entrySize).order(ByteOrder.BIG_ENDIAN);
        entry.putInt(entrySize);
        entry.put(codec.getBytes());
        entry.put(new byte[6]); // reserved
        entry.putShort((short) 1); // data ref index
        entry.put(new byte[16]); // pre-defined + version + revision + vendor etc.
        entry.putShort((short) width);
        entry.putShort((short) height);

        int stsdPayloadSize = 4 + 4 + entrySize; // version/flags + count + entry
        ByteBuffer stsdPayload = ByteBuffer.allocate(stsdPayloadSize).order(ByteOrder.BIG_ENDIAN);
        stsdPayload.putInt(0); // version + flags
        stsdPayload.putInt(1); // entry count
        stsdPayload.put(entry.array());

        return buildBox("stsd", stsdPayload.array());
    }

    /** Builds a stsz full box with one sample of the given size. */
    private static byte[] buildStsz(int sampleSize) {
        ByteBuffer payload = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN);
        payload.putInt(0); // version + flags
        payload.putInt(sampleSize); // sample size (uniform)
        payload.putInt(1); // sample count
        return buildBox("stsz", payload.array());
    }

    /** Builds a stco full box with one chunk offset. */
    private static byte[] buildStco(int offset) {
        ByteBuffer payload = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN);
        payload.putInt(0); // version + flags
        payload.putInt(1); // entry count
        payload.putInt(offset);
        return buildBox("stco", payload.array());
    }

    /**
     * Patches the chunk offset in the Nth stco box found within the moov box
     * in the given file byte array.
     */
    private static void patchStcoOffset(byte[] file, int moovStart, int trackIndex, int newOffset) {
        int stcoCount = 0;
        // Scan the moov region for "stco" boxes
        for (int i = moovStart; i < file.length - 4; i++) {
            if (file[i] == 's' && file[i + 1] == 't' && file[i + 2] == 'c' && file[i + 3] == 'o') {
                if (stcoCount == trackIndex) {
                    // stco type is at offset i within file; the box started 4 bytes before (size field)
                    // After type: version/flags (4) + count (4) + offset (4)
                    int offsetPos = i + 4 + 4 + 4; // type(4) + version(4) + count(4)
                    file[offsetPos] = (byte) (newOffset >> 24);
                    file[offsetPos + 1] = (byte) (newOffset >> 16);
                    file[offsetPos + 2] = (byte) (newOffset >> 8);
                    file[offsetPos + 3] = (byte) newOffset;
                    return;
                }
                stcoCount++;
            }
        }
    }

    /** Concatenates multiple byte arrays. */
    public static byte[] concat(byte[]... arrays) {
        int totalLen = 0;
        for (byte[] a : arrays) {
            totalLen += a.length;
        }
        byte[] result = new byte[totalLen];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, result, pos, a.length);
            pos += a.length;
        }
        return result;
    }

    /** Creates an ImageInputStream from byte data. */
    public static MemoryCacheImageInputStream toImageInputStream(byte[] data) {
        return new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
    }
}
