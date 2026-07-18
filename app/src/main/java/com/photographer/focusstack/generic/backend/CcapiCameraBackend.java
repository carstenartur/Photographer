package com.photographer.focusstack.generic.backend;

import com.photographer.focusstack.camera.backend.CanonCcapiBackend;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CameraInfo;
import com.photographer.focusstack.common.camera.CameraState;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.camera.LiveViewFrame;
import com.photographer.focusstack.common.camera.Operation;
import com.photographer.focusstack.common.capability.Capability;
import com.photographer.focusstack.common.capability.CapabilitySet;
import com.photographer.focusstack.common.device.BatteryInfo;
import com.photographer.focusstack.common.device.DeviceInfo;
import com.photographer.focusstack.common.device.LensInfo;
import com.photographer.focusstack.common.device.StorageInfo;
import com.photographer.focusstack.common.media.MediaItem;
import com.photographer.focusstack.common.media.TransferResult;
import com.photographer.focusstack.common.media.TransferState;
import com.photographer.focusstack.common.transport.CameraConnection;
import com.photographer.focusstack.common.transport.GenericCameraBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.util.List;
import java.util.Objects;

/**
 * Adapts the existing {@link CanonCcapiBackend} (and any other legacy
 * {@link CameraBackend}) to the transport-independent
 * {@link GenericCameraBackend} surface.
 *
 * <p>This is the seam that lets workflows migrate to the generic interface
 * incrementally. Capture, live view, battery and aggregate storage status are
 * delegated today. Lens details and media list/download/delete remain absent
 * from the generic adapter and are therefore not advertised as capabilities.</p>
 *
 * <p>Battery and storage are mapped from one {@link CameraState} snapshot per
 * adapter operation. The adapter does not duplicate Canon HTTP requests or
 * invent vendor details that the legacy state model does not expose. The
 * synthetic primary storage volume is intentionally transitional; issue #142
 * tracks the documented CCAPI {@code storagelist} multi-volume contract.</p>
 */
public class CcapiCameraBackend implements GenericCameraBackend {

    private static final Logger log = LoggerFactory.getLogger(CcapiCameraBackend.class);

    private final CameraBackend legacy;
    private final String cameraId;

    public CcapiCameraBackend(String cameraId, CameraBackend legacy) {
        this.cameraId = Objects.requireNonNull(cameraId, "cameraId");
        this.legacy = Objects.requireNonNull(legacy, "legacy");
    }

    @Override
    public CameraConnection connect() {
        log.debug("Opening CCAPI connection to {}", cameraId);
        return new SimpleConnection(cameraId, "canon-ccapi");
    }

    @Override
    public DeviceInfo getDeviceInfo(CameraConnection connection) {
        CameraInfo info = legacy.getInfo();
        return new DeviceInfo(
                cameraId,
                info.model(),
                /* serialNumber */ null,
                info.firmwareVersion(),
                "Canon",
                info.name(),
                "canon-ccapi",
                info.host()
        );
    }

    @Override
    public CapabilitySet getCapabilities(CameraConnection connection) {
        CapabilitySet.Builder capabilities = CapabilitySet.builder()
                .add(Capability.DEVICE_INFO);
        if (legacy.supports(Operation.LIVE_VIEW)) {
            capabilities.add(Capability.LIVE_VIEW);
        }
        if (legacy.supports(Operation.CAPTURE)) {
            capabilities.add(Capability.CAPTURE_STILL);
        }
        if (legacy.supports(Operation.FOCUS_CONTROL)) {
            capabilities.add(Capability.FOCUS_DRIVE);
        }
        if (legacy.supports(Operation.ZOOM_CONTROL)) {
            capabilities.add(Capability.ZOOM_CONTROL);
        }
        if (legacy.supports(Operation.SETTINGS_READ)) {
            capabilities.add(Capability.EXPOSURE_READ);
        }
        if (legacy.supports(Operation.SETTINGS_WRITE)) {
            capabilities.addRw(Capability.EXPOSURE_WRITE);
        }

        CameraState state = readStateSafely();
        if (hasKnownBattery(state)) {
            capabilities.add(Capability.BATTERY_INFO);
        }
        if (hasKnownStorage(state)) {
            capabilities.add(Capability.STORAGE_INFO);
        }
        return capabilities.build();
    }

