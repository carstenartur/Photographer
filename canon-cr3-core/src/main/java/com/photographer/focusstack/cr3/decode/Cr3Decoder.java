package com.photographer.focusstack.cr3.decode;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.metadata.Cr3Metadata;
import com.photographer.focusstack.cr3.metadata.Cr3MetadataReader;
import com.photographer.focusstack.cr3.preview.Cr3PreviewExtractor;
import com.photographer.focusstack.cr3.processing.BayerDemosaicer;
import com.photographer.focusstack.cr3.processing.BlackLevel;
import com.photographer.focusstack.cr3.processing.BlackLevelProcessor;
import com.photographer.focusstack.cr3.processing.CameraColorMatrixRegistry;
import com.photographer.focusstack.cr3.processing.ColorMatrixProcessor;
import com.photographer.focusstack.cr3.processing.DualIlluminantColorMatrix;
import com.photographer.focusstack.cr3.processing.ExifOrientationTransformer;
import com.photographer.focusstack.cr3.processing.ObBlackLevelEstimator;
import com.photographer.focusstack.cr3.processing.SrgbConverter;
import com.photographer.focusstack.cr3.processing.WhiteBalanceProcessor;
import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import com.photographer.focusstack.cr3.raw.codec.CrxHeader;
import com.photographer.focusstack.cr3.raw.codec.CrxLosslessRawDecoder;
import com.photographer.focusstack.cr3.raw.codec.CrxLossyDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Main entry point for decoding Canon CR3 files.
 *
 * <p>The decoder orchestrates parsing, metadata extraction, and image decoding.
 * It does not depend on {@code javax.imageio.ImageReader} — the ImageIO adapter
 * is a separate layer in the {@code canon-cr3-imageio} module.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * Cr3Decoder decoder = new Cr3Decoder();
 * try (ImageInputStream input = ImageIO.createImageInputStream(new File("photo.cr3"))) {
 *     decoder.setInput(input);
 *     BufferedImage preview = decoder.decode(Cr3DecodeOptions.defaults());
 * }
 * }</pre>
 */
