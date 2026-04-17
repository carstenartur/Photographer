package com.photographer.focusstack.analysis.service;

import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * {@link StackMerger} implementation using ImageJ (headless) for focus-stack merging.
 *
 * <h3>Algorithm</h3>
 * <p>For each pixel position, we select the value from the frame that has the
 * highest local sharpness (Laplacian variance) in a small neighbourhood around
 * that pixel.  This produces an "Extended Depth of Field" image that keeps the
 * sharpest content from every frame.
 *
 * <p>The algorithm works as follows:
 * <ol>
 *   <li>Decode every frame as a grayscale {@link FloatProcessor}.</li>
 *   <li>Compute the absolute Laplacian response at every pixel for every frame.</li>
 *   <li>For each pixel, record which frame index had the highest Laplacian
 *       response.</li>
 *   <li>Build the output image by sampling each pixel from its "winner" frame.</li>
 * </ol>
 *
 * <p>For RGB frames the algorithm operates on the luminance channel and then
 * applies the selection mask to the original colour data — for now the output
 * is grayscale because the mock camera produces 8-bit data.  Colour support can
 * be added without changing the interface.
 */
@Service
public class ImageJStackMerger implements StackMerger {

    private static final Logger log = LoggerFactory.getLogger(ImageJStackMerger.class);

    /** 3×3 Laplacian kernel for sharpness measurement. */
    private static final float[] LAPLACIAN = {0, 1, 0, 1, -4, 1, 0, 1, 0};

    @Override
    public byte[] merge(List<byte[]> frames) {
        if (frames == null || frames.isEmpty()) {
            throw new IllegalArgumentException("frames must not be null or empty");
        }

        log.info("Starting ImageJ EDF stack merge of {} frames", frames.size());

        // Decode all frames
        ByteProcessor[] decoded = new ByteProcessor[frames.size()];
        int width = 0, height = 0;
        for (int i = 0; i < frames.size(); i++) {
            ByteProcessor bp = decode(frames.get(i));
            if (bp == null) {
                log.warn("Frame {} could not be decoded — skipping", i);
                continue;
            }
            if (width == 0) {
                width = bp.getWidth();
                height = bp.getHeight();
            } else if (bp.getWidth() != width || bp.getHeight() != height) {
                throw new IllegalArgumentException(
                        "All decodable frames must have identical dimensions; frame " + i
                                + " was " + bp.getWidth() + "×" + bp.getHeight()
                                + " but expected " + width + "×" + height);
            }
            decoded[i] = bp;
        }

        if (width == 0 || height == 0) {
            log.error("No valid frames to merge");
            return new byte[0];
        }

        // Compute Laplacian response for each frame
        float[][] laplacianMaps = new float[frames.size()][];
        for (int i = 0; i < frames.size(); i++) {
            if (decoded[i] == null) {
                laplacianMaps[i] = new float[width * height]; // zeros → never selected
                continue;
            }
            FloatProcessor fp = decoded[i].convertToFloatProcessor();
            fp.convolve(LAPLACIAN, 3, 3);
            float[] pixels = (float[]) fp.getPixels();
            laplacianMaps[i] = pixels;
        }

        // Build output: for each pixel pick the frame with the highest |Laplacian|
        // Track whether any valid frame was found for pixel composition
        boolean anyValidFrame = false;
        for (ByteProcessor dp : decoded) {
            if (dp != null) { anyValidFrame = true; break; }
        }
        if (!anyValidFrame) {
            log.error("All frames failed to decode — cannot produce merged output");
            return new byte[0];
        }

        ByteProcessor output = new ByteProcessor(width, height);
        for (int p = 0; p < width * height; p++) {
            int bestFrame = -1;
            float bestResponse = -1f;
            for (int i = 0; i < frames.size(); i++) {
                if (decoded[i] == null) continue; // skip undecodable frames
                float response = Math.abs(laplacianMaps[i][p]);
                if (response > bestResponse) {
                    bestResponse = response;
                    bestFrame = i;
                }
            }
            int value = bestFrame >= 0
                    ? (decoded[bestFrame].get(p % width, p / width) & 0xFF)
                    : 0;
            output.set(p % width, p / width, value);
        }

        // Encode to PNG
        try {
            BufferedImage bi = output.getBufferedImage();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(bi, "png", baos);
            byte[] result = baos.toByteArray();
            log.info("Stack merge completed: {}×{} px, {} bytes", width, height, result.length);
            return result;
        } catch (IOException e) {
            log.error("Failed to encode merged image: {}", e.getMessage(), e);
            return new byte[0];
        }
    }

    @Override
    public BufferedImage mergeImages(List<BufferedImage> images) {
        if (images == null || images.isEmpty()) {
            throw new IllegalArgumentException("images must not be null or empty");
        }

        int width = images.getFirst().getWidth();
        int height = images.getFirst().getHeight();
        int n = images.size();
        int totalPixels = width * height;

        log.info("Starting ImageJ RGB EDF stack merge of {} frames ({}×{})", n, width, height);

        for (int i = 1; i < n; i++) {
            if (images.get(i).getWidth() != width || images.get(i).getHeight() != height) {
                throw new IllegalArgumentException(
                        "All frames must have identical dimensions; frame " + i
                                + " was " + images.get(i).getWidth() + "×" + images.get(i).getHeight()
                                + " but expected " + width + "×" + height);
            }
        }

        // Memory-efficient: keep only best-response and best-frame-index arrays
        // instead of all N Laplacian maps at once
        float[] bestResponse = new float[totalPixels];
        int[] bestFrameIndex = new int[totalPixels];

        for (int i = 0; i < n; i++) {
            log.info("  Computing sharpness map for frame {}/{}...", i + 1, n);
            FloatProcessor luminance = toLuminance(images.get(i));
            luminance.convolve(LAPLACIAN, 3, 3);
            float[] laplacian = (float[]) luminance.getPixels();

            for (int p = 0; p < totalPixels; p++) {
                float response = Math.abs(laplacian[p]);
                if (response > bestResponse[p]) {
                    bestResponse[p] = response;
                    bestFrameIndex[p] = i;
                }
            }
            // laplacian array is GC-eligible now
        }
        bestResponse = null; // free ~120 MB

        // Compose output — preserve original colour
        log.info("  Compositing output image...");
        BufferedImage output = new BufferedImage(width, height, images.getFirst().getType());
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = y * width + x;
                output.setRGB(x, y, images.get(bestFrameIndex[idx]).getRGB(x, y));
            }
        }

        log.info("RGB stack merge completed: {}×{} px", width, height);
        return output;
    }

    /** Converts a BufferedImage to a luminance FloatProcessor for sharpness analysis. */
    private FloatProcessor toLuminance(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        FloatProcessor fp = new FloatProcessor(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                // ITU-R BT.709 luminance
                fp.setf(x, y, 0.2126f * r + 0.7152f * g + 0.0722f * b);
            }
        }
        return fp;
    }

    @Override
    public String name() {
        return "imagej-edf";
    }

    private ByteProcessor decode(byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(data));
            return bi != null ? new ByteProcessor(bi) : null;
        } catch (IOException e) {
            log.warn("Could not decode frame: {}", e.getMessage());
            return null;
        }
    }
}
