package com.photographer.focusstack.cr3.imageio;

import java.awt.image.BufferedImage;

/**
 * Self-contained SSIM and CIE ΔE2000 colour-difference helpers for
 * perceptual quality comparison of decoded RAW vs embedded JPEG previews.
 *
 * <p>No runtime dependencies beyond the JDK are required; this class lives
 * entirely in {@code src/test/java}.</p>
 *
 * <h3>SSIM</h3>
 * <p>Computes the mean Structural Similarity Index Measure (SSIM) on the
 * luminance (Y) channel over non-overlapping 8×8 blocks.  The standard SSIM
 * constants C1 = (0.01·255)² and C2 = (0.03·255)² are used.</p>
 *
 * <h3>ΔE2000</h3>
 * <p>Converts both images from sRGB to CIELAB and returns the mean CIE ΔE2000
 * colour difference, using the full ΔE2000 formula (Sharma et al., 2005).</p>
 */
final class ImageQualityHelper {

    // SSIM stability constants (Wang et al., 2004)
    private static final double C1 = 6.5025;   // (0.01 * 255)²
    private static final double C2 = 58.5225;  // (0.03 * 255)²

    // D65 reference white for CIELAB conversion
    private static final double XN = 0.95047;
    private static final double YN = 1.00000;
    private static final double ZN = 1.08883;

    // sRGB → linear → XYZ D65 matrix (IEC 61966-2-1)
    private static final double[] M_R = { 0.4124564, 0.3575761, 0.1804375 };
    private static final double[] M_G = { 0.2126729, 0.7151522, 0.0721750 };
    private static final double[] M_B = { 0.0193339, 0.1191920, 0.9503041 };

    private ImageQualityHelper() {}

    // ─────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Computes the mean SSIM on the luminance channel.
     * Both images must have the same dimensions.
     *
     * @return SSIM in [0, 1]; 1.0 means identical
     */
    static double computeSsim(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            throw new IllegalArgumentException(
                    "Images must be same size: %dx%d vs %dx%d".formatted(
                            a.getWidth(), a.getHeight(), b.getWidth(), b.getHeight()));
        }
        int w = a.getWidth();
        int h = a.getHeight();
        float[] ya = toLuminance(a);
        float[] yb = toLuminance(b);

