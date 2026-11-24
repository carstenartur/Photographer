package com.photographer.focusstack.cr3.processing;

import com.photographer.focusstack.cr3.raw.Cr3RawImage.BayerPattern;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntUnaryOperator;

/**
 * Represents a black level configuration that may be global, per-channel, or per-row.
 *
 * <p>Canon sensors have optical black (OB) regions whose values represent the sensor
 * noise floor. The black level must be subtracted before further processing.</p>
 *
 * <p>Supported modes:</p>
 * <ul>
 *   <li><b>Global</b>: single value for all pixels (simplest, least accurate)</li>
 *   <li><b>Per-channel</b>: 4 values for R, Gr, Gb, B Bayer channels</li>
 *   <li><b>Per-row</b>: one pedestal per Bayer channel and row pair, estimated from optically masked pixels</li>
 * </ul>
 *
 * <p>Normal RAW development uses optical-black estimates when available, then MakerNote
 * per-channel levels, then the global default.</p>
 */
public final class BlackLevel {

    /** Default global black level for Canon 14-bit sensors. */
    public static final int DEFAULT_14BIT = 512;

    /** Modes of black level specification. */
    public enum Mode {
        /** Single value for all pixels. */
        GLOBAL,
        /** One value per Bayer channel [R, Gr, Gb, B]. */
        PER_CHANNEL,
        /** Per-row black levels (one value per row, after Bayer channel separation). */
        PER_ROW
    }

    private final Mode mode;
    private final int globalLevel;
    private final int[] perChannelLevels;  // [R, Gr, Gb, B] or null
    private final int[][] perRowLevels;    // [4][height/2] — one array per Bayer channel, or null

    private BlackLevel(Mode mode, int globalLevel, int[] perChannelLevels, int[][] perRowLevels) {
        this.mode = mode;
        this.globalLevel = globalLevel;
        this.perChannelLevels = perChannelLevels;
        this.perRowLevels = perRowLevels;
    }

    /**
     * Creates a global (uniform) black level.
     *
     * @param level the black level value to subtract from all pixels
     * @return a global black level
     */
    public static BlackLevel global(int level) {
        return new BlackLevel(Mode.GLOBAL, level, null, null);
    }

    /**
     * Creates a per-channel black level with values for each Bayer channel.
     *
     * @param r  red channel black level
     * @param gr green-in-red-row channel black level
     * @param gb green-in-blue-row channel black level
     * @param b  blue channel black level
     * @return a per-channel black level
     */
    public static BlackLevel perChannel(int r, int gr, int gb, int b) {
        return new BlackLevel(Mode.PER_CHANNEL, 0, new int[]{r, gr, gb, b}, null);
    }

    /**
     * Creates a per-channel black level from a 4-element array [R, Gr, Gb, B].
     *
     * @param levels 4-element array of black levels
     * @return a per-channel black level
     * @throws IllegalArgumentException if the array does not have exactly 4 elements
     */
    public static BlackLevel perChannel(int[] levels) {
        if (levels.length != 4) {
            throw new IllegalArgumentException("Per-channel black level needs 4 values [R,Gr,Gb,B], got " + levels.length);
        }
        return new BlackLevel(Mode.PER_CHANNEL, 0, Arrays.copyOf(levels, 4), null);
    }

    /**
     * Creates a per-row black level from OB (optical black) region estimation.
     *
     * <p>Each Bayer channel has a separate per-row array. The arrays are indexed
     * by the half-row within that channel (e.g., for an image height H, each
     * channel array has H/2 entries).</p>
     *
     * @param perRowLevels 4 arrays of per-row black levels [R, Gr, Gb, B].
     *                     Each array must have the same length.
     * @return a per-row black level
     * @throws IllegalArgumentException if the structure is invalid
     */
    public static BlackLevel perRow(int[][] perRowLevels) {
        if (perRowLevels.length != 4) {
            throw new IllegalArgumentException(
                    "Per-row black level needs 4 channel arrays [R,Gr,Gb,B], got " + perRowLevels.length);
        }
        int len = perRowLevels[0].length;
        for (int ch = 1; ch < 4; ch++) {
            if (perRowLevels[ch].length != len) {
                throw new IllegalArgumentException(
                        "Per-row channel arrays must all have the same length; channel 0 has "
                        + len + ", channel " + ch + " has " + perRowLevels[ch].length);
            }
        }
        // Defensive copy
        int[][] copy = new int[4][];
        for (int ch = 0; ch < 4; ch++) {
            copy[ch] = Arrays.copyOf(perRowLevels[ch], perRowLevels[ch].length);
        }
        return new BlackLevel(Mode.PER_ROW, 0, null, copy);
    }

    /**
     * Creates the default Canon 14-bit global black level (512).
     *
     * @return the default black level
     */
    public static BlackLevel defaultLevel() {
        return global(DEFAULT_14BIT);
    }

    /** Returns {@code true} if this is a per-channel black level. */
    public boolean isPerChannel() {
        return mode == Mode.PER_CHANNEL;
    }

    /** Returns {@code true} if this is a per-row black level. */
    public boolean isPerRow() {
        return mode == Mode.PER_ROW;
    }

    /** Returns the mode of this black level. */
    public Mode mode() {
        return mode;
    }

    /** Returns the global black level value. */
    public int globalLevel() {
        return globalLevel;
    }

