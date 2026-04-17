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
import com.photographer.focusstack.cr3.processing.ExifOrientationTransformer;
import com.photographer.focusstack.cr3.processing.SrgbConverter;
import com.photographer.focusstack.cr3.processing.WhiteBalanceProcessor;
import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import com.photographer.focusstack.cr3.raw.codec.CrxHeader;
import com.photographer.focusstack.cr3.raw.codec.CrxLosslessRawDecoder;
import com.photographer.focusstack.cr3.raw.codec.CrxLossyDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;

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
public final class Cr3Decoder {

    private static final Logger LOG = LoggerFactory.getLogger(Cr3Decoder.class);

    private ImageInputStream input;
    private Cr3Container container;
    private Cr3Metadata metadata;

    /**
     * Sets the input stream for decoding.
     *
     * @param input the image input stream positioned at the start of the CR3 file
     * @throws IOException if an I/O error occurs during initial parsing
     * @throws Cr3Exception if the file is not a valid CR3 file
     */
    public void setInput(ImageInputStream input) throws IOException, Cr3Exception {
        this.input = input;
        this.container = Cr3Parser.parse(input);
        this.metadata = extractMetadata();
        LOG.info("CR3 input set: {}", container);
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
     * Resets the decoder, releasing the input stream reference.
     */
    public void reset() {
        this.input = null;
        this.container = null;
        this.metadata = null;
    }

    private Cr3Metadata extractMetadata() throws IOException {
        // Delegate to Cr3MetadataReader — metadata and image data are separate concerns
        return Cr3MetadataReader.read(input, container);
    }

    /**
     * Full RAW processing pipeline:
     * CrxLosslessRawDecoder → BlackLevel → WhiteBalance → Demosaic → ColorMatrix → sRGB → Orientation
     */
    private BufferedImage decodeRawProcessed(Cr3DecodeOptions options) throws Cr3Exception, IOException {
        Cr3RawImage rawImage = decodeRawBayer(options);

        // 1. Black level subtraction — use per-channel if available from metadata
        if (options.applyBlackLevel()) {
            BlackLevel blackLevel = resolveBlackLevel();
            BlackLevelProcessor.process(rawImage, blackLevel);
        }

        // 2. White balance — use MakerNote multipliers if available
        if (options.applyWhiteBalance()) {
            double[] wbMultipliers = resolveWhiteBalance();
            WhiteBalanceProcessor.process(rawImage, wbMultipliers);
        }

        // 3. Bayer demosaicing → full-colour RGB
        BufferedImage rgb = BayerDemosaicer.demosaic(rawImage);

        // 4. Color matrix correction — use camera-specific if available
        if (options.applyColorMatrix()) {
            double[][] colorMatrix = resolveColorMatrix();
            ColorMatrixProcessor.process(rgb, colorMatrix);
        }

        // 5. sRGB gamma conversion
        if (options.applySrgbConversion()) {
            SrgbConverter.process(rgb);
        }

        // 6. Apply EXIF orientation (rotation/flip)
        if (metadata != null && metadata.orientation() > 1) {
            rgb = ExifOrientationTransformer.apply(rgb, metadata.orientation());
        }

        return rgb;
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

        // Read the raw CRX data
        byte[] crxData = new byte[(int) rawTrack.dataSize()];
        input.seek(rawTrack.dataOffset());
        input.readFully(crxData);

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
     * Resolves the best available black level for the current image.
     * Priority: per-channel from metadata &gt; global default.
     */
    private BlackLevel resolveBlackLevel() {
        if (metadata != null && metadata.perChannelBlackLevels() != null) {
            return BlackLevel.perChannel(metadata.perChannelBlackLevels());
        }
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
     * Priority: camera-specific &gt; generic fallback.
     */
    private double[][] resolveColorMatrix() {
        if (metadata != null && metadata.cameraModel() != null && !metadata.cameraModel().isEmpty()) {
            return CameraColorMatrixRegistry.getMatrix(metadata.cameraModel());
        }
        return CameraColorMatrixRegistry.GENERIC_CANON_D65;
    }
}
