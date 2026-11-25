package com.photographer.focusstack.cr3.processing;

import com.photographer.focusstack.cr3.raw.Cr3Workers;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.util.Arrays;

/**
 * Centered rectilinear lens calibration at one focal length, aperture and focus distance.
 * The PTLens polynomial maps ideal coordinates back into the source. The red and blue
 * scale factors describe constant lateral chromatic aberration relative to green. The
 * PA polynomial describes source illumination relative to the center. See the
 * <a href="https://lensfun.github.io/manual/latest/group__Lens.html">published models</a>.
 * All coefficients are dimensionless. Identity uses zero polynomial coefficients and
 * unit red and blue scales.
 */
public record OpticalCorrection(double a, double b, double c, double redScale, double blueScale,
                                double k1, double k2, double k3) {
    public OpticalCorrection {
        if (!Double.isFinite(a) || !Double.isFinite(b) || !Double.isFinite(c)
                || !Double.isFinite(k1) || !Double.isFinite(k2) || !Double.isFinite(k3)
                || !Double.isFinite(redScale) || redScale <= 0
                || !Double.isFinite(blueScale) || blueScale <= 0) {
            throw new IllegalArgumentException("Invalid radial lens coefficients");
        }
    }

    /**
     * Correct a complete, exclusively owned active-area development at its original dimensions.
     * Output pixels sample the largest centered valid field up to the input scale that
     * preserves the source sampling density on axis. Rotation
     * or mirroring about the image center commutes with these radial models. Crop and
     * subsampling requests should be applied to the result.
     *
     * <p>Each bilinear source tap is shaded in linear light before interpolation. Each band
     * is resampled once and committed before starting the next, so scratch space is one
     * channel plus a few small lookup tables, independent of the worker count.</p>
     *
     * @param image developed RGB image with three unsigned 8- or 16-bit samples
     * @param srgb whether the samples use the sRGB transfer function
     * @param maxWorkers worker limit, or zero to use the available processors
     * @return the corrected image, reusing the input storage
     */
    public BufferedImage applyToOwned(BufferedImage image, boolean srgb, int maxWorkers) {
        return applyToOwned(image, srgb, maxWorkers, 1);
    }

    /**
     * Apply geometry and color-channel registration, scaling only the optical shading gain.
     * A strength of zero keeps scene brightness and one applies the complete calibrated gain.
     * The valid image field is determined from the full calibration at every strength, so
     * bracketing the amount of shading correction never moves the subject.
     *
     * @param image complete, exclusively owned RGB development
     * @param srgb whether the source samples use the sRGB transfer function
     * @param maxWorkers maximum worker count, or zero for automatic selection
     * @param shadingStrength fraction of the calibrated gain above or below unity, from zero to one
     * @return the input image after correction
     */
    public BufferedImage applyToOwned(BufferedImage image, boolean srgb, int maxWorkers, double shadingStrength) {
        if (!Double.isFinite(shadingStrength) || shadingStrength < 0 || shadingStrength > 1) {
            throw new IllegalArgumentException("Shading strength must be in [0, 1]");
        }
        var raster = image.getRaster();
        int bits = raster.getSampleModel().getSampleSize(0);
        if ((bits != 8 && bits != 16)
                || !Arrays.equals(raster.getSampleModel().getSampleSize(), new int[]{bits, bits, bits})) {
            throw new IllegalArgumentException("Lens correction requires three 8- or 16-bit RGB bands");
        }
        int width = image.getWidth(), height = image.getHeight();
        int workers = Cr3Workers.count(maxWorkers, Pixels.maxStripes(width, height));
        if (width < 2 || height < 2 || (a == 0 && b == 0 && c == 0 && redScale == 1
                && blueScale == 1 && k1 == 0 && k2 == 0 && k3 == 0)) {
            return image;
        }

        Frame frame = new Frame(width, height, shadingStrength);
        short[][] banks = Pixels.denseRgb16Bands(image);
        int count = Math.multiplyExact(width, height);
        short[] output16 = bits == 16 ? new short[count] : null;
        byte[] output8 = bits == 8 ? new byte[count] : null;
        double[] decode = srgb ? (bits == 16 ? InverseSrgb.USHORT : InverseSrgb.BYTE) : null;
        var packed = Pixels.packed(image);
        for (int band = 0; band < 3; band++) {
            final int channel = band;
            double colorScale = band == 0 ? redScale : band == 2 ? blueScale : 1;
            Source source;
            if (banks != null) {
                short[] bank = banks[band];
                source = (x, y) -> bank[y * width + x] & 0xffff;
            } else if (packed != null) {
                int shift = 16 - 8 * band;
                source = (x, y) -> (packed.data()[packed.rowStart(y) + x] >>> shift) & 255;
            } else {
                source = (x, y) -> raster.getSample(x, y, channel);
            }
            Cr3Workers.run(workers, worker -> {
                int end = Pixels.stripeStart(worker + 1, height, workers);
                for (int y = Pixels.stripeStart(worker, height, workers); y < end; y++) {
                    frame.renderRow(y, colorScale, source, decode, output16, output8);
                }
            });
            if (banks != null) {
                System.arraycopy(output16, 0, banks[band], 0, count);
            } else {
                writeBand(raster, band, width, height, output16, output8);
            }
        }
        return image;
    }

    private static void writeBand(WritableRaster raster, int band, int width, int height, short[] high, byte[] low) {
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            int offset = y * width;
            for (int x = 0; x < width; x++) {
                row[x] = high == null ? low[offset + x] & 255 : high[offset + x] & 0xffff;
            }
            raster.setSamples(0, y, width, 1, band, row);
        }
    }

    @FunctionalInterface
    private interface Source {
        int at(int x, int y);
    }

    private static final class InverseSrgb {
        static final double[] USHORT = codes(65535), BYTE = codes(255);
        private static double[] codes(int maximum) {
            double[] values = new double[maximum + 1];
            for (int i = 0; i <= maximum; i++) {
                double v = (double) i / maximum;
                values[i] = maximum * (v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4));
            }
            return values;
        }
    }

    private double distortionScale(double r) {
        return ((a * r + b) * r + c) * r + (1 - a - b - c);
    }

    private double attenuation(double rSquared) {
        return 1 + rSquared * (k1 + rSquared * (k2 + rSquared * k3));
    }

    private final class Frame {
        private static final int INTERVALS = 16384;
        final int width, height;
        final double cx, cy, diagonal, shortRadius;
        final double[] xSquare, ySquare;
        final double[] geometry = new double[INTERVALS + 2], gain = new double[INTERVALS + 2];
        final double validSourceRadius, validTargetRadius;

        Frame(int width, int height, double shadingStrength) {
            this.width = width;
            this.height = height;
            cx = (width - 1) * 0.5;
            cy = (height - 1) * 0.5;
            diagonal = Math.hypot(cx, cy);
            shortRadius = Math.min(cx, cy);
            xSquare = axis(width, cx, diagonal);
            ySquare = axis(height, cy, diagonal);
            // A polynomial calibrated for the visible image circle may cross zero before
            // reaching the extreme RAW corner. Use its continuous center branch only.
            int lastPositive = 0, lastIncreasing = 0;
            double previousRadius = -1;
            for (int i = 0; i <= INTERVALS; i++) {
                double t = (double) i / INTERVALS;
                double illumination = attenuation(t);
                if (illumination > 0 && i <= lastPositive + 1) {
                    gain[i] = 1 + shadingStrength * (1 / illumination - 1);
                    lastPositive = i;
                }
                double r = Math.sqrt(t) * diagonal / shortRadius;
                double sourceRadius = r * distortionScale(r);
                if (sourceRadius > previousRadius && i <= lastIncreasing + 1) {
                    lastIncreasing = i;
                }
                previousRadius = sourceRadius;
            }
            validSourceRadius = diagonal * Math.sqrt((double) lastPositive / INTERVALS);
            validTargetRadius = diagonal * Math.sqrt((double) lastIncreasing / INTERVALS);
            double crop = cropScale();
            for (int i = 0; i <= INTERVALS; i++) {
                double r = crop * diagonal / shortRadius * Math.sqrt((double) i / INTERVALS);
                geometry[i] = crop * distortionScale(r);
            }
            gain[INTERVALS + 1] = gain[INTERVALS];
            geometry[INTERVALS + 1] = geometry[INTERVALS];
        }

        private double[] axis(int size, double center, double halfDiagonal) {
            double[] values = new double[size];
            for (int i = 0; i < size; i++) {
                double radius = (i - center) / halfDiagonal;
                values[i] = radius * radius * INTERVALS;
            }
            return values;
        }

        private double cropScale() {
            // The center slope of the backward PTLens map is d = 1-a-b-c. When d > 1,
            // an output pixel at native scale spans more than one source pixel near the
            // center and shrinks the subject. Preserve that detail, then check the borders.
            double upper = Math.min(1, 1 / distortionScale(0));
            if (upper <= 0) throw new IllegalArgumentException("Lens calibration folds at its center");
            if (fits(upper)) return upper;
            double low = 0, high = upper;
            for (int i = 0; i < 32; i++) {
                double mid = (low + high) * 0.5;
                if (fits(mid)) low = mid;
                else high = mid;
            }
            if (low == 0) throw new IllegalArgumentException("Lens calibration has no valid image field");
            return low;
        }

        private boolean fits(double crop) {
            if (crop * diagonal > validTargetRadius) return false;
            // On the monotone radial branch the extrema of each coordinate lie on
            // the perimeter. Axis symmetry reduces the check to two half edges.
            double outerBand = Math.max(1, Math.max(redScale, blueScale));
            for (int x = width / 2; x < width; x++) {
                if (!fitsPoint(x - cx, cy, crop, outerBand)) return false;
            }
            for (int y = height / 2; y < height; y++) {
                if (!fitsPoint(cx, y - cy, crop, outerBand)) return false;
            }
            return true;
        }

        private boolean fitsPoint(double dx, double dy, double crop, double outerBand) {
            double r = Math.hypot(dx, dy) * crop / shortRadius;
            double scale = crop * distortionScale(r) * outerBand;
            double x = dx * scale, y = dy * scale;
            return scale > 0 && x <= cx && y <= cy && (validSourceRadius == diagonal
                    || Math.hypot(Math.ceil(cx + x) - cx, Math.ceil(cy + y) - cy) <= validSourceRadius);
        }

        void renderRow(int y, double colorScale, Source source, double[] decode, short[] high, byte[] low) {
            int max = high == null ? 255 : 65535;
            for (int x = 0; x < width; x++) {
                double scale = colorScale * lookup(geometry, xSquare[x] + ySquare[y]);
                double sx = Math.clamp(cx + (x - cx) * scale, 0, width - 1);
                double sy = Math.clamp(cy + (y - cy) * scale, 0, height - 1);
                int left = Math.min((int) sx, width - 2), top = Math.min((int) sy, height - 2);
                double fx = sx - left, fy = sy - top;
                double tl = radiance(source, decode, left, top);
                double tr = radiance(source, decode, left + 1, top);
                double bl = radiance(source, decode, left, top + 1);
                double br = radiance(source, decode, left + 1, top + 1);
                double upper = tl + fx * (tr - tl);
                double value = upper + fy * (bl + fx * (br - bl) - upper);
                int code;
                if (decode == null) {
                    code = (int) Math.round(Math.clamp(value, 0, max));
                } else {
                    int encoded = Srgb16Encoder.encode(value / max) & 0xffff;
                    code = high == null ? (encoded + 128) / 257 : encoded;
                }
                if (high == null) low[y * width + x] = (byte) code;
                else high[y * width + x] = (short) code;
            }
        }

        private double radiance(Source source, double[] decode, int x, int y) {
            int code = source.at(x, y);
            return (decode == null ? code : decode[code]) * lookup(gain, xSquare[x] + ySquare[y]);
        }

        private static double lookup(double[] table, double index) {
            int i = (int) index;
            return table[i] + (index - i) * (table[i + 1] - table[i]);
        }
    }
}
