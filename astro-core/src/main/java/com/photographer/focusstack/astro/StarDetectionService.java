package com.photographer.focusstack.astro;

import com.photographer.focusstack.common.model.StarPoint;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Detects star-like bright points in an astronomical image.
 */
public class StarDetectionService {

    private final int blurRadius;
    private final double brightnessThreshold;
    private final int minBlobSize;
    private final int maxBlobSize;

    public StarDetectionService() {
        this(1, 0.3, 1, 50);
    }

    public StarDetectionService(int blurRadius, double brightnessThreshold,
                                int minBlobSize, int maxBlobSize) {
        this.blurRadius = blurRadius;
        this.brightnessThreshold = brightnessThreshold;
        this.minBlobSize = minBlobSize;
        this.maxBlobSize = maxBlobSize;
    }

    /**
     * Detects star points in the given image.
     * Pipeline: grayscale → optional blur → threshold → find local maxima → compute centroids → filter by size.
     */
    public List<StarPoint> detectStars(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();

        // 1. Convert to grayscale luminance array
        double[] luminance = toLuminance(image, w, h);

        // 2. Optional box blur
        if (blurRadius > 0) {
            luminance = boxBlur(luminance, w, h, blurRadius);
        }

        // 3. Estimate background as mean luminance
        double background = mean(luminance);
        double threshold = background + brightnessThreshold * (1.0 - background);

        // 4. Find bright local maxima (3x3 neighborhood)
        List<StarPoint> stars = new java.util.ArrayList<>();
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                double val = luminance[y * w + x];
                if (val < threshold) continue;
                if (!isLocalMax(luminance, w, x, y)) continue;

                // Compute centroid and radius in a small window
                double[] centroid = computeCentroid(luminance, w, h, x, y, threshold);
                double cx = centroid[0];
                double cy = centroid[1];
                double radius = centroid[2];
                int blobPixels = (int) centroid[3];

                if (blobPixels < minBlobSize || blobPixels > maxBlobSize) continue;

                stars.add(new StarPoint(cx, cy, val, radius > 0 ? radius : null));
            }
        }
        return stars;
    }

    /** Computes a focus score: higher is better (more stars, tighter blobs). */
    public double computeFocusScore(List<StarPoint> stars) {
        if (stars.isEmpty()) return 0.0;
        double avgBrightness = stars.stream().mapToDouble(StarPoint::brightness).average().orElse(0);
        double avgRadius = stars.stream()
                .filter(s -> s.radius() != null)
                .mapToDouble(StarPoint::radius)
                .average().orElse(5.0);
        // More stars + brighter + tighter = better
        double compactness = avgRadius > 0 ? 1.0 / avgRadius : 0;
        return stars.size() * avgBrightness * compactness;
    }

    private double[] toLuminance(BufferedImage image, int w, int h) {
        double[] lum = new double[w * h];
        int[] pixels = Pixels.intBufferOf(image);
        for (int i = 0; i < pixels.length; i++) {
            int rgb = pixels[i];
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            lum[i] = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0;
        }
        return lum;
    }

    private double[] boxBlur(double[] src, int w, int h, int radius) {
        double[] dst = new double[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double sum = 0;
                int count = 0;
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                            sum += src[ny * w + nx];
                            count++;
                        }
                    }
                }
                dst[y * w + x] = sum / count;
            }
        }
        return dst;
    }

    private double mean(double[] arr) {
        double sum = 0;
        for (double v : arr) sum += v;
        return sum / arr.length;
    }

    private boolean isLocalMax(double[] lum, int w, int x, int y) {
        double val = lum[y * w + x];
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                if (lum[(y + dy) * w + (x + dx)] >= val) return false;
            }
        }
        return true;
    }

    /**
     * Returns [centroidX, centroidY, radius, blobPixelCount] around a peak.
     */
    private double[] computeCentroid(double[] lum, int w, int h, int px, int py, double threshold) {
        int searchRadius = 5;
        double sumX = 0, sumY = 0, sumW = 0;
        double maxDist = 0;
        int count = 0;
        for (int dy = -searchRadius; dy <= searchRadius; dy++) {
            for (int dx = -searchRadius; dx <= searchRadius; dx++) {
                int nx = px + dx, ny = py + dy;
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                double val = lum[ny * w + nx];
                if (val < threshold) continue;
                double weight = val - threshold;
                sumX += nx * weight;
                sumY += ny * weight;
                sumW += weight;
                count++;
                double dist = Math.sqrt(dx * dx + dy * dy);
                if (dist > maxDist) maxDist = dist;
            }
        }
        if (sumW == 0) return new double[]{px, py, 0, 0};
        return new double[]{sumX / sumW, sumY / sumW, maxDist, count};
    }
}
