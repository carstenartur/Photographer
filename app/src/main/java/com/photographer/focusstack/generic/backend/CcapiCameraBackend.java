package com.photographer.focusstack.generic.backend;

import com.photographer.focusstack.camera.backend.CanonCcapiBackend;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CameraInfo;
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
import com.photographer.focusstack.common.media.MediaType;
import com.photographer.focusstack.common.media.TransferResult;
import com.photographer.focusstack.common.media.TransferState;
import com.photographer.focusstack.common.transport.CameraConnection;
import com.photographer.focusstack.common.transport.GenericCameraBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.util.List;

/**
 * Adapts the existing {@link CanonCcapiBackend} (and any other legacy
 * {@link CameraBackend}) to the new transport-independent
 * {@link GenericCameraBackend} surface.
 *
 * <p>This is the seam that lets us migrate workflows over to the generic
 * interface incrementally: today only {@code captureStill} and live-view
 * actually delegate; the richer endpoints (storage / battery / lens / media
 * list / download / delete) are wired up to safe defaults and marked
 * <strong>missing</strong> in the {@link CapabilitySet}, so UI code that
 * binds to capabilities will simply not show those buttons until the real
 * implementation lands.
 *
 * <p>TODO: when the CCAPI media-list / storage / battery probes are added
 * to {@link CanonCcapiBackend}, drop the {@code unsupported} returns below
 * and add the corresponding entries to {@link #getCapabilities}.
 */
public class CcapiCameraBackend implements GenericCameraBackend {

    private static final Logger log = LoggerFactory.getLogger(CcapiCameraBackend.class);

    private final CameraBackend legacy;
    private final String cameraId;

    public CcapiCameraBackend(String cameraId, CameraBackend legacy) {
        this.cameraId = cameraId;
        this.legacy = legacy;
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
        CapabilitySet.Builder b = CapabilitySet.builder()
                .add(Capability.DEVICE_INFO);
        if (legacy.supports(Operation.LIVE_VIEW))      b.add(Capability.LIVE_VIEW);
        if (legacy.supports(Operation.CAPTURE))        b.add(Capability.CAPTURE_STILL);
        if (legacy.supports(Operation.FOCUS_CONTROL))  b.add(Capability.FOCUS_DRIVE);
        if (legacy.supports(Operation.ZOOM_CONTROL))   b.add(Capability.ZOOM_CONTROL);
        if (legacy.supports(Operation.SETTINGS_READ))  b.add(Capability.EXPOSURE_READ);
        if (legacy.supports(Operation.SETTINGS_WRITE)) b.addRw(Capability.EXPOSURE_WRITE);
        // TODO: list storage/battery/lens/media capabilities once the legacy
        //       backend exposes them. The adapter is the single switch-on
        //       point — UI code will pick them up automatically.
        return b.build();
    }

    @Override
    public StorageInfo getStorageInfo(CameraConnection connection) {
        // TODO: wire to CanonCcapiBackend's /devicestatus/storage probe.
        return StorageInfo.empty();
    }

    @Override
    public BatteryInfo getBatteryInfo(CameraConnection connection) {
        // TODO: wire to CanonCcapiBackend's /devicestatus/battery probe.
        return BatteryInfo.unknown();
    }

    @Override
    public LensInfo getLensInfo(CameraConnection connection) {
        // TODO: wire to CanonCcapiBackend's /devicestatus/lens probe.
        return LensInfo.none();
    }

    @Override
    public List<MediaItem> listMedia(CameraConnection connection) {
        // TODO: implement /contents listing through the legacy backend.
        return List.of();
    }

    @Override
    public TransferResult downloadMedia(CameraConnection connection, MediaItem item, OutputStream sink) {
        // TODO: wire to CanonCcapiBackend's contents download.
        return TransferResult.failed(item, "downloadMedia not yet implemented for CCAPI adapter");
    }

    @Override
    public boolean deleteMedia(CameraConnection connection, MediaItem item) {
        // TODO: wire to CanonCcapiBackend modify-contents (DELETE).
        log.warn("deleteMedia: refusing — not yet implemented for CCAPI adapter, item={}", item.id());
        return false;
    }

    @Override
    public MediaItem captureStill(CameraConnection connection) {
        legacy.stopLiveView();
        CaptureResult r = legacy.capture();
        String id       = r != null && r.imageId() != null ? r.imageId() : java.util.UUID.randomUUID().toString();
        String filename = r != null && r.fileName() != null ? r.fileName() : id;
        String path     = r != null ? r.filePath() : null;
        long size       = r != null ? r.fileSize() : -1L;
        return MediaItem.newDiscovered(id, cameraId, "primary", "DCIM",
                filename, path, /* sourceUrl */ null, size)
                .withTransferState(TransferState.DISCOVERED);
    }

    @Override
    public void startLiveView(CameraConnection connection) {
        // The legacy interface starts live-view implicitly on first frame request.
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

    /** Lightweight connection holder; CCAPI keeps its own HTTP state inside the legacy backend. */
    private static final class SimpleConnection implements CameraConnection {
        private final String cameraId;
        private final String transport;
        private volatile boolean open = true;
        SimpleConnection(String cameraId, String transport) { this.cameraId = cameraId; this.transport = transport; }
        @Override public String cameraId() { return cameraId; }
        @Override public String transport() { return transport; }
        @Override public boolean isOpen() { return open; }
        @Override public void close() { open = false; }
    }
}
