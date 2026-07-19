package com.photographer.focusstack.common.camera;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Camera-advertised native focus-bracketing capabilities.
 *
 * <p>The base setting may be advertised while one of the required integer
 * settings is missing. {@link #executable()} therefore distinguishes a complete
 * native sequence from partial endpoint coverage.</p>
 */
public record NativeFocusBracketingCapabilities(
        boolean bracketingSupported,
        Optional<IntegerSettingRange> numberOfShots,
        Optional<IntegerSettingRange> focusIncrement,
        boolean exposureSmoothingSupported,
        boolean depthCompositionSupported,
        boolean cropDepthCompositionSupported
) {

    public NativeFocusBracketingCapabilities {
        numberOfShots = Objects.requireNonNull(numberOfShots, "numberOfShots");
        focusIncrement = Objects.requireNonNull(focusIncrement, "focusIncrement");
        if (cropDepthCompositionSupported && !depthCompositionSupported) {
            throw new IllegalArgumentException(
                    "crop depth composition cannot be supported without depth composition");
        }
    }

    public static NativeFocusBracketingCapabilities unsupported() {
        return new NativeFocusBracketingCapabilities(
                false, Optional.empty(), Optional.empty(), false, false, false);
    }

    /** Returns whether the minimum endpoint set required for execution is complete. */
    public boolean executable() {
        return bracketingSupported && numberOfShots.isPresent() && focusIncrement.isPresent();
    }

    /**
     * Validates one request against the advertised range and optional features.
     *
     * @return immutable validation messages; an empty list means the request is executable
     */
    public List<String> validate(NativeFocusBracketingRequest request) {
        Objects.requireNonNull(request, "request");
        List<String> errors = new ArrayList<>();
        if (!bracketingSupported) {
            errors.add("native focus bracketing is not advertised");
        }
        if (numberOfShots.isEmpty()) {
            errors.add("number-of-shots capability is missing");
        } else if (!numberOfShots.orElseThrow().contains(request.numberOfShots())) {
            errors.add("numberOfShots=" + request.numberOfShots()
                    + " is outside " + numberOfShots.orElseThrow());
        }
        if (focusIncrement.isEmpty()) {
            errors.add("focus-increment capability is missing");
        } else if (!focusIncrement.orElseThrow().contains(request.focusIncrement())) {
            errors.add("focusIncrement=" + request.focusIncrement()
                    + " is outside " + focusIncrement.orElseThrow());
        }
        if (request.exposureSmoothing() && !exposureSmoothingSupported) {
            errors.add("exposure smoothing is not advertised");
        }
        if (request.depthComposition() && !depthCompositionSupported) {
            errors.add("depth composition is not advertised");
        }
        if (request.cropDepthComposition() && !cropDepthCompositionSupported) {
            errors.add("crop depth composition is not advertised");
        }
        return List.copyOf(errors);
    }

    /** Rejects an invalid request with one message containing every mismatch. */
    public void requireExecutable(NativeFocusBracketingRequest request) {
        List<String> errors = validate(request);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
    }
}
