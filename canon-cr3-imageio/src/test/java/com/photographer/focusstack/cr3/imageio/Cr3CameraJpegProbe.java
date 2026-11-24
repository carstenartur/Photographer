package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.processing.ExifOrientationTransformer;

import javax.imageio.ImageIO;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;

/**
 * Measures the public RGB16 import against the same capture's camera-rendered JPEG.
 * Camera tone curves, white balance, gamut mapping and lens correction can all affect the result.
 * Coarse tiles in the central 75% of each image dimension reduce the influence of small crop and
 * registration differences. Residual differences in framing can still affect a tile. There is no
 * image registration, tone matching or exposure fitting.
 */
public final class Cr3CameraJpegProbe {
    static final String HEADER = "file\tstatus\tjpeg_source\tprofile\tjpeg_orientation"
            + "\traw_width\traw_height\tjpeg_width\tjpeg_height"
            + "\tmedian_dE00\tp90_dE00\tmedian_dL\tmedian_da\tmedian_db\tjpeg_median_L";
    private static final int COLUMNS = 8;
    private static final int ROWS = 6;
    private static final double[] LINEAR = new double[65536];
    static {
        for (int i = 0; i < LINEAR.length; i++) {
            LINEAR[i] = ImageQualityHelper.srgbToLinear(i / 65535.0);
        }
    }

    private Cr3CameraJpegProbe() {}

    public static void main(String[] args) throws IOException {
        if (args.length == 0) throw new IllegalArgumentException("Usage: Cr3CameraJpegProbe FILE.cr3 [FILE.cr3 ...]");
        System.out.println(HEADER);
        boolean failed = false;
        for (String arg : args) {
            try {
                System.out.println(measure(new File(arg)).tsv(arg));
            } catch (IOException | Cr3Exception | IllegalArgumentException e) {
                System.err.println(arg + ": " + e.getMessage());
                System.out.println(new Result("error", null, 0, 0, null).tsv(arg));
                failed = true;
            }
        }
        if (failed) throw new IOException("At least one CR3 could not be measured");
    }

    record Metrics(double medianE, double p90E, double medianL, double medianA, double medianB,
                   double jpegMedianL) {}
    record Camera(String source, String profile, boolean comparable, int orientation,
                  int width, int height, double[][] lab) {}
    record Result(String status, Camera camera, int rawWidth, int rawHeight, Metrics metrics) {
        String tsv(String name) {
            String jpeg = camera == null ? "-\t-\t-" : String.join("\t", camera.source, camera.profile,
                    Integer.toString(camera.orientation));
            String sizes = (rawWidth == 0 ? "-\t-" : rawWidth + "\t" + rawHeight) + "\t"
                    + (camera == null ? "-\t-" : camera.width + "\t" + camera.height);
            String scores = metrics == null ? "-\t-\t-\t-\t-\t-"
                    : String.format(Locale.ROOT, "%.4f\t%.4f\t%+.4f\t%+.4f\t%+.4f\t%.4f",
                        metrics.medianE, metrics.p90E, metrics.medianL, metrics.medianA, metrics.medianB,
                        metrics.jpegMedianL);
            return String.join("\t", name.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '),
                    status, jpeg, sizes, scores);
        }
    }

    static Result measure(File file) throws IOException, Cr3Exception {
        // Finish the full-size JPEG and retain just its Lab tiles before developing the RAW.
        Camera camera;
        try (var input = new FileImageInputStream(file)) {
            camera = readCamera(input, Cr3Parser.parse(input));
        }
        if (camera == null) return new Result("no_camera_jpeg", null, 0, 0, null);
        if (!camera.comparable) return new Result("unsupported_profile", camera, 0, 0, null);
        if (camera.orientation < 1 || camera.orientation > 8) return new Result("orientation", camera, 0, 0, null);
        var raw = ImageQualityHelper.readDefaultCr3(file);
        ImageQualityHelper.requireRgb16(raw);
        int w = raw.getWidth();
        int h = raw.getHeight();
        if (!compatible(w, h, camera.width, camera.height)) return new Result("geometry", camera, w, h, null);
        return new Result("ok", camera, w, h, compare(grid(raw, 1, 1), camera.lab));
    }

    static Camera readCamera(ImageInputStream input, Cr3Container container) throws IOException {
        for (Cr3Track track : container.tracks()) {
            if (track.type() != Cr3Track.TrackType.JPEG_BIG) continue;
            long offset = track.dataOffset(), size = track.dataSize();
            if (offset <= 0 || size < 2 || offset > input.length() || size > input.length() - offset
                    || size > Integer.MAX_VALUE) throw new IOException("Invalid embedded JPEG_BIG sample range");
            input.seek(offset);
            if (input.readUnsignedShort() != 0xffd8) throw new IOException("JPEG_BIG sample has no JPEG SOI");
            input.seek(offset);
            byte[] bytes = new byte[(int) size];
            input.readFully(bytes);
            return decodeCamera(input, container, "JPEG_BIG", bytes, track.width(), track.height());
        }
        var preview = container.prvwBox();
        // Some HDR captures store another image format in PRVW; it is not a JPEG reference.
        if (preview == null || preview.jpegData().length == 0) return null;
        byte[] bytes = preview.jpegData();
        if (bytes.length >= 20 && ByteBuffer.wrap(bytes).getInt() == 20
                && bytes[4] == 'C' && bytes[5] == 'I' && bytes[6] == 'S' && bytes[7] == 'Z') return null;
        return decodeCamera(input, container, "PRVW", preview.jpegData(), preview.width(), preview.height());
    }

