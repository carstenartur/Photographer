package com.photographer.focusstack.cr3.processing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;

/**
 * Applies EXIF orientation transformations to an image.
 *
 * <p>EXIF orientation values 1-8 describe how the image data should be
 * rotated/flipped to match the camera's shooting orientation. This class
 * transforms the pixel data accordingly so the output image appears correctly
 * oriented for display.</p>
 *
 * <p>EXIF orientation values (per EXIF 2.3 specification):</p>
 * <pre>
 *   1 = Normal (no transformation)
 *   2 = Flip horizontal
 *   3 = Rotate 180°
 *   4 = Flip vertical
 *   5 = Transpose (flip horizontal + rotate 90° CW)
 *   6 = Rotate 90° CW
 *   7 = Transverse (flip horizontal + rotate 270° CW)
 *   8 = Rotate 270° CW (= 90° CCW)
 * </pre>
 *
 * <p>This transformation operates on the final output image only; the RAW Bayer
 * data is always stored in sensor orientation.</p>
 */
public final class ExifOrientationTransformer {

    private static final Logger LOG = LoggerFactory.getLogger(ExifOrientationTransformer.class);

    private ExifOrientationTransformer() {}

    /**
     * Applies the EXIF orientation transformation to an image.
     *
     * @param image       the input image
     * @param orientation the EXIF orientation value (1-8)
     * @return the transformed image (may be a new image for rotations, or same for no-op)
     */
    public static BufferedImage apply(BufferedImage image, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            // 1 = normal, <1 or >8 = invalid → no transformation
            return image;
        }

        LOG.debug("Applying EXIF orientation {} to {}x{} image",
                orientation, image.getWidth(), image.getHeight());

        return switch (orientation) {
            case 2 -> flipHorizontal(image);
            case 3 -> rotate180(image);
            case 4 -> flipVertical(image);
            case 5 -> transpose(image);
            case 6 -> rotate90CW(image);
            case 7 -> transverse(image);
            case 8 -> rotate270CW(image);
            default -> image;
        };
    }

    /**
     * Returns the output dimensions after applying EXIF orientation.
     *
     * @param width       input width
     * @param height      input height
     * @param orientation EXIF orientation (1-8)
     * @return int[2] with {outputWidth, outputHeight}
     */
    public static int[] outputDimensions(int width, int height, int orientation) {
        if (orientation >= 5 && orientation <= 8) {
            // Orientations 5-8 swap width and height
            return new int[]{height, width};
        }
        return new int[]{width, height};
    }

    private static BufferedImage flipHorizontal(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage dst = new BufferedImage(w, h, src.getType());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                dst.setRGB(w - 1 - x, y, src.getRGB(x, y));
            }
        }
        return dst;
    }

    private static BufferedImage flipVertical(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage dst = new BufferedImage(w, h, src.getType());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                dst.setRGB(x, h - 1 - y, src.getRGB(x, y));
            }
        }
        return dst;
    }

    private static BufferedImage rotate180(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage dst = new BufferedImage(w, h, src.getType());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                dst.setRGB(w - 1 - x, h - 1 - y, src.getRGB(x, y));
            }
        }
        return dst;
    }

    private static BufferedImage rotate90CW(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage dst = new BufferedImage(h, w, src.getType());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                dst.setRGB(h - 1 - y, x, src.getRGB(x, y));
            }
        }
        return dst;
    }

    private static BufferedImage rotate270CW(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage dst = new BufferedImage(h, w, src.getType());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                dst.setRGB(y, w - 1 - x, src.getRGB(x, y));
            }
        }
        return dst;
    }

    private static BufferedImage transpose(BufferedImage src) {
        // Transpose = flip horizontal + rotate 90° CW
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage dst = new BufferedImage(h, w, src.getType());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                dst.setRGB(y, x, src.getRGB(x, y));
            }
        }
        return dst;
    }

    private static BufferedImage transverse(BufferedImage src) {
        // Transverse = flip horizontal + rotate 270° CW
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage dst = new BufferedImage(h, w, src.getType());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                dst.setRGB(h - 1 - y, w - 1 - x, src.getRGB(x, y));
            }
        }
        return dst;
    }
}
