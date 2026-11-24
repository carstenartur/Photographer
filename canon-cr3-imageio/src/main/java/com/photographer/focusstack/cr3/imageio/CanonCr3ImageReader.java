package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions.OutputMode;
import com.photographer.focusstack.cr3.decode.Cr3Decoder;
import com.photographer.focusstack.cr3.metadata.Cr3Metadata;
import com.photographer.focusstack.cr3.preview.Cr3PreviewExtractor;
import com.photographer.focusstack.cr3.processing.BayerDemosaicer;
import com.photographer.focusstack.dng.imageio.DngImageMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.IIOException;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

/**
 * {@code javax.imageio.ImageReader} implementation for Canon CR3 files.
 *
 * <p>This reader is a thin adapter around {@link Cr3Decoder}. The core decoding
 * logic lives in the {@code cr3-core} artifact.</p>
 *
 * <p>Image index 0 is the developed 16-bit sRGB image by default. Select
 * {@link CanonCr3ImageReadParam#setPreviewOnly(boolean)} to read the embedded JPEG.
 * Pass {@link Cr3DecodeOptions#rawSensor()} through
 * {@link CanonCr3ImageReadParam#setDecodeOptions(Cr3DecodeOptions)} to read the
 * native Bayer codes in a single unsigned-short band. Use
 * {@link #getImageMetadata(int)} to retrieve the available sensor calibration.</p>
 *
 * <p>Thumbnail index 0 of image 0 selects the embedded JPEG thumbnail.
 * The caller owns the input stream and closes it after the reader is disposed.</p>
 */
public class CanonCr3ImageReader extends ImageReader {

    private static final Logger LOG = LoggerFactory.getLogger(CanonCr3ImageReader.class);

