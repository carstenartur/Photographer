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
 * Test-only helper that extracts a Bayer-channel reference grid from a
 * <em>tile-local</em> sub-region of the embedded JPEG preview — the
 * counterpart to {@link PreviewReferenceExtractor}, which always scales the
 * full preview to plane size.
 *
 * <p>This was introduced for {@code CrxPlaneFollowupRound3IT} (Update (g),
 * 2026-04-30) per the new requirement: tile-cropped reference scoring
 * isolates per-tile correlations and removes global crop/scale bias from the
 * Pearson r metric. If a candidate decoder is structurally correct on a tile
 * but the full-frame scoring smears across tile boundaries, this helper will
 * surface the difference.</p>
 *
 * <p>For sample.cr3 the tile layout is horizontal (two tiles, left and
 * right), so {@code tileIndex=0} maps to the left half and {@code 1} to the
 * right half of the preview. The helper accepts an explicit
 * {@code tilesPerRow}/{@code tilesPerCol} grid so it generalises to other
 * fixtures without further changes.</p>
 */
public final class TileCroppedPreviewExtractor {

    private TileCroppedPreviewExtractor() { }

    /**
     * Extracts a Bayer-channel reference grid from one tile of the preview.
     *
     * @param cr3File      the CR3 file
     * @param planeIdx     Bayer plane index (0=R, 1=Gr, 2=Gb, 3=B)
     * @param planeWidth   target width of the reference grid (one plane in one tile)
     * @param planeHeight  target height of the reference grid
     * @param tileIndex    0-based tile index (row-major if multi-row)
     * @param tilesPerRow  number of tiles per image row
     * @param tilesPerCol  number of tile rows
     */
    public static PreviewReferenceExtractor.Reference extract(
            File cr3File, int planeIdx, int planeWidth, int planeHeight,
            int tileIndex, int tilesPerRow, int tilesPerCol) throws IOException {
        if (planeIdx < 0 || planeIdx > 3) {
            throw new IllegalArgumentException("planeIdx must be in [0,3], got " + planeIdx);
        }
        if (tilesPerRow < 1 || tilesPerCol < 1) {
            throw new IllegalArgumentException("tilesPerRow/tilesPerCol must be ≥ 1");
        }
        if (tileIndex < 0 || tileIndex >= tilesPerRow * tilesPerCol) {
            throw new IllegalArgumentException("tileIndex out of range");
        }

        BufferedImage jpeg = readPreviewJpeg(cr3File);
        int tw = jpeg.getWidth() / tilesPerRow;
        int th = jpeg.getHeight() / tilesPerCol;
        int tx = (tileIndex % tilesPerRow) * tw;
        int ty = (tileIndex / tilesPerRow) * th;
        BufferedImage crop = jpeg.getSubimage(tx, ty, tw, th);

        Image scaled = crop.getScaledInstance(planeWidth, planeHeight, Image.SCALE_AREA_AVERAGING);
        BufferedImage scaledRgb = new BufferedImage(planeWidth, planeHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaledRgb.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(scaled, 0, 0, null);
        } finally {
            g.dispose();
        }

        int[] result = new int[planeWidth * planeHeight];
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
        String channel = switch (planeIdx) {
            case 0 -> "R";
            case 1 -> "Gr";
            case 2 -> "Gb";
            case 3 -> "B";
            default -> "?";
        };
        return new PreviewReferenceExtractor.Reference(planeWidth, planeHeight, result, channel);
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
