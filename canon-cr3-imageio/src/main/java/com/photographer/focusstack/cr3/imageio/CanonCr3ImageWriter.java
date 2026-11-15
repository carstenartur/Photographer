package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.encode.Cr3Writer;

import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Writes a full native Bayer mosaic using the camera layout and metadata from a supplied CR3. */
public final class CanonCr3ImageWriter extends ImageWriter {
    private Path defaultCameraReference;

    CanonCr3ImageWriter(ImageWriterSpi provider) {
        super(provider);
    }

    /** Sets this writer's default camera reference. Per-image parameters can override it. */
    public void setDefaultCameraReference(Path cameraReference) {
        defaultCameraReference = Objects.requireNonNull(cameraReference, "cameraReference");
    }

    @Override
    public void reset() {
        super.reset();
        defaultCameraReference = null;
    }

    @Override
    public CanonCr3ImageWriteParam getDefaultWriteParam() {
        return new CanonCr3ImageWriteParam();
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
        return null;
    }

    @Override
    public IIOMetadata convertStreamMetadata(IIOMetadata metadata, ImageWriteParam param) {
        return null;
    }

    @Override
    public IIOMetadata convertImageMetadata(IIOMetadata metadata, ImageTypeSpecifier type, ImageWriteParam param) {
        return null;
    }

    @Override
    public void write(IIOMetadata streamMetadata, IIOImage image, ImageWriteParam param) throws IOException {
        if (!(getOutput() instanceof ImageOutputStream output)) {
            throw new IllegalStateException("Set an ImageOutputStream before writing a CR3");
        }
        if (image == null) {
            throw new IllegalArgumentException("An image is required");
        }
        Path reference = defaultCameraReference;
        if (param instanceof CanonCr3ImageWriteParam cr3 && cr3.getCameraReference() != null) {
            reference = cr3.getCameraReference();
        }
        if (reference == null) {
            throw new IIOException("Select a lossless CR3 camera reference with setCameraReference "
                    + "or setDefaultCameraReference before writing");
        }

        Raster sensor = sensorRaster(image);
        if (param != null && (param.getSourceXSubsampling() != 1 || param.getSourceYSubsampling() != 1
                || param.getSourceBands() != null
                || (param.getSourceRegion() != null && !param.getSourceRegion().equals(sensor.getBounds())))) {
            throw new IIOException("CR3 writing requires the complete camera sensor mosaic");
        }
        clearAbortRequest();
        processImageStarted(0);
        if (abortRequested()) {
            processWriteAborted();
            return;
        }
        Cr3Writer.write(output, sensor, reference);
        processImageComplete();
    }

    private Raster sensorRaster(IIOImage image) throws IIOException {
        if (image.hasRaster()) {
            return image.getRaster();
        }
        var rendered = image.getRenderedImage();
        if (!getOriginatingProvider().canEncodeImage(ImageTypeSpecifier.createFromRenderedImage(rendered))) {
            throw new IIOException("CR3 writing requires a single-band unsigned-short Bayer image");
        }
        return rendered instanceof BufferedImage buffered ? buffered.getRaster() : rendered.getData();
    }
}
