package com.photographer.focusstack.cr3.encode;

import com.photographer.focusstack.cr3.encode.Cr3CameraProfile.RawLayout;
import com.photographer.focusstack.cr3.processing.SrgbConverter;
import com.photographer.focusstack.cr3.raw.codec.CrxLosslessEncoder;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;

/** Writes lossless 14-bit Bayer samples using a built-in CR3 camera profile. */
public final class Cr3Writer {
    private static final int SENSOR_MAX = (1 << Cr3CameraProfile.BITS_PER_SAMPLE) - 1;
    private static final int[] PREVIEW_LEVELS = previewLevels();
    private static final ComponentColorModel JPEG_COLOR_MODEL = new ComponentColorModel(
            ColorSpace.getInstance(ColorSpace.CS_sRGB), false, false, Transparency.OPAQUE, DataBuffer.TYPE_BYTE);

    private Cr3Writer() {
    }

    /**
     * Writes one image at the stream's current byte-aligned position.
     * The raster contains the full native mosaic, in the selected profile's Bayer pattern.
     * All image tracks, embedded JPEGs and metadata are generated from the profile and raster.
     * Use a new destination or write at the end of its current contents.
     * The caller owns the output stream.
     *
     * @param output seekable destination for the CR3 file
     * @param sensor full-sensor, single-band unsigned-short raster in native sensor units
     * @param profile format of the target camera's lossless RAW mode
     * @throws IOException if the sensor samples or destination cannot be written
     */
    public static void write(ImageOutputStream output, Raster sensor, Cr3CameraProfile profile) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(sensor, "sensor");
        Objects.requireNonNull(profile, "profile");
        if (output.getBitOffset() != 0) {
            throw new IIOException("A CR3 file must start at a byte boundary");
        }
        if (output.length() > output.getStreamPosition()) {
            throw new IIOException("Use a new or truncated destination, or write at the end of the stream");
        }
        validateSensor(sensor, profile);
        RawLayout raw = profile.raw();
        RawLayout small = profile.reducedRaw();
        var area = raw.crop();
        Raster crop = sensor.createChild(sensor.getMinX() + area.left(), sensor.getMinY() + area.top(),
                area.width(), area.height(), 0, 0, null);
        int cropCfa = raw.cfa() ^ (area.left() & 1) ^ ((area.top() & 1) << 1);
        Raster reduced = reducedMosaic(crop, cropCfa, small);
        Cr3ContainerWriter.write(output, profile, Cr3ProfileMetadata.camera(profile),
                Cr3ProfileMetadata.exif(), Cr3ProfileMetadata.makerNote(profile), Cr3ProfileMetadata.gps(),
                Cr3ProfileMetadata.timed(profile),
                out -> CrxLosslessEncoder.write(out, sensor, raw.tileWidth(), raw.directory()),
                out -> CrxLosslessEncoder.write(out, reduced, small.tileWidth(), small.directory()),
                out -> writeJpeg(out, crop, cropCfa, profile.jpegWidth(), profile.jpegHeight()),
                out -> writeJpeg(out, crop, cropCfa, profile.previewWidth(), profile.previewHeight()),
                out -> writeJpeg(out, crop, cropCfa, profile.thumbnailWidth(), profile.thumbnailHeight()));
    }

    private static void validateSensor(Raster sensor, Cr3CameraProfile profile) throws IIOException {
        if (sensor.getNumBands() != 1 || sensor.getTransferType() != DataBuffer.TYPE_USHORT) {
            throw new IIOException("CR3 writing requires a single-band unsigned-short Bayer raster");
        }
        int width = sensor.getWidth();
        int height = sensor.getHeight();
        if (width != profile.sensorWidth() || height != profile.sensorHeight()) {
            throw new IIOException("The Bayer raster for " + profile.cameraModel() + " must be "
                    + profile.sensorWidth() + "x" + profile.sensorHeight() + " including masked margins");
        }
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            sensor.getSamples(sensor.getMinX(), sensor.getMinY() + y, width, 1, 0, row);
            for (int code : row) {
                if (code > SENSOR_MAX) {
                    throw new IIOException("CR3 writing requires native 14-bit sample codes in the range 0–16383");
                }
            }
        }
    }

    private static Raster reducedMosaic(Raster crop, int cropCfa, RawLayout target) {
        int width = target.width();
        int height = target.height();
        var reduced = Raster.createInterleavedRaster(DataBuffer.TYPE_USHORT, width, height, 1, null);
        int phase = cropCfa ^ target.cfa();
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            int cellY = cell(y / 2, height / 2, crop.getHeight());
            for (int x = 0; x < width; x++) {
                int cellX = cell(x / 2, width / 2, crop.getWidth());
                int colourSite = (((y & 1) << 1) | (x & 1)) ^ phase;
                row[x] = site(crop, cellX, cellY, colourSite);
            }
            reduced.setSamples(0, y, width, 1, 0, row);
        }
        return reduced;
    }

    private static void writeJpeg(ImageOutputStream output, Raster crop, int cfa, int width, int height)
            throws IOException {
        // RGB byte order lets the JDK JPEG writer read scanlines without rearranging their samples.
        var raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, width, height,
                3 * width, 3, new int[]{0, 1, 2}, null);
        var image = new BufferedImage(JPEG_COLOR_MODEL, raster, false, null);
        byte[] pixels = ((DataBufferByte) raster.getDataBuffer()).getData();
        int offset = 0;
        for (int y = 0; y < height; y++) {
            int cellY = cell(y, height, crop.getHeight());
            for (int x = 0; x < width; x++) {
                int cellX = cell(x, width, crop.getWidth());
                int red = site(crop, cellX, cellY, cfa);
                int blue = site(crop, cellX, cellY, cfa ^ 3);
                int green = (site(crop, cellX, cellY, cfa ^ 1) + site(crop, cellX, cellY, cfa ^ 2)) / 2;
                pixels[offset++] = (byte) PREVIEW_LEVELS[red];
                pixels[offset++] = (byte) PREVIEW_LEVELS[green];
                pixels[offset++] = (byte) PREVIEW_LEVELS[blue];
            }
        }
        // The JPEG provider owns its cache and can flush it without constraining later CR3 header patches.
        var bytes = new ByteArrayOutputStream();
        try (var jpeg = new MemoryCacheImageOutputStream(bytes)) {
            if (!ImageIO.write(image, "JPEG", jpeg)) {
                throw new IIOException("A JPEG ImageIO writer is required for CR3 previews");
            }
        }
        output.write(bytes.toByteArray());
    }

    private static int cell(int coordinate, int targetExtent, int sourceExtent) {
        return (int) ((long) coordinate * (sourceExtent / 2) / targetExtent);
    }

    private static int site(Raster raster, int cellX, int cellY, int colourSite) {
        return raster.getSample(2 * cellX + (colourSite & 1), 2 * cellY + (colourSite >>> 1), 0);
    }

    private static int[] previewLevels() {
        int[] levels = new int[SENSOR_MAX + 1];
        for (int i = 0; i < levels.length; i++) {
            levels[i] = (int) Math.round(255 * SrgbConverter.linearToSrgb((double) i / SENSOR_MAX));
        }
        return levels;
    }
}
