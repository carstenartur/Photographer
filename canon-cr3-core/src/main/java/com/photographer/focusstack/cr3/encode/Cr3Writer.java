package com.photographer.focusstack.cr3.encode;

import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.processing.SrgbConverter;
import com.photographer.focusstack.cr3.raw.codec.CrxLosslessEncoder;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.awt.image.Raster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Writes lossless 14-bit Bayer samples using the layout and metadata of a camera CR3 reference. */
public final class Cr3Writer {
    private static final int SENSOR_MAX = (1 << 14) - 1;
    private static final int[] PREVIEW_LEVELS = previewLevels();

    private Cr3Writer() {
    }

    /**
     * Writes one image at the stream's current byte-aligned position.
     * The source raster contains the camera's full native mosaic, in the reference's CFA order.
     * Capture metadata comes from the reference. All image tracks and embedded JPEGs are regenerated.
     * Use a destination separate from the camera reference, at the end of its current contents.
     * The caller owns the output stream.
     *
     * @param output seekable destination for the CR3 file
     * @param sensor full-sensor, single-band unsigned-short raster in native sensor units
     * @param cameraReference reference capture with a supported lossless camera layout
     * @throws IOException if the reference, sensor samples or destination cannot be written
     */
    public static void write(ImageOutputStream output, Raster sensor, Path cameraReference) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(sensor, "sensor");
        Objects.requireNonNull(cameraReference, "cameraReference");
        if (output.getBitOffset() != 0) {
            throw new IIOException("A CR3 file must start at a byte boundary");
        }
        if (output.length() > output.getStreamPosition()) {
            throw new IIOException("Use a new or truncated destination, or write at the end of the stream");
        }
        try (var reference = new FileImageInputStream(cameraReference.toFile())) {
            var template = Cr3CameraTemplate.read(reference);
            var raw = template.raw();
            var small = template.reducedRaw();
            validateSensor(sensor, raw);
            Raster crop = visibleMosaic(sensor, raw);
            validateImageBounds(template, crop);
            var area = raw.iad1();
            int cropCfa = raw.cmp1().cfaLayout() ^ (area.cropLeft() & 1) ^ ((area.cropTop() & 1) << 1);
            Raster reduced = reducedMosaic(crop, cropCfa, small);
            byte[] directory = readDirectory(reference, raw);
            byte[] reducedDirectory = readDirectory(reference, small);
            template.write(output,
                    out -> CrxLosslessEncoder.write(out, sensor, raw.cmp1().tileWidth(), directory),
                    out -> CrxLosslessEncoder.write(out, reduced, small.cmp1().tileWidth(), reducedDirectory),
                    out -> writeJpeg(out, crop, cropCfa, template.jpeg().width(), template.jpeg().height()),
                    out -> writeJpeg(out, crop, cropCfa, template.previewWidth(), template.previewHeight()),
                    out -> writeJpeg(out, crop, cropCfa, template.thumbnailWidth(), template.thumbnailHeight()));
        }
    }

    private static void validateSensor(Raster sensor, Cr3Track track) throws IIOException {
        if (sensor.getNumBands() != 1 || sensor.getTransferType() != DataBuffer.TYPE_USHORT) {
            throw new IIOException("CR3 writing requires a single-band unsigned-short Bayer raster");
        }
        var cmp = track.cmp1();
        int width = sensor.getWidth();
        int height = sensor.getHeight();
        if (width != cmp.imageWidth() || height != cmp.imageHeight()) {
            throw new IIOException("The Bayer raster must match the camera reference's full sensor dimensions of "
                    + cmp.imageWidth() + "x" + cmp.imageHeight());
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

    private static Raster visibleMosaic(Raster sensor, Cr3Track raw) throws IIOException {
        var area = raw.iad1();
        if (area == null) {
            throw new IIOException("The CR3 camera reference lacks its sensor crop");
        }
        int left = area.cropLeft();
        int top = area.cropTop();
        // The stored inclusive IAD1 endpoints are also used by the CR3-to-DNG mapping.
        int width = area.cropRight() - left + 1;
        int height = area.cropBottom() - top + 1;
        if (width < 2 || height < 2 || area.cropRight() >= sensor.getWidth()
                || area.cropBottom() >= sensor.getHeight()) {
            throw new IIOException("The CR3 camera reference has an invalid preview crop");
        }
        return sensor.createChild(sensor.getMinX() + left, sensor.getMinY() + top, width, height, 0, 0, null);
    }

    private static byte[] readDirectory(ImageInputStream reference, Cr3Track track) throws IOException {
        byte[] directory = new byte[track.cmp1().mdatHeaderSize()];
        reference.seek(track.dataOffset());
        reference.readFully(directory);
        return directory;
    }

    private static void validateImageBounds(Cr3CameraTemplate template, Raster crop) throws IIOException {
        if (template.jpeg().width() > crop.getWidth() || template.jpeg().height() > crop.getHeight()
                || template.reducedRaw().width() > crop.getWidth() || template.reducedRaw().height() > crop.getHeight()
                || template.previewWidth() > crop.getWidth() || template.previewHeight() > crop.getHeight()
                || template.thumbnailWidth() > crop.getWidth() || template.thumbnailHeight() > crop.getHeight()) {
            throw new IIOException("A CR3 image track exceeds the camera reference's visible sensor dimensions");
        }
    }

    private static Raster reducedMosaic(Raster crop, int cropCfa, Cr3Track target) {
        int width = target.cmp1().imageWidth();
        int height = target.cmp1().imageHeight();
        var reduced = Raster.createInterleavedRaster(DataBuffer.TYPE_USHORT, width, height, 1, null);
        int phase = cropCfa ^ target.cmp1().cfaLayout();
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
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        for (int y = 0; y < height; y++) {
            int cellY = cell(y, height, crop.getHeight());
            for (int x = 0; x < width; x++) {
                int cellX = cell(x, width, crop.getWidth());
                int red = site(crop, cellX, cellY, cfa);
                int blue = site(crop, cellX, cellY, cfa ^ 3);
                int green = (site(crop, cellX, cellY, cfa ^ 1) + site(crop, cellX, cellY, cfa ^ 2)) / 2;
                pixels[y * width + x] = (PREVIEW_LEVELS[red] << 16)
                        | (PREVIEW_LEVELS[green] << 8) | PREVIEW_LEVELS[blue];
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
