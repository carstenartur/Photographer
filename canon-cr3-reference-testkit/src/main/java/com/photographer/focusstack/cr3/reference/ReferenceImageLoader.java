package com.photographer.focusstack.cr3.reference;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Loads single-band RAW-linear reference images through ImageIO. */
public final class ReferenceImageLoader {

    private ReferenceImageLoader() {
    }

    /**
     * Loads the first band of a TIFF/reference image as unsigned integer samples.
     *
     * @param path image path
     * @return reference image with exactly {@code width * height} samples
     * @throws IOException if the file is missing or cannot be decoded
     */
    public static ReferenceImage load(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        if (!Files.isRegularFile(path)) {
            throw new IOException("Reference image does not exist: " + path);
        }

        BufferedImage image = ImageIO.read(path.toFile());
        if (image == null) {
            throw new IOException("Could not decode reference image: " + path);
        }

        Raster raster = image.getRaster();
        if (raster.getNumBands() < 1) {
            throw new IOException("Reference image has no sample band: " + path);
        }

        int width = image.getWidth();
        int height = image.getHeight();
        int[] samples = raster.getSamples(0, 0, width, height, 0, (int[]) null);
        return new ReferenceImage(width, height, samples);
    }
}
