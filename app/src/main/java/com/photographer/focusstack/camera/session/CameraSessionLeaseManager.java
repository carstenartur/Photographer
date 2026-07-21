package com.photographer.focusstack.camera.session;

/** Enforces one logical opposing-device owner per private camera key. */
public interface CameraSessionLeaseManager {
    CameraSessionLease acquire(CameraLeaseRequest request);

    CameraSessionLeaseMetrics metrics();
}
