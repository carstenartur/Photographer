package com.photographer.focusstack.common.camera;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeFocusBracketingCapabilitiesTest {

    @Test
    void integerRangeUsesMinimumRelativeStep() {
        IntegerSettingRange range = new IntegerSettingRange(2, 10, 3);

        assertTrue(range.contains(2));
        assertTrue(range.contains(5));
        assertTrue(range.contains(8));
        assertFalse(range.contains(10));
        assertFalse(range.contains(1));
    }

    @Test
    void requestRequiresDepthCompositionBeforeCrop() {
        assertThrows(IllegalArgumentException.class, () ->
                new NativeFocusBracketingRequest(10, 4, false, false, true));
    }

    @Test
    void validatesRangesAndOptionalFeaturesTogether() {
        NativeFocusBracketingCapabilities capabilities = capabilities(false, false, false);
        NativeFocusBracketingRequest request = new NativeFocusBracketingRequest(
                1_000, 11, true, true, false);

        List<String> errors = capabilities.validate(request);

        assertEquals(4, errors.size());
        assertTrue(errors.stream().anyMatch(error -> error.contains("numberOfShots")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("focusIncrement")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("exposure smoothing")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("depth composition")));
    }

    @Test
    void executableRequiresBothMandatoryRanges() {
        NativeFocusBracketingCapabilities incomplete =
                new NativeFocusBracketingCapabilities(
                        true,
                        Optional.of(new IntegerSettingRange(2, 999, 1)),
                        Optional.empty(),
                        false,
                        false,
                        false);

        assertFalse(incomplete.executable());
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> incomplete.requireExecutable(
                        new NativeFocusBracketingRequest(10, 4, false, false, false)));
        assertTrue(exception.getMessage().contains("focus-increment capability is missing"));
    }

    @Test
    void acceptsCompleteSupportedRequest() {
        NativeFocusBracketingCapabilities capabilities = capabilities(true, true, true);
        NativeFocusBracketingRequest request = new NativeFocusBracketingRequest(
                100, 4, true, true, true);

        assertTrue(capabilities.executable());
        assertTrue(capabilities.validate(request).isEmpty());
    }

    private static NativeFocusBracketingCapabilities capabilities(
            boolean exposureSmoothing,
            boolean depthComposition,
            boolean cropDepthComposition) {
        return new NativeFocusBracketingCapabilities(
                true,
                Optional.of(new IntegerSettingRange(2, 999, 1)),
                Optional.of(new IntegerSettingRange(1, 10, 1)),
                exposureSmoothing,
                depthComposition,
                cropDepthComposition);
    }
}