    private static final ImageTypeSpecifier RGB8 = ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB);
    private static final ImageTypeSpecifier SRGB16 = rgb16Type(ColorSpace.CS_sRGB);
    private static final ImageTypeSpecifier LINEAR_RGB16 = rgb16Type(ColorSpace.CS_LINEAR_RGB);

    private static ImageTypeSpecifier rgb16Type(int colorSpace) {
        return ImageTypeSpecifier.createBanded(ColorSpace.getInstance(colorSpace), new int[]{0, 1, 2},
                new int[]{0, 0, 0}, DataBuffer.TYPE_USHORT, false, false);
    }

    private Cr3Decoder decoder;
    private Cr3ImageMetadata imageMetadata;

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
        this.imageMetadata = null;
    }

    @Override
    public int getNumImages(boolean allowSearch) throws IOException {
        if (getInput() == null || (allowSearch && isSeekForwardOnly())) {
            throw new IllegalStateException("An input is required and searching must allow backward seeks");
        }
        return 1;
    }

    @Override
    public int getWidth(int imageIndex) throws IOException {
        return defaultImageBounds(imageIndex).width;
    }

    @Override
    public int getHeight(int imageIndex) throws IOException {
        return defaultImageBounds(imageIndex).height;
    }

    private Rectangle defaultImageBounds(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        ensureParsed();
        Cr3DecodeOptions options = Cr3DecodeOptions.rawProcessed(Cr3DecodeOptions.BitDepth.SIXTEEN);
        try {
            return decoder.getRawProcessedBounds(options);
        } catch (Cr3Exception e) {
            throw decodeError(options, e);
        }
    }

    @Override
    public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        ensureParsed();
        return List.of(SRGB16, LINEAR_RGB16, RGB8).iterator();
    }

    @Override
    public boolean canReadRaster() {
        return true;
    }

    @Override
    public ImageTypeSpecifier getRawImageType(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        if (getInput() == null) {
            throw new IllegalStateException("No input set");
        }
        // RAW data is unsigned 16-bit grayscale (Bayer pattern)
        return ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_USHORT_GRAY);
    }

    /**
     * Reads a Bayer mosaic in sensor orientation. The default is scaled linear output.
     * Select {@link Cr3DecodeOptions#rawSensor()} for unscaled native sensor codes,
     * including the optical-black margins in a full raster read.
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
                    .maxWorkers(requested.maxWorkers()).memoryMode(requested.memoryMode())
                    .postDecodeMemoryBytes(requested.postDecodeMemoryBytes());
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
            if (imageMetadata == null && meta != null) {
                Cr3SensorCalibration calibration = Cr3SensorCalibration.from(
                        decoder.getContainer(), decoder.getCaptureColorData());
                imageMetadata = new Cr3ImageMetadata(meta, calibration);
            }
            return imageMetadata;
        } catch (IOException e) {
            LOG.warn("Failed to get CR3 image metadata: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public BufferedImage read(int imageIndex, ImageReadParam param) throws IOException {
        Cr3DecodeOptions options;
        if (param instanceof CanonCr3ImageReadParam cr3) {
            options = cr3.isPreviewOnly() ? Cr3DecodeOptions.defaults() : cr3.getDecodeOptions();
        } else {
            options = Cr3DecodeOptions.rawProcessed(Cr3DecodeOptions.BitDepth.SIXTEEN);
        }
        if (param == null) {
            return decode(imageIndex, options);
        }
        if (options.outputMode() == OutputMode.RAW_LINEAR || options.outputMode() == OutputMode.RAW_SENSOR) {
            return readBayerImage(imageIndex, options, param);
        }
        if (options.outputMode() == OutputMode.RAW_PROCESSED) {
            return readProcessedImage(imageIndex, negotiateRgbDestination(options, param), param);
        }
        BufferedImage decoded = decode(imageIndex, options);
        ImageTypeSpecifier requested = param.getDestinationType();
        if (param.getDestination() == null && requested != null
                && !requested.equals(ImageTypeSpecifier.createFromRenderedImage(decoded))
                && !(decoded.getRaster().getNumBands() == 3 && requested.equals(RGB8))) {
            throw new IIOException("Destination image type is incompatible with the selected JPEG");
        }
        return copyImage(param, decoded, imageRegions(param, decoded.getWidth(), decoded.getHeight()),
                param.getSourceXSubsampling(), param.getSourceYSubsampling());
    }

    private static Cr3DecodeOptions negotiateRgbDestination(Cr3DecodeOptions options, ImageReadParam param) {
        if ((param instanceof CanonCr3ImageReadParam cr3 && cr3.hasExplicitDecodeOptions())
                || options.hasExplicitBitDepth() || !options.applySrgbConversion()) {
            return options;
        }
        ImageTypeSpecifier target = param.getDestination() == null ? param.getDestinationType()
                : ImageTypeSpecifier.createFromRenderedImage(param.getDestination());
        if (target == null) {
            return options;
        }
        var color = target.getColorModel();
        if (color.getComponentSize(0) == 8) {
            return options.copy().bitDepth(Cr3DecodeOptions.BitDepth.EIGHT);
        }
        if (color.equals(LINEAR_RGB16.getColorModel())) {
            return options.copy().applySrgbConversion(false);
        }
        return options;
    }

    private BufferedImage readProcessedImage(int imageIndex, Cr3DecodeOptions options, ImageReadParam param)
            throws IOException {
        checkIndex(imageIndex);
        ensureParsed();
        try {
            boolean sixteen = options.bitDepth() == Cr3DecodeOptions.BitDepth.SIXTEEN;
            if (!sixteen && param.getDestination() == null && param.getDestinationType() != null
                    && !RGB8.equals(param.getDestinationType())) {
                throw new IIOException("Destination image type is incompatible with 8-bit RGB");
            }
            checkRgbDestination(param, options);
            Rectangle bounds = decoder.getRawProcessedBounds(options);
            ImageRegions regions = imageRegions(param, bounds.width, bounds.height);
            Rectangle source = regions.source();
            int xStep = param.getSourceXSubsampling();
            int yStep = param.getSourceYSubsampling();
            boolean sampled = xStep != 1 || yStep != 1;
            if (param.getDestination() == null && options.memoryMode() != Cr3DecodeOptions.MemoryMode.FAST) {
                var developed = sixteen ? (options.applySrgbConversion() ? SRGB16 : LINEAR_RGB16) : RGB8;
                ImageTypeSpecifier requested = param.getDestinationType();
                Rectangle dest = regions.destination();
                boolean direct = param.getSourceBands() == null && param.getDestinationBands() == null
                        && dest.x == 0 && dest.y == 0;
                // The advertised INT_RGB has a 32-bit color model; core's packed RGB8 has a
                // 24-bit model. Partial packed views can also retain their halo's row stride.
                boolean requestedCopy = requested != null && (sampled || !sixteen
                        || !Cr3DestinationMemory.matches(requested, developed, dest.width, dest.height));
                if (!direct || requestedCopy) {
                    options = Cr3DestinationMemory.forCopy(options, requested == null ? developed : requested, dest, 3);
                }
            }
            if (sampled) {
                BufferedImage decoded = decoder.decodeRawProcessedRegion(options, source, xStep, yStep);
                return copyImage(param, decoded, new ImageRegions(decoded.getRaster().getBounds(),
                        regions.destination()), 1, 1);
            }
            if (source.equals(bounds)) {
                BufferedImage target = param.getDestination();
                boolean fullDestination = target != null && regions.destination().equals(bounds)
                        && param.getSourceBands() == null && param.getDestinationBands() == null;
                BufferedImage workspace = fullDestination && param instanceof CanonCr3ImageReadParam cr3
                        && cr3.usesDestinationAsWorkspace() ? target : null;
                BufferedImage decoded = decoder.decodeRawProcessedRegion(options, null, workspace);
                if (decoded == target) {
                    return decoded;
                }
                if (sixteen && fullDestination && copyRgb16Banks(decoded, target, options.applySrgbConversion())) {
                    return target;
                }
                return copyImage(param, decoded, regions, 1, 1);
            }
            BufferedImage decoded = decoder.decodeRawProcessedRegion(options, source);
            // The decoder returns the source region with a zero-based raster.
            Rectangle localSource = new Rectangle(decoded.getWidth(), decoded.getHeight());
            return copyImage(param, decoded, new ImageRegions(localSource, regions.destination()), 1, 1);
        } catch (Cr3Exception e) {
            throw decodeError(options, e);
        }
    }

    /** Copy a complete successfully staged image into a distinct, compatible destination. */
    private static boolean copyRgb16Banks(BufferedImage decoded, BufferedImage target, boolean srgb) {
        // A caller-defined raster can customize the writes performed by setRect.
        if (target.getRaster().getClass().getModule() != Raster.class.getModule()) {
            return false;
        }
        int width = decoded.getWidth();
        int height = decoded.getHeight();
        short[][] to = BayerDemosaicer.rgb16DestinationBanks(target, width, height, srgb);
        if (to == null) {
            return false;
        }
        short[][] from = BayerDemosaicer.rgb16DestinationBanks(decoded, width, height, srgb);
        if (from == null) {
            return false;
        }
        // The decoder created these source banks independently of the caller's destination.
        for (int band = 0; band < from.length; band++) {
            System.arraycopy(from[band], 0, to[band], 0, from[band].length);
        }
        return true;
    }

    private static void checkRgbDestination(ImageReadParam param, Cr3DecodeOptions options) throws IIOException {
        BufferedImage supplied = param.getDestination();
        ImageTypeSpecifier requested = param.getDestinationType();
        if (supplied == null && requested == null) {
            return;
        }
        var model = supplied != null ? supplied.getSampleModel() : requested.getSampleModel();
        var color = supplied != null ? supplied.getColorModel() : requested.getColorModel();
        checkReadParamBandSettings(param, 3, model.getNumBands());
        int[] bands = param.getDestinationBands();
        int count = bands == null ? model.getNumBands() : bands.length;
        boolean fullRgb = count == 3 && (bands == null || color.getNumColorComponents() == 3);
        boolean sixteen = options.bitDepth() == Cr3DecodeOptions.BitDepth.SIXTEEN;
        int bits = sixteen ? 16 : 8;
        for (int i = 0; i < count; i++) {
            int band = bands == null ? i : bands[i];
            if ((sixteen && model.getDataType() != DataBuffer.TYPE_USHORT) || model.getSampleSize(band) != bits
                    || band >= color.getNumComponents() || color.getComponentSize(band) != bits) {
                throw new IIOException("Developed RGB requires " + (sixteen ? "unsigned 16-bit" : "8-bit")
                        + " destination components");
            }
            if (bands != null && band >= color.getNumColorComponents()) {
                fullRgb = false; // Explicit mapping into alpha also acts as a sample copy.
            }
        }
        var encoding = options.applySrgbConversion() ? SRGB16 : LINEAR_RGB16;
        if (fullRgb && !color.getColorSpace().equals(encoding.getColorModel().getColorSpace())) {
            throw new IIOException("Destination color space is incompatible with the selected RGB encoding");
        }
    }

    private static BufferedImage copyImage(ImageReadParam param, BufferedImage decoded, ImageRegions regions,
                                           int xStep, int yStep)
            throws IIOException {
        Rectangle sourceRegion = regions.source();
        Rectangle destRegion = regions.destination();
        int[] sourceBands = param.getSourceBands();
        int[] destBands = param.getDestinationBands();
        ImageTypeSpecifier requested = param.getDestinationType();
        if (param.getDestination() == null
                && sourceBands == null && destBands == null
                && xStep == 1 && yStep == 1
                && sourceRegion.equals(decoded.getRaster().getBounds()) && destRegion.equals(sourceRegion)
                // A sampled read has already applied the stride. Preserve the requested destination factory.
                && (requested == null || param.getSourceXSubsampling() == 1 && param.getSourceYSubsampling() == 1
                && requested.getColorModel().equals(decoded.getColorModel())
                && requested.getSampleModel(decoded.getWidth(), decoded.getHeight()).equals(decoded.getSampleModel()))) {
            return decoded;
        }

        BufferedImage target = destination(param, ImageTypeSpecifier.createFromRenderedImage(decoded), destRegion);
        Raster source = decoded.getRaster();
        WritableRaster dest = target.getRaster();
        checkReadParamBandSettings(param, source.getNumBands(), dest.getNumBands());
        if (sourceBands != null) {
            source = source.createChild(0, 0, source.getWidth(), source.getHeight(), 0, 0, sourceBands);
        }
        if (destBands != null) {
            dest = dest.createWritableChild(0, 0, dest.getWidth(), dest.getHeight(), 0, 0, destBands);
        }
        copySamples(source, sourceRegion, xStep, yStep, dest, destRegion);
        return target;
    }

    private BufferedImage readBayerImage(int imageIndex, Cr3DecodeOptions options, ImageReadParam param)
            throws IOException {
        checkIndex(imageIndex);
        ensureParsed();
        ImageTypeSpecifier type = getRawImageType(imageIndex);
        BufferedImage supplied = param.getDestination();
        if (supplied != null) {
            var model = supplied.getSampleModel();
            int[] bands = param.getDestinationBands();
            checkReadParamBandSettings(param, 1, model.getNumBands());
            int targetBand = bands == null ? 0 : bands[0];
            if (model.getDataType() != DataBuffer.TYPE_USHORT || model.getSampleSize(targetBand) != 16) {
                throw new IIOException("Bayer samples require an unsigned 16-bit destination band");
            }
        } else {
            checkReadParamBandSettings(param, 1, 1);
        }
        if (supplied == null && param.getDestinationType() != null && !type.equals(param.getDestinationType())) {
            throw new IIOException("Destination type is incompatible with unsigned 16-bit Bayer samples");
        }

        try {
            Rectangle bounds = decoder.getRawBounds(options);
            ImageRegions regions = imageRegions(param, bounds.width, bounds.height);
            Rectangle output = regions.destination();
            int xStep = param.getSourceXSubsampling();
            int yStep = param.getSourceYSubsampling();
            if (supplied != null && supplied.getType() == BufferedImage.TYPE_USHORT_GRAY) {
                decoder.decodeRawRegionInto(options, regions.source(), xStep, yStep,
                        supplied.getRaster(), output.x, output.y);
                return supplied;
            }
            if (supplied == null && (output.x != 0 || output.y != 0)
                    && options.memoryMode() != Cr3DecodeOptions.MemoryMode.FAST) {
                ImageTypeSpecifier requested = param.getDestinationType();
                options = Cr3DestinationMemory.forCopy(options, requested == null ? type : requested, output, 1);
            }
            BufferedImage samples = decoder.decodeRawRegion(options, regions.source(), xStep, yStep);
            if (supplied == null && output.x == 0 && output.y == 0) {
                if (options.outputMode() == OutputMode.RAW_SENSOR && !isIgnoringMetadata()
                        && regions.source().equals(bounds) && output.equals(bounds)
                        && xStep == 1 && yStep == 1) {
                    IIOMetadata metadata = getImageMetadata(imageIndex);
                    if (metadata != null) {
                        return DngImageMetadata.attach(samples, metadata);
                    }
                }
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
        imageMetadata = null;
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
