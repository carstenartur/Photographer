package com.photographer.focusstack.dng.imageio;

import com.photographer.focusstack.cr3.imageio.Cr3ImageMetadata;

import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.event.IIOWriteProgressListener;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.ImageOutputStreamImpl;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Writes a complete, native unsigned-16-bit sensor mosaic as an uncompressed CFA DNG.
 * Supply capture metadata through {@link IIOImage} or the rendered image's
 * {@link DngImageMetadata#PROPERTY_NAME} property.
 */
public final class DngImageWriter extends ImageWriter {

    DngImageWriter(ImageWriterSpi provider) {
        super(provider);
    }

    @Override
    public boolean canWriteRasters() {
        return true;
    }

    @Override
    public IIOMetadata getDefaultStreamMetadata(ImageWriteParam param) {
        return null;
    }

    @Override
    public IIOMetadata getDefaultImageMetadata(ImageTypeSpecifier type, ImageWriteParam param) {
        // An image type alone does not describe the camera or the color filter arrangement.
        return null;
    }

    @Override
    public IIOMetadata convertStreamMetadata(IIOMetadata metadata, ImageWriteParam param) {
        Objects.requireNonNull(metadata, "metadata");
        return null;
    }

    @Override
    public DngImageMetadata convertImageMetadata(IIOMetadata metadata, ImageTypeSpecifier type, ImageWriteParam param) {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(type, "type");
        try {
            return getOriginatingProvider().canEncodeImage(type) ? sensorMetadata(metadata) : null;
        } catch (IIOException ex) {
            return null;
        }
    }

    private static DngImageMetadata sensorMetadata(Object metadata) throws IIOException {
        if (metadata instanceof DngImageMetadata dng) {
            return dng;
        }
        return metadata instanceof Cr3ImageMetadata cr3 ? DngImageMetadata.fromCr3(cr3) : null;
    }

    @Override
    public void write(IIOMetadata streamMetadata, IIOImage image, ImageWriteParam param) throws IOException {
        if (getOutput() == null) {
            throw new IllegalStateException("Set an ImageOutputStream before writing a DNG");
        }
        if (image == null) {
            throw new IllegalArgumentException("An image is required");
        }

        RenderedImage sensor;
        Rectangle bounds;
        if (image.hasRaster()) {
            Raster raster = image.getRaster();
            if (!DngImageWriterSpi.isSensorRaster(raster.getSampleModel())) {
                throw new IIOException("Expected a single-band unsigned-16-bit sensor raster");
            }
            bounds = raster.getBounds();
            sensor = asImage(raster);
        } else {
            sensor = image.getRenderedImage();
            if (!getOriginatingProvider().canEncodeImage(ImageTypeSpecifier.createFromRenderedImage(sensor))) {
                throw new IIOException("Expected a single-band unsigned-16-bit sensor image");
            }
            bounds = new Rectangle(sensor.getMinX(), sensor.getMinY(), sensor.getWidth(), sensor.getHeight());
        }
        requireFullImage(param, bounds);
        Object source = image.getMetadata();
        if (source == null && !image.hasRaster()) {
            source = sensor.getProperty(DngImageMetadata.PROPERTY_NAME);
        }
        DngImageMetadata metadata = sensorMetadata(source);
        if (metadata == null) {
            throw new IIOException("Supply DNG capture metadata for the native sensor mosaic");
        }
        if (sensor.getWidth() != metadata.sensorWidth() || sensor.getHeight() != metadata.sensorHeight()) {
            throw new IIOException("The mosaic dimensions differ from the sensor dimensions in the DNG metadata");
        }

        ImageWriter tiff = defaultTiffWriter();
        try {
            clearAbortRequest();
            var destination = (ImageOutputStream) getOutput();
            tiff.setOutput(destination.getStreamPosition() == 0 ? destination : new RelativeOutput(destination));
            tiff.addIIOWriteProgressListener(new Progress());
            tiff.addIIOWriteWarningListener((writer, index, warning) -> processWarningOccurred(index, warning));
            var encoding = tiff.getDefaultWriteParam();
            encoding.setCompressionMode(ImageWriteParam.MODE_DISABLED);
            tiff.write(null, new IIOImage(sensor, null, metadata.toDirectory().getAsMetadata()), encoding);
        } finally {
            tiff.dispose();
        }
    }

    private static void requireFullImage(ImageWriteParam param, Rectangle bounds) throws IIOException {
        if (param == null) {
            return;
        }
        Rectangle region = param.getSourceRegion();
        if ((region != null && !region.contains(bounds)) || param.getSourceXSubsampling() != 1
                || param.getSourceYSubsampling() != 1
                || (param.getSourceBands() != null && !Arrays.equals(param.getSourceBands(), new int[]{0}))) {
            throw new IIOException("DNG export requires the full CFA mosaic without cropping or subsampling");
        }
    }

    // A Raster has no ColorModel. Wrap its existing storage for the stock TIFF RenderedImage writer.
    private static BufferedImage asImage(Raster raster) {
        ColorModel gray = ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_USHORT_GRAY).getColorModel();
        if (gray.isCompatibleRaster(raster)) {
            var origin = new Point(raster.getSampleModelTranslateX(), raster.getSampleModelTranslateY());
            var base = Raster.createWritableRaster(raster.getSampleModel(), raster.getDataBuffer(), origin);
            var view = base.createWritableChild(raster.getMinX(), raster.getMinY(),
                    raster.getWidth(), raster.getHeight(), 0, 0, null);
            return new BufferedImage(gray, view, false, null);
        }
        var normalized = new BufferedImage(raster.getWidth(), raster.getHeight(), BufferedImage.TYPE_USHORT_GRAY);
        normalized.getRaster().setRect(raster.createTranslatedChild(0, 0));
        return normalized;
    }

    private static ImageWriter defaultTiffWriter() throws IIOException {
        var writers = ImageIO.getImageWritersByFormatName("TIFF");
        while (writers.hasNext()) {
            var writer = writers.next();
            if (writer.getClass().getModule() == ImageWriter.class.getModule()) {
                return writer;
            }
            writer.dispose();
        }
        throw new IIOException("The Java platform TIFF writer is unavailable");
    }

    // The JDK TIFF writer seeks absolute offsets. Embedded DNG streams start at their own header.
    private static final class RelativeOutput extends ImageOutputStreamImpl {
        private final ImageOutputStream target;
        private final long start;

        RelativeOutput(ImageOutputStream target) throws IOException {
            this.target = target;
            this.start = target.getStreamPosition();
        }

        @Override public int read() throws IOException {
            int value = target.read();
            bitOffset = 0;
            if (value >= 0) streamPos++;
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = target.read(bytes, offset, length);
            bitOffset = 0;
            if (count > 0) streamPos += count;
            return count;
        }

        @Override public void write(int value) throws IOException {
            flushBits();
            target.write(value);
            streamPos++;
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            flushBits();
            target.write(bytes, offset, length);
            streamPos += length;
        }

        @Override public void seek(long position) throws IOException {
            super.seek(position);
            target.seek(Math.addExact(start, position));
        }

        @Override public long length() {
            try {
                long length = target.length();
                return length < 0 ? -1 : length - start;
            } catch (IOException ex) {
                return -1;
            }
        }
    }

    /** Forward progress and cancellation between the outer writer and its TIFF encoder. */
    private final class Progress implements IIOWriteProgressListener {
        @Override public void imageStarted(ImageWriter writer, int index) {
            processImageStarted(index);
            if (abortRequested()) {
                writer.abort();
            }
        }
        @Override public void imageProgress(ImageWriter writer, float percentage) {
            processImageProgress(percentage);
            if (abortRequested()) {
                writer.abort();
            }
        }
        @Override public void imageComplete(ImageWriter writer) { processImageComplete(); }
        @Override public void writeAborted(ImageWriter writer) { processWriteAborted(); }
        @Override public void thumbnailStarted(ImageWriter writer, int image, int thumbnail) { }
        @Override public void thumbnailProgress(ImageWriter writer, float percentage) { }
        @Override public void thumbnailComplete(ImageWriter writer) { }
    }
}
