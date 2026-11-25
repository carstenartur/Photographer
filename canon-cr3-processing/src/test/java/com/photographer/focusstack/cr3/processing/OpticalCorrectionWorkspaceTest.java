package com.photographer.focusstack.cr3.processing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OpticalCorrectionWorkspaceTest {
    @Test
    void reservesFirstUseSrgbTablesAndEveryWorkersTileAlongsideTheOutputPlane() {
        int width = 1024, height = 773;
        // This frame admits three useful stripes. Include both first-use transfer
        // tables: direct RGB8 callers need the forward encoder as well as its inverse.
        long radialTables = 2L * 16386 * Double.BYTES;
        long inverseTables = (65536L + 256) * Double.BYTES;
        long encoderTables = 294912L * Short.BYTES + 65537L * Long.BYTES;
        long axesAndWriteRow = (long) (width + height) * Double.BYTES + (long) width * Integer.BYTES;
        for (int bits : new int[]{8, 16}) {
            for (int limit : new int[]{1, 2, 4, 0}) {
                int available = Math.min(3, Runtime.getRuntime().availableProcessors());
                int workers = limit == 0 ? available : Math.min(available, limit);
                // Each 32×32 tile has two int-coordinate arrays, two double-fraction
                // arrays and up to 64×64 double source values. No headers are counted
                // in this independent minimum, so the planner must exceed it.
                long tiles = workers * (2L * 32 * 32 * Integer.BYTES
                        + 2L * 32 * 32 * Double.BYTES + 64L * 64 * Double.BYTES);
                long minimum = (long) width * height * (bits / 8)
                        + radialTables + inverseTables + encoderTables + axesAndWriteRow + tiles;
                long estimate = OpticalCorrection.estimatedWorkspaceBytes(width, height, bits, limit);
                assertTrue(estimate >= minimum, "workspace omits live arrays for " + bits
                        + " bits and " + workers + " workers: " + estimate + " < " + minimum);
            }
        }
    }
}
