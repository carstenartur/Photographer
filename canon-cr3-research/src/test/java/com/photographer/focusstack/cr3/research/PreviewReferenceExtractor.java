package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;

/**
 * Test-only utility that derives a low-resolution reference pixel grid for one
 * Bayer plane of a CR3 file from its embedded JPEG preview.
 *
 * <p>This is the ground-truth signal needed by {@link SubbandHypothesisHarness}.
 * It is approximate (the JPEG is a developed RGB image, not raw Bayer values)
 * but it is good enough to discriminate <em>noise</em> from <em>signal</em>:
 * any candidate CRX subband decoder that produces output strongly correlated
 * with this reference is reconstructing real image structure; any candidate
 * whose output is uncorrelated is producing noise.</p>
 *
 * <p>For each Bayer plane (R, Gr, Gb, B), we extract the matching colour
 * channel from the preview JPEG, downsample it to the plane grid using
 * area-average (Image.SCALE_AREA_AVERAGING), and return it as an array of
 * unsigned byte luminance values 0..255.</p>
 */
public final class PreviewReferenceExtractor {

    /** A reference pixel grid for a single Bayer plane. */
    public record Reference(int width, int height, int[] pixels, String channel) { }

    private PreviewReferenceExtractor() {
        // utility class
    }

    /**
     * Extracts a reference grid for one Bayer plane from a CR3 file.
     *
     * @param cr3File   the CR3 file
     * @param planeIdx  Bayer plane index (0=R, 1=Gr, 2=Gb, 3=B)
     * @param planeWidth target width of the reference grid
     * @param planeHeight target height of the reference grid
     * @return the downsampled reference grid for the requested colour channel
     * @throws IOException if reading or JPEG decoding fails
     */
    public static Reference extract(File cr3File, int planeIdx, int planeWidth, int planeHeight)
            throws IOException {
        if (planeIdx < 0 || planeIdx > 3) {
            throw new IllegalArgumentException("planeIdx must be in [0,3], got " + planeIdx);
        }
        if (planeWidth <= 0 || planeHeight <= 0) {
            throw new IllegalArgumentException("plane dimensions must be positive");
        }

        BufferedImage jpeg = readPreviewJpeg(cr3File);
        Image scaled = jpeg.getScaledInstance(planeWidth, planeHeight, Image.SCALE_AREA_AVERAGING);
        BufferedImage scaledRgb = new BufferedImage(planeWidth, planeHeight,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaledRgb.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(scaled, 0, 0, null);
        } finally {
            g.dispose();
        }

        int[] result = new int[planeWidth * planeHeight];
        String channel;
        for (int y = 0; y < planeHeight; y++) {
            for (int x = 0; x < planeWidth; x++) {
                int rgb = scaledRgb.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g2 = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int v = switch (planeIdx) {
                    case 0 -> r;        // R plane
                    case 1, 2 -> g2;    // Gr or Gb plane
                    case 3 -> b;        // B plane
                    default -> 0;
                };
                result[y * planeWidth + x] = v;
            }
        }
        channel = switch (planeIdx) {
            case 0 -> "R";
            case 1 -> "Gr";
            case 2 -> "Gb";
            case 3 -> "B";
            default -> "?";
        };
        return new Reference(planeWidth, planeHeight, result, channel);
    }

    private static BufferedImage readPreviewJpeg(File cr3File) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(cr3File)) {
            if (in == null) {
                throw new IOException("Cannot open file: " + cr3File);
            }
            Cr3Container container;
            try {
                container = Cr3Parser.parse(in);
            } catch (com.photographer.focusstack.cr3.Cr3Exception e) {
                throw new IOException("Failed to parse CR3: " + e.getMessage(), e);
            }
            // Prefer the highest-resolution JPEG track (JPEG_BIG); fall back to PRVW box.
            for (Cr3Track t : container.tracks()) {
                if (t.type() == Cr3Track.TrackType.JPEG_BIG
                        || t.type() == Cr3Track.TrackType.JPEG_PREVIEW) {
                    byte[] bytes = new byte[(int) t.dataSize()];
                    in.seek(t.dataOffset());
                    in.readFully(bytes);
                    return ImageIO.read(new ByteArrayInputStream(bytes));
                }
            }
            if (container.prvwBox() != null && container.prvwBox().jpegData() != null) {
                return ImageIO.read(new ByteArrayInputStream(container.prvwBox().jpegData()));
            }
            throw new IOException("No JPEG preview track or PRVW box found");
        }
    }
}
