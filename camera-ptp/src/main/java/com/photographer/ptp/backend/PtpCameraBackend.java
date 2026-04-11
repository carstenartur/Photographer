package com.photographer.ptp.backend;

import com.photographer.focusstack.common.camera.*;
import com.photographer.ptp.core.PtpDeviceInfo;
import com.photographer.ptp.core.PtpStorageInfo;
import com.photographer.ptp.session.CameraCapabilities;
import com.photographer.ptp.session.ImageDownload;
import com.photographer.ptp.session.PtpCamera;

import java.time.Instant;

/**
 * Adapter: PtpCamera → CameraBackend interface.
 * Makes the PTP module compatible with the Focus Stack application.
 */
public class PtpCameraBackend implements CameraBackend {
    private final PtpCamera camera;
    private volatile boolean liveViewActive;

    public PtpCameraBackend(PtpCamera camera) {
        this.camera = camera;
    }

    @Override
    public CameraInfo getInfo() {
        PtpDeviceInfo info = camera.getDeviceInfo();
        return new CameraInfo(
                "ptp-" + info.serialNumber(),
                info.manufacturer() + " " + info.model(),
                info.model(),
                "ptp-usb",
                "usb-local",
                info.deviceVersion());
    }

    @Override
    public CaptureResult capture() {
        ImageDownload download = camera.capture();
        return new CaptureResult(
                download.objectId(),
                download.fileName(),
                "usb://local/" + download.fileName(),
                Instant.now(),
                0,
                download.size(),
                download.data());
    }

    @Override
    public LiveViewFrame getLiveView() {
        if (!liveViewActive) {
            camera.startLiveView();
            liveViewActive = true;
        }
        byte[] jpeg = camera.getLiveViewFrame();
        return new LiveViewFrame(jpeg, 960, 640, Instant.now(), "image/jpeg");
    }

    @Override
    public void setFocus(FocusCommand command) {
        int magnitude = mapStepsToMagnitude(command.steps());
        if (command.direction() == FocusCommand.Direction.NEAR) {
            camera.driveFocusNear(magnitude);
        } else {
            camera.driveFocusFar(magnitude);
        }
    }

    @Override
    public CameraState getState() {
        int battery = camera.getBatteryLevel();
        long storageAvailable = 0;
        try {
            int[] storageIds = camera.getSession().getStorageIds();
            if (storageIds.length > 0) {
                PtpStorageInfo storageInfo = camera.getSession().getStorageInfo(storageIds[0]);
                storageAvailable = storageInfo.freeSpaceInBytes();
            }
        } catch (Exception e) {
            // Storage info may not be available
        }
        String aperture = "";
        String shutterSpeed = "";
        String iso = "";
        try {
            aperture = camera.getAperture();
            shutterSpeed = camera.getShutterSpeed();
            iso = camera.getIso();
        } catch (Exception e) {
            // Properties may not be available
        }
        return new CameraState(
                true,
                battery,
                storageAvailable,
                0,
                shutterSpeed,
                aperture,
                iso,
                liveViewActive);
    }

    @Override
    public boolean supports(Operation operation) {
        CameraCapabilities caps = camera.getCapabilities();
        return switch (operation) {
            case LIVE_VIEW      -> caps.liveView();
            case CAPTURE        -> caps.capture();
            case FOCUS_CONTROL  -> caps.focusDrive();
            case ZOOM_CONTROL   -> caps.zoom();
            case SETTINGS_READ  -> caps.propertiesRead();
            case SETTINGS_WRITE -> caps.propertiesWrite();
        };
    }

    /**
     * Maps focus steps (1+) to Canon magnitude (1-3).
     * Small steps=1, medium steps=2-5, large steps=6+.
     */
    private static int mapStepsToMagnitude(int steps) {
        if (steps <= 1) return 1;
        if (steps <= 5) return 2;
        return 3;
    }

    /** Returns the underlying PtpCamera. */
    public PtpCamera getCamera() {
        return camera;
    }
}
