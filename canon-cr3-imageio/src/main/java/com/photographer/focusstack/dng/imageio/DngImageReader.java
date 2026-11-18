package com.photographer.focusstack.dng.imageio;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import javax.imageio.IIOException;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.plugins.tiff.TIFFDirectory;
import javax.imageio.plugins.tiff.TIFFImageReadParam;
import javax.imageio.stream.ImageInputStream;

/** Reads the native sensor mosaic of a classic, uncompressed, 16-bit Bayer DNG in IFD0. */
public final class DngImageReader extends ImageReader {
    private static final ImageTypeSpecifier SENSOR_TYPE =
            ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_USHORT_GRAY);

    private ImageReader tiff;
    private DngImageMetadata metadata;

    DngImageReader(DngImageReaderSpi provider) {
        super(provider);
    }

    @Override
    public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
        super.setInput(input, seekForwardOnly, ignoreMetadata);
        releaseTiff();
    }

    private void releaseTiff() {
        if (tiff != null) {
            tiff.dispose();
            tiff = null;
        }
        metadata = null;
    }

    @Override
    public void dispose() {
        releaseTiff();
        super.dispose();
    }

    private DngImageMetadata metadata(int imageIndex) throws IOException {
        if (input == null) {
            throw new IllegalStateException("DNG input not set");
        }
        if (imageIndex != 0) {
            throw new IndexOutOfBoundsException("DNG image index: " + imageIndex);
        }
        if (metadata != null) {
            return metadata;
        }

        ImageInputStream stream = (ImageInputStream) input;
        if (!getOriginatingProvider().canDecodeInput(stream)) {
            throw new IIOException("Expected an uncompressed unsigned 16-bit Bayer DNG in the first TIFF directory");
        }
        var provider = DngImageReaderSpi.platformTiffProvider();
        if (provider == null) {
            throw new IIOException("The Java TIFF reader is unavailable");
        }
        tiff = provider.createReaderInstance();

        long start = stream.getStreamPosition();
        try {
            tiff.setInput(new DngTiffInput(stream), false, false);
            var param = (TIFFImageReadParam) tiff.getDefaultReadParam();
            param.setReadUnknownTags(true);
            // Initialize the TIFF IFD including DNG extensions before any other TIFF calls.
            // This JDK rendered image is lazy; no pixel buffer is created here.
            tiff.readAsRenderedImage(0, param);
            var dng = DngImageMetadata.fromDirectory(TIFFDirectory.createFromMetadata(tiff.getImageMetadata(0)));
            if ((long) dng.sensorWidth() * dng.sensorHeight() > Integer.MAX_VALUE) {
                throw new IIOException("DNG image exceeds the Java TIFF reader's pixel limit");
            }
            metadata = dng;
            return dng;
        } catch (IOException | RuntimeException ex) {
            releaseTiff();
            stream.seek(start);
            throw ex;
        }
    }

    @Override
    public int getNumImages(boolean allowSearch) throws IOException {
        if (allowSearch && isSeekForwardOnly()) {
            throw new IllegalStateException("Cannot search in seek-forward-only mode");
        }
        metadata(0);
        return 1;
    }

    @Override
    public int getWidth(int imageIndex) throws IOException {
        return metadata(imageIndex).sensorWidth();
    }

    @Override
    public int getHeight(int imageIndex) throws IOException {
        return metadata(imageIndex).sensorHeight();
    }

    @Override
    public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IOException {
        metadata(imageIndex);
        return List.of(SENSOR_TYPE).iterator();
    }

    @Override
    public IIOMetadata getStreamMetadata() {
        return null;
    }

    @Override
    public DngImageMetadata getImageMetadata(int imageIndex) throws IOException {
        return metadata(imageIndex);
    }

    @Override
    public boolean canReadRaster() {
        return true;
    }

    @Override
    public Raster readRaster(int imageIndex, ImageReadParam param) throws IOException {
        metadata(imageIndex);
        // readRaster ignores the image/type destination, and its destination offset is logical.
        ImageReadParam samples = new ImageReadParam();
        Point origin = new Point();
        if (param != null) {
            checkReadParamBandSettings(param, 1, 1);
            samples.setSourceRegion(param.getSourceRegion());
            samples.setSourceSubsampling(param.getSourceXSubsampling(), param.getSourceYSubsampling(),
                    param.getSubsamplingXOffset(), param.getSubsamplingYOffset());
            origin = param.getDestinationOffset();
        }
        Raster raster = tiff.read(0, samples).getRaster();
        return origin.x == 0 && origin.y == 0 ? raster : raster.createTranslatedChild(origin.x, origin.y);
    }

    @Override
    public BufferedImage read(int imageIndex, ImageReadParam param) throws IOException {
        var dng = metadata(imageIndex);
        if (param != null) {
            BufferedImage target = param.getDestination();
            int bands = target == null ? 1 : target.getSampleModel().getNumBands();
            checkReadParamBandSettings(param, 1, bands);
            int[] selection = param.getDestinationBands();
            int band = selection == null ? 0 : selection[0];
            if (target != null && (target.getSampleModel().getDataType() != DataBuffer.TYPE_USHORT
                    || target.getSampleModel().getSampleSize(band) != 16)) {
                throw new IIOException("Bayer samples require an unsigned 16-bit destination band");
            }
            if (target == null && param.getDestinationType() != null && !SENSOR_TYPE.equals(param.getDestinationType())) {
                throw new IIOException("Destination type is incompatible with unsigned 16-bit Bayer samples");
            }
            if (target != null || !param.getDestinationOffset().equals(new Point())) {
                return readIntoDestination(param, dng, target, band);
            }
        }
        var image = tiff.read(0, param);
        // The original CFA phase, crop and calibration describe only the full sensor geometry.
        if (param != null) {
            Rectangle source = param.getSourceRegion();
            var full = new Rectangle(dng.sensorWidth(), dng.sensorHeight());
            if ((source != null && !full.intersection(source).equals(full))
                    || param.getSourceXSubsampling() != 1 || param.getSourceYSubsampling() != 1) {
                return image;
            }
        }
        return DngImageMetadata.attach(image, dng);
    }

    private BufferedImage readIntoDestination(ImageReadParam param, DngImageMetadata dng, BufferedImage target, int band)
            throws IOException {
        // The platform TIFF reader can write an entire strip across a caller's ROI.
        // Decode into a zero-origin window, then copy only the requested destination samples.
        Rectangle source = new Rectangle();
        Rectangle output = new Rectangle();
        computeRegions(param, dng.sensorWidth(), dng.sensorHeight(), target, source, output);
        var window = new ImageReadParam();
        window.setSourceRegion(source);
        window.setSourceSubsampling(param.getSourceXSubsampling(), param.getSourceYSubsampling(), 0, 0);
        Raster decoded = tiff.read(0, window).getRaster();
        if (target == null) {
            target = SENSOR_TYPE.createBufferedImage(output.x + output.width, output.y + output.height);
        }
        var region = target.getRaster().createWritableChild(output.x, output.y, output.width, output.height,
                0, 0, new int[]{band});
        region.setRect(decoded);
        return target;
    }

}
