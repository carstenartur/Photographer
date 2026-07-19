package com.photographer.focusstack.common.capability;

import java.util.Objects;

/**
 * Evidence-based availability of one camera capability.
 *
 * <p>The model separates what a camera advertised from what the current backend
 * implements. A missing or failed catalog is {@link State#UNKNOWN}, never an
 * invented unsupported claim.</p>
 */
public record CapabilityStatus(
        Capability capability,
        State state,
        CapabilitySet.Access access,
        TemporaryCondition temporaryCondition) {

    public enum State {
        /** Camera advertised the required operation and the backend implements it. */
        AVAILABLE,
        /** Authoritative catalog did not advertise the required operation. */
        UNSUPPORTED,
        /** Camera advertised the operation, but this backend does not implement it. */
        ADVERTISED_NOT_IMPLEMENTED,
        /** Advertised and implemented, but currently blocked by camera state. */
        TEMPORARILY_UNAVAILABLE,
        /** No authoritative advertisement evidence is available. */
        UNKNOWN
    }

    /** Vendor-neutral rendering of documented temporary camera states. */
    public enum TemporaryCondition {
        NONE,
        DEVICE_BUSY,
        SHOOTING_OR_RECORDING,
        PREPARING,
        MODE_NOT_SUPPORTED,
        LIVE_VIEW_NOT_STARTED,
        ALREADY_STARTED,
        OUT_OF_FOCUS,
        MEDIA_WRITE_UNAVAILABLE,
        OPERATION_NOT_STARTED,
        UNKNOWN
    }

    public CapabilityStatus {
        capability = Objects.requireNonNull(capability, "capability");
        state = Objects.requireNonNull(state, "state");
        temporaryCondition = Objects.requireNonNull(
                temporaryCondition, "temporaryCondition");
        if (state == State.TEMPORARILY_UNAVAILABLE
                && temporaryCondition == TemporaryCondition.NONE) {
            throw new IllegalArgumentException(
                    "A temporarily unavailable capability requires a temporary condition");
        }
        if (state != State.TEMPORARILY_UNAVAILABLE
                && temporaryCondition != TemporaryCondition.NONE) {
            throw new IllegalArgumentException(
                    "Temporary conditions are valid only for TEMPORARILY_UNAVAILABLE");
        }
        if (state == State.AVAILABLE && access == null) {
            throw new IllegalArgumentException("Available capabilities require an access mode");
        }
    }

    public static CapabilityStatus available(
            Capability capability,
            CapabilitySet.Access access) {
        return new CapabilityStatus(capability, State.AVAILABLE,
                Objects.requireNonNull(access, "access"), TemporaryCondition.NONE);
    }

    public static CapabilityStatus unsupported(Capability capability) {
        return new CapabilityStatus(capability, State.UNSUPPORTED,
                null, TemporaryCondition.NONE);
    }

    public static CapabilityStatus advertisedNotImplemented(Capability capability) {
        return new CapabilityStatus(capability, State.ADVERTISED_NOT_IMPLEMENTED,
                null, TemporaryCondition.NONE);
    }

    public static CapabilityStatus temporarilyUnavailable(
            Capability capability,
            CapabilitySet.Access access,
            TemporaryCondition condition) {
        return new CapabilityStatus(capability, State.TEMPORARILY_UNAVAILABLE,
                Objects.requireNonNull(access, "access"),
                Objects.requireNonNull(condition, "condition"));
    }

    public static CapabilityStatus unknown(Capability capability) {
        return new CapabilityStatus(capability, State.UNKNOWN,
                null, TemporaryCondition.NONE);
    }

    public boolean advertised() {
        return state == State.AVAILABLE
                || state == State.ADVERTISED_NOT_IMPLEMENTED
                || state == State.TEMPORARILY_UNAVAILABLE;
    }

    public boolean implemented() {
        return state == State.AVAILABLE || state == State.TEMPORARILY_UNAVAILABLE;
    }
}
