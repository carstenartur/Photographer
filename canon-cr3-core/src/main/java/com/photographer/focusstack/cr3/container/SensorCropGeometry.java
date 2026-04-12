package com.photographer.focusstack.cr3.container;

/**
 * Represents the full sensor geometry and crop information for a CR3 image.
 *
 * <p>This is a dedicated data model for the crop/sensor problem that LibRaw has shown
 * to be a separate concern. Canon sensors have optical black regions, and the visible
 * image area is a subset of the full sensor. Additionally, Canon provides both a
 * "default crop" (standard processing) and a "visible area" (minimal crop).</p>
 *
 * <p>All dimensions are in pixels. Border values represent the offset from the
 * corresponding sensor edge to the visible region edge.</p>
 *
 * @param sensorWidth      full sensor width in pixels
 * @param sensorHeight     full sensor height in pixels
 * @param activeLeft       left border of the active (visible) area
 * @param activeTop        top border of the active (visible) area
 * @param activeRight      right edge of the active area (exclusive)
 * @param activeBottom     bottom edge of the active area (exclusive)
 * @param defaultCropLeft  left border of the default (recommended) crop
 * @param defaultCropTop   top border of the default crop
 * @param defaultCropRight right edge of the default crop (exclusive)
 * @param defaultCropBottom bottom edge of the default crop (exclusive)
 */
public record SensorCropGeometry(
        int sensorWidth,
        int sensorHeight,
        int activeLeft,
        int activeTop,
        int activeRight,
        int activeBottom,
        int defaultCropLeft,
        int defaultCropTop,
        int defaultCropRight,
        int defaultCropBottom
) {

    /**
     * Returns the width of the active (visible) sensor area.
     */
    public int activeWidth() {
        return activeRight - activeLeft;
    }

    /**
     * Returns the height of the active (visible) sensor area.
     */
    public int activeHeight() {
        return activeBottom - activeTop;
    }

    /**
     * Returns the width of the default crop region.
     */
    public int defaultCropWidth() {
        return defaultCropRight - defaultCropLeft;
    }

    /**
     * Returns the height of the default crop region.
     */
    public int defaultCropHeight() {
        return defaultCropBottom - defaultCropTop;
    }

    /**
     * Returns whether a default crop is defined (non-zero dimensions).
     */
    public boolean hasDefaultCrop() {
        return defaultCropWidth() > 0 && defaultCropHeight() > 0;
    }

    /**
     * Returns whether an active area is defined (non-zero dimensions).
     */
    public boolean hasActiveArea() {
        return activeWidth() > 0 && activeHeight() > 0;
    }

    /**
     * Returns the best available output dimensions: default crop if available,
     * then active area, then full sensor.
     *
     * @return a two-element array [width, height]
     */
    public int[] bestOutputDimensions() {
        if (hasDefaultCrop()) {
            return new int[]{defaultCropWidth(), defaultCropHeight()};
        }
        if (hasActiveArea()) {
            return new int[]{activeWidth(), activeHeight()};
        }
        return new int[]{sensorWidth, sensorHeight};
    }

    /**
     * Creates a geometry with only sensor dimensions and active area (no default crop).
     */
    public static SensorCropGeometry withActiveArea(int sensorWidth, int sensorHeight,
                                                     int left, int top, int right, int bottom) {
        return new SensorCropGeometry(sensorWidth, sensorHeight,
                left, top, right, bottom,
                0, 0, 0, 0);
    }

    /**
     * Creates a geometry with only sensor dimensions (no crop information).
     */
    public static SensorCropGeometry sensorOnly(int sensorWidth, int sensorHeight) {
        return new SensorCropGeometry(sensorWidth, sensorHeight,
                0, 0, sensorWidth, sensorHeight,
                0, 0, 0, 0);
    }
}