    /**
     * Returns the per-channel black levels [R, Gr, Gb, B], or {@code null} if global.
     */
    public int[] perChannelLevels() {
        return perChannelLevels != null ? Arrays.copyOf(perChannelLevels, 4) : null;
    }

    /**
     * Returns the black level for a specific Bayer position.
     *
     * @param x column
     * @param y row
     * @param pattern the Bayer pattern
     * @return the appropriate black level
     */
    public int levelAt(int x, int y,
                       com.photographer.focusstack.cr3.raw.Cr3RawImage.BayerPattern pattern) {
        if (mode == Mode.GLOBAL) {
            return globalLevel;
        }
        int channel = bayerChannelIndex(x, y, pattern);
        if (mode == Mode.PER_ROW && perRowLevels != null) {
            // Map from full-image row to half-row index for this channel
            int halfRow = y / 2;
            if (halfRow < perRowLevels[channel].length) {
                return perRowLevels[channel][halfRow];
            }
            // Fall through to per-channel if row index out of bounds
        }
        if (perChannelLevels != null) {
            return perChannelLevels[channel];
        }
        return globalLevel;
    }

    /**
     * Returns the smallest black-level value across all per-row entries (over all
     * 4 Bayer channels). Returns {@code DEFAULT_14BIT} if this {@code BlackLevel}
     * is not in {@link Mode#PER_ROW} mode or has no entries.
     */
    public int minPerRowLevel() {
        if (mode != Mode.PER_ROW || perRowLevels == null) {
            return DEFAULT_14BIT;
        }
        int min = Integer.MAX_VALUE;
        for (int ch = 0; ch < 4; ch++) {
            for (int v : perRowLevels[ch]) {
                if (v < min) min = v;
            }
        }
        return (min == Integer.MAX_VALUE) ? DEFAULT_14BIT : min;
    }

    /**
     * Projects the calibration of selected native rows onto a new, zero-based row grid.
     * Column coordinates retain their original parity. Each row uses its original per-channel
     * pedestal even when its position in the new grid has a different parity.
     *
     * @param height number of retained rows, at least one
     * @param sensorRowAt maps a retained zero-based row to its nonnegative native sensor row
     * @param pattern Bayer pattern used for both calibrations
     * @return calibration addressed by native column and retained row
     */
    public BlackLevel forSensorRows(int height, IntUnaryOperator sensorRowAt, BayerPattern pattern) {
        if (height < 1) {
            throw new IllegalArgumentException("At least one retained sensor row is required");
        }
        Objects.requireNonNull(sensorRowAt);
        Objects.requireNonNull(pattern);
        if (mode == Mode.GLOBAL) {
            return this;
        }
        int pairs = Math.ceilDiv(height, 2);
        int[][] selected = new int[4][pairs];
        for (int pair = 0; pair < pairs; pair++) {
            for (int parity = 0; parity < 2; parity++) {
                int row = 2 * pair + parity;
                // An incomplete last pair repeats the final actual pedestal in its unused entries.
                int sensorRow = sensorRowAt.applyAsInt(Math.min(row, height - 1));
                if (sensorRow < 0) {
                    throw new IllegalArgumentException("Sensor row must be nonnegative");
                }
                for (int x = 0; x < 2; x++) {
                    int channel = bayerChannelIndex(x, row, pattern);
                    selected[channel][pair] = levelAt(x, sensorRow, pattern);
                }
            }
        }
        return new BlackLevel(Mode.PER_ROW, 0, null, selected);
    }

    /**
     * Returns the Bayer channel index (0=R, 1=Gr, 2=Gb, 3=B) for a pixel position.
     */
    private static int bayerChannelIndex(int x, int y,
                                          com.photographer.focusstack.cr3.raw.Cr3RawImage.BayerPattern pattern) {
        boolean evenRow = (y % 2) == 0;
        boolean evenCol = (x % 2) == 0;
        return switch (pattern) {
            case RGGB -> {
                if (evenRow && evenCol) yield 0;       // R
                if (evenRow) yield 1;                  // Gr
                if (evenCol) yield 2;                  // Gb
                yield 3;                               // B
            }
            case BGGR -> {
                if (evenRow && evenCol) yield 3;       // B
                if (evenRow) yield 2;                  // Gb
                if (evenCol) yield 1;                  // Gr
                yield 0;                               // R
            }
            case GRBG -> {
                if (evenRow && evenCol) yield 1;       // Gr
                if (evenRow) yield 0;                  // R
                if (evenCol) yield 3;                  // B
                yield 2;                               // Gb
            }
            case GBRG -> {
                if (evenRow && evenCol) yield 2;       // Gb
                if (evenRow) yield 3;                  // B
                if (evenCol) yield 0;                  // R
                yield 1;                               // Gr
            }
        };
    }

    @Override
    public String toString() {
        if (mode == Mode.PER_ROW && perRowLevels != null) {
            return "BlackLevel[perRow, %d rows per channel]".formatted(perRowLevels[0].length);
        }
        if (perChannelLevels != null) {
            return "BlackLevel[perChannel=R:%d,Gr:%d,Gb:%d,B:%d]".formatted(
                    perChannelLevels[0], perChannelLevels[1],
                    perChannelLevels[2], perChannelLevels[3]);
        }
        return "BlackLevel[global=%d]".formatted(globalLevel);
    }
}
