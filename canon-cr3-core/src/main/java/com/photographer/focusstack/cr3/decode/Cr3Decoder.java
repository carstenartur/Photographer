package com.photographer.focusstack.cr3.decode;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.metadata.Cr3Metadata;
import com.photographer.focusstack.cr3.metadata.Cr3MetadataReader;
import com.photographer.focusstack.cr3.preview.Cr3PreviewExtractor;
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
 *
 * TODO: Implement full RAW decoding (lossless CRX)
 * TODO: Implement C-RAW (lossy CRX) decoding
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

    private BufferedImage decodeRawProcessed(Cr3DecodeOptions options) throws Cr3Exception {
        // TODO: Implement full RAW processing pipeline:
        //   1. CrxLosslessRawDecoder -> linear Bayer data
        //   2. BlackLevelProcessor -> subtract black level
        //   3. BayerDemosaicer -> RGB image
        //   4. WhiteBalanceProcessor -> apply WB multipliers
        //   5. ColorMatrixProcessor -> camera-to-XYZ-to-sRGB
        //   6. SrgbConverter -> gamma correction
        throw new Cr3Exception("Full RAW processing not yet implemented — use PREVIEW mode");
    }

    private BufferedImage decodeRawLinear(Cr3DecodeOptions options) throws Cr3Exception {
        // TODO: Implement linear RAW output:
        //   1. CrxLosslessRawDecoder -> linear Bayer data
        //   2. BlackLevelProcessor -> subtract black level
        //   3. Return as single-channel or Bayer-pattern image
        throw new Cr3Exception("Linear RAW decoding not yet implemented — use PREVIEW mode");
    }
}
