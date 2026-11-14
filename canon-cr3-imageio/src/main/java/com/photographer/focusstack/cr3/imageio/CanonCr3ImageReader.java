package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions.OutputMode;
import com.photographer.focusstack.cr3.decode.Cr3Decoder;
import com.photographer.focusstack.cr3.metadata.Cr3Metadata;
import com.photographer.focusstack.cr3.preview.Cr3PreviewExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

/**
 * {@code javax.imageio.ImageReader} implementation for Canon CR3 files.
 *
 * <p>This reader is a thin adapter around {@link Cr3Decoder}. The core decoding
 * logic lives in the {@code cr3-core} artifact.</p>
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
 */
public class CanonCr3ImageReader extends ImageReader {

    private static final Logger LOG = LoggerFactory.getLogger(CanonCr3ImageReader.class);

    private Cr3Decoder decoder;
    private PreviewHeader previewHeader;

    private record PreviewHeader(Dimension dimensions, ImageTypeSpecifier type) {}

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
        this.previewHeader = null;
    }

    @Override
    public int getNumImages(boolean allowSearch) throws IOException {
        // CR3 files contain a single main image
        return 1;
    }

    @Override
    public int getWidth(int imageIndex) throws IOException {
        return previewDimensions(imageIndex).width;
    }

    @Override
    public int getHeight(int imageIndex) throws IOException {
        return previewDimensions(imageIndex).height;
    }

    private Dimension previewDimensions(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        ensureParsed();
        Cr3Container container = decoder.getContainer();
        for (Cr3Track track : container.tracks()) {
            if (track.type() == Cr3Track.TrackType.JPEG_PREVIEW) {
                if (track.width() > 0 && track.height() > 0) {
                    return new Dimension(track.width(), track.height());
                }
                return previewHeader().dimensions();
            }
        }
        var preview = container.prvwBox();
        if (preview != null && preview.width() > 0 && preview.height() > 0) {
            return new Dimension(preview.width(), preview.height());
        }
        return previewHeader().dimensions();
    }

    @Override
    public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        ensureParsed();
        return destinationTypes(previewHeader().type()).iterator();
    }

    private static List<ImageTypeSpecifier> destinationTypes(ImageTypeSpecifier decodedType) {
        if (decodedType.getNumBands() == 3 && decodedType.getBufferedImageType() != BufferedImage.TYPE_INT_RGB) {
            return List.of(decodedType, ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB));
        }
        return List.of(decodedType);
    }

    // Query the same embedded JPEG as read(0). Sensor metadata describes a different image.
    private PreviewHeader previewHeader() throws IOException {
        if (previewHeader == null) {
            byte[] jpeg;
            try {
                jpeg = Cr3PreviewExtractor.extractPreviewBytes((ImageInputStream) getInput(), decoder.getContainer());
            } catch (Cr3Exception e) {
                throw new IIOException("Could not locate CR3 preview", e);
            }
            Iterator<ImageReader> jpegReaders = ImageIO.getImageReadersByFormatName("JPEG");
            if (!jpegReaders.hasNext()) {
                throw new IIOException("No JPEG reader available for the CR3 preview");
            }
            ImageReader reader = jpegReaders.next();
            try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(jpeg))) {
                reader.setInput(stream);
                previewHeader = new PreviewHeader(new Dimension(reader.getWidth(0), reader.getHeight(0)),
                        reader.getImageTypes(0).next());
            } finally {
                reader.dispose();
            }
        }
        return previewHeader;
    }

    @Override
    public boolean canReadRaster() {
        return true;
    }

    @Override
    public ImageTypeSpecifier getRawImageType(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        // RAW data is unsigned 16-bit grayscale (Bayer pattern)
        return ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_USHORT_GRAY);
    }

    /**
     * Reads a Bayer mosaic. The default is the core's scaled linear output.
     * Select {@link Cr3DecodeOptions#rawSensor()} to read the original sensor codes.
     */
    @Override
    public Raster readRaster(int imageIndex, ImageReadParam param) throws IOException {
        Cr3DecodeOptions options = rasterOptions(param);
        if (param == null) {
            return decode(imageIndex, options).getRaster();
        }
        checkIndex(imageIndex);
        ensureParsed();
        checkReadParamBandSettings(param, 1, 1);
        try {
            Rectangle bounds = decoder.getRawBounds(options);
            Rectangle region = getSourceRegion(param, bounds.width, bounds.height);
            if (region.isEmpty()) {
                throw new IllegalArgumentException("Empty source region");
            }
            Raster samples = decoder.decodeRawRegion(options, region, param.getSourceXSubsampling(),
                    param.getSourceYSubsampling()).getRaster();
            Point origin = param.getDestinationOffset(); // Raster offsets are logical, including negative ones.
            return origin.x == 0 && origin.y == 0 ? samples : samples.createTranslatedChild(origin.x, origin.y);
        } catch (Cr3Exception e) {
            throw decodeError(options, e);
        }
    }

    private static Cr3DecodeOptions rasterOptions(ImageReadParam param) {
        if (!(param instanceof CanonCr3ImageReadParam cr3)) {
            return Cr3DecodeOptions.rawLinear();
        }
        Cr3DecodeOptions requested = cr3.getDecodeOptions();
        return switch (requested.outputMode()) {
            case RAW_SENSOR, RAW_LINEAR -> requested;
            case PREVIEW, THUMBNAIL, RAW_PROCESSED -> Cr3DecodeOptions.rawLinear()
                    .cropToActiveArea(requested.cropToActiveArea()).applyBlackLevel(requested.applyBlackLevel())
                    .maxWorkers(requested.maxWorkers());
        };
    }

    @Override
    public IIOMetadata getStreamMetadata() {
        // CR3 stream-level metadata is not exposed as IIOMetadata.
        // Use getDecoder().getMetadata() for programmatic access to CR3 metadata.
        return null;
    }

    @Override
    public IIOMetadata getImageMetadata(int imageIndex) {
        try {
            checkIndex(imageIndex);
            ensureParsed();
            Cr3Metadata meta = decoder.getMetadata();
            if (meta != null) {
                return new Cr3ImageMetadata(meta);
            }
        } catch (IOException e) {
            LOG.warn("Failed to get CR3 image metadata: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public BufferedImage read(int imageIndex, ImageReadParam param) throws IOException {
        Cr3DecodeOptions options = param instanceof CanonCr3ImageReadParam cr3 && !cr3.isPreviewOnly()
                ? cr3.getDecodeOptions() : Cr3DecodeOptions.defaults();
        if (param == null) {
            return decode(imageIndex, options);
        }
        if (options.outputMode() == OutputMode.RAW_LINEAR || options.outputMode() == OutputMode.RAW_SENSOR) {
            return readBayerImage(imageIndex, options, param);
        }
        BufferedImage decoded = decode(imageIndex, options);
        int width = decoded.getWidth();
        int height = decoded.getHeight();
        ImageRegions regions = imageRegions(param, width, height);
        Rectangle sourceRegion = regions.source();
        Rectangle destRegion = regions.destination();
        int[] sourceBands = param.getSourceBands();
        int[] destBands = param.getDestinationBands();
        if (param.getDestination() == null && param.getDestinationType() == null
                && sourceBands == null && destBands == null
                && sourceRegion.equals(decoded.getRaster().getBounds()) && destRegion.equals(sourceRegion)) {
            return decoded;
        }

        BufferedImage target = destination(param, ImageTypeSpecifier.createFromRenderedImage(decoded), destRegion);
        Raster source = decoded.getRaster();
        WritableRaster dest = target.getRaster();
        checkReadParamBandSettings(param, source.getNumBands(), dest.getNumBands());
        if (sourceBands != null) {
            source = source.createChild(0, 0, width, height, 0, 0, sourceBands);
        }
        if (destBands != null) {
            dest = dest.createWritableChild(0, 0, dest.getWidth(), dest.getHeight(), 0, 0, destBands);
        }
        copySamples(source, sourceRegion, param.getSourceXSubsampling(), param.getSourceYSubsampling(), dest, destRegion);
        return target;
    }

    private BufferedImage readBayerImage(int imageIndex, Cr3DecodeOptions options, ImageReadParam param)
            throws IOException {
        checkIndex(imageIndex);
        ensureParsed();
        ImageTypeSpecifier type = getRawImageType(imageIndex);
        BufferedImage supplied = param.getDestination();
        checkReadParamBandSettings(param, 1, supplied == null ? 1 : supplied.getRaster().getNumBands());
        if (supplied == null && param.getDestinationType() != null && !type.equals(param.getDestinationType())) {
            throw new IIOException("Destination type is incompatible with unsigned 16-bit Bayer samples");
        }

        try {
            Rectangle bounds = decoder.getRawBounds(options);
            ImageRegions regions = imageRegions(param, bounds.width, bounds.height);
            Rectangle output = regions.destination();
            BufferedImage samples = decoder.decodeRawRegion(options, regions.source(), param.getSourceXSubsampling(),
                    param.getSourceYSubsampling());
            if (supplied == null && output.x == 0 && output.y == 0) {
                return samples;
            }
            BufferedImage target = destination(param, type, output);
            WritableRaster dest = target.getRaster();
            int[] destBands = param.getDestinationBands();
            if (destBands != null) {
                dest = dest.createWritableChild(0, 0, dest.getWidth(), dest.getHeight(), 0, 0, destBands);
            }
            dest.setRect(output.x, output.y, samples.getRaster());
            return target;
        } catch (Cr3Exception e) {
            throw decodeError(options, e);
        }
    }

    private record ImageRegions(Rectangle source, Rectangle destination) {}
    private record Axis(int first, int span, int offset, int count) {}

    private static ImageRegions imageRegions(ImageReadParam param, int width, int height) {
        Rectangle region = getSourceRegion(param, width, height);
        Point offset = param.getDestinationOffset();
        BufferedImage target = param.getDestination();
        Axis x = clipAxis(region.x, region.width, param.getSourceXSubsampling(), offset.x,
                target == null ? null : target.getWidth());
        Axis y = clipAxis(region.y, region.height, param.getSourceYSubsampling(), offset.y,
                target == null ? null : target.getHeight());
        return new ImageRegions(new Rectangle(x.first, y.first, x.span, y.span),
                new Rectangle(x.offset, y.offset, x.count, y.count));
    }

    // Clip on the sampled grid first. Negating an int offset or adding period-1 to a span can overflow.
    private static Axis clipAxis(int first, int span, int period, int offset, Integer limit) {
        long skipped = Math.max(0L, -(long) offset);
        int start = Math.max(0, offset);
        long count = Math.ceilDiv(span, period) - skipped;
        if (limit != null) {
            count = Math.min(count, (long) limit - start);
        } else if (start + count > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Destination dimension exceeds integer range");
        }
        if (span <= 0 || count <= 0) {
            throw new IllegalArgumentException("Empty source or destination region");
        }
        return new Axis((int) (first + skipped * period), (int) (1L + (count - 1) * period), start, (int) count);
    }

    private static BufferedImage destination(ImageReadParam param, ImageTypeSpecifier decoded, Rectangle region)
            throws IIOException {
        if (param.getDestination() != null) {
            return param.getDestination();
        }
        ImageTypeSpecifier requested = param.getDestinationType();
        if (requested != null && !destinationTypes(decoded).contains(requested)) {
            throw new IIOException("Destination image type is incompatible with the selected output");
        }
        ImageTypeSpecifier type = requested == null ? decoded : requested;
        return type.createBufferedImage(region.x + region.width, region.y + region.height);
    }

    private static void copySamples(Raster source, Rectangle region, int stepX, int stepY,
                                    WritableRaster target, Rectangle output) {
        if (stepX == 1 && stepY == 1) {
            target.setRect(source.createChild(region.x, region.y, output.width, output.height,
                    output.x, output.y, null));
            return;
        }
        int[] pixel = new int[source.getNumBands()];
        for (int y = 0; y < output.height; y++) {
            for (int x = 0; x < output.width; x++) {
                source.getPixel(region.x + x * stepX, region.y + y * stepY, pixel);
                target.setPixel(output.x + x, output.y + y, pixel);
            }
        }
    }

    private BufferedImage decode(int imageIndex, Cr3DecodeOptions options) throws IOException {
        checkIndex(imageIndex);
        ensureParsed();
        try {
            return decoder.decode(options);
        } catch (Cr3Exception e) {
            throw decodeError(options, e);
        }
    }

    private static IIOException decodeError(Cr3DecodeOptions options, Cr3Exception e) {
        return new IIOException("Failed to decode CR3 " + options.outputMode() + ": " + e.getMessage(), e);
    }

    @Override
    public boolean readerSupportsThumbnails() {
        return true;
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
        if (thumbnailIndex != 0 || getNumThumbnails(imageIndex) == 0) {
            throw new IndexOutOfBoundsException("Thumbnail index: " + thumbnailIndex);
        }
        return decode(imageIndex, Cr3DecodeOptions.defaults().outputMode(OutputMode.THUMBNAIL));
    }

    @Override
    public CanonCr3ImageReadParam getDefaultReadParam() {
        return new CanonCr3ImageReadParam();
    }

    @Override
    public void dispose() {
        super.dispose();
        if (decoder != null) {
            decoder.reset();
            decoder = null;
        }
        previewHeader = null;
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
        if (decoder != null) {
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
            Cr3Decoder parsed = new Cr3Decoder();
            parsed.setInput(iis);
            decoder = parsed;
        } catch (Cr3Exception e) {
            throw new IOException("Failed to parse CR3 file: " + e.getMessage(), e);
        }
    }

    private void checkIndex(int imageIndex) {
        if (imageIndex != 0) {
            throw new IndexOutOfBoundsException("Image index: " + imageIndex);
        }
    }
}