    @Override
    public StorageInfo getStorageInfo(CameraConnection connection) {
        return toStorageInfo(readStateSafely());
    }

    @Override
    public BatteryInfo getBatteryInfo(CameraConnection connection) {
        return toBatteryInfo(readStateSafely());
    }

    @Override
    public LensInfo getLensInfo(CameraConnection connection) {
        // The legacy CameraState does not expose a lens identity or mount state.
        return LensInfo.none();
    }

    @Override
    public List<MediaItem> listMedia(CameraConnection connection) {
        // TODO: implement the version-aware /contents hierarchy through the legacy backend.
        return List.of();
    }

    @Override
    public TransferResult downloadMedia(CameraConnection connection, MediaItem item, OutputStream sink) {
        // TODO: expose version-aware contents download through the legacy backend.
        return TransferResult.failed(item, "downloadMedia not yet implemented for CCAPI adapter");
    }

    @Override
    public boolean deleteMedia(CameraConnection connection, MediaItem item) {
        // TODO: expose CCAPI contents DELETE after capability and protection checks.
        log.warn("deleteMedia: refusing — not yet implemented for CCAPI adapter, item={}", item.id());
        return false;
    }

    @Override
    public MediaItem captureStill(CameraConnection connection) {
        legacy.stopLiveView();
        CaptureResult result = legacy.capture();
        String id = result != null && result.imageId() != null
                ? result.imageId() : java.util.UUID.randomUUID().toString();
        String filename = result != null && result.fileName() != null ? result.fileName() : id;
        String path = result != null ? result.filePath() : null;
        long size = result != null ? result.fileSize() : -1L;
        return MediaItem.newDiscovered(id, cameraId, "primary", "DCIM",
                        filename, path, /* sourceUrl */ null, size)
                .withTransferState(TransferState.DISCOVERED);
    }

    @Override
    public void startLiveView(CameraConnection connection) {
        // The legacy interface starts live view implicitly on first frame request.
        legacy.getLiveView();
    }

    @Override
    public void stopLiveView(CameraConnection connection) {
        legacy.stopLiveView();
    }

    @Override
    public LiveViewFrame nextLiveViewFrame(CameraConnection connection) {
        return legacy.getLiveView();
    }

    private CameraState readStateSafely() {
        try {
            return legacy.getState();
        } catch (RuntimeException exception) {
            log.warn("Could not read CCAPI status for {}: {}", cameraId, exception.getMessage());
            return null;
        }
    }

    private static boolean hasKnownBattery(CameraState state) {
        return state != null && state.connected()
                && state.batteryLevel() >= 0 && state.batteryLevel() <= 100;
    }

    private static boolean hasKnownStorage(CameraState state) {
        return state != null && state.connected() && state.storageAvailable() >= 0L;
    }

    static BatteryInfo toBatteryInfo(CameraState state) {
        if (!hasKnownBattery(state)) {
            return BatteryInfo.unknown();
        }
        int percent = state.batteryLevel();
        String level;
        if (percent == 0) {
            level = "empty";
        } else if (percent <= 25) {
            level = "low";
        } else if (percent < 75) {
            level = "half";
        } else if (percent < 100) {
            level = "high";
        } else {
            level = "full";
        }
        return new BatteryInfo(level, "unknown", "unknown", percent);
    }

    static StorageInfo toStorageInfo(CameraState state) {
        if (!hasKnownStorage(state)) {
            return StorageInfo.empty();
        }
        StorageInfo.Volume primary = new StorageInfo.Volume(
                "primary",
                null,
                -1L,
                state.storageAvailable(),
                -1,
                "unknown"
        );
        return new StorageInfo(List.of(primary));
    }

    /** Lightweight connection holder; CCAPI keeps its HTTP state inside the legacy backend. */
    private static final class SimpleConnection implements CameraConnection {
        private final String cameraId;
        private final String transport;
        private volatile boolean open = true;

        private SimpleConnection(String cameraId, String transport) {
            this.cameraId = cameraId;
            this.transport = transport;
        }

        @Override
        public String cameraId() {
            return cameraId;
        }

        @Override
        public String transport() {
            return transport;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }
}
