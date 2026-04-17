package com.photographer.focusstack.cr3.processing;

import java.util.Arrays;

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
 *   <li><b>Per-row</b>: black level varies by row (future — from OB estimation)</li>
 * </ul>
 *
 * <p>Priority: per-channel from MakerNote &gt; global default</p>
 */
public final class BlackLevel {

    /** Default global black level for Canon 14-bit sensors. */
    public static final int DEFAULT_14BIT = 512;

    private final int globalLevel;
    private final int[] perChannelLevels;  // [R, Gr, Gb, B] or null

    private BlackLevel(int globalLevel, int[] perChannelLevels) {
        this.globalLevel = globalLevel;
        this.perChannelLevels = perChannelLevels;
    }

    /**
     * Creates a global (uniform) black level.
     *
     * @param level the black level value to subtract from all pixels
     * @return a global black level
     */
    public static BlackLevel global(int level) {
        return new BlackLevel(level, null);
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
        return new BlackLevel(0, new int[]{r, gr, gb, b});
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
        return new BlackLevel(0, Arrays.copyOf(levels, 4));
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
        return perChannelLevels != null;
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
        if (perChannelLevels == null) {
            return globalLevel;
        }
        // Map Bayer position to channel index: 0=R, 1=Gr, 2=Gb, 3=B
        int channel = bayerChannelIndex(x, y, pattern);
        return perChannelLevels[channel];
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
        if (perChannelLevels != null) {
            return "BlackLevel[perChannel=R:%d,Gr:%d,Gb:%d,B:%d]".formatted(
                    perChannelLevels[0], perChannelLevels[1],
                    perChannelLevels[2], perChannelLevels[3]);
        }
        return "BlackLevel[global=%d]".formatted(globalLevel);
    }
}
