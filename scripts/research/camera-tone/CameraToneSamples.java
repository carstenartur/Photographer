package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.container.Cr3Parser;

import javax.imageio.stream.FileImageInputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

/** Exports central RGB16 luminance distributions and the same exposure's regional JPEG means. */
public final class CameraToneSamples {
    private static final int BINS = 128;
    private static final double[] LINEAR = new double[65536];
    static {
        for (int i = 0; i < LINEAR.length; i++) {
            LINEAR[i] = ImageQualityHelper.srgbToLinear(i / 65535.0);
        }
    }

    private CameraToneSamples() {}

    public static void main(String[] args) throws Exception {
        System.out.println("file\ttile\tcount\tmean_peak\tmean_Y\tjpeg_Y");
        for (String arg : args) {
            File file = new File(arg);
            Cr3CameraJpegProbe.Camera camera;
            try (var input = new FileImageInputStream(file)) {
                camera = Cr3CameraJpegProbe.readCamera(input, Cr3Parser.parse(input));
            }
            BufferedImage raw = ImageQualityHelper.readDefaultCr3(file);
            ImageQualityHelper.requireRgb16(raw);
            if (camera == null || !camera.comparable() || camera.lab() == null
                    || !Cr3CameraJpegProbe.compatible(raw.getWidth(), raw.getHeight(), camera.width(), camera.height())) {
                throw new IOException("No comparable embedded sRGB reference for " + file);
            }
            // The inner 4 × 4 of the camera JPEG probe's 8 × 6 regions limit optical shading.
            for (int row = 1; row < 5; row++) {
                for (int col = 2; col < 6; col++) {
                    int tile = row * 8 + col;
                    double l = camera.lab()[tile][0];
                    double reference = l > 8 ? Math.pow((l + 16) / 116, 3) : l * 27 / 24389;
                    sample(file.getName(), raw, row, col, tile, reference);
                }
            }
        }
    }

    private static void sample(String name, BufferedImage image, int row, int col, int tile, double reference) {
        int width = image.getWidth(), height = image.getHeight();
        int x0 = edge(col, 8, width), x1 = edge(col + 1, 8, width);
        int y0 = edge(row, 6, height), y1 = edge(row + 1, 6, height);
        int[] count = new int[BINS];
        double[] peak = new double[BINS], luminance = new double[BINS];
        int[] rgb = new int[3 * (x1 - x0)];
        for (int y = y0; y < y1; y += 4) {
            image.getRaster().getPixels(x0, y, x1 - x0, 1, rgb);
            for (int i = 0; i < rgb.length; i += 12) {
                int max = Math.max(rgb[i], Math.max(rgb[i + 1], rgb[i + 2]));
                int bin = max * BINS / 65536;
                count[bin]++;
                peak[bin] += LINEAR[max];
                luminance[bin] += .2126729 * LINEAR[rgb[i]] + .7151522 * LINEAR[rgb[i + 1]]
                        + .0721750 * LINEAR[rgb[i + 2]];
            }
        }
        for (int i = 0; i < BINS; i++) {
            if (count[i] > 0) {
                System.out.printf(Locale.ROOT, "%s\t%d\t%d\t%.10f\t%.10f\t%.10f%n", name, tile, count[i],
                        peak[i] / count[i], luminance[i] / count[i], reference);
            }
        }
    }

    private static int edge(int i, int count, int dimension) {
        return (int) Math.round(dimension * (.125 + .75 * i / count));
    }
}
