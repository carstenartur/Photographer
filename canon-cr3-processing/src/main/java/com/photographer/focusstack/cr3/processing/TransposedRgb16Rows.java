package com.photographer.focusstack.cr3.processing;

import com.photographer.focusstack.cr3.processing.BayerDemosaicer.PixelColor;
import com.photographer.focusstack.cr3.raw.Cr3Workers;

import java.awt.Rectangle;

/**
 * Develops RGB16 for EXIF orientations 5–8, where source columns become destination rows.
 * A tile of up to 32 source columns by 128 rows writes short contiguous spans across at most
 * 32 destination rows. The next source scanline revisits those same cache lines.
 */
record TransposedRgb16Rows(Rgb16Developer developer) {
    private static final int TILE_WIDTH = 32;
    private static final int TILE_HEIGHT = 128;

    static void develop(Rgb16Developer developer, short[][] channels, Rectangle region, int orientation, int stripes) {
        var rows = new TransposedRgb16Rows(developer);
        Cr3Workers.run(stripes, stripe -> {
            int first = region.y + Pixels.stripeStart(stripe, region.height, stripes);
            int end = region.y + Pixels.stripeStart(stripe + 1, region.height, stripes);
            rows.developTransposed(channels, region, orientation, first, end);
        });
    }

    private void developTransposed(short[][] channels, Rectangle region, int orientation, int first, int end) {
        BayerSamples image = developer.image();
        double[] above = new double[image.width() + 2];
        double[] current = new double[above.length];
        double[] below = new double[above.length];
        boolean flipX = ExifOrientationTransformer.reversesX(orientation);
        boolean flipY = ExifOrientationTransformer.reversesY(orientation);
        int step = flipX ? -region.height : region.height;
        int regionEnd = region.x + region.width;
        for (int top = first; top < end;) {
            int bottom = top + Math.min(TILE_HEIGHT, end - top);
            for (int left = region.x; left < regionEnd;) {
                int right = left + Math.min(TILE_WIDTH, regionEnd - left);
                // Include real neighbors beyond each tile and crop edge. Normalization and
                // bilinear interpolation use the same source coordinates as a full-width read.
                int from = Math.max(0, left - 1);
                int to = right == image.width() ? right : right + 1;
                int column = flipX ? regionEnd - 1 - left : left - region.x;
                int start = column * region.height;
                developer.correctRow(top - 1, above, from, to);
                developer.correctRow(top, current, from, to);
                for (int y = top; y < bottom; y++) {
                    developer.correctRow(y + 1, below, from, to);
                    int row = flipY ? region.y + region.height - 1 - y : y - region.y;
                    int verticalCount = Rgb16Developer.neighbors(y, image.height());
                    PixelColor even = developer.color(0, y);
                    PixelColor odd = developer.color(1, y);
                    for (int x = left, dest = start + row; x < right; x++, dest += step) {
                        developer.writeInterpolatedPixel(channels, dest, above, current, below, x, verticalCount,
                                (x & 1) == 0 ? even : odd);
                    }
                    double[] old = above;
                    above = current;
                    current = below;
                    below = old;
                }
                left = right;
            }
            top = bottom;
        }
    }
}
