package com.photographer.focusstack.astro;

import com.photographer.focusstack.common.model.FrameTransform;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Stacks registered astronomical frames into a final image.
 * Applies transforms to align frames to the reference coordinate system and combines using mean.
 */
public class AstroStackingService {

    /**
     * Input frame with its transform to reference.
     */
    public record FrameInput(BufferedImage image, FrameTransform transform) {}

    /**
     * Stacks the given frames by applying transforms and computing the mean pixel value.
     * The reference frame should have an identity transform.
     *
     * @param frames list of frames with their transforms
     * @param width  output image width
     * @param height output image height
     * @return the stacked result image
     */
    public BufferedImage stack(List<FrameInput> frames, int width, int height) {
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("No frames to stack");
        }

        int size = width * height;
        // Use 1D long arrays to reduce memory overhead vs double[][]
        long[] sumR = new long[size];
        long[] sumG = new long[size];
        long[] sumB = new long[size];
        int[] count = new int[size];

        for (FrameInput frame : frames) {
            BufferedImage img = frame.image();
            FrameTransform t = frame.transform();
            double tx = t.translationX();
            double ty = t.translationY();

            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    // Transform: map frame pixel to reference coordinates
                    int refX = (int) Math.round(x + tx);
                    int refY = (int) Math.round(y + ty);

                    if (refX < 0 || refX >= width || refY < 0 || refY >= height) continue;

                    int rgb = img.getRGB(x, y);
                    int idx = refY * width + refX;
                    sumR[idx] += (rgb >> 16) & 0xFF;
                    sumG[idx] += (rgb >> 8) & 0xFF;
                    sumB[idx] += rgb & 0xFF;
                    count[idx]++;
                }
            }
        }

        // Compute mean
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < size; i++) {
            if (count[i] == 0) continue;
            int r = Math.min(255, (int) (sumR[i] / count[i]));
            int g = Math.min(255, (int) (sumG[i] / count[i]));
            int b = Math.min(255, (int) (sumB[i] / count[i]));
            result.setRGB(i % width, i / width, (r << 16) | (g << 8) | b);
        }
        return result;
    }
}
