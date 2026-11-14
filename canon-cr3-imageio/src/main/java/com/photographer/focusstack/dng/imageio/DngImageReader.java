package com.photographer.focusstack.dng.imageio;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import javax.imageio.IIOException;
import javax.imageio.ImageIO;
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
        var readers = ImageIO.getImageReadersByFormatName("TIFF");
        while (readers.hasNext()) {
            ImageReader candidate = readers.next();
            if (candidate.getClass().getModule() == ImageReader.class.getModule()
                    && candidate.getDefaultReadParam() instanceof TIFFImageReadParam) {
                tiff = candidate;
                break;
            }
            candidate.dispose();
        }
        if (tiff == null) {
            throw new IIOException("The Java TIFF reader is unavailable");
        }

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
        var image = tiff.read(0, param);
        // The original CFA phase, crop and calibration describe only the full sensor geometry.
        if (param != null) {
            Rectangle source = param.getSourceRegion();
            var full = new Rectangle(dng.sensorWidth(), dng.sensorHeight());
            if (param.getDestination() != null || (source != null && !full.intersection(source).equals(full))
                    || param.getSourceXSubsampling() != 1 || param.getSourceYSubsampling() != 1
                    || !param.getDestinationOffset().equals(new Point())) {
                return image;
            }
        }
        return DngImageMetadata.attach(image, dng);
    }

}
