package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.encode.Cr3CameraProfile;
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
import java.util.Objects;

/** Writes a full native Bayer mosaic using a built-in lossless CR3 camera profile. */
public final class CanonCr3ImageWriter extends ImageWriter {
    private Cr3CameraProfile defaultCameraProfile;

    CanonCr3ImageWriter(ImageWriterSpi provider) {
        super(provider);
    }

    /** Sets this writer's default camera profile. Per-image parameters can override it. */
    public void setDefaultCameraProfile(Cr3CameraProfile profile) {
        defaultCameraProfile = Objects.requireNonNull(profile, "profile");
    }

    @Override
    public void reset() {
        super.reset();
        defaultCameraProfile = null;
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
        Cr3CameraProfile profile = defaultCameraProfile;
        if (param instanceof CanonCr3ImageWriteParam cr3 && cr3.getCameraProfile() != null) {
            profile = cr3.getCameraProfile();
        }
        if (profile == null) {
            throw new IIOException("Select a lossless CR3 camera profile with setCameraProfile "
                    + "or setDefaultCameraProfile before writing");
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
        Cr3Writer.write(output, sensor, profile);
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
