package com.photographer.focusstack.common.camera;

/**
 * Vendor-neutral request for a camera-owned fixed focus-bracketing sequence.
 *
 * <p>This request deliberately does not model the existing adaptive manual
 * drive-focus loop. Native bracketing uses fixed settings that the camera applies
 * to the complete sequence after one shutter action.</p>
 */
public record NativeFocusBracketingRequest(
        int numberOfShots,
        int focusIncrement,
        boolean exposureSmoothing,
        boolean depthComposition,
        boolean cropDepthComposition
) {

    public NativeFocusBracketingRequest {
        if (numberOfShots < 2) {
            throw new IllegalArgumentException("numberOfShots must be at least 2");
        }
        if (focusIncrement <= 0) {
            throw new IllegalArgumentException("focusIncrement must be positive");
        }
        if (cropDepthComposition && !depthComposition) {
            throw new IllegalArgumentException(
                    "cropDepthComposition requires depthComposition");
        }
    }
}
