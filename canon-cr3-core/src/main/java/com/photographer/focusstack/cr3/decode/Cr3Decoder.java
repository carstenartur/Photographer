package com.photographer.focusstack.cr3.decode;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.decode.Cr3MemoryPolicy.ImageBytes;
import com.photographer.focusstack.cr3.metadata.Cr3ColorData;
import com.photographer.focusstack.cr3.metadata.Cr3Metadata;
import com.photographer.focusstack.cr3.metadata.Cr3MetadataReader;
import com.photographer.focusstack.cr3.preview.Cr3PreviewExtractor;
import com.photographer.focusstack.cr3.processing.BayerDemosaicer;
import com.photographer.focusstack.cr3.processing.BlackLevel;
import com.photographer.focusstack.cr3.processing.BlackLevelProcessor;
import com.photographer.focusstack.cr3.processing.CameraColorMatrixRegistry;
import com.photographer.focusstack.cr3.processing.CameraToneCurve;
import com.photographer.focusstack.cr3.processing.ColorMatrixProcessor;
import com.photographer.focusstack.cr3.processing.DualIlluminantColorMatrix;
import com.photographer.focusstack.cr3.processing.ExifOrientationTransformer;
import com.photographer.focusstack.cr3.processing.LensProfiles;
import com.photographer.focusstack.cr3.processing.OpticalCorrection;
import com.photographer.focusstack.cr3.processing.ObBlackLevelEstimator;
import com.photographer.focusstack.cr3.processing.SrgbConverter;
import com.photographer.focusstack.cr3.processing.WhiteBalanceProcessor;
import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import com.photographer.focusstack.cr3.raw.codec.CrxHeader;
import com.photographer.focusstack.cr3.raw.codec.CrxLosslessRawDecoder;
import com.photographer.focusstack.cr3.raw.codec.CrxLossyDecoder;
import com.photographer.focusstack.cr3.raw.codec.CrxSensorDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBufferInt;
import java.awt.image.DataBufferUShort;
import java.awt.image.SinglePixelPackedSampleModel;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Decodes embedded images, Bayer mosaics and developed RGB from Canon CR3 files.
 *
 * <p>The decoder parses container and metadata at input selection. RAW reads prepare
 * the selected sample's bounded CRX directory and convert its tile-plane rows into
 * the chosen output. The decoder's input and metadata are mutable. Use one decoder
 * per concurrent import and keep its input open until the read completes.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * try (var decoder = new Cr3Decoder()) {
 *     decoder.setInput(Path.of("photo.cr3"));
 *     BufferedImage preview = decoder.decode(Cr3DecodeOptions.defaults());
 * }
 * }</pre>
 */
