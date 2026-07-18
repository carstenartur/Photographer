package com.photographer.focusstack.cr3.reference;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Loads committed CR3 reference sidecars into a canonical single-band form. */
public final class ReferenceImageLoader {

    private ReferenceImageLoader() {
    }

    /**
     * Loads the first band of an image sidecar without display conversion.
     * This is intended primarily for unsigned linear RAW TIFF references.
     *
     * @param path reference image path
     * @return canonical reference image
     * @throws IOException if no registered ImageIO reader can decode the file
     */
    public static ReferenceImage load(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        BufferedImage image = ImageIO.read(path.toFile());
        if (image == null) {
            throw new IOException("Could not decode reference image " + path);
        }
        return from(image);
    }

    /** Converts the first raster band of an already decoded image. */
    public static ReferenceImage from(BufferedImage image) {
        Objects.requireNonNull(image, "image");
        Raster raster = image.getRaster();
        if (raster.getNumBands() < 1) {
            throw new IllegalArgumentException("Reference image has no raster bands");
        }
        int width = image.getWidth();
        int height = image.getHeight();
        int[] samples = raster.getSamples(0, 0, width, height, 0, (int[]) null);
        return new ReferenceImage(width, height, samples);
    }
}
