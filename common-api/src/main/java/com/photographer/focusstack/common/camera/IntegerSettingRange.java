package com.photographer.focusstack.common.camera;

/**
 * Inclusive integer setting range advertised by a camera.
 *
 * <p>A value is valid when it lies between {@code min} and {@code max} and can be
 * reached from {@code min} by repeatedly adding {@code step}. The maximum does
 * not have to be aligned to the step; cameras occasionally advertise a final
 * upper bound that is not itself selectable.</p>
 */
public record IntegerSettingRange(int min, int max, int step) {

    public IntegerSettingRange {
        if (min > max) {
            throw new IllegalArgumentException("min must not exceed max");
        }
        if (step <= 0) {
            throw new IllegalArgumentException("step must be positive");
        }
    }

    /** Returns whether the supplied value is selectable in this range. */
    public boolean contains(int value) {
        long offset = (long) value - min;
        return value >= min && value <= max && offset % step == 0L;
    }

    /**
     * Requires the supplied value to be selectable and returns it for fluent use.
     */
    public int requireContains(int value, String settingName) {
        if (!contains(value)) {
            throw new IllegalArgumentException(
                    settingName + "=" + value + " is outside " + this);
        }
        return value;
    }
}
