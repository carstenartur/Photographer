package com.photographer.focusstack.cr3.processing;

import com.photographer.focusstack.cr3.raw.Cr3Workers;

import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * Camera-like display contrast measured from paired Canon JPEG and developed RGB16 images.
 * A single monotone curve is shared by the verified camera/style combinations. It adjusts
 * the largest linear channel and applies the same gain to all three, preserving linear RGB
 * ratios, neutral greys, black and white. The fit is documented in scripts/research/camera-tone.
 */
public final class CameraToneCurve {
    private static final int MAX = 65535;
    private static final double SLOPE = 1.523;
    private static final double OFFSET = 2.0;
    private static final double SRGB_OFFSET = .055 * MAX;

    private CameraToneCurve() {}

    /**
     * Returns the array payload first allocated by supported display tone. These
     * tables are shared across imports and workers. Querying this estimate does
     * not initialize them; array headers and collector reserves are not included.
     */
    public static long estimateTableBytes() {
        return (MAX + 1L) * (3 * Double.BYTES + Integer.BYTES);
    }

    /** Returns whether this camera and recorded Canon Picture Style have been evaluated. */
    public static boolean supports(String cameraModel, int pictureStyle) {
        if (cameraModel == null) return false;
        String model = cameraModel.strip();
        return ("Canon PowerShot G5 X Mark II".equalsIgnoreCase(model)
                && (pictureStyle == 0x84 || pictureStyle == 0x85))
                || ("Canon EOS R".equalsIgnoreCase(model)
                && (pictureStyle == 0x81 || pictureStyle == 0x83 || pictureStyle == 0x87));
    }

    /**
     * Applies the curve to an 8- or 16-bit three-channel sRGB image in place.
     * Returns false and leaves the image intact when the capture is outside the evaluated set.
     * Source regions and subsampled reads use the same curve as a complete frame.
     */
    public static boolean apply(BufferedImage image, String cameraModel, int pictureStyle, int maxWorkers) {
        if (!supports(cameraModel, pictureStyle)) return false;
        int bits = image.getSampleModel().getSampleSize(0);
        if (!image.getColorModel().getColorSpace().isCS_sRGB() || (bits != 8 && bits != 16)
                || !Arrays.equals(image.getSampleModel().getSampleSize(), new int[]{bits, bits, bits})) {
            throw new IllegalArgumentException("Camera tone requires three 8- or 16-bit sRGB channels");
        }
        var bands = Pixels.denseRgb16Bands(image);
        if (bands != null) {
            int width = image.getWidth(), height = image.getHeight();
            int stripes = Cr3Workers.count(maxWorkers, Pixels.maxStripes(width, height));
            Cr3Workers.run(stripes, stripe -> {
                int first = width * Pixels.stripeStart(stripe, height, stripes);
                int end = width * Pixels.stripeStart(stripe + 1, height, stripes);
                transform16(bands[0], bands[1], bands[2], first, end);
            });
        } else if (bits == 8) {
            Pixels.transform(image, maxWorkers, CameraToneCurve::transform8);
        } else {
            // Other legal layouts, including interleaved destinations and translated subimages.
            Cr3Workers.count(maxWorkers, 1);
            var raster = image.getRaster();
            int[] row = new int[3 * image.getWidth()];
            for (int y = 0; y < image.getHeight(); y++) {
                raster.getPixels(0, y, image.getWidth(), 1, row);
                for (int i = 0; i < row.length; i += 3) {
                    int peak = Math.max(row[i], Math.max(row[i + 1], row[i + 2]));
                    row[i] = applyGain(row[i], peak);
                    row[i + 1] = applyGain(row[i + 1], peak);
                    row[i + 2] = applyGain(row[i + 2], peak);
                }
                raster.setPixels(0, y, image.getWidth(), 1, row);
            }
        }
        return true;
    }

    private static void transform16(short[] red, short[] green, short[] blue, int first, int end) {
        for (int i = first; i < end; i++) {
            int r = Short.toUnsignedInt(red[i]), g = Short.toUnsignedInt(green[i]), b = Short.toUnsignedInt(blue[i]);
            int peak = Math.max(r, Math.max(g, b));
            red[i] = (short) applyGain(r, peak);
            green[i] = (short) applyGain(g, peak);
            blue[i] = (short) applyGain(b, peak);
        }
    }

    private static int transform8(int argb) {
        int r = ((argb >>> 16) & 255) * 257, g = ((argb >>> 8) & 255) * 257, b = (argb & 255) * 257;
        int peak = Math.max(r, Math.max(g, b));
        if (peak == MAX) return argb;
        return (argb & 0xff000000) | (encode8(r, peak) << 16) | (encode8(g, peak) << 8) | encode8(b, peak);
    }

    private static int encode8(int channel, int peak) {
        return Math.min(254, (applyGain(channel, peak) + 128) / 257);
    }

    private static int applyGain(int channel, int peak) {
        // In the power segment, forward(gain * inverse(sRGB)) is affine in the original code.
        // The per-peak cutoff requires both the input and the scaled output to be in that segment.
        if (peak == MAX) return channel;
        if (channel > Tables.POWER_CUTOFF[peak]) {
            return Math.min(MAX - 1, (int) ((channel + SRGB_OFFSET) * Tables.POWER_SCALE[peak] - SRGB_OFFSET + .5));
        }
        return Short.toUnsignedInt(Srgb16Encoder.encode(Tables.LINEAR[channel] * Tables.GAIN[peak]));
    }

    static double mapPeak(double x) {
        if (x == 0 || x == 1) return x;
        double odds = Math.pow(x / (1 - x), SLOPE) * Math.exp(OFFSET);
        return .1 * x + .9 * odds / (1 + odds);
    }

    /** Initialized only for supported display tone; coverage queries and explicit neutral imports allocate none. */
    private static final class Tables {
        static final double[] LINEAR = new double[MAX + 1];
        static final double[] GAIN = new double[MAX + 1];
        static final double[] POWER_SCALE = new double[MAX + 1];
        static final int[] POWER_CUTOFF = new int[MAX + 1];
        static {
            for (int i = 0; i <= MAX; i++) {
                double code = (double) i / MAX;
                double linear = code <= .04045 ? code / 12.92 : Math.pow((code + .055) / 1.055, 2.4);
                double gain = i == 0 ? 1 : mapPeak(linear) / linear;
                LINEAR[i] = linear;
                GAIN[i] = gain;
                POWER_SCALE[i] = Math.pow(gain, 1 / 2.4);
                POWER_CUTOFF[i] = (int) Math.ceil(MAX * Math.max(.04045, SrgbConverter.linearToSrgb(.0031308 / gain)));
            }
        }
    }
}
