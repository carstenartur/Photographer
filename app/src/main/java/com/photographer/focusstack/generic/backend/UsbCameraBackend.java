package com.photographer.focusstack.generic.backend;

import com.photographer.focusstack.common.camera.LiveViewFrame;
import com.photographer.focusstack.common.capability.Capability;
import com.photographer.focusstack.common.capability.CapabilitySet;
import com.photographer.focusstack.common.device.BatteryInfo;
import com.photographer.focusstack.common.device.DeviceInfo;
import com.photographer.focusstack.common.device.LensInfo;
import com.photographer.focusstack.common.device.StorageInfo;
import com.photographer.focusstack.common.media.MediaItem;
import com.photographer.focusstack.common.media.TransferResult;
import com.photographer.focusstack.common.transport.CameraConnection;
import com.photographer.focusstack.common.transport.GenericCameraBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.util.List;

/**
 * USB / PTP backend skeleton.
 *
 * <p>USB (libusb + PTP) is already partially wired through the
 * {@code camera-ptp} module ({@code PtpCameraBackendFactory}). This adapter
 * exists so the GUI/workflow layer can target the new generic interface and
 * pick up USB cameras as a first-class transport once the PTP feature
 * surface (storage list, content list, transfer, delete) is fleshed out.
 *
 * <p><strong>TODO</strong> Items currently stubbed:
 * <ul>
 *   <li>{@link #getDeviceInfo} — read PTP device-info opcode (model, serial, firmware).</li>
 *   <li>{@link #getStorageInfo} — {@code GetStorageIDs} + {@code GetStorageInfo}.</li>
 *   <li>{@link #getBatteryInfo} — {@code BatteryLevel} device property.</li>
 *   <li>{@link #listMedia}, {@link #downloadMedia}, {@link #deleteMedia} — object-handle iteration.</li>
 *   <li>{@link #captureStill}, live-view — vendor-specific (Canon: Initiate-Capture + bulk read).</li>
 * </ul>
 *
 * <p>The capability set returned today reports only what the skeleton can
 * honour, which means the UI will gracefully hide unsupported actions.
 */
public class UsbCameraBackend implements GenericCameraBackend {

    private static final Logger log = LoggerFactory.getLogger(UsbCameraBackend.class);

    private final String cameraId;

    public UsbCameraBackend(String cameraId) {
        this.cameraId = cameraId;
    }

    @Override
    public CameraConnection connect() {
        log.info("USB backend: opening connection to {} (stub)", cameraId);
        return new SimpleUsbConnection(cameraId);
    }

    @Override
    public DeviceInfo getDeviceInfo(CameraConnection connection) {
        // TODO: bridge to camera-ptp PtpCamera.getDeviceInfo().
        return DeviceInfo.minimal(cameraId, "USB camera (stub)", "usb-ptp");
    }

    @Override
    public CapabilitySet getCapabilities(CameraConnection connection) {
        return CapabilitySet.builder()
                .add(Capability.DEVICE_INFO)
                // TODO: derive remaining capabilities from PTP DeviceInfo.OperationsSupported.
                .build();
    }

    @Override public StorageInfo getStorageInfo(CameraConnection connection) { return StorageInfo.empty(); }
    @Override public BatteryInfo getBatteryInfo(CameraConnection connection) { return BatteryInfo.unknown(); }
    @Override public LensInfo    getLensInfo(CameraConnection connection)    { return LensInfo.none();     }
    @Override public List<MediaItem> listMedia(CameraConnection connection)  { return List.of();           }

    @Override
    public TransferResult downloadMedia(CameraConnection connection, MediaItem item, OutputStream sink) {
        return TransferResult.failed(item, "USB downloadMedia not yet implemented");
    }

    @Override
    public boolean deleteMedia(CameraConnection connection, MediaItem item) {
        log.warn("USB deleteMedia: refusing — not yet implemented, item={}", item.id());
        return false;
    }

    @Override
    public MediaItem captureStill(CameraConnection connection) {
        // TODO: bridge to camera-ptp PtpCamera.initiateCapture().
        throw new UnsupportedOperationException("USB captureStill not yet implemented");
    }

    @Override public void startLiveView(CameraConnection connection) { /* TODO */ }
    @Override public void stopLiveView(CameraConnection connection)  { /* TODO */ }
    @Override public LiveViewFrame nextLiveViewFrame(CameraConnection connection) { return null; }

    private static final class SimpleUsbConnection implements CameraConnection {
        private final String cameraId;
        private volatile boolean open = true;
        SimpleUsbConnection(String cameraId) { this.cameraId = cameraId; }
        @Override public String cameraId() { return cameraId; }
        @Override public String transport() { return "usb-ptp"; }
        @Override public boolean isOpen() { return open; }
        @Override public void close() { open = false; }
    }
}