public final class Cr3Decoder implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Cr3Decoder.class);

    private ImageInputStream input;
    private Cr3Container container;
    private Cr3Metadata metadata;
    private Cr3ColorData captureColorData;
    private boolean captureColorDataRead;
    /**
     * Positional access to the same file handle as the owned input stream.
     * Null for streams supplied by the caller.
     */
    private FileChannel ownedChannel;

    /** Source used for the resolved black-level/pedestal value. */
    public enum PedestalSource {
        OB_PER_ROW,
        METADATA_PER_CHANNEL,
        DEFAULT_GLOBAL,
        EXPERIMENTAL_REFERENCE_MATCH
    }

    /**
     * Diagnostic report describing where the decoder resolved the pedestal
     * (black level) from for the current input.
     */
    public record PedestalReport(
            PedestalSource source,
            BlackLevel blackLevel,
            int activeAreaLeftColumns,
            int minObColumnsRequired,
            int[] metadataPerChannelBlackLevels,
            int rawWidth,
            int rawHeight,
            Integer rawFirstPixel,
            Integer rawActiveAreaFirstPixel
    ) {}

    /**
     * Sets the input stream for decoding.
     *
     * <p>The caller owns the stream and closes it. {@link #reset} and {@link #close}
     * release the decoder's reference. Parsing and sample reads can move the stream
     * position. Reserve the stream for this decoder while a read is in progress.</p>
     *
     * @param input the image input stream positioned at the first box of the CR3 image
     * @throws IOException if an I/O error occurs during initial parsing
     * @throws Cr3Exception if the file is not a valid CR3 file
     */
    public void setInput(ImageInputStream input) throws IOException, Cr3Exception {
        reset();
        var parsed = Cr3Parser.parse(input);
        var parsedMetadata = Cr3MetadataReader.read(input, parsed);
        this.input = input;
        this.container = parsed;
        this.metadata = parsedMetadata;
        LOG.info("CR3 input set: {}", container);
    }

    /**
     * Opens a file for metadata, preview and bounded RAW subband reads.
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
        reset();
        var source = new RandomAccessFile(file.toFile(), "r");
        var iis = new FileImageInputStream(source);
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
        this.ownedChannel = source.getChannel();
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
            case RAW_PROCESSED -> decodeRawProcessedRegion(options, null);
            case RAW_LINEAR, RAW_SENSOR -> decodeRawRegion(options, null, 1, 1);
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
     * Resolves and returns a pedestal/black-level diagnostic report for the
     * current input without running the full RAW→RGB processing path.
     *
     * @return diagnostic pedestal report for the currently loaded CR3
     * @throws Cr3Exception if RAW decoding fails
     * @throws IOException if an I/O error occurs
     * @throws IllegalStateException if no input has been set
     */
    public PedestalReport pedestalReport() throws Cr3Exception, IOException {
        if (input == null || container == null) {
            throw new IllegalStateException("No input set — call setInput() first");
        }
        Cr3RawImage rawImage = decodeRawBayer();
        BlackLevelResolution resolution = resolveBlackLevelResolution(
                ObBlackLevelEstimator.estimate(rawImage, metadata == null ? 0 : metadata.activeAreaLeft()));

        int activeAreaLeft = metadata != null ? metadata.activeAreaLeft() : 0;
        Integer activePixel = null;
        if (metadata != null
                && metadata.activeAreaTop() >= 0
                && metadata.activeAreaTop() < rawImage.height()
                && activeAreaLeft >= 0
                && activeAreaLeft < rawImage.width()) {
            activePixel = rawImage.data()[metadata.activeAreaTop() * rawImage.width() + activeAreaLeft];
        }

        return new PedestalReport(
                resolution.source(),
                resolution.blackLevel(),
                activeAreaLeft,
                ObBlackLevelEstimator.MIN_OB_COLUMNS,
                metadata != null && metadata.perChannelBlackLevels() != null
                        ? Arrays.copyOf(metadata.perChannelBlackLevels(), metadata.perChannelBlackLevels().length)
                        : null,
                rawImage.width(),
                rawImage.height(),
                rawImage.data().length > 0 ? rawImage.data()[0] : null,
                activePixel);
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
        this.captureColorData = null;
        this.captureColorDataRead = false;
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
        if (ownedChannel != null) {
            try {
                input.close();
            } catch (IOException e) {
                LOG.warn("Failed to close internally-owned ImageInputStream", e);
            } finally {
                ownedChannel = null;
            }
        }
    }

    /**
     * Returns the developed image bounds after applying the selected crop and EXIF orientation.
     *
     * @param options processed RAW options controlling the active-area crop
     * @return zero-based bounds in the final image's coordinate system
     * @throws Cr3Exception if the input has no usable RAW track
     */
    public Rectangle getRawProcessedBounds(Cr3DecodeOptions options) throws Cr3Exception {
        Rectangle area = processedArea(options, rawTrack());
        return orientedBounds(area, orientation());
    }

    /**
     * Decompresses the complete Bayer image and resolves the default color parameters for
     * a caller that performs its own RGB development. Its crop matches the default RAW
     * processed decode. The caller applies EXIF orientation after demosaicing.
     *
     * @param maxWorkers maximum number of decode workers, or zero for automatic selection
     * @return owned Bayer samples and color parameters, usable after the decoder closes
     * @throws Cr3Exception if the file cannot be decoded
     * @throws IOException if an I/O error occurs
     */
    public Cr3PreparedRaw prepareRawDevelopment(int maxWorkers) throws Cr3Exception, IOException {
        var options = Cr3DecodeOptions.rawProcessed().maxWorkers(maxWorkers);
        RawTrack track = rawTrack();
        Rectangle area = processedArea(options, track);
        int orientation = orientation();
        CrxSensorDecoder sensor = prepareSensor(track);
        var imageBytes = ImageBytes.samples((long) area.width * area.height, Integer.BYTES);
        options = memoryOptions(options, track, imageBytes, 0);
        var prepared = prepareRawDevelopment(track, options, area, false, null, null, sensor, null);
        double[][] matrix = prepared.colorMatrix();
        return new Cr3PreparedRaw(prepared.image(), prepared.blackLevel(), area.x, area.y,
                prepared.whiteBalance().clone(), prepared.whiteLevel(),
                new double[][] {matrix[0].clone(), matrix[1].clone(), matrix[2].clone()}, orientation);
    }

    /**
     * Develops a region in final, oriented image coordinates with the same colors as a full decode.
     * A null region selects the complete output. Partial reads retain the Bayer neighbors and
     * optical-black data needed to reproduce the full-frame development.
     *
     * @param options processed RAW options
     * @param outputRegion rectangle within {@link #getRawProcessedBounds(Cr3DecodeOptions)}, or
     *                     {@code null} for the complete image
     * @return the developed image with a zero-based raster
     * @throws IllegalArgumentException for an invalid region or a different output mode
     * @throws Cr3Exception if RAW decoding fails
     * @throws IOException if reading fails
     */
    public BufferedImage decodeRawProcessedRegion(Cr3DecodeOptions options, Rectangle outputRegion)
            throws Cr3Exception, IOException {
        return decodeRawProcessedRegion(options, outputRegion, null);
    }

    /**
     * Develops RAW into a compatible full-frame packed RGB8 or dense three-bank
     * unsigned-short RGB16 image when supplied. The caller must own the destination
     * exclusively during the read. A failed decode can leave partial data in its
     * backing arrays. Other layouts and partial reads return a separately allocated image.
     *
     * @param options RAW development options
     * @param outputRegion region in final image coordinates, or null for the complete image
     * @param destination optional reusable RGB image
     * @return the developed image
     * @throws Cr3Exception if RAW decoding fails
     * @throws IOException if reading fails
     */
    public BufferedImage decodeRawProcessedRegion(Cr3DecodeOptions options, Rectangle outputRegion,
                                                  BufferedImage destination) throws Cr3Exception, IOException {
        return decodeRawProcessedRegion(options, outputRegion, destination, 1, 1);
    }

    /**
     * Develops selected pixels on the oriented output grid. Each selected pixel uses
     * the same full-resolution Bayer neighbours and calibration as a complete read.
     *
     * @param options RAW development options
     * @param outputRegion rectangle in oriented coordinates, or null for the whole image
     * @param xStep spacing between selected output columns, at least one
     * @param yStep spacing between selected output rows, at least one
     * @return zero-based developed image whose first sample is from the region's origin
     * @throws IllegalArgumentException if the rectangle or sample spacing is invalid
     * @throws Cr3Exception if RAW decoding fails
     * @throws IOException if reading fails
     */
    public BufferedImage decodeRawProcessedRegion(Cr3DecodeOptions options, Rectangle outputRegion,
                                                  int xStep, int yStep) throws Cr3Exception, IOException {
        return decodeRawProcessedRegion(options, outputRegion, null, xStep, yStep);
    }

    private BufferedImage decodeRawProcessedRegion(Cr3DecodeOptions options, Rectangle outputRegion,
                                                   BufferedImage destination, int xStep, int yStep)
            throws Cr3Exception, IOException {
        if (xStep < 1 || yStep < 1) {
            throw new IllegalArgumentException("RGB sample spacing must be positive");
        }
        RawTrack track = rawTrack();
        Rectangle fullArea = processedArea(options, track);
        int orientation = orientation();
        Rectangle bounds = orientedBounds(fullArea, orientation);
        if (outputRegion != null && (outputRegion.isEmpty() || !bounds.contains(outputRegion))) {
            throw new IllegalArgumentException("RGB region must be within the developed image");
        }
        OpticalCorrection lens = options.applyLensCorrection() && options.cropToActiveArea() && metadata != null
                ? LensProfiles.find(metadata.cameraModel(), metadata.lensModel(), metadata.focalLengthMm(),
                        metadata.fNumber(), metadata.focusDistanceMetres(), fullArea.width, fullArea.height)
                        .orElse(null)
                : null;
        BufferedImage rgb;
        if (lens == null) {
            rgb = developRegion(options, outputRegion, destination, xStep, yStep, track, fullArea, bounds);
        } else {
            // Each corrected site may use different sensor neighborhoods for red, green and blue.
            // Keep the complete optical field until the remap, even for a small ImageIO window.
            Rectangle region = outputRegion == null ? bounds : outputRegion;
            boolean select = !bounds.equals(region) || xStep != 1 || yStep != 1;
            int sampleBytes = options.bitDepth() == Cr3DecodeOptions.BitDepth.SIXTEEN ? 2 : 1;
            long planeBytes = (long) fullArea.width * fullArea.height * sampleBytes;
            long selectionBytes = select ? (long) Math.ceilDiv(region.width, xStep)
                    * Math.ceilDiv(region.height, yStep) * (sampleBytes == 2 ? 6 : 4) : 0;
            // Remapping and extracting the requested raster are successive allocations.
            long workspace = Math.max(planeBytes, selectionBytes);
            var planned = options.copy().postDecodeMemoryBytes(Math.max(options.postDecodeMemoryBytes(), workspace));
            rgb = developRegion(planned, null, select ? null : destination, 1, 1, track, fullArea, bounds);
            rgb = lens.applyToOwned(rgb, options.applySrgbConversion(), options.maxWorkers(),
                    options.lensShadingStrength());
            if (select) {
                rgb = selectRgbSamples(rgb, region, xStep, yStep);
            }
        }
        if (options.applyCameraTone() && options.applySrgbConversion() && metadata != null) {
            CameraToneCurve.apply(rgb, metadata.cameraModel(), metadata.pictureStyle(), options.maxWorkers());
        }
        return rgb;
    }

    /** Projects the full oriented optical field onto the caller's sampling grid without retaining it. */
    private static BufferedImage selectRgbSamples(BufferedImage full, Rectangle region, int xStep, int yStep) {
        int width = Math.ceilDiv(region.width, xStep);
        int height = Math.ceilDiv(region.height, yStep);
        var source = full.getRaster();
        var out = source.createCompatibleWritableRaster(width, height);
        if (xStep == 1) {
            Object row = null;
            for (int i = 0; i < height; i++) {
                row = source.getDataElements(region.x, region.y + i * yStep, width, 1, row);
                out.setDataElements(0, i, width, 1, row);
            }
        } else {
            int[] row = new int[region.width];
            for (int i = 0; i < height; i++) {
                for (int band = 0; band < source.getNumBands(); band++) {
                    source.getSamples(region.x, region.y + i * yStep, region.width, 1, band, row);
                    for (int j = 1; j < width; j++) {
                        row[j] = row[j * xStep];
                    }
                    out.setSamples(0, i, width, 1, band, row);
                }
            }
        }
        return new BufferedImage(full.getColorModel(), out, false, null);
    }

    private BufferedImage developRegion(Cr3DecodeOptions options, Rectangle outputRegion, BufferedImage destination,
            int xStep, int yStep, RawTrack track, Rectangle fullArea, Rectangle bounds)
            throws Cr3Exception, IOException {
        int orientation = orientation();
        boolean sampled = xStep != 1 || yStep != 1;
        if (sampled) {
            Rectangle grid = outputRegion == null ? bounds : outputRegion;
            // Both ends must land on sample sites before reflecting the grid in sensor coordinates.
            outputRegion = new Rectangle(grid.x, grid.y,
                    1 + (grid.width - 1) / xStep * xStep, 1 + (grid.height - 1) / yStep * yStep);
        }
        boolean transpose = orientation >= 5 && orientation <= 8;
        int columnStep = transpose ? yStep : xStep;
        int rowStep = transpose ? xStep : yStep;
        boolean partial = outputRegion != null && !bounds.equals(outputRegion);
        Rectangle region = partial
                ? ExifOrientationTransformer.sourceRegion(outputRegion, fullArea.width, fullArea.height, orientation)
                : new Rectangle(fullArea.width, fullArea.height);
        Rectangle area = partial ? withDemosaicNeighbors(region, fullArea) : fullArea;
        boolean sixteenBit = options.bitDepth() == Cr3DecodeOptions.BitDepth.SIXTEEN;
        int[] suppliedSamples = sixteenBit || partial || transpose
                ? null : reusableRgbSamples(destination, area);
        short[][] destinationBanks = destination != null && sixteenBit && !partial && !sampled
                ? BayerDemosaicer.rgb16DestinationBanks(destination, bounds.width, bounds.height,
                        options.applySrgbConversion())
                : null;
        CrxSensorDecoder sensor = prepareSensor(track);
        if (sixteenBit && sampled && (canPackAxis(region.width, columnStep) || canPackAxis(region.height, rowStep))) {
            return decodePackedSampledRgb16(track, options, fullArea, region, area, partial, sensor,
                    columnStep, rowStep, orientation);
        }
        long areaSites = (long) area.width * area.height;
        long outputSites = (long) Math.ceilDiv(region.width, columnStep) * Math.ceilDiv(region.height, rowStep);
        var imageBytes = sampled ? ImageBytes.sampledRgb(sixteenBit, areaSites, outputSites, transpose)
                : ImageBytes.rgb(sixteenBit, areaSites, outputSites, partial, orientation,
                        suppliedSamples != null || destinationBanks != null);
        options = memoryOptions(options, track, imageBytes, area.width);
        boolean compact = options.memoryMode() == Cr3DecodeOptions.MemoryMode.LOW_MEMORY;
        // For an unrotated full frame, each source row fits directly in the final green bank.
        short[] suppliedMosaic = destinationBanks != null && orientation == 1 ? destinationBanks[1] : null;
        var prepared = prepareRawDevelopment(track, options, area, partial, suppliedSamples, suppliedMosaic, sensor, null);

        Rectangle localRegion = new Rectangle(fullArea.x + region.x - area.x, fullArea.y + region.y - area.y,
                region.width, region.height);
        if (sixteenBit) {
            var pattern = CrxLosslessRawDecoder.resolveBayerPattern(track.source.cmp1());
            if (destinationBanks != null) {
                return BayerDemosaicer.develop16Into(prepared.compactSamples(), area.width, area.height,
                        pattern, prepared.blackLevel(), area.x, area.y, prepared.whiteBalance(), prepared.whiteLevel(),
                        prepared.colorMatrix(), localRegion, orientation, options.applySrgbConversion(),
                        options.maxWorkers(), destination);
            }
            if (sampled) {
                BufferedImage rgb = BayerDemosaicer.develop16Sampled(prepared.compactSamples(), area.width,
                        area.height, pattern, prepared.blackLevel(), area.x, area.y, prepared.whiteBalance(),
                        prepared.whiteLevel(), prepared.colorMatrix(), localRegion, columnStep, rowStep,
                        options.applySrgbConversion(), options.maxWorkers());
                return ExifOrientationTransformer.applyToOwnedImageCompact(rgb, orientation);
            }
            BufferedImage rgb = BayerDemosaicer.develop16Owned(prepared.compactSamples(), area.width, area.height,
                    pattern, prepared.blackLevel(), area.x, area.y,
                    prepared.whiteBalance(), prepared.whiteLevel(), prepared.colorMatrix(), localRegion,
                    compact && !partial ? 1 : orientation, options.applySrgbConversion(), options.maxWorkers());
            return compact && !partial ? ExifOrientationTransformer.applyToOwnedImageCompact(rgb, orientation) : rgb;
        }

        // White balance and interpolation retain their crop-local Bayer phase.
        if (prepared.whiteBalance() != null) {
            WhiteBalanceProcessor.process(prepared.image(), prepared.whiteBalance(), prepared.blackLevel(),
                    area.x, area.y, options.maxWorkers());
        } else if (prepared.blackLevel() != null) {
            BlackLevelProcessor.process(prepared.image(), prepared.blackLevel(), area.x, area.y);
        }
        BufferedImage rgb = sampled
                ? BayerDemosaicer.demosaicSampled(prepared.image(), prepared.whiteLevel(),
                        localRegion, columnStep, rowStep, options.maxWorkers())
                : BayerDemosaicer.demosaicInPlace(prepared.image(), prepared.whiteLevel(), options.maxWorkers());
        if (prepared.colorMatrix() != null) {
            ColorMatrixProcessor.process(rgb, prepared.colorMatrix(), options.maxWorkers());
        }
        if (options.applySrgbConversion()) {
            SrgbConverter.process(rgb, options.maxWorkers());
        }
        if (sampled) {
            return ExifOrientationTransformer.applyToOwnedImageCompact(rgb, orientation);
        }
        if (partial) {
            rgb = rgb.getSubimage(localRegion.x, localRegion.y, localRegion.width, localRegion.height);
        }
        BufferedImage oriented = compact && !partial
                ? ExifOrientationTransformer.applyToOwnedImageCompact(rgb, orientation)
                : ExifOrientationTransformer.applyToOwnedImage(rgb, orientation);
        return suppliedSamples == null ? oriented : destination;
    }

    private static boolean canPackAxis(int length, int spacing) {
        // There must be two selected sites and room for at least one removable Bayer pair.
        return spacing >= 5 && length > spacing;
    }

    /** Develops the coarse RGB16 grid with the same interpolation neighbors and native calibration. */
    private BufferedImage decodePackedSampledRgb16(RawTrack track, Cr3DecodeOptions options, Rectangle fullArea,
            Rectangle region, Rectangle area, boolean partial, CrxSensorDecoder sensor,
            int columnStep, int rowStep, int orientation) throws Cr3Exception, IOException {
        var local = new Rectangle(fullArea.x + region.x - area.x, fullArea.y + region.y - area.y,
                region.width, region.height);
        var cols = new SampleAxis(area.width, local.x, local.width, columnStep);
        var rows = new SampleAxis(area.height, local.y, local.height, rowStep);
        var grid = new SampleGrid(cols, rows);
        long storedSites = (long) cols.size * rows.size;
        if (rows.gap != 0 && options.applyBlackLevel()) {
            // Four int pedestals per retained row pair, expressed in unsigned-short sample units.
            storedSites += 8L * Math.ceilDiv(rows.size, 2);
        }
        long outputSites = (long) Math.ceilDiv(region.width, columnStep) * Math.ceilDiv(region.height, rowStep);
        boolean transpose = orientation >= 5 && orientation <= 8;
        var imageBytes = ImageBytes.sampledRgb(true, storedSites, outputSites, transpose);
        options = memoryOptions(options, track, imageBytes, cols.size);
        var prepared = prepareRawDevelopment(track, options, area, partial, null, null, sensor, grid);
        var pattern = CrxLosslessRawDecoder.resolveBayerPattern(track.source.cmp1());
        BlackLevel black = prepared.blackLevel();
        int sensorY = area.y;
        if (rows.gap != 0 && black != null && black.isPerRow()) {
            // White normalization was resolved against every optical-black row before projection.
            black = black.forSensorRows(rows.size, y -> area.y + rows.original(y), pattern);
            sensorY = 0;
        }
        local.setSize(cols.span, rows.span);
        var rgb = BayerDemosaicer.develop16Sampled(prepared.compactSamples(), cols.size, rows.size,
                pattern, black, area.x, sensorY, prepared.whiteBalance(), prepared.whiteLevel(),
                prepared.colorMatrix(), local, cols.step, rows.step, options.applySrgbConversion(), options.maxWorkers());
        return ExifOrientationTransformer.applyToOwnedImageCompact(rgb, orientation);
    }

    private static int[] reusableRgbSamples(BufferedImage image, Rectangle area) {
        if (image == null || image.getType() != BufferedImage.TYPE_INT_RGB
                || image.getWidth() != area.width || image.getHeight() != area.height) {
            return null;
        }
        WritableRaster raster = image.getRaster();
        if (raster.getSampleModelTranslateX() != 0 || raster.getSampleModelTranslateY() != 0
                || !(raster.getSampleModel() instanceof SinglePixelPackedSampleModel layout)
                || layout.getScanlineStride() != area.width
                || !(raster.getDataBuffer() instanceof DataBufferInt data)
                || data.getNumBanks() != 1 || data.getOffset() != 0
                || data.getSize() != (long) area.width * area.height) {
            return null;
        }
        int[] samples = data.getData();
        return samples.length == (long) area.width * area.height ? samples : null;
    }

    /** RGB8 keeps an int image; RGB16 keeps compact native codes. The calibration is shared. */
    private record PreparedDevelopment(Cr3RawImage image, short[] compactSamples, BlackLevel blackLevel,
                                       double[] whiteBalance, int whiteLevel, double[][] colorMatrix) {}

    private PreparedDevelopment prepareRawDevelopment(RawTrack track, Cr3DecodeOptions options, Rectangle area,
                                                      boolean partial, int[] suppliedSamples, short[] suppliedMosaic,
                                                      CrxSensorDecoder sensor, SampleGrid grid)
            throws Cr3Exception, IOException {
        int sampleCount = grid == null ? Math.multiplyExact(area.width, area.height)
                : Math.multiplyExact(grid.columns.size, grid.rows.size);
        boolean compact = options.bitDepth() == Cr3DecodeOptions.BitDepth.SIXTEEN;
        short[] compactSamples = compact && suppliedMosaic == null ? new short[sampleCount] : suppliedMosaic;
        int[] samples = compact ? null : suppliedSamples == null ? new int[sampleCount] : suppliedSamples;
        var rawImage = compact ? null : new Cr3RawImage(area.width, area.height, samples, track.depth,
                CrxLosslessRawDecoder.resolveBayerPattern(track.source.cmp1()));

        // Collect the left optical-black border for every sensor row, including rows outside the crop.
        // Even masked rows can set the minimum black level used for demosaic normalization.
        boolean experiment = options.pedestalStrategy() == Cr3DecodeOptions.PedestalStrategy.EXPERIMENTAL_REFERENCE_MATCH;
        int blackColumns = metadata == null ? 0 : metadata.activeAreaLeft();
        var ob = options.applyBlackLevel() && !experiment
                ? ObBlackLevelEstimator.forSensor(track.width, track.height, blackColumns) : null;
        CrxSensorDecoder.RowConsumer write;
        if (grid == null) {
            write = (x, y, row) -> {
                if (ob != null) {
                    ob.addPlaneRow(x, y, row);
                }
                copyBayerRow(samples, compactSamples, area, x, y, row);
            };
        } else {
            write = grid.consumer(compactSamples, area, ob);
        }
        // With the masked columns in the first tile, no two tiles add to the same black-level sums.
        boolean independentTiles = ob == null || blackColumns <= track.source.cmp1().tileWidth();
        CrxSensorDecoder.PlaneRowLimit limit = partial
                ? (x, y, columns, rows) -> ob != null && x < blackColumns
                        ? rows : rowsToRead(area, 1, 1, x, y, columns, rows)
                : CrxSensorDecoder.PlaneRowLimit.ALL_ROWS;
        sensor.decodeSelectedRows(write, options.maxWorkers(), limit, independentTiles);

        int minBlackLevelSubtracted = 0;
        BlackLevel black = null;
        if (options.applyBlackLevel()) {
            black = experiment ? BlackLevel.global(options.experimentalPedestalLevel())
                    : resolveBlackLevelResolution(ob == null ? null : ob.blackLevel()).blackLevel();
            minBlackLevelSubtracted = effectiveMinBlackLevel(black);
        }

        int whiteLevel = Math.max(1, ((1 << track.depth) - 1) - minBlackLevelSubtracted);
        double[] whiteBalance = options.applyWhiteBalance() ? resolveWhiteBalance() : null;
        double[][] colorMatrix = options.applyColorMatrix() ? resolveColorMatrix() : null;
        return new PreparedDevelopment(rawImage, compactSamples, black, whiteBalance, whiteLevel, colorMatrix);
    }

    private static Cr3DecodeOptions memoryOptions(Cr3DecodeOptions options, RawTrack track,
                                                  ImageBytes imageBytes, int processingWidth) {
        var cmp = track.source.cmp1();
        var estimate = Cr3MemoryPolicy.Estimate.forTrack(imageBytes, track.width, track.height,
                cmp.tileWidth(), CrxLossyDecoder.isLossy(cmp), processingWidth);
        return Cr3MemoryPolicy.apply(options, estimate);
    }

    private Rectangle processedArea(Cr3DecodeOptions options, RawTrack track) {
        if (options.outputMode() != Cr3DecodeOptions.OutputMode.RAW_PROCESSED) {
            throw new IllegalArgumentException("A processed RAW output mode is required");
        }
        return options.cropToActiveArea()
                ? activeArea(track.width, track.height) : new Rectangle(track.width, track.height);
    }

    private int orientation() {
        return metadata == null ? 1 : metadata.orientation();
    }

    private static Rectangle orientedBounds(Rectangle area, int orientation) {
        int[] dimensions = ExifOrientationTransformer.outputDimensions(area.width, area.height, orientation);
        return new Rectangle(dimensions[0], dimensions[1]);
    }

    private static Rectangle withDemosaicNeighbors(Rectangle region, Rectangle fullArea) {
        // An even origin preserves the Bayer phase of the whole developed crop.
        int left = Math.max(0, region.x - 1) & ~1;
        int top = Math.max(0, region.y - 1) & ~1;
        int right = region.x + region.width;
        int bottom = region.y + region.height;
        if (right < fullArea.width) {
            right++;
        }
        if (bottom < fullArea.height) {
            bottom++;
        }
        return new Rectangle(fullArea.x + left, fullArea.y + top, right - left, bottom - top);
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
     * Returns zero-based output bounds for RAW_LINEAR or RAW_SENSOR without reading the CRX payload.
     *
     * @param options Bayer output options, including the active-area crop for RAW_LINEAR
     * @return the bounds in which to specify a Bayer read region
     * @throws IllegalArgumentException if the options select a different output mode
     * @throws Cr3Exception if the input has no usable RAW track
     */
    public Rectangle getRawBounds(Cr3DecodeOptions options) throws Cr3Exception {
        RawTrack track = rawTrack();
        Rectangle area = rawArea(options, track);
        return new Rectangle(area.width, area.height);
    }

    /**
     * Decodes a window in the selected Bayer mode's zero-based output coordinates. The top-left
     * source site is always included. The next sites are separated by the supplied positive steps.
     * Passing a null region selects the full output. Partial reads decode the required tile-plane
     * prefixes and allocate raster storage for the requested sites. The output
     * keeps sensor orientation and the selected mode's sample units.
     *
     * @param options native or scaled Bayer output options
     * @param region rectangle within {@link #getRawBounds(Cr3DecodeOptions)}, or {@code null} for the full bounds
     * @param xStep horizontal distance in source sites between retained samples
     * @param yStep vertical distance in source sites between retained samples
     * @return a zero-based, single-band unsigned-short Bayer image
     * @throws IllegalArgumentException for a different output mode, invalid window or subsampling step
     * @throws Cr3Exception if RAW decoding fails
     * @throws IOException if reading fails
     */
    public BufferedImage decodeRawRegion(Cr3DecodeOptions options, Rectangle region, int xStep, int yStep)
            throws Cr3Exception, IOException {
        RawTrack track = rawTrack();
        BayerWindow window = bayerWindow(options, track, region, xStep, yStep);
        CrxSensorDecoder decoder = prepareSensor(track);
        var imageBytes = ImageBytes.samples((long) window.width * window.height, Short.BYTES);
        options = memoryOptions(options, track, imageBytes, 0);
        var output = new BufferedImage(window.width, window.height, BufferedImage.TYPE_USHORT_GRAY);
        short[] pixels = ((DataBufferUShort) output.getRaster().getDataBuffer()).getData();
        decodeBayerWindow(options, track, decoder, window, xStep, yStep, pixels, 0, window.width);
        return output;
    }

    /**
     * Decodes Bayer sites into a caller-owned, one-band unsigned-short raster with unit pixel
     * stride, a single storage bank and nonoverlapping rows.
     * If decoding fails, the raster may contain the sites written before the failure.
     *
     * @param options native or scaled Bayer output options
     * @param region rectangle within {@link #getRawBounds(Cr3DecodeOptions)}, or {@code null} for the full bounds
     * @param xStep horizontal distance in source sites between retained samples
     * @param yStep vertical distance in source sites between retained samples
     * @param target destination raster that contains the complete subsampled output rectangle
     * @param destX destination column of the first sample in the raster's coordinate system
     * @param destY destination row of the first sample in the raster's coordinate system
     * @throws IllegalArgumentException for an incompatible destination, invalid window or subsampling step,
     *                                  or a different output mode
     * @throws Cr3Exception if RAW decoding fails
     * @throws IOException if reading fails
     */
    public void decodeRawRegionInto(Cr3DecodeOptions options, Rectangle region, int xStep, int yStep,
                                    WritableRaster target, int destX, int destY)
            throws Cr3Exception, IOException {
        RawTrack track = rawTrack();
        BayerWindow window = bayerWindow(options, track, region, xStep, yStep);
        if (!(target.getDataBuffer() instanceof DataBufferUShort data) || data.getNumBanks() != 1
                || !(target.getSampleModel() instanceof ComponentSampleModel layout)
                || layout.getNumBands() != 1 || layout.getPixelStride() != 1 || layout.getBankIndices()[0] != 0
                || layout.getScanlineStride() < layout.getWidth()
                || !target.getBounds().contains(destX, destY, window.width, window.height)) {
            throw new IllegalArgumentException("A compatible unsigned-short Bayer destination is required");
        }
        int offset = data.getOffset() + layout.getOffset(destX - target.getSampleModelTranslateX(),
                destY - target.getSampleModelTranslateY());
        CrxSensorDecoder decoder = prepareSensor(track);
        options = memoryOptions(options, track, ImageBytes.samples(0, Short.BYTES), 0);
        decodeBayerWindow(options, track, decoder, window, xStep, yStep, data.getData(),
                offset, layout.getScanlineStride());
    }

    private record BayerWindow(Rectangle area, int width, int height, boolean full) {}

    private BayerWindow bayerWindow(Cr3DecodeOptions options, RawTrack track, Rectangle region, int xStep, int yStep) {
        Rectangle area = rawArea(options, track);
        if (xStep <= 0 || yStep <= 0) {
            throw new IllegalArgumentException("RAW subsampling steps must be positive");
        }
        Rectangle bounds = new Rectangle(area.width, area.height);
        if (region != null) {
            if (region.isEmpty() || !bounds.contains(region)) {
                throw new IllegalArgumentException("RAW region must be within the selected image");
            }
            area.setBounds(area.x + region.x, area.y + region.y, region.width, region.height);
        }
        return new BayerWindow(area, Math.ceilDiv(area.width, xStep), Math.ceilDiv(area.height, yStep),
                (region == null || bounds.equals(region)) && xStep == 1 && yStep == 1);
    }

    private static void decodeBayerWindow(Cr3DecodeOptions options, RawTrack track, CrxSensorDecoder decoder,
                                          BayerWindow window, int xStep, int yStep, short[] pixels,
                                          int offset, int stride) throws Cr3Exception, IOException {
        boolean nativeCodes = options.outputMode() == Cr3DecodeOptions.OutputMode.RAW_SENSOR;
        short[] levels = nativeCodes ? null : linearLevels(track.depth, options.applyBlackLevel());
        CrxSensorDecoder.PlaneRowLimit limit = window.full ? CrxSensorDecoder.PlaneRowLimit.ALL_ROWS
                : (x, y, columns, rows) -> rowsToRead(window.area, xStep, yStep, x, y, columns, rows);
        decoder.decodeSelectedRows((x, y, row) ->
                        writeSamples(pixels, levels, window.area, window.width, offset, stride, xStep, yStep, x, y, row),
                options.maxWorkers(), limit, true);
    }

    private static void writeSamples(short[] pixels, short[] levels, Rectangle area, int outputWidth,
                                     int offset, int stride, int xStep, int yStep, int x, int y, int[] row) {
        int relativeY = y - area.y;
        if (relativeY < 0 || relativeY >= area.height || relativeY % yStep != 0) {
            return;
        }
        // A plane row visits alternate columns. The first sampled column must belong to this tile and phase.
        int first = Math.max(0, Math.ceilDiv(x - area.x, xStep));
        int end = Math.min(outputWidth, Math.ceilDiv(x + 2 * row.length - area.x, xStep));
        if (first >= end) {
            return;
        }
        long firstSite = (long) area.x + (long) first * xStep;
        boolean oddStep = (xStep & 1) != 0;
        if (((firstSite - x) & 1) != 0) {
            if (!oddStep) {
                return;
            }
            first++;
            firstSite += xStep;
        }
        int destinationStep = oddStep ? 2 : 1;
        int sourceStep = oddStep ? xStep : xStep / 2;
        int source = (int) ((firstSite - x) / 2);
        int destinationRow = offset + (relativeY / yStep) * stride;
        for (int column = first; column < end; column += destinationStep, source += sourceStep) {
            pixels[destinationRow + column] = levels == null ? (short) row[source] : levels[row[source]];
        }
    }

    private static int rowsToRead(Rectangle area, int xStep, int yStep, int x, int y, int columns, int rows) {
        if (lastSample(area.x, area.width, xStep, x, columns) < 0) {
            return 0;
        }
        int lastY = lastSample(area.y, area.height, yStep, y, rows);
        return lastY < 0 ? 0 : 1 + (lastY - y) / 2;
    }

    /** Last site common to the requested sampling grid and this tile plane's even or odd phase. */
    private static int lastSample(int start, int length, int step, int planeStart, int planeLength) {
        int first = Math.max(start, planeStart);
        int end = Math.min(start + length - 1, planeStart + 2 * (planeLength - 1));
        if (end < first) {
            return -1;
        }
        int last = end - (end - start) % step;
        if (((last - planeStart) & 1) != 0) {
            if ((step & 1) == 0) {
                return -1;
            }
            last -= step;
        }
        return last >= first ? last : -1;
    }

    private Rectangle rawArea(Cr3DecodeOptions options, RawTrack track) {
        return switch (options.outputMode()) {
            case RAW_SENSOR -> new Rectangle(track.width, track.height);
            case RAW_LINEAR -> options.cropToActiveArea()
                    ? activeArea(track.width, track.height) : new Rectangle(track.width, track.height);
            default -> throw new IllegalArgumentException("A Bayer output mode is required");
        };
    }

    private static short[] linearLevels(int depth, boolean subtractBlack) {
        int maximum = (1 << depth) - 1;
        int black = subtractBlack ? BlackLevelProcessor.DEFAULT_BLACK_LEVEL : 0;
        short[] levels = new short[maximum + 1];
        for (int i = black; i <= maximum; i++) {
            levels[i] = (short) ((long) (i - black) * 65535 / maximum);
        }
        return levels;
    }

    private record RawTrack(Cr3Track source, int width, int height, int depth, CrxHeader.CodecVersion version) {}

    private Cr3RawImage decodeRawBayer() throws Cr3Exception, IOException {
        RawTrack track = rawTrack();
        CrxSensorDecoder sensor = prepareSensor(track);
        var area = new Rectangle(track.width, track.height);
        int[] samples = new int[Math.multiplyExact(track.width, track.height)];
        sensor.decodeRows((x, y, row) -> copyBayerRow(samples, null, area, x, y, row), 0);
        return new Cr3RawImage(track.width, track.height, samples, track.depth,
                CrxLosslessRawDecoder.resolveBayerPattern(track.source.cmp1()));
    }

    private CrxSensorDecoder prepareSensor(RawTrack track) throws Cr3Exception, IOException {
        // The container yields stream positions. The codec resolves its subbands within this sample.
        long offset = track.source.dataOffset();
        int length = (int) track.source.dataSize();
        if (ownedChannel != null) {
            return CrxSensorDecoder.prepare(ownedChannel, offset, length, track.width, track.height, track.depth,
                    track.version, track.source.cmp1());
        }
        return CrxSensorDecoder.prepare(input, offset, length, track.width, track.height, track.depth,
                track.version, track.source.cmp1());
    }

    private RawTrack rawTrack() throws Cr3Exception {
        if (container == null) {
            throw new IllegalStateException("No input set — call setInput() first");
        }
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

        String cncvVersion = container.cncvBox() == null ? null : container.cncvBox().versionString();
        var version = CrxHeader.detectVersion(cmp1, cncvVersion);
        return new RawTrack(rawTrack, rawWidth, rawHeight, bitDepth, version);
    }

    private Rectangle activeArea(int width, int height) {
        if (metadata != null) {
            int left = Math.clamp(metadata.activeAreaLeft(), 0, width);
            int top = Math.clamp(metadata.activeAreaTop(), 0, height);
            int right = Math.clamp(metadata.activeAreaRight(), 0, width);
            int bottom = Math.clamp(metadata.activeAreaBottom(), 0, height);
            if (right > left && bottom > top) {
                return new Rectangle(left, top, right - left, bottom - top);
            }
        }
        return new Rectangle(width, height);
    }

    private static void copyBayerRow(int[] destination, short[] compactDestination, Rectangle area,
                                     int x, int y, int[] row) {
        int relativeY = y - area.y;
        if (relativeY < 0 || relativeY >= area.height) {
            return;
        }
        // A tile-plane row visits every second site. Clip in plane coordinates to retain its phase.
        int first = Math.max(0, Math.ceilDiv(area.x - x, 2));
        int end = Math.min(row.length, Math.ceilDiv(area.x + area.width - x, 2));
        int dest = relativeY * area.width + (x + 2 * first - area.x);
        if (compactDestination != null) {
            // Native decoders validate or clamp the emitted sensor codes before this copy.
            for (int i = first; i < end; i++, dest += 2) {
                compactDestination[dest] = (short) row[i];
            }
        } else {
            for (int i = first; i < end; i++, dest += 2) {
                destination[dest] = row[i];
            }
        }
    }

    /** Sensor and compact coordinates along one axis of a sampled RGB grid. */
    private static final class SampleAxis {
        final int extent;
        final int first;
        final int requestedStep;
        final int intervals;
        final int gap;
        final int size;
        final int span;
        final int step;

        SampleAxis(int extent, int first, int length, int requestedStep) {
            this.extent = extent;
            this.first = first;
            this.requestedStep = requestedStep;
            intervals = (length - 1) / requestedStep;
            // Each selected pixel needs its left and right neighbor. Keeping four
            // slots for even spacing or three for odd spacing preserves the CFA phase.
            step = intervals == 0 ? requestedStep : Math.min(requestedStep, 4 - (requestedStep & 1));
            gap = requestedStep - step;
            size = extent - intervals * gap;
            span = length - intervals * gap;
        }

        /** Compact index for a coordinate to retain, or -1 in a discarded gap. */
        int index(int coordinate) {
            if (gap == 0) {
                return coordinate;
            }
            int relative = Math.max(0, coordinate - first + 1);
            int block = Math.min(relative / requestedStep, intervals);
            if (block < intervals && relative - block * requestedStep >= step) {
                return -1;
            }
            return coordinate - block * gap;
        }

        /** Number of retained coordinates strictly before the given clipped endpoint. */
        int countBefore(int endpoint) {
            // The right-exclusive full extent can be Integer.MAX_VALUE.
            long relative = Math.max(0L, (long) endpoint - first + 1);
            int block = (int) Math.min(relative / requestedStep, intervals);
            int skipped = block * gap;
            if (block < intervals) {
                skipped += Math.max(0, (int) (relative - (long) block * requestedStep) - step);
            }
            return endpoint - skipped;
        }

        int original(int position) {
            int relative = Math.max(0, position - first + 1);
            // Where gaps exist the physical spacing is always three or four.
            int block = step == 3 ? relative / 3 : relative / 4;
            return position + Math.min(block, intervals) * gap;
        }
    }

    private record SampleGrid(SampleAxis columns, SampleAxis rows) {
        CrxSensorDecoder.RowConsumer consumer(short[] samples, Rectangle area, ObBlackLevelEstimator ob) {
            return (x, y, row) -> {
                if (ob != null) {
                    ob.addPlaneRow(x, y, row);
                }
                copy(samples, x - area.x, y - area.y, row);
            };
        }

        void copy(short[] samples, int x, int y, int[] plane) {
            if (y < 0 || y >= rows.extent) {
                return;
            }
            int storedY = rows.index(y);
            if (storedY < 0) {
                return;
            }
            int rowOffset = storedY * columns.size;
            if (columns.gap == 0) {
                int first = Math.max(0, Math.ceilDiv(-x, 2));
                int end = Math.min(plane.length, Math.ceilDiv(columns.extent - x, 2));
                int dest = rowOffset + x + 2 * first;
                for (int i = first; i < end; i++, dest += 2) {
                    samples[dest] = (short) plane[i];
                }
                return;
            }
            int left = Math.max(0, x);
            int right = (int) Math.min(columns.extent, (long) x + 2L * plane.length);
            if (left >= right) {
                return;
            }
            int start = columns.countBefore(left);
            int end = columns.countBefore(right);
            // Removing pairs preserves parity, including when the left edge was discarded.
            start += (start ^ x) & 1;
            for (int dest = start; dest < end; dest += 2) {
                int source = (columns.original(dest) - x) / 2;
                samples[rowOffset + dest] = (short) plane[source];
            }
        }
    }

    private record BlackLevelResolution(PedestalSource source, BlackLevel blackLevel) {}

    private BlackLevelResolution resolveBlackLevelResolution(BlackLevel obLevel) {
        if (obLevel != null) {
            LOG.debug("Using OB-estimated per-row black level");
            return new BlackLevelResolution(PedestalSource.OB_PER_ROW, obLevel);
        }

        // Fall back to per-channel from MakerNote metadata
        if (metadata != null && metadata.perChannelBlackLevels() != null) {
            return new BlackLevelResolution(
                    PedestalSource.METADATA_PER_CHANNEL,
                    BlackLevel.perChannel(metadata.perChannelBlackLevels()));
        }

        // Final fallback: global default
        return new BlackLevelResolution(PedestalSource.DEFAULT_GLOBAL, BlackLevel.defaultLevel());
    }

    /**
     * Returns the recorded CTMD calibration for the current input, or null when unavailable.
     * Resolves it on demand so geometry, preview and native sensor reads can skip CTMD.
     */
    public Cr3ColorData getCaptureColorData() {
        if (input != null && !captureColorDataRead) {
            try {
                captureColorData = Cr3ColorData.read(input, container);
            } catch (IOException e) {
                LOG.warn("Could not read CR3 sensor calibration: {}", e.getMessage());
            }
            captureColorDataRead = true;
        }
        return captureColorData;
    }

    /**
     * Resolves the best available white balance multipliers.
     * Uses the recorded CTMD or MakerNote gains, with daylight values as fallback.
     */
    private double[] resolveWhiteBalance() {
        Cr3ColorData color = getCaptureColorData();
        if (color != null) {
            double[] captureGains = color.whiteBalanceMultipliers();
            if (captureGains != null) {
                return captureGains;
            }
        }
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
