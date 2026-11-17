package com.photographer.focusstack.camera.session;

/** Enforces one logical opposing-device owner per private camera key. */
public interface CameraSessionLeaseManager {
    CameraSessionLease acquire(CameraLeaseRequest request);

    CameraSessionLeaseMetrics metrics();

    /** Snapshot for discovery. Acquisition remains the authority for access. */
    default boolean isLeased(CameraLeaseKey key) { return false; }

    /** Blocks all subsequent acquisitions while existing handles may still close. */
    default void beginShutdown() {
        // Unmanaged/test implementations have no process-local lifecycle state.
    }
}