    private static boolean isJpeg(byte[] bytes) {
        return bytes != null && bytes.length >= 2 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216;
    }

    private static Camera decodeCamera(ImageInputStream input, Cr3Container container, String source,
            byte[] bytes, int declaredWidth, int declaredHeight) throws IOException {
        if (!isJpeg(bytes)) {
            throw new IOException(source + " has no JPEG SOI");
        }
        var tags = Cr3CameraJpegTags.read(input, container, bytes);
        var readers = ImageIO.getImageReadersByFormatName("JPEG");
        if (!readers.hasNext()) throw new IOException("No JPEG ImageIO reader");
        var reader = readers.next();
        try (var jpegInput = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            reader.setInput(jpegInput);
            int width = reader.getWidth(0), height = reader.getHeight(0);
            if ((declaredWidth > 0 && width != declaredWidth) || (declaredHeight > 0 && height != declaredHeight)) {
                throw new IOException(source + " image disagrees with its container dimensions");
            }
            int orientation = tags.orientation();
            var size = ExifOrientationTransformer.outputDimensions(width, height, orientation);
            String profile = tags.profile();
            boolean comparable = tags.comparable();
            double[][] tiles = null;
            if (comparable && orientation >= 1 && orientation <= 8 && gridFits(size[0], size[1])) {
                var jpeg = reader.read(0);
                if (jpeg.getColorModel().getColorSpace().isCS_sRGB()
                        && Arrays.equals(jpeg.getSampleModel().getSampleSize(), new int[] {8, 8, 8})) {
                    tiles = grid(jpeg, orientation, 257);
                } else {
                    profile = "unsupported_JPEG_pixel_format";
                    comparable = false;
                }
            }
            return new Camera(source, profile, comparable, orientation, size[0], size[1], tiles);
        } finally {
            reader.dispose();
        }
    }

    /** A 1% ratio tolerance permits modest active-area margins, but rejects different aspect crops. */
    static boolean compatible(int w1, int h1, int w2, int h2) {
        if (!gridFits(w1, h1) || !gridFits(w2, h2)) return false;
        double a = (double) w1 * h2;
        double b = (double) w2 * h1;
        return Math.abs(a - b) <= 0.01 * Math.min(a, b);
    }

    private static boolean gridFits(int width, int height) {
        return width * 0.75 >= COLUMNS && height * 0.75 >= ROWS;
    }

    /** Every pixel in a tile contributes in linear light. scaleTo16 is 1 for RGB16 and 257 for RGB8. */
    static double[][] grid(BufferedImage image, int orientation, int scaleTo16) {
        int w = image.getWidth(), h = image.getHeight();
        var size = ExifOrientationTransformer.outputDimensions(w, h, orientation);
        double[][] tiles = new double[ROWS * COLUMNS][];
        for (int y = 0; y < ROWS; y++) {
            int top = edge(y, ROWS, size[1]), bottom = edge(y + 1, ROWS, size[1]);
            for (int x = 0; x < COLUMNS; x++) {
                int left = edge(x, COLUMNS, size[0]), right = edge(x + 1, COLUMNS, size[0]);
                var output = new Rectangle(left, top, right - left, bottom - top);
                var source = ExifOrientationTransformer.sourceRegion(output, w, h, orientation);
                tiles[y * COLUMNS + x] = meanLab(image.getRaster(), source, scaleTo16);
            }
        }
        return tiles;
    }

    private static int edge(int i, int count, int dimension) {
        return (int) Math.round(dimension * (0.125 + 0.75 * i / count));
    }

    private static double[] meanLab(Raster raster, Rectangle region, int scaleTo16) {
        double r = 0, g = 0, b = 0;
        int[] row = new int[3 * region.width];
        for (int y = region.y; y < region.y + region.height; y++) {
            raster.getPixels(region.x, y, region.width, 1, row);
            for (int i = 0; i < row.length; i += 3) {
                r += LINEAR[row[i] * scaleTo16];
                g += LINEAR[row[i + 1] * scaleTo16];
                b += LINEAR[row[i + 2] * scaleTo16];
            }
        }
        double pixels = (double) region.width * region.height;
        return ImageQualityHelper.linearSrgbToLab(r / pixels, g / pixels, b / pixels,
                ImageQualityHelper.ReferenceWhite.D65);
    }

    static Metrics compare(double[][] raw, double[][] jpeg) {
        int n = raw.length;
        double[] e = new double[n], l = new double[n], a = new double[n], b = new double[n], cameraL = new double[n];
        for (int i = 0; i < n; i++) {
            e[i] = ImageQualityHelper.deltaE2000(raw[i], jpeg[i]);
            l[i] = raw[i][0] - jpeg[i][0];
            a[i] = raw[i][1] - jpeg[i][1];
            b[i] = raw[i][2] - jpeg[i][2];
            cameraL[i] = jpeg[i][0];
        }
        double medianE = median(e);
        return new Metrics(medianE, e[(int) Math.ceil(0.90 * n) - 1], median(l), median(a), median(b), median(cameraL));
    }

    private static double median(double[] values) {
        Arrays.sort(values);
        return (values[(values.length - 1) / 2] + values[values.length / 2]) / 2;
    }
}
