package com.photographer.focusstack.analysis.service;

import org.springframework.stereotype.Service;

@Service
public class SharpnessAnalyzer {

    private static final double MAX_LAPLACIAN_VARIANCE = 16256.25;

    /**
     * Calculates sharpness of an image using Laplacian variance on raw grayscale pixel data.
     * @param imageData raw grayscale pixel data (one byte per pixel)
     * @return normalized sharpness score 0.0 - 1.0
     */
    public double calculateSharpness(byte[] imageData) {
        if (imageData == null || imageData.length < 9) {
            return 0.0;
        }

        int width = estimateWidth(imageData.length);
        int height = imageData.length / width;

        if (width < 3 || height < 3) {
            return 0.0;
        }

        // Laplacian kernel: [0, 1, 0; 1, -4, 1; 0, 1, 0]
        double sum = 0.0;
        int count = 0;

        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int center = Byte.toUnsignedInt(imageData[y * width + x]);
                int top = Byte.toUnsignedInt(imageData[(y - 1) * width + x]);
                int bottom = Byte.toUnsignedInt(imageData[(y + 1) * width + x]);
                int left = Byte.toUnsignedInt(imageData[y * width + (x - 1)]);
                int right = Byte.toUnsignedInt(imageData[y * width + (x + 1)]);

                double laplacian = top + bottom + left + right - 4.0 * center;
                sum += laplacian * laplacian;
                count++;
            }
        }

        if (count == 0) {
            return 0.0;
        }

        double variance = sum / count;
        return Math.min(1.0, variance / MAX_LAPLACIAN_VARIANCE);
    }

    private int estimateWidth(int totalPixels) {
        int width = (int) Math.sqrt(totalPixels);
        while (width > 1 && totalPixels % width != 0) {
            width--;
        }
        return width;
    }
}
