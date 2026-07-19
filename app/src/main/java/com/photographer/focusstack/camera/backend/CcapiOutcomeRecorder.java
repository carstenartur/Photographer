package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.camera.exception.CameraOperationException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Records CCAPI outcomes using one bounded, secret-free outcome tag. */
@Component
final class CcapiOutcomeRecorder {

    static final String METER_NAME = "camera.ccapi.outcomes";

    enum Outcome {
        AUTH_CHALLENGE,
        AUTH_SUCCESS_AFTER_CHALLENGE,
        AUTH_FINAL_FAILURE,
        ENDPOINT_NOT_FOUND,
        METHOD_NOT_ALLOWED,
        DEVICE_BUSY,
        DURING_SHOOTING_OR_RECORDING,
        TAKEN_IN_PREPARATION,
        MODE_NOT_SUPPORTED,
        LIVE_VIEW_NOT_STARTED,
        ALREADY_STARTED,
        OUT_OF_FOCUS,
        CANNOT_WRITE_TO_CARD,
        NOT_STARTED,
        SERVICE_UNAVAILABLE,
        CAMERA_REJECTED,
        CANCELLED,
        TIMEOUT,
        CONNECTION_FAILED
    }

    private final Map<Outcome, Counter> counters;

    CcapiOutcomeRecorder(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        EnumMap<Outcome, Counter> created = new EnumMap<>(Outcome.class);
        for (Outcome outcome : Outcome.values()) {
            created.put(outcome, Counter.builder(METER_NAME)
                    .description("Sanitized Canon CCAPI transport outcomes")
                    .tag("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT))
                    .register(registry));
        }
        counters = Map.copyOf(created);
    }

    void record(Outcome outcome) {
        counters.get(Objects.requireNonNull(outcome, "outcome")).increment();
    }

    void record(CameraOperationException.Reason reason) {
        record(switch (Objects.requireNonNull(reason, "reason")) {
            case ENDPOINT_NOT_FOUND -> Outcome.ENDPOINT_NOT_FOUND;
            case METHOD_NOT_ALLOWED -> Outcome.METHOD_NOT_ALLOWED;
            case DEVICE_BUSY -> Outcome.DEVICE_BUSY;
            case DURING_SHOOTING_OR_RECORDING -> Outcome.DURING_SHOOTING_OR_RECORDING;
            case TAKEN_IN_PREPARATION -> Outcome.TAKEN_IN_PREPARATION;
            case MODE_NOT_SUPPORTED -> Outcome.MODE_NOT_SUPPORTED;
            case LIVE_VIEW_NOT_STARTED -> Outcome.LIVE_VIEW_NOT_STARTED;
            case ALREADY_STARTED -> Outcome.ALREADY_STARTED;
            case OUT_OF_FOCUS -> Outcome.OUT_OF_FOCUS;
            case CANNOT_WRITE_TO_CARD -> Outcome.CANNOT_WRITE_TO_CARD;
            case NOT_STARTED -> Outcome.NOT_STARTED;
            case SERVICE_UNAVAILABLE -> Outcome.SERVICE_UNAVAILABLE;
            case CAMERA_REJECTED -> Outcome.CAMERA_REJECTED;
            case CANCELLED -> Outcome.CANCELLED;
            case TIMEOUT -> Outcome.TIMEOUT;
            case CONNECTION_FAILED -> Outcome.CONNECTION_FAILED;
        });
    }
}
