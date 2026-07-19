package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.common.camera.NativeFocusBracketingCapabilities;
import com.photographer.focusstack.common.camera.NativeFocusBracketingRequest;
import com.photographer.focusstack.common.camera.NativeFocusBracketingSession;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reversible setting transaction used by native focus-bracketing backends.
 *
 * <p>The camera-owned sequence is enabled only after every subordinate setting has
 * been applied. Restoration disables the sequence first, restores subordinate
 * values in reverse order and restores the original enabled state last.</p>
 */
final class FocusBracketingSettingsTransaction implements NativeFocusBracketingSession {

    enum Setting {
        ENABLED(Boolean.class),
        NUMBER_OF_SHOTS(Integer.class),
        FOCUS_INCREMENT(Integer.class),
        EXPOSURE_SMOOTHING(Boolean.class),
        DEPTH_COMPOSITION(Boolean.class),
        CROP_DEPTH_COMPOSITION(Boolean.class);

        private final Class<?> valueType;

        Setting(Class<?> valueType) {
            this.valueType = valueType;
        }

        void requireType(Object value) {
            if (!valueType.isInstance(value)) {
                throw new IllegalArgumentException(
                        name() + " requires " + valueType.getSimpleName()
                                + " but got " + (value == null ? "null" : value.getClass().getSimpleName()));
            }
        }
    }

    interface SettingAccess {
        boolean supports(Setting setting);

        Object read(Setting setting);

        void write(Setting setting, Object value);
    }

    private static final List<Setting> RESTORE_ORDER = List.of(
            Setting.CROP_DEPTH_COMPOSITION,
            Setting.DEPTH_COMPOSITION,
            Setting.EXPOSURE_SMOOTHING,
            Setting.FOCUS_INCREMENT,
            Setting.NUMBER_OF_SHOTS
    );

    private final SettingAccess access;
    private final NativeFocusBracketingRequest request;
    private final Map<Setting, Object> originalValues;
    private final AtomicBoolean closed = new AtomicBoolean();

    private FocusBracketingSettingsTransaction(
            SettingAccess access,
            NativeFocusBracketingRequest request,
            Map<Setting, Object> originalValues) {
        this.access = access;
        this.request = request;
        this.originalValues = Map.copyOf(originalValues);
    }

    static FocusBracketingSettingsTransaction prepare(
            SettingAccess access,
            NativeFocusBracketingCapabilities capabilities,
            NativeFocusBracketingRequest request) {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(request, "request");
        capabilities.requireExecutable(request);

        List<Setting> requiredSettings = requiredSettings(capabilities);
        EnumMap<Setting, Object> snapshot = new EnumMap<>(Setting.class);
        for (Setting setting : requiredSettings) {
            if (!access.supports(setting)) {
                throw new IllegalStateException(
                        "Setting access does not implement advertised " + setting);
            }
            Object value = access.read(setting);
            setting.requireType(value);
            snapshot.put(setting, value);
        }

        FocusBracketingSettingsTransaction transaction =
                new FocusBracketingSettingsTransaction(access, request, snapshot);
        try {
            transaction.apply(capabilities);
            return transaction;
        } catch (RuntimeException failure) {
            try {
                transaction.restore();
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            transaction.closed.set(true);
            throw failure;
        }
    }

    @Override
    public NativeFocusBracketingRequest request() {
        return request;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            restore();
        }
    }

    private void apply(NativeFocusBracketingCapabilities capabilities) {
        writeIfDifferent(Setting.ENABLED, false);
        writeIfDifferent(Setting.NUMBER_OF_SHOTS, request.numberOfShots());
        writeIfDifferent(Setting.FOCUS_INCREMENT, request.focusIncrement());
        if (capabilities.exposureSmoothingSupported()) {
            writeIfDifferent(Setting.EXPOSURE_SMOOTHING, request.exposureSmoothing());
        }
        if (capabilities.depthCompositionSupported()) {
            writeIfDifferent(Setting.DEPTH_COMPOSITION, request.depthComposition());
        }
        if (capabilities.cropDepthCompositionSupported()) {
            writeIfDifferent(Setting.CROP_DEPTH_COMPOSITION, request.cropDepthComposition());
        }
        access.write(Setting.ENABLED, true);
    }

    private void writeIfDifferent(Setting setting, Object desired) {
        setting.requireType(desired);
        Object original = originalValues.get(setting);
        if (!Objects.equals(original, desired)) {
            access.write(setting, desired);
        }
    }

    private void restore() {
        List<RuntimeException> failures = new ArrayList<>();
        writeForRestore(Setting.ENABLED, false, failures);
        for (Setting setting : RESTORE_ORDER) {
            if (originalValues.containsKey(setting)) {
                writeForRestore(setting, originalValues.get(setting), failures);
            }
        }
        writeForRestore(Setting.ENABLED, originalValues.get(Setting.ENABLED), failures);

        if (!failures.isEmpty()) {
            RuntimeException first = failures.getFirst();
            failures.stream().skip(1).forEach(first::addSuppressed);
            throw first;
        }
    }

    private void writeForRestore(
            Setting setting,
            Object value,
            List<RuntimeException> failures) {
        try {
            setting.requireType(value);
            access.write(setting, value);
        } catch (RuntimeException failure) {
            failures.add(failure);
        }
    }

    private static List<Setting> requiredSettings(
            NativeFocusBracketingCapabilities capabilities) {
        List<Setting> settings = new ArrayList<>();
        settings.add(Setting.ENABLED);
        settings.add(Setting.NUMBER_OF_SHOTS);
        settings.add(Setting.FOCUS_INCREMENT);
        if (capabilities.exposureSmoothingSupported()) {
            settings.add(Setting.EXPOSURE_SMOOTHING);
        }
        if (capabilities.depthCompositionSupported()) {
            settings.add(Setting.DEPTH_COMPOSITION);
        }
        if (capabilities.cropDepthCompositionSupported()) {
            settings.add(Setting.CROP_DEPTH_COMPOSITION);
        }
        return List.copyOf(settings);
    }
}
