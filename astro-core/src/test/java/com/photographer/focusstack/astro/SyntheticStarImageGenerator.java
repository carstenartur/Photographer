package com.photographer.focusstack.astro;

import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * Generates synthetic star field images for testing.
 * Stars are bright Gaussian-like blobs on a dark background.
 */
public final class SyntheticStarImageGenerator {

    private SyntheticStarImageGenerator() {}

    /**
     * Generates a star field image with the given number of stars.
     * Each star is a bright blob with Gaussian-like falloff.
     *
     * @param width image width
     * @param height image height
     * @param starCount number of stars to place
     * @param seed random seed for reproducibility
     * @return synthetic star field image
     */
    public static BufferedImage generate(int width, int height, int starCount, long seed) {
        return generate(width, height, starCount, seed, 0, 0);
    }

    /**
     * Generates a star field with optional translation offset (simulating sky motion).
     */
    public static BufferedImage generate(int width, int height, int starCount, long seed,
                                         double offsetX, double offsetY) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random rng = new Random(seed);

        // Dark background with slight noise
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int bg = 10 + rng.nextInt(5);
                img.setRGB(x, y, (bg << 16) | (bg << 8) | bg);
            }
        }

        // Reset RNG to get same star positions regardless of noise
        rng = new Random(seed + 12345);

        // Place stars
        for (int i = 0; i < starCount; i++) {
            double cx = rng.nextDouble() * (width - 20) + 10 + offsetX;
            double cy = rng.nextDouble() * (height - 20) + 10 + offsetY;
            int brightness = 180 + rng.nextInt(76); // 180-255
            double radius = 1.5 + rng.nextDouble() * 2.0; // 1.5-3.5 pixels

            // Draw Gaussian blob
            int r = (int) (radius * 3);
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    int px = (int) Math.round(cx + dx);
                    int py = (int) Math.round(cy + dy);
                    if (px < 0 || px >= width || py < 0 || py >= height) continue;
                    double dist = Math.sqrt(dx * dx + dy * dy);
                    double intensity = brightness * Math.exp(-(dist * dist) / (2 * radius * radius));
                    int current = img.getRGB(px, py) & 0xFF;
                    int val = Math.min(255, current + (int) intensity);
                    img.setRGB(px, py, (val << 16) | (val << 8) | val);
                }
            }
        }
        return img;
    }

    /**
     * Generates a blurry star field (simulating out-of-focus) with larger, dimmer blobs.
     */
    public static BufferedImage generateBlurry(int width, int height, int starCount, long seed, double blurFactor) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random rng = new Random(seed);

        // Dark background
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int bg = 10 + rng.nextInt(5);
                img.setRGB(x, y, (bg << 16) | (bg << 8) | bg);
            }
        }

        rng = new Random(seed + 12345);

        for (int i = 0; i < starCount; i++) {
            double cx = rng.nextDouble() * (width - 20) + 10;
            double cy = rng.nextDouble() * (height - 20) + 10;
            int brightness = (int) ((180 + rng.nextInt(76)) / blurFactor);
            double radius = (1.5 + rng.nextDouble() * 2.0) * blurFactor;

            int r = (int) (radius * 3);
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    int px = (int) Math.round(cx + dx);
                    int py = (int) Math.round(cy + dy);
                    if (px < 0 || px >= width || py < 0 || py >= height) continue;
                    double dist = Math.sqrt(dx * dx + dy * dy);
                    double intensity = brightness * Math.exp(-(dist * dist) / (2 * radius * radius));
                    int current = img.getRGB(px, py) & 0xFF;
                    int val = Math.min(255, current + (int) intensity);
                    img.setRGB(px, py, (val << 16) | (val << 8) | val);
                }
            }
        }
        return img;
    }
}
