package com.photographer.focusstack.analysis.service;

import com.photographer.focusstack.common.analysis.SharpnessMap;
import ij.IJ;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Image processing engine backed by ImageJ (headless).
 * Provides real image analysis capabilities for JPEG, PNG, and TIFF images
 * using the ImageJ library in headless mode.
 * <p>
 * Uses ImageJ's {@link ImageProcessor} directly (ByteProcessor, FloatProcessor)
 * to avoid GUI-dependent code paths in headless environments.
 */
@Service
public class ImageJProcessor {

    private static final Logger log = LoggerFactory.getLogger(ImageJProcessor.class);
    private static final double MAX_LAPLACIAN_VARIANCE = 16256.25;
    private static final float[] LAPLACIAN_KERNEL = {0, 1, 0, 1, -4, 1, 0, 1, 0};

    public ImageJProcessor() {
        System.setProperty("java.awt.headless", "true");
        log.info("ImageJ processor initialized (headless mode), ImageJ version: {}", IJ.getVersion());
    }

    /**
     * Calculates sharpness of an actual image file (JPEG, PNG, TIFF) using ImageJ
     * Laplacian variance method.
     *
     * @param imageFileBytes raw bytes of an image file (JPEG, PNG, TIFF, BMP)
     * @return normalized sharpness score 0.0 - 1.0
     */
    public double calculateSharpness(byte[] imageFileBytes) {
        FloatProcessor fp = readAsFloatProcessor(imageFileBytes);
        if (fp == null) {
            return 0.0;
        }

        fp.convolve(LAPLACIAN_KERNEL, 3, 3);

        ImageStatistics stats = fp.getStatistics();
        double variance = stats.stdDev * stats.stdDev;

        return Math.min(1.0, variance / MAX_LAPLACIAN_VARIANCE);
    }

    /**
     * Detects motion between two image files using ImageJ pixel difference analysis.
     *
     * @param currentImageBytes  bytes of the current frame image file
     * @param previousImageBytes bytes of the previous frame image file
     * @param threshold          motion detection threshold (0.0 - 1.0)
     * @return MotionResult with score, detected flag, and threshold
     */
    public MotionDetector.MotionResult detectMotion(byte[] currentImageBytes,
                                                     byte[] previousImageBytes,
                                                     double threshold) {
        FloatProcessor fpCurrent = readAsFloatProcessor(currentImageBytes);
        FloatProcessor fpPrevious = readAsFloatProcessor(previousImageBytes);

        if (fpCurrent == null || fpPrevious == null) {
            return new MotionDetector.MotionResult(0.0, false, threshold);
        }

        int width = Math.min(fpCurrent.getWidth(), fpPrevious.getWidth());
        int height = Math.min(fpCurrent.getHeight(), fpPrevious.getHeight());

        if (width == 0 || height == 0) {
            return new MotionDetector.MotionResult(0.0, false, threshold);
        }

        double totalDiff = 0.0;
        int pixelCount = width * height;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float curr = fpCurrent.getf(x, y);
                float prev = fpPrevious.getf(x, y);
                totalDiff += Math.abs(curr - prev);
            }
        }

        double motionScore = totalDiff / (pixelCount * 255.0);
        return new MotionDetector.MotionResult(motionScore, motionScore > threshold, threshold);
    }

    /**
     * Creates a block-wise sharpness map of an image using ImageJ.
     *
     * @param imageFileBytes bytes of the image file
     * @param blockSize      size of each analysis block in pixels
     * @return SharpnessMap with per-block sharpness scores
     */
    public SharpnessMap createSharpnessMap(byte[] imageFileBytes, int blockSize) {
        ByteProcessor bp = readAsByteProcessor(imageFileBytes);
        if (bp == null) {
            return new SharpnessMap(0, 0, blockSize, new double[0][0], 0.0);
        }

        int imgWidth = bp.getWidth();
        int imgHeight = bp.getHeight();

        int blocksX = (imgWidth + blockSize - 1) / blockSize;
        int blocksY = (imgHeight + blockSize - 1) / blockSize;
        double[][] scores = new double[blocksY][blocksX];
        double totalScore = 0.0;
        int blockCount = 0;

        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                int x = bx * blockSize;
                int y = by * blockSize;
                int w = Math.min(blockSize, imgWidth - x);
                int h = Math.min(blockSize, imgHeight - y);

                if (w < 3 || h < 3) {
                    scores[by][bx] = 0.0;
                    continue;
                }

                bp.setRoi(x, y, w, h);
                ImageProcessor cropped = bp.crop();
                FloatProcessor block = cropped.convertToFloatProcessor();
                block.convolve(LAPLACIAN_KERNEL, 3, 3);

                ImageStatistics stats = block.getStatistics();
                double variance = stats.stdDev * stats.stdDev;
                double blockScore = Math.min(1.0, variance / MAX_LAPLACIAN_VARIANCE);

                scores[by][bx] = blockScore;
                totalScore += blockScore;
                blockCount++;
            }
        }

        double overallScore = blockCount > 0 ? totalScore / blockCount : 0.0;
        return new SharpnessMap(blocksX, blocksY, blockSize, scores, overallScore);
    }

    /**
     * Checks if the given byte array represents a recognized image file format.
     *
     * @param data raw bytes to check
     * @return true if the data appears to be a JPEG, PNG, BMP, or TIFF image
     */
    public boolean isImageFile(byte[] data) {
        if (data == null || data.length < 4) {
            return false;
        }
        // JPEG: FF D8 FF
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return true;
        }
        // PNG: 89 50 4E 47
        if ((data[0] & 0xFF) == 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47) {
            return true;
        }
        // BMP: 42 4D
        if (data[0] == 0x42 && data[1] == 0x4D) {
            return true;
        }
        // TIFF: 49 49 or 4D 4D
        if ((data[0] == 0x49 && data[1] == 0x49) || (data[0] == 0x4D && data[1] == 0x4D)) {
            return true;
        }
        return false;
    }

    /**
     * Returns the ImageJ version string for health checks.
     */
    public String getVersion() {
        return IJ.getVersion();
    }

    /**
     * Reads image file bytes into an ImageJ FloatProcessor (grayscale).
     * Uses ByteProcessor as intermediate to ensure grayscale conversion.
     */
    private FloatProcessor readAsFloatProcessor(byte[] imageFileBytes) {
        ByteProcessor bp = readAsByteProcessor(imageFileBytes);
        return bp != null ? bp.convertToFloatProcessor() : null;
    }

    /**
     * Reads image file bytes into an ImageJ ByteProcessor (8-bit grayscale).
     * Uses javax.imageio.ImageIO for format decoding, then wraps in ImageJ processor.
     */
    private ByteProcessor readAsByteProcessor(byte[] imageFileBytes) {
        if (imageFileBytes == null || imageFileBytes.length == 0) {
            return null;
        }
        try {
            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(imageFileBytes));
            if (bi == null) {
                log.warn("Could not decode image from {} bytes", imageFileBytes.length);
                return null;
            }
            return new ByteProcessor(bi);
        } catch (IOException e) {
            log.error("Error reading image: {}", e.getMessage(), e);
            return null;
        }
    }
}