        int blockSize = 8;
        double ssimSum = 0;
        int count = 0;
        for (int y0 = 0; y0 + blockSize <= h; y0 += blockSize) {
            for (int x0 = 0; x0 + blockSize <= w; x0 += blockSize) {
                ssimSum += blockSsim(ya, yb, w, x0, y0, blockSize);
                count++;
            }
        }
        return count > 0 ? ssimSum / count : 0.0;
    }

    /**
     * Computes the mean CIE ΔE2000 colour difference per pixel.
     * Both images must have the same dimensions.
     *
     * @return mean ΔE2000; 0.0 means identical
     */
    static double computeMeanDeltaE2000(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            throw new IllegalArgumentException(
                    "Images must be same size: %dx%d vs %dx%d".formatted(
                            a.getWidth(), a.getHeight(), b.getWidth(), b.getHeight()));
        }
        int w = a.getWidth();
        int h = a.getHeight();

        double total = 0.0;
        long n = 0;
        // Sample every 4th pixel in x and y for speed (still ~16k samples for 1620×1080)
        int step = 4;
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int rgb1 = a.getRGB(x, y);
                int rgb2 = b.getRGB(x, y);
                double[] lab1 = srgbToLab(rgb1);
                double[] lab2 = srgbToLab(rgb2);
                total += deltaE2000(lab1, lab2);
                n++;
            }
        }
        return n > 0 ? total / n : 0.0;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  SSIM helpers
    // ─────────────────────────────────────────────────────────────────────

    private static float[] toLuminance(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        float[] lum = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int bv = rgb & 0xFF;
                // BT.601 Y
                lum[y * w + x] = 0.299f * r + 0.587f * g + 0.114f * bv;
            }
        }
        return lum;
    }

    private static double blockSsim(float[] ya, float[] yb, int stride,
                                     int x0, int y0, int blockSize) {
        double sumA = 0, sumB = 0;
        int n = blockSize * blockSize;
        for (int dy = 0; dy < blockSize; dy++) {
            for (int dx = 0; dx < blockSize; dx++) {
                int idx = (y0 + dy) * stride + (x0 + dx);
                sumA += ya[idx];
                sumB += yb[idx];
            }
        }
        double muA = sumA / n;
        double muB = sumB / n;

        double varA = 0, varB = 0, cov = 0;
        for (int dy = 0; dy < blockSize; dy++) {
            for (int dx = 0; dx < blockSize; dx++) {
                int idx = (y0 + dy) * stride + (x0 + dx);
                double da = ya[idx] - muA;
                double db = yb[idx] - muB;
                varA += da * da;
                varB += db * db;
                cov += da * db;
            }
        }
        varA /= n;
        varB /= n;
        cov /= n;

        double num = (2 * muA * muB + C1) * (2 * cov + C2);
        double den = (muA * muA + muB * muB + C1) * (varA + varB + C2);
        return den > 0 ? num / den : 1.0;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  ΔE2000 helpers
    // ─────────────────────────────────────────────────────────────────────

    /** Converts packed sRGB int to CIELAB double[3] = {L*, a*, b*}. */
    private static double[] srgbToLab(int rgb) {
        double r = srgbToLinear((rgb >> 16) & 0xFF);
        double g = srgbToLinear((rgb >> 8) & 0xFF);
        double bv = srgbToLinear(rgb & 0xFF);

        // sRGB → XYZ D65
        double x = M_R[0] * r + M_R[1] * g + M_R[2] * bv;
        double y = M_G[0] * r + M_G[1] * g + M_G[2] * bv;
        double z = M_B[0] * r + M_B[1] * g + M_B[2] * bv;

        // XYZ → Lab
        double fx = labF(x / XN);
        double fy = labF(y / YN);
        double fz = labF(z / ZN);

        return new double[]{
                116.0 * fy - 16.0,   // L*
                500.0 * (fx - fy),    // a*
                200.0 * (fy - fz)     // b*
        };
    }

    private static double srgbToLinear(int v8) {
        double c = v8 / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double labF(double t) {
        return t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16.0 / 116.0;
    }

    /**
     * Full CIE ΔE2000 (Sharma, Wu, Dalal, 2005).
     *
     * @param lab1 first colour {L*, a*, b*}
     * @param lab2 second colour {L*, a*, b*}
     * @return ΔE2000 colour difference
     */
    static double deltaE2000(double[] lab1, double[] lab2) {
        double l1 = lab1[0], a1 = lab1[1], b1 = lab1[2];
        double l2 = lab2[0], a2 = lab2[1], b2 = lab2[2];

        // Compute C*_ab and h_ab for each sample
        double c1 = Math.hypot(a1, b1);
        double c2 = Math.hypot(a2, b2);

        // Step 1: Calculate C'_ab
        double cBarPow7 = Math.pow((c1 + c2) / 2.0, 7);
        double k = Math.pow(25.0, 7);
        double g = 0.5 * (1.0 - Math.sqrt(cBarPow7 / (cBarPow7 + k)));
        double a1p = a1 * (1.0 + g);
        double a2p = a2 * (1.0 + g);
        double c1p = Math.hypot(a1p, b1);
        double c2p = Math.hypot(a2p, b2);

        // Step 2: Calculate h'_ab
        double h1p = hprime(a1p, b1);
        double h2p = hprime(a2p, b2);

        // Step 3: Calculate ΔL', ΔC', ΔH'
        double dLp = l2 - l1;
        double dCp = c2p - c1p;
        double dhp = deltahprime(c1p, c2p, h1p, h2p);
        double dHp = 2.0 * Math.sqrt(c1p * c2p) * Math.sin(Math.toRadians(dhp / 2.0));

        // Step 4: Calculate CIEDE2000
        double lBarP = (l1 + l2) / 2.0;
        double cBarP = (c1p + c2p) / 2.0;
        double hBarP = hBarPrime(c1p, c2p, h1p, h2p);

        double t = 1.0
                - 0.17 * Math.cos(Math.toRadians(hBarP - 30.0))
                + 0.24 * Math.cos(Math.toRadians(2.0 * hBarP))
                + 0.32 * Math.cos(Math.toRadians(3.0 * hBarP + 6.0))
                - 0.20 * Math.cos(Math.toRadians(4.0 * hBarP - 63.0));

        double sl = 1.0 + 0.015 * Math.pow(lBarP - 50.0, 2)
                / Math.sqrt(20.0 + Math.pow(lBarP - 50.0, 2));
        double sc = 1.0 + 0.045 * cBarP;
        double sh = 1.0 + 0.015 * cBarP * t;

        double cBarPPow7 = Math.pow(cBarP, 7);
        double rc = 2.0 * Math.sqrt(cBarPPow7 / (cBarPPow7 + k));
        double dTheta = 30.0 * Math.exp(-Math.pow((hBarP - 275.0) / 25.0, 2));
        double rt = -Math.sin(Math.toRadians(2.0 * dTheta)) * rc;

        double lTerm = dLp / sl;
        double cTerm = dCp / sc;
        double hTerm = dHp / sh;

        return Math.sqrt(lTerm * lTerm + cTerm * cTerm + hTerm * hTerm + rt * cTerm * hTerm);
    }

    private static double hprime(double a, double b) {
        if (a == 0.0 && b == 0.0) return 0.0;
        double h = Math.toDegrees(Math.atan2(b, a));
        return h < 0 ? h + 360.0 : h;
    }

    private static double deltahprime(double c1p, double c2p, double h1p, double h2p) {
        if (c1p * c2p == 0.0) return 0.0;
        double dh = h2p - h1p;
        if (dh > 180.0) return dh - 360.0;
        if (dh < -180.0) return dh + 360.0;
        return dh;
    }

    private static double hBarPrime(double c1p, double c2p, double h1p, double h2p) {
        if (c1p * c2p == 0.0) return h1p + h2p;
        if (Math.abs(h1p - h2p) <= 180.0) return (h1p + h2p) / 2.0;
        if (h1p + h2p < 360.0) return (h1p + h2p + 360.0) / 2.0;
        return (h1p + h2p - 360.0) / 2.0;
    }
}
