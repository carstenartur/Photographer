package com.photographer.focusstack.analysis.service;

import com.photographer.focusstack.common.analysis.SharpnessMap;
import com.photographer.focusstack.common.camera.CapturedImageFormat;
import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.decode.Cr3Decoder;
import ij.IJ;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Image processing engine backed by ImageJ (headless).
     * Provides real image analysis capabilities for JPEG, PNG, BMP, and CR3 images
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
    private static final int STACK_ANALYSIS_MAX_EDGE = 2048;
    private final RawDecodeBudget rawDecodeBudget;

    public ImageJProcessor() {
        this(new RawDecodeBudget(0, 0));
    }

    @Autowired
    public ImageJProcessor(RawDecodeBudget rawDecodeBudget) {
        this.rawDecodeBudget = rawDecodeBudget;
        System.setProperty("java.awt.headless", "true");
        log.info("ImageJ processor initialized (headless mode), ImageJ version: {}", IJ.getVersion());
    }

    /**
     * Calculates sharpness of an actual image file (JPEG, PNG, BMP, CR3) using ImageJ
     * Laplacian variance method.
     *
     * @param imageFileBytes raw bytes of an image file (JPEG, PNG, BMP, CR3)
     * @return normalized sharpness score 0.0 - 1.0
     */
    public double calculateSharpness(byte[] imageFileBytes) {
        return calculateDecodedSharpness(readAsByteProcessor(imageFileBytes));
    }

    double calculateDecodedSharpness(ByteProcessor pixels) {
        if (pixels == null) {
            return 0.0;
        }

        FloatProcessor fp = pixels.convertToFloatProcessor();
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
        return detectDecodedMotion(readAsByteProcessor(currentImageBytes),
                readAsByteProcessor(previousImageBytes), threshold);
    }

    MotionDetector.MotionResult detectDecodedMotion(
            ByteProcessor current, ByteProcessor previous, double threshold) {
        if (current == null || previous == null) {
            return new MotionDetector.MotionResult(0.0, false, threshold);
        }

        int width = Math.min(current.getWidth(), previous.getWidth());
        int height = Math.min(current.getHeight(), previous.getHeight());

        if (width == 0 || height == 0) {
            return new MotionDetector.MotionResult(0.0, false, threshold);
        }

        double totalDiff = 0.0;
        int pixelCount = width * height;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int curr = current.get(x, y);
                int prev = previous.get(x, y);
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
     * Only formats supported by {@link javax.imageio.ImageIO} are recognized:
     * JPEG, PNG, BMP, and Canon CR3.
     *
     * @param data raw bytes to check
     * @return true if the data appears to be a JPEG, PNG, BMP, or CR3 image
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
        return CapturedImageFormat.hasCr3Header(data);
    }

    /**
     * Detects motion using edge-based structural similarity (SSIM-like).
     *
     * <p>Converts both frames to grayscale, applies a Sobel edge-detection kernel,
     * then computes the normalised cross-correlation (NCC) of the two edge maps.
     * Edge maps are largely invariant to defocus blur, so normal focus-stepping
     * does not trigger false positives.  Real subject motion shifts edge positions,
     * lowering the NCC and producing a high motion score.
     *
     * @param currentImageBytes  bytes of the current frame image file
     * @param previousImageBytes bytes of the previous frame image file
     * @param threshold          motion detection threshold (0.0–1.0)
     * @return MotionResult with score = 1 - NCC, detected flag, and threshold
     */
    public MotionDetector.MotionResult detectStructuralMotion(byte[] currentImageBytes,
                                                               byte[] previousImageBytes,
                                                               double threshold) {
        return detectDecodedStructuralMotion(readAsByteProcessor(currentImageBytes),
                readAsByteProcessor(previousImageBytes), threshold);
    }

    MotionDetector.MotionResult detectDecodedStructuralMotion(
            ByteProcessor current, ByteProcessor previous, double threshold) {
        if (current == null || previous == null) {
            return new MotionDetector.MotionResult(0.0, false, threshold);
        }

        int width = Math.min(current.getWidth(), previous.getWidth());
        int height = Math.min(current.getHeight(), previous.getHeight());

        if (width < 3 || height < 3) {
            return new MotionDetector.MotionResult(0.0, false, threshold);
        }

        // Compute Sobel edge magnitude for both frames
        float[] edgesCurrent = sobelMagnitude(current, width, height);
        float[] edgesPrevious = sobelMagnitude(previous, width, height);

        // Normalised cross-correlation of edge maps
        double similarity = normalizedCrossCorrelation(edgesCurrent, edgesPrevious);
        double motionScore = Math.max(0.0, 1.0 - similarity);

        log.debug("Structural motion detection: similarity={}, motionScore={}, threshold={}",
                String.format("%.4f", similarity),
                String.format("%.4f", motionScore),
                threshold);

        return new MotionDetector.MotionResult(motionScore, motionScore > threshold, threshold);
    }

    /** Computes Sobel gradient magnitude for grayscale pixels. */
    private float[] sobelMagnitude(ByteProcessor fp, int width, int height) {
        int innerW = width - 2;
        int innerH = height - 2;
        float[] result = new float[innerW * innerH];

        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                float gx =
                        -fp.getf(x - 1, y - 1) + fp.getf(x + 1, y - 1)
                        - 2f * fp.getf(x - 1, y) + 2f * fp.getf(x + 1, y)
                        - fp.getf(x - 1, y + 1) + fp.getf(x + 1, y + 1);

                float gy =
                        -fp.getf(x - 1, y - 1) - 2f * fp.getf(x, y - 1) - fp.getf(x + 1, y - 1)
                        + fp.getf(x - 1, y + 1) + 2f * fp.getf(x, y + 1) + fp.getf(x + 1, y + 1);

                result[(y - 1) * innerW + (x - 1)] = (float) Math.sqrt(gx * gx + gy * gy);
            }
        }
        return result;
    }

    /** Normalised cross-correlation of two float arrays. Returns [0, 1]. */
    private double normalizedCrossCorrelation(float[] a, float[] b) {
        if (a.length != b.length || a.length == 0) return 0.0;

        double meanA = 0, meanB = 0;
        for (int i = 0; i < a.length; i++) {
            meanA += a[i];
            meanB += b[i];
        }
        meanA /= a.length;
        meanB /= b.length;

        double sumAB = 0, sumAA = 0, sumBB = 0;
        for (int i = 0; i < a.length; i++) {
            double da = a[i] - meanA;
            double db = b[i] - meanB;
            sumAB += da * db;
            sumAA += da * da;
            sumBB += db * db;
        }

        double denom = Math.sqrt(sumAA * sumBB);
        if (denom < 1e-10) return 1.0; // both constant → identical
        double ncc = sumAB / denom;
        return Math.max(0.0, Math.min(1.0, ncc));
    }

    /**
     * Returns the ImageJ version string for health checks.
     */
    public String getVersion() {
        return IJ.getVersion();
    }

    ByteProcessor readForStacking(byte[] imageFileBytes) {
        return readAsByteProcessor(imageFileBytes, STACK_ANALYSIS_MAX_EDGE);
    }

    /**
     * Reads image file bytes into an ImageJ ByteProcessor (8-bit grayscale).
     * Uses javax.imageio.ImageIO for format decoding, then wraps in ImageJ processor.
     *
     * <p>Real camera JPEGs are always RGB ({@code TYPE_INT_RGB} or similar).
     * ImageJ's {@link ByteProcessor} constructor requires {@code TYPE_BYTE_GRAY},
     * so we must convert non-grayscale images before wrapping them.
     */
    private ByteProcessor readAsByteProcessor(byte[] imageFileBytes) {
        return readAsByteProcessor(imageFileBytes, Integer.MAX_VALUE);
    }

    private ByteProcessor readAsByteProcessor(byte[] imageFileBytes, int maxEdge) {
        if (imageFileBytes == null || imageFileBytes.length == 0) {
            return null;
        }
        try {
            if (CapturedImageFormat.hasCr3Header(imageFileBytes)) {
                return readCr3(imageFileBytes, maxEdge);
            }
            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(imageFileBytes));
            if (bi == null) {
                log.warn("Could not decode image from {} bytes", imageFileBytes.length);
                return null;
            }
            return toGrayscale(bi, maxEdge);
        } catch (Cr3Exception | IOException e) {
            log.warn("Could not read image: {}", e.getMessage());
            return null;
        }
    }

    private ByteProcessor readCr3(byte[] imageFileBytes, int maxEdge) throws Cr3Exception, IOException {
        try (MemoryCacheImageInputStream iis =
                     new MemoryCacheImageInputStream(new ByteArrayInputStream(imageFileBytes))) {
            Cr3Decoder decoder = new Cr3Decoder();
            decoder.setInput(iis);
            Cr3DecodeOptions options = Cr3DecodeOptions.rawProcessed();
            Rectangle bounds;
            try {
                bounds = decoder.getRawProcessedBounds(options);
            } catch (Cr3Exception exception) {
                return readCr3Preview(decoder, maxEdge, exception);
            }
            long estimatedBytes = RawDecodeBudget.estimateRawBytes(bounds.width, bounds.height);
            try (RawDecodeBudget.Lease lease = rawDecodeBudget.acquire(estimatedBytes, 1)) {
                return toGrayscale(decoder.decode(options.maxWorkers(lease.workers())), maxEdge);
            } catch (Cr3Exception | IOException exception) {
                return readCr3Preview(decoder, maxEdge, exception);
            }
        }
    }

    private ByteProcessor readCr3Preview(Cr3Decoder decoder, int maxEdge, Exception rawFailure)
            throws Cr3Exception, IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("CR3 analysis interrupted", rawFailure);
        }
        log.debug("Using embedded preview for CR3 analysis: {}", rawFailure.getMessage());
        return toGrayscale(decoder.decode(Cr3DecodeOptions.defaults()), maxEdge);
    }

    private static ByteProcessor toGrayscale(BufferedImage image, int maxEdge) {
        int longestEdge = Math.max(image.getWidth(), image.getHeight());
        if (image.getType() == BufferedImage.TYPE_BYTE_GRAY && longestEdge <= maxEdge) {
            return new ByteProcessor(image);
        }
        double scale = Math.min(1.0, (double) maxEdge / longestEdge);
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage gray = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = gray.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return new ByteProcessor(gray);
    }
}
