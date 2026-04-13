package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.decode.Cr3Decoder;
import com.photographer.focusstack.cr3.metadata.Cr3Metadata;
import com.photographer.focusstack.cr3.preview.Cr3PreviewExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

/**
 * {@code javax.imageio.ImageReader} implementation for Canon CR3 files.
 *
 * <p>This reader is a thin adapter around {@link Cr3Decoder}. The core decoding
 * logic lives in the {@code canon-cr3-core} module and does not depend on ImageIO.</p>
 *
 * <p>Image index mapping:</p>
 * <ul>
 *   <li>Index 0: Main image (JPEG preview by default, or full RAW if configured)</li>
 * </ul>
 *
 * <p>Thumbnail support:</p>
 * <ul>
 *   <li>Thumbnail 0 of image 0: Embedded JPEG thumbnail</li>
 * </ul>
 *
 * TODO: Support multiple images (preview + full RAW)
 * TODO: Expose CR3 metadata as IIOMetadata
 */
public class CanonCr3ImageReader extends ImageReader {

    private static final Logger LOG = LoggerFactory.getLogger(CanonCr3ImageReader.class);

    private Cr3Decoder decoder;
    private boolean parsed;

    /**
     * Constructs a new CR3 image reader.
     *
     * @param originatingProvider the SPI that created this reader
     */
    protected CanonCr3ImageReader(ImageReaderSpi originatingProvider) {
        super(originatingProvider);
    }

    @Override
    public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
        super.setInput(input, seekForwardOnly, ignoreMetadata);
        this.decoder = null;
        this.parsed = false;
    }

    @Override
    public int getNumImages(boolean allowSearch) throws IOException {
        // CR3 files contain a single main image
        return 1;
    }

    @Override
    public int getWidth(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        ensureParsed();
        Cr3Metadata meta = decoder.getMetadata();
        if (meta != null && meta.sensorWidth() > 0) {
            return meta.visibleWidth();
        }
        // Fall back to preview dimensions
        return getPreviewWidth();
    }

    @Override
    public int getHeight(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        ensureParsed();
        Cr3Metadata meta = decoder.getMetadata();
        if (meta != null && meta.sensorHeight() > 0) {
            return meta.visibleHeight();
        }
        return getPreviewHeight();
    }

    @Override
    public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        ImageTypeSpecifier type = ImageTypeSpecifier.createFromBufferedImageType(
                BufferedImage.TYPE_INT_RGB);
        return List.of(type).iterator();
    }

    @Override
    public IIOMetadata getStreamMetadata() {
        // TODO: Return CR3 stream metadata
        return null;
    }

    @Override
    public IIOMetadata getImageMetadata(int imageIndex) {
        // TODO: Return CR3 image metadata (EXIF, MakerNote, etc.)
        return null;
    }

    @Override
    public BufferedImage read(int imageIndex, ImageReadParam param) throws IOException {
        checkIndex(imageIndex);
        ensureParsed();

        Cr3DecodeOptions options;
        if (param instanceof CanonCr3ImageReadParam cr3Param) {
            options = cr3Param.getDecodeOptions();
            if (cr3Param.isPreviewOnly()) {
                options = options.outputMode(Cr3DecodeOptions.OutputMode.PREVIEW);
            }
        } else {
            options = Cr3DecodeOptions.defaults();
        }

        try {
            LOG.info("Reading CR3 image with options: {}", options);
            return decoder.decode(options);
        } catch (Cr3Exception e) {
            throw new IOException("Failed to decode CR3 image: " + e.getMessage(), e);
        }
    }

    @Override
    public int getNumThumbnails(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        ensureParsed();
        return Cr3PreviewExtractor.hasThumbnail(decoder.getContainer()) ? 1 : 0;
    }

    @Override
    public BufferedImage readThumbnail(int imageIndex, int thumbnailIndex) throws IOException {
        checkIndex(imageIndex);
        if (thumbnailIndex != 0) {
            throw new IndexOutOfBoundsException("Thumbnail index: " + thumbnailIndex);
        }
        ensureParsed();

        try {
            return Cr3PreviewExtractor.extractThumbnail(
                    (ImageInputStream) getInput(), decoder.getContainer());
        } catch (Cr3Exception e) {
            throw new IOException("Failed to extract thumbnail: " + e.getMessage(), e);
        }
    }

    @Override
    public ImageReadParam getDefaultReadParam() {
        return new CanonCr3ImageReadParam();
    }

    @Override
    public void dispose() {
        super.dispose();
        if (decoder != null) {
            decoder.reset();
            decoder = null;
        }
        parsed = false;
    }

    /**
     * Returns the underlying CR3 decoder for advanced access.
     *
     * @return the decoder, or {@code null} if no input is set
     */
    public Cr3Decoder getDecoder() {
        return decoder;
    }

    private void ensureParsed() throws IOException {
        if (parsed) {
            return;
        }

        Object input = getInput();
        if (input == null) {
            throw new IllegalStateException("No input set");
        }
        if (!(input instanceof ImageInputStream iis)) {
            throw new IOException("Input must be an ImageInputStream, got: " + input.getClass());
        }

        try {
            decoder = new Cr3Decoder();
            decoder.setInput(iis);
            parsed = true;
        } catch (Cr3Exception e) {
            throw new IOException("Failed to parse CR3 file: " + e.getMessage(), e);
        }
    }

    private void checkIndex(int imageIndex) {
        if (imageIndex != 0) {
            throw new IndexOutOfBoundsException("Image index: " + imageIndex);
        }
    }

    private int getPreviewWidth() throws IOException {
        Cr3Container container = decoder.getContainer();
        if (container != null) {
            // Prefer JPEG_PREVIEW track, fall back to any track with non-zero width
            int fallbackWidth = 0;
            for (var track : container.tracks()) {
                if (track.type() == com.photographer.focusstack.cr3.container.Cr3Track.TrackType.JPEG_PREVIEW
                        && track.width() > 0) {
                    return track.width();
                }
                if (fallbackWidth == 0 && track.width() > 0) {
                    fallbackWidth = track.width();
                }
            }
            return fallbackWidth;
        }
        return 0;
    }

    private int getPreviewHeight() throws IOException {
        Cr3Container container = decoder.getContainer();
        if (container != null) {
            // Prefer JPEG_PREVIEW track, fall back to any track with non-zero height
            int fallbackHeight = 0;
            for (var track : container.tracks()) {
                if (track.type() == com.photographer.focusstack.cr3.container.Cr3Track.TrackType.JPEG_PREVIEW
                        && track.height() > 0) {
                    return track.height();
                }
                if (fallbackHeight == 0 && track.height() > 0) {
                    fallbackHeight = track.height();
                }
            }
            return fallbackHeight;
        }
        return 0;
    }
}
