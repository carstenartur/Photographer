package com.photographer.focusstack.cr3.container;

/**
 * Represents the full sensor geometry and crop information for a CR3 image.
 *
 * <p>This is a dedicated data model for the crop/sensor problem. Canon sensors
 * include optical-black regions, while the visible image area is a subset of the
 * full sensor. Canon may also provide a recommended default crop.</p>
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

    public int activeWidth() {
        return activeRight - activeLeft;
    }

    public int activeHeight() {
        return activeBottom - activeTop;
    }

    public int defaultCropWidth() {
        return defaultCropRight - defaultCropLeft;
    }

    public int defaultCropHeight() {
        return defaultCropBottom - defaultCropTop;
    }

    public boolean hasDefaultCrop() {
        return defaultCropWidth() > 0 && defaultCropHeight() > 0;
    }

    public boolean hasActiveArea() {
        return activeWidth() > 0 && activeHeight() > 0;
    }

    /** Returns [width, height] for default crop, active area, or full sensor. */
    public int[] bestOutputDimensions() {
        if (hasDefaultCrop()) {
            return new int[]{defaultCropWidth(), defaultCropHeight()};
        }
        if (hasActiveArea()) {
            return new int[]{activeWidth(), activeHeight()};
        }
        return new int[]{sensorWidth, sensorHeight};
    }

    public static SensorCropGeometry withActiveArea(int sensorWidth, int sensorHeight,
                                                     int left, int top, int right, int bottom) {
        return new SensorCropGeometry(sensorWidth, sensorHeight,
                left, top, right, bottom,
                0, 0, 0, 0);
    }

    public static SensorCropGeometry sensorOnly(int sensorWidth, int sensorHeight) {
        return new SensorCropGeometry(sensorWidth, sensorHeight,
                0, 0, sensorWidth, sensorHeight,
                0, 0, 0, 0);
    }
}
