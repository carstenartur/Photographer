package com.photographer.focusstack.cr3.raw.codec;

import java.util.Objects;

/** Copies decoded tile planes and assembles four positional planes into a Bayer grid. */
final class CrxPlaneAssembler {

    private CrxPlaneAssembler() {
    }

    static void copyTilePlane(
            int[] decodedTile,
            int[] destinationPlane,
            int tileWidth,
            int tileHeight,
            int destinationPlaneWidth,
            int destinationX) {
        Objects.requireNonNull(decodedTile, "decodedTile");
        Objects.requireNonNull(destinationPlane, "destinationPlane");
        if (tileWidth <= 0 || tileHeight <= 0 || destinationPlaneWidth <= 0 || destinationX < 0) {
            throw new IllegalArgumentException("Invalid tile-plane geometry");
        }
        if (decodedTile.length < Math.multiplyExact(tileWidth, tileHeight)) {
            throw new IllegalArgumentException("Decoded tile is smaller than declared geometry");
        }
        if ((long) destinationX + tileWidth > destinationPlaneWidth) {
            throw new IllegalArgumentException("Tile exceeds destination plane width");
        }
        if (destinationPlane.length < Math.multiplyExact(destinationPlaneWidth, tileHeight)) {
            throw new IllegalArgumentException("Destination plane is smaller than copied tile rows");
        }

        for (int row = 0; row < tileHeight; row++) {
            System.arraycopy(
                    decodedTile,
                    row * tileWidth,
                    destinationPlane,
                    row * destinationPlaneWidth + destinationX,
                    tileWidth);
        }
    }

    static void interleaveByPosition(
            int[][] planes,
            int[] output,
            int bayerWidth,
            int bayerHeight,
            int planeWidth,
            int planeHeight) {
        Objects.requireNonNull(planes, "planes");
        Objects.requireNonNull(output, "output");
        if (planes.length < 4) {
            throw new IllegalArgumentException("Four positional planes are required");
        }
        int planeSamples = Math.multiplyExact(planeWidth, planeHeight);
        for (int plane = 0; plane < 4; plane++) {
            Objects.requireNonNull(planes[plane], "planes[" + plane + "]");
            if (planes[plane].length < planeSamples) {
                throw new IllegalArgumentException("Plane " + plane + " is smaller than declared geometry");
            }
        }
        if (output.length < Math.multiplyExact(bayerWidth, bayerHeight)) {
            throw new IllegalArgumentException("Output is smaller than Bayer geometry");
        }

        for (int planeY = 0; planeY < planeHeight; planeY++) {
            int bayerY0 = planeY * 2;
            int bayerY1 = bayerY0 + 1;
            for (int planeX = 0; planeX < planeWidth; planeX++) {
                int planeIndex = planeY * planeWidth + planeX;
                int bayerX0 = planeX * 2;
                int bayerX1 = bayerX0 + 1;

                if (bayerY0 < bayerHeight && bayerX0 < bayerWidth) {
                    output[bayerY0 * bayerWidth + bayerX0] = planes[0][planeIndex];
                }
                if (bayerY0 < bayerHeight && bayerX1 < bayerWidth) {
                    output[bayerY0 * bayerWidth + bayerX1] = planes[1][planeIndex];
                }
                if (bayerY1 < bayerHeight && bayerX0 < bayerWidth) {
                    output[bayerY1 * bayerWidth + bayerX0] = planes[2][planeIndex];
                }
                if (bayerY1 < bayerHeight && bayerX1 < bayerWidth) {
                    output[bayerY1 * bayerWidth + bayerX1] = planes[3][planeIndex];
                }
            }
        }
    }
}
