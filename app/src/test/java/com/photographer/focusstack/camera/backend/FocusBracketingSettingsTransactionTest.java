package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.common.camera.IntegerSettingRange;
import com.photographer.focusstack.common.camera.NativeFocusBracketingCapabilities;
import com.photographer.focusstack.common.camera.NativeFocusBracketingRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.photographer.focusstack.camera.backend.FocusBracketingSettingsTransaction.Setting.CROP_DEPTH_COMPOSITION;
import static com.photographer.focusstack.camera.backend.FocusBracketingSettingsTransaction.Setting.DEPTH_COMPOSITION;
import static com.photographer.focusstack.camera.backend.FocusBracketingSettingsTransaction.Setting.ENABLED;
import static com.photographer.focusstack.camera.backend.FocusBracketingSettingsTransaction.Setting.EXPOSURE_SMOOTHING;
import static com.photographer.focusstack.camera.backend.FocusBracketingSettingsTransaction.Setting.FOCUS_INCREMENT;
import static com.photographer.focusstack.camera.backend.FocusBracketingSettingsTransaction.Setting.NUMBER_OF_SHOTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FocusBracketingSettingsTransactionTest {

    @Test
    void appliesAndRestoresSettingsInSafeDeterministicOrder() {
        FakeSettingAccess access = fullAccess(true, 10, 2, false, false, false);
        NativeFocusBracketingRequest request = new NativeFocusBracketingRequest(
                20, 5, true, true, true);

        FocusBracketingSettingsTransaction transaction =
                FocusBracketingSettingsTransaction.prepare(access, fullCapabilities(), request);

        assertEquals(List.of(
                "ENABLED=false",
                "NUMBER_OF_SHOTS=20",
                "FOCUS_INCREMENT=5",
                "EXPOSURE_SMOOTHING=true",
                "DEPTH_COMPOSITION=true",
                "CROP_DEPTH_COMPOSITION=true",
                "ENABLED=true"
        ), access.writes());
        assertEquals(request, transaction.request());

        transaction.close();

        assertEquals(List.of(
                "ENABLED=false",
                "NUMBER_OF_SHOTS=20",
                "FOCUS_INCREMENT=5",
                "EXPOSURE_SMOOTHING=true",
                "DEPTH_COMPOSITION=true",
                "CROP_DEPTH_COMPOSITION=true",
                "ENABLED=true",
                "ENABLED=false",
                "CROP_DEPTH_COMPOSITION=false",
                "DEPTH_COMPOSITION=false",
                "EXPOSURE_SMOOTHING=false",
                "FOCUS_INCREMENT=2",
                "NUMBER_OF_SHOTS=10",
                "ENABLED=true"
        ), access.writes());
        assertEquals(Map.of(
                ENABLED, true,
                NUMBER_OF_SHOTS, 10,
                FOCUS_INCREMENT, 2,
                EXPOSURE_SMOOTHING, false,
                DEPTH_COMPOSITION, false,
                CROP_DEPTH_COMPOSITION, false
        ), access.values());
    }

    @Test
    void closeIsIdempotent() {
        FakeSettingAccess access = fullAccess(false, 10, 2, false, false, false);
        FocusBracketingSettingsTransaction transaction =
                FocusBracketingSettingsTransaction.prepare(
                        access,
                        fullCapabilities(),
                        new NativeFocusBracketingRequest(10, 2, false, false, false));

        transaction.close();
        int writesAfterFirstClose = access.writes().size();
        transaction.close();

        assertEquals(writesAfterFirstClose, access.writes().size());
        assertEquals(false, access.values().get(ENABLED));
    }

    @Test
    void rollsBackCompleteSnapshotWhenApplyFailsPartWayThrough() {
        FakeSettingAccess access = fullAccess(true, 10, 2, false, false, false);
        access.failOnce(FOCUS_INCREMENT, 5);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> FocusBracketingSettingsTransaction.prepare(
                        access,
                        fullCapabilities(),
                        new NativeFocusBracketingRequest(20, 5, true, true, true)));

        assertTrue(failure.getMessage().contains("synthetic write failure"));
        assertEquals(Map.of(
                ENABLED, true,
                NUMBER_OF_SHOTS, 10,
                FOCUS_INCREMENT, 2,
                EXPOSURE_SMOOTHING, false,
                DEPTH_COMPOSITION, false,
                CROP_DEPTH_COMPOSITION, false
        ), access.values());
        assertEquals("ENABLED=true", access.writes().getLast());
    }

    @Test
    void doesNotReadOrWriteUnadvertisedOptionalSettings() {
        NativeFocusBracketingCapabilities capabilities = new NativeFocusBracketingCapabilities(
                true,
                Optional.of(new IntegerSettingRange(2, 999, 1)),
                Optional.of(new IntegerSettingRange(1, 10, 1)),
                false,
                false,
                false);
        FakeSettingAccess access = new FakeSettingAccess(Map.of(
                ENABLED, false,
                NUMBER_OF_SHOTS, 10,
                FOCUS_INCREMENT, 2
        ));

        try (FocusBracketingSettingsTransaction ignored =
                     FocusBracketingSettingsTransaction.prepare(
                             access,
                             capabilities,
                             new NativeFocusBracketingRequest(12, 3, false, false, false))) {
            assertEquals(List.of(
                    "NUMBER_OF_SHOTS=12",
                    "FOCUS_INCREMENT=3",
                    "ENABLED=true"
            ), access.writes());
        }

        assertTrue(access.reads().stream().noneMatch(
                setting -> setting == EXPOSURE_SMOOTHING
                        || setting == DEPTH_COMPOSITION
                        || setting == CROP_DEPTH_COMPOSITION));
    }

    @Test
    void rejectsAccessThatCannotImplementAdvertisedSettingBeforeWriting() {
        FakeSettingAccess access = new FakeSettingAccess(Map.of(
                ENABLED, false,
                NUMBER_OF_SHOTS, 10,
                FOCUS_INCREMENT, 2,
                EXPOSURE_SMOOTHING, false,
                DEPTH_COMPOSITION, false
        ));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> FocusBracketingSettingsTransaction.prepare(
                        access,
                        fullCapabilities(),
                        new NativeFocusBracketingRequest(20, 5, true, true, true)));

        assertTrue(failure.getMessage().contains("CROP_DEPTH_COMPOSITION"));
        assertTrue(access.writes().isEmpty());
    }

    private static NativeFocusBracketingCapabilities fullCapabilities() {
        return new NativeFocusBracketingCapabilities(
                true,
                Optional.of(new IntegerSettingRange(2, 999, 1)),
                Optional.of(new IntegerSettingRange(1, 10, 1)),
                true,
                true,
                true);
    }

    private static FakeSettingAccess fullAccess(
            boolean enabled,
            int numberOfShots,
            int focusIncrement,
            boolean exposureSmoothing,
            boolean depthComposition,
            boolean cropDepthComposition) {
        return new FakeSettingAccess(Map.of(
                ENABLED, enabled,
                NUMBER_OF_SHOTS, numberOfShots,
                FOCUS_INCREMENT, focusIncrement,
                EXPOSURE_SMOOTHING, exposureSmoothing,
                DEPTH_COMPOSITION, depthComposition,
                CROP_DEPTH_COMPOSITION, cropDepthComposition
        ));
    }

    private static final class FakeSettingAccess
            implements FocusBracketingSettingsTransaction.SettingAccess {

        private final EnumMap<FocusBracketingSettingsTransaction.Setting, Object> values =
                new EnumMap<>(FocusBracketingSettingsTransaction.Setting.class);
        private final List<FocusBracketingSettingsTransaction.Setting> reads = new ArrayList<>();
        private final List<String> writes = new ArrayList<>();
        private FocusBracketingSettingsTransaction.Setting failingSetting;
        private Object failingValue;
        private boolean failurePending;

        private FakeSettingAccess(Map<FocusBracketingSettingsTransaction.Setting, Object> initial) {
            values.putAll(initial);
        }

        @Override
        public boolean supports(FocusBracketingSettingsTransaction.Setting setting) {
            return values.containsKey(setting);
        }

        @Override
        public Object read(FocusBracketingSettingsTransaction.Setting setting) {
            reads.add(setting);
            return values.get(setting);
        }

        @Override
        public void write(FocusBracketingSettingsTransaction.Setting setting, Object value) {
            if (failurePending && setting == failingSetting && value.equals(failingValue)) {
                failurePending = false;
                throw new IllegalStateException(
                        "synthetic write failure for " + setting + "=" + value);
            }
            setting.requireType(value);
            values.put(setting, value);
            writes.add(setting + "=" + value);
        }

        private void failOnce(
                FocusBracketingSettingsTransaction.Setting setting,
                Object value) {
            failingSetting = setting;
            failingValue = value;
            failurePending = true;
        }

        private Map<FocusBracketingSettingsTransaction.Setting, Object> values() {
            return Map.copyOf(values);
        }

        private List<FocusBracketingSettingsTransaction.Setting> reads() {
            return List.copyOf(reads);
        }

        private List<String> writes() {
            return List.copyOf(writes);
        }
    }
}