public final class Cr3Decoder implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Cr3Decoder.class);

    private ImageInputStream input;
    private Cr3Container container;
    private Cr3Metadata metadata;
    /**
     * Source file path when {@link #setInput(Path)} was used; enables a fast
     * memory-mapped read of the CRX RAW track in {@link #decodeRawBayer}.
     * {@code null} when the decoder was driven by the
     * {@link #setInput(ImageInputStream)} overload.
     */
    private Path sourceFile;
    /**
     * {@code true} when the decoder itself opened {@link #input} (via
     * {@link #setInput(Path)}) and therefore owns its lifecycle. {@code false}
     * when the caller supplied the stream via {@link #setInput(ImageInputStream)}
     * — in that case the caller retains ownership and {@link #reset} / {@link #close}
     * must NOT close it.
     */
    private boolean ownsInput;

    /**
     * Sets the input stream for decoding.
     *
     * <p>The caller retains ownership of the supplied stream; {@link #reset} and
     * {@link #close} will release the decoder's reference but will <em>not</em>
     * close the stream.</p>
     *
     * @param input the image input stream positioned at the start of the CR3 file
     * @throws IOException if an I/O error occurs during initial parsing
     * @throws Cr3Exception if the file is not a valid CR3 file
     */
    public void setInput(ImageInputStream input) throws IOException, Cr3Exception {
        closeOwnedInputQuietly();
        this.input = input;
        this.sourceFile = null;
        this.ownsInput = false;
        this.container = Cr3Parser.parse(input);
        this.metadata = extractMetadata();
        LOG.info("CR3 input set: {}", container);
    }

    /**
     * File-based input variant. The file is opened with a {@code FileImageInputStream}
     * for the parser/metadata/preview paths, and additionally retained so that
     * {@link #decodeRawBayer} can pull the (typically multi-MB) CRX RAW track
     * via a single {@link FileChannel#map memory-mapped read} instead of the
     * {@code ImageInputStream.readFully(byte[])} bulk copy.
     *
     * <p>The decoder takes ownership of the internally-opened stream; the caller
     * must invoke {@link #reset} or {@link #close} (or use the decoder in a
     * try-with-resources block) to release the underlying file handle.</p>
     *
     * <p>If parsing fails the internally-opened stream is closed before the
     * exception propagates so no file descriptor leaks on the error path.</p>
     *
     * @param file the CR3 file to decode
     * @throws IOException if the file cannot be opened or parsed
     * @throws Cr3Exception if the file is not a valid CR3 file
     */
    public void setInput(Path file) throws IOException, Cr3Exception {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        closeOwnedInputQuietly();
        ImageInputStream iis = ImageIO.createImageInputStream(file.toFile());
        if (iis == null) {
            throw new IOException("Could not open ImageInputStream for " + file);
        }
        // Parse into local variables first; only commit to the instance fields
        // once parsing succeeded, otherwise close the stream we just opened so
        // the file descriptor is released on the error path.
        Cr3Container parsedContainer;
        Cr3Metadata parsedMetadata;
        try {
            parsedContainer = Cr3Parser.parse(iis);
            parsedMetadata = Cr3MetadataReader.read(iis, parsedContainer);
        } catch (IOException | Cr3Exception | RuntimeException e) {
            try {
                iis.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
        this.input = iis;
        this.sourceFile = file;
        this.ownsInput = true;
        this.container = parsedContainer;
        this.metadata = parsedMetadata;
        LOG.info("CR3 input set (file={}): {}", file, container);
    }

    /**
     * Decodes the CR3 file according to the given options.
     *
     * @param options decode options controlling output mode and processing
     * @return the decoded image
     * @throws Cr3Exception if decoding fails
     * @throws IOException  if an I/O error occurs
     * @throws IllegalStateException if no input has been set
     */
    public BufferedImage decode(Cr3DecodeOptions options) throws Cr3Exception, IOException {
        if (input == null || container == null) {
            throw new IllegalStateException("No input set — call setInput() first");
        }

        LOG.info("Decoding CR3 with options: {}", options);

        return switch (options.outputMode()) {
            case PREVIEW -> Cr3PreviewExtractor.extractPreview(input, container);
            case THUMBNAIL -> Cr3PreviewExtractor.extractThumbnail(input, container);
            case RAW_PROCESSED -> decodeRawProcessed(options);
            case RAW_LINEAR -> decodeRawLinear(options);
        };
    }

    /**
     * Returns the parsed CR3 container structure.
     *
     * @return the container, or {@code null} if no input is set
     */
    public Cr3Container getContainer() {
        return container;
    }

    /**
     * Returns the extracted metadata.
     *
     * @return the metadata, or {@code null} if no input is set
     */
    public Cr3Metadata getMetadata() {
        return metadata;
    }

    /**
     * Resets the decoder, releasing the input stream reference and closing the
     * underlying {@link ImageInputStream} when the decoder owns it (i.e. the
     * input was supplied via {@link #setInput(Path)}). Streams supplied through
     * {@link #setInput(ImageInputStream)} are <em>not</em> closed — their
     * lifecycle remains the caller's responsibility.
     */
    public void reset() {
        closeOwnedInputQuietly();
        this.input = null;
        this.container = null;
        this.metadata = null;
        this.sourceFile = null;
        this.ownsInput = false;
    }

    /**
     * Equivalent to {@link #reset}; provided so the decoder can be used in a
     * try-with-resources block when the input was opened internally via
     * {@link #setInput(Path)}.
     */
    @Override
    public void close() {
        reset();
    }

    private void closeOwnedInputQuietly() {
        if (ownsInput && input != null) {
            try {
                input.close();
            } catch (IOException e) {
                LOG.warn("Failed to close internally-owned ImageInputStream", e);
            }
        }
    }

    private Cr3Metadata extractMetadata() throws IOException {
        // Delegate to Cr3MetadataReader — metadata and image data are separate concerns
        return Cr3MetadataReader.read(input, container);
    }

    /**
     * Full RAW processing pipeline:
     * CrxLosslessRawDecoder → BlackLevel (on uncropped) → Crop → WhiteBalance → Demosaic → ColorMatrix → sRGB → Orientation
     *
     * <p><b>Important:</b> OB (optical black) column estimation must happen
     * <em>before</em> cropping to the active area. The OB columns sit outside
     * the active area; if we crop first, the estimator would read real image
     * pixels instead of masked OB pixels, producing wildly wrong black levels.</p>
     */
    private BufferedImage decodeRawProcessed(Cr3DecodeOptions options) throws Cr3Exception, IOException {
        boolean shouldCrop = options.cropToActiveArea();

        // Always decode uncropped — OB columns are needed for black level estimation
        Cr3RawImage rawImage = decodeRawBayerUncropped(options);

        // 1. Black level subtraction on the UNCROPPED image (OB columns still present).
        //    We also record the minimum subtracted level so the demosaic step can
        //    normalise against the correct post-BL white level (avoids a slight
        //    green-channel under-exposure when G_mult ≈ 1.0 but R/B are clipped
        //    by the WB multiplier).
        int minBlackLevelSubtracted = 0;
        if (options.applyBlackLevel()) {
            BlackLevel blackLevel = resolveBlackLevel(rawImage);
            BlackLevelProcessor.process(rawImage, blackLevel);
            minBlackLevelSubtracted = effectiveMinBlackLevel(blackLevel);
        }

        // 2. Now crop to active area (OB columns removed after estimation)
        if (shouldCrop) {
            rawImage = cropToActiveArea(rawImage);
        }

        // 3. White balance — use MakerNote multipliers if available
        if (options.applyWhiteBalance()) {
            double[] wbMultipliers = resolveWhiteBalance();
            WhiteBalanceProcessor.process(rawImage, wbMultipliers);
        }

        // 4. Bayer demosaicing → full-colour RGB.
        //    The white level passed here is (maxValue − minBlackLevel) so that
        //    the green channel (G_mult ≈ 1.0, not amplified by WB) reaches 255
        //    at sensor saturation rather than stopping at ~97%.
        int whiteLevel = Math.max(1, rawImage.maxValue() - minBlackLevelSubtracted);
        BufferedImage rgb = BayerDemosaicer.demosaic(rawImage, whiteLevel);

        // 5. Color matrix correction — use camera-specific if available
        if (options.applyColorMatrix()) {
            double[][] colorMatrix = resolveColorMatrix();
            ColorMatrixProcessor.process(rgb, colorMatrix);
        }

        // 6. sRGB gamma conversion
        if (options.applySrgbConversion()) {
            SrgbConverter.process(rgb);
        }

        // 7. Apply EXIF orientation (rotation/flip)
        if (metadata != null && metadata.orientation() > 1) {
            rgb = ExifOrientationTransformer.apply(rgb, metadata.orientation());
        }

        return rgb;
    }

    /**
     * Returns the minimum black level subtracted across all channels.
     * Used to compute the effective white level for demosaic normalisation.
     */
    private static int effectiveMinBlackLevel(BlackLevel blackLevel) {
        return switch (blackLevel.mode()) {
            case GLOBAL -> blackLevel.globalLevel();
            case PER_CHANNEL -> {
                int[] levels = blackLevel.perChannelLevels();
                int min = levels[0];
                for (int l : levels) {
                    if (l < min) min = l;
                }
                yield min;
            }
            // For per-row, use the minimum across all per-row entries so the
            // effective white level reflects the actual OB-estimated levels rather
            // than the 14-bit default.
            case PER_ROW -> blackLevel.minPerRowLevel();
        };
    }

    /**
     * Linear RAW output: CrxLosslessRawDecoder → BlackLevel → grayscale BufferedImage.
     */
    private BufferedImage decodeRawLinear(Cr3DecodeOptions options) throws Cr3Exception, IOException {
        Cr3RawImage rawImage = decodeRawBayer(options);

        if (options.applyBlackLevel()) {
            BlackLevelProcessor.process(rawImage);
        }

        // Return as grayscale image (raw Bayer values)
        int w = rawImage.width();
        int h = rawImage.height();
        int maxVal = rawImage.maxValue();
        BufferedImage output = new BufferedImage(w, h, BufferedImage.TYPE_USHORT_GRAY);
        short[] outData = ((java.awt.image.DataBufferUShort) output.getRaster()
                .getDataBuffer()).getData();
        int[] rawData = rawImage.data();
        for (int i = 0; i < rawData.length; i++) {
            // Scale to 16-bit range
            outData[i] = (short) Math.min(65535,
                    (int) ((long) rawData[i] * 65535 / maxVal));
        }

        return output;
    }

    /**
     * Reads the CRX RAW track data and decodes it into a Bayer-pattern image.
     */
    private Cr3RawImage decodeRawBayer(Cr3DecodeOptions options) throws Cr3Exception, IOException {
        // Find the CRX RAW track
        Cr3Track rawTrack = null;
        for (var track : container.tracks()) {
            if (track.type() == Cr3Track.TrackType.CRX_BIG
                    || track.type() == Cr3Track.TrackType.CRX_RAW) {
                rawTrack = track;
                break;
            }
        }

        if (rawTrack == null) {
            throw new Cr3Exception("No CRX RAW track found in CR3 file");
        }

        if (rawTrack.dataSize() <= 0 || rawTrack.dataSize() > Integer.MAX_VALUE) {
            throw new Cr3Exception("Invalid CRX RAW track size: " + rawTrack.dataSize());
        }

        int rawWidth = rawTrack.width();
        int rawHeight = rawTrack.height();
        int bitDepth = metadata != null ? metadata.bitDepth() : 14;
        Cmp1Box cmp1 = rawTrack.cmp1();
        if (cmp1 != null) {
            if (cmp1.imageWidth() > 0) {
                rawWidth = cmp1.imageWidth();
            }
            if (cmp1.imageHeight() > 0) {
                rawHeight = cmp1.imageHeight();
            }
            if (cmp1.bitsPerSample() > 0) {
                bitDepth = cmp1.bitsPerSample();
            }
            LOG.info("Using CMP1 metadata: {}x{}, bitDepth={}, tiles={}x{}, planes={}, "
                            + "waveletLevels={}, encodingType={}, multipleHTiles={}, mdatHeaderSize={}",
                    rawWidth, rawHeight, bitDepth, cmp1.tileWidth(), cmp1.tileHeight(),
                    cmp1.numPlanes(), cmp1.waveletLevels(), cmp1.encodingType(),
                    cmp1.multipleHTiles(), cmp1.mdatHeaderSize());
        }

        LOG.info("Reading CRX RAW track: {}x{}, {} bytes at offset {}",
                rawWidth, rawHeight, rawTrack.dataSize(), rawTrack.dataOffset());

        // Read the raw CRX data — prefer a memory-mapped read when the input
        // is a known file (setInput(Path)). For multi-MB CRX tracks this avoids
        // the ImageInputStream's intermediate buffering and the RandomAccessFile
        // syscall loop that backs readFully(byte[]).
        byte[] crxData;
        if (sourceFile != null) {
            crxData = readCrxTrackMmap(sourceFile, rawTrack.dataOffset(), (int) rawTrack.dataSize());
        } else {
            crxData = new byte[(int) rawTrack.dataSize()];
            input.seek(rawTrack.dataOffset());
            input.readFully(crxData);
        }

        // Determine codec version
        CrxHeader.CodecVersion codecVersion = CrxHeader.CodecVersion.V1;
        if (container.cncvBox() != null) {
            codecVersion = CrxHeader.detectVersion(container.cncvBox().versionString());
        }

        // Route to lossless or lossy decoder based on CMP1
        Cr3RawImage decoded;
        if (CrxLossyDecoder.isLossy(cmp1)) {
            LOG.info("Detected C-RAW (lossy) compression — attempting lossy decode");
            decoded = CrxLossyDecoder.decode(crxData, rawWidth, rawHeight,
                    bitDepth, codecVersion, cmp1);
        } else {
            decoded = CrxLosslessRawDecoder.decode(crxData, rawWidth, rawHeight,
                    bitDepth, codecVersion, cmp1);
        }

        if (options.cropToActiveArea()) {
            return cropToActiveArea(decoded);
        }
        return decoded;
    }

    /**
     * Decodes the CRX RAW track WITHOUT cropping, regardless of the options.
     * Used by the RAW_PROCESSED pipeline which needs OB columns for black level
     * estimation before cropping.
     */
    private Cr3RawImage decodeRawBayerUncropped(Cr3DecodeOptions options) throws Cr3Exception, IOException {
        boolean savedCrop = options.cropToActiveArea();
        try {
            options.cropToActiveArea(false);
            return decodeRawBayer(options);
        } finally {
            options.cropToActiveArea(savedCrop);
        }
    }

    private Cr3RawImage cropToActiveArea(Cr3RawImage image) {
        if (metadata == null) {
            return image;
        }

        int left = clamp(metadata.activeAreaLeft(), 0, image.width());
        int top = clamp(metadata.activeAreaTop(), 0, image.height());
        int right = clamp(metadata.activeAreaRight(), 0, image.width());
        int bottom = clamp(metadata.activeAreaBottom(), 0, image.height());

        if (right <= left || bottom <= top) {
            return image;
        }

        int croppedWidth = right - left;
        int croppedHeight = bottom - top;
        if (croppedWidth == image.width() && croppedHeight == image.height()) {
            return image;
        }

        int[] src = image.data();
        int[] cropped = new int[croppedWidth * croppedHeight];
        for (int y = 0; y < croppedHeight; y++) {
            int srcOffset = (top + y) * image.width() + left;
            int dstOffset = y * croppedWidth;
            System.arraycopy(src, srcOffset, cropped, dstOffset, croppedWidth);
        }
        return new Cr3RawImage(croppedWidth, croppedHeight, cropped,
                image.bitDepth(), image.bayerPattern());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    /**
     * Reads {@code length} bytes starting at {@code offset} from {@code file}
     * via {@link FileChannel#map} and copies them into a fresh {@code byte[]}.
     *
     * <p>For the typical multi-megabyte CRX RAW track this is measurably
     * faster than {@code RandomAccessFile.readFully}: the read becomes a single
     * page-fault-driven copy instead of an 8 KB-buffered syscall loop.</p>
     *
     * <p>Note: the {@link MappedByteBuffer} returned by {@code FileChannel.map}
     * is unmapped non-deterministically by the JVM's {@code Cleaner} — closing
     * the {@link FileChannel} does not force-unmap it. That is harmless here
     * because we copy the contents into a fresh {@code byte[]} before returning
     * and the buffer becomes unreachable as soon as this method exits.</p>
     */
    private static byte[] readCrxTrackMmap(Path file, long offset, int length) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer mapped = ch.map(FileChannel.MapMode.READ_ONLY, offset, length);
            byte[] dst = new byte[length];
            mapped.get(dst);
            return dst;
        }
    }

    /**
     * Resolves the best available black level for the current image.
     * Priority: OB per-row estimation &gt; per-channel from metadata &gt; global default.
     *
     * @param rawImage the raw image (used for OB estimation if metadata indicates OB columns)
     */
    private BlackLevel resolveBlackLevel(Cr3RawImage rawImage) {
        // Try OB-based per-row estimation first (highest accuracy)
        if (metadata != null && metadata.activeAreaLeft() >= ObBlackLevelEstimator.MIN_OB_COLUMNS) {
            BlackLevel obLevel = ObBlackLevelEstimator.estimate(rawImage, metadata.activeAreaLeft());
            if (obLevel != null) {
                LOG.debug("Using OB-estimated per-row black level");
                return obLevel;
            }
        }

        // Fall back to per-channel from MakerNote metadata
        if (metadata != null && metadata.perChannelBlackLevels() != null) {
            return BlackLevel.perChannel(metadata.perChannelBlackLevels());
        }

        // Final fallback: global default
        return BlackLevel.defaultLevel();
    }

    /**
     * Resolves the best available white balance multipliers.
     * Priority: MakerNote WB &gt; default daylight.
     */
    private double[] resolveWhiteBalance() {
        if (metadata != null && metadata.whiteBalanceMultipliers() != null) {
            return metadata.whiteBalanceMultipliers();
        }
        return WhiteBalanceProcessor.DEFAULT_DAYLIGHT_MULTIPLIERS;
    }

    /**
     * Resolves the best available color matrix for the camera model.
     * Priority: dual-illuminant interpolated (if color temp available) &gt; camera-specific &gt; generic fallback.
     */
    private double[][] resolveColorMatrix() {
        if (metadata == null || metadata.cameraModel() == null || metadata.cameraModel().isEmpty()) {
            return CameraColorMatrixRegistry.GENERIC_CANON_D65;
        }

        String model = metadata.cameraModel();

        // Try dual-illuminant interpolation if color temperature is available
        if (DualIlluminantColorMatrix.hasDualProfile(model)
                && metadata.makerNoteData() != null
                && metadata.makerNoteData().colorTemperature() > 0) {
            int colorTemp = metadata.makerNoteData().colorTemperature();
            LOG.debug("Using dual-illuminant matrix interpolation for '{}' at {}K", model, colorTemp);
            return DualIlluminantColorMatrix.getInterpolatedMatrix(model, colorTemp);
        }

        return CameraColorMatrixRegistry.getMatrix(model);
    }
}
