package com.photographer.ptp.session;

import com.photographer.ptp.canon.*;
import com.photographer.ptp.core.*;
import com.photographer.ptp.usb.PtpTransport;
import com.photographer.ptp.usb.UsbDeviceScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * High-level PTP camera API.
 * Represents a connected PTP camera, independent of transport (USB/IP/mock).
 */
public class PtpCamera implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PtpCamera.class);

    private final PtpTransport transport;
    private final PtpSession session;
    private final EventLoop eventLoop;
    private PtpDeviceInfo deviceInfo;
    private boolean isCanon;

    public PtpCamera(PtpTransport transport) {
        this.transport = transport;
        this.session = new PtpSession(transport);
        this.eventLoop = new EventLoop(transport);
    }

    /**
     * Opens a USB-connected Canon camera.
     * Convenience factory method.
     */
    public static PtpCamera openUsb() {
        PtpTransport transport = UsbDeviceScanner.findCanon();
        transport.open();
        PtpCamera camera = new PtpCamera(transport);
        camera.initialize();
        return camera;
    }

    /**
     * Opens a camera with the given transport (for testing/mock).
     */
    public static PtpCamera open(PtpTransport transport) {
        transport.open();
        PtpCamera camera = new PtpCamera(transport);
        camera.initialize();
        return camera;
    }

    private void initialize() {
        session.open();
        deviceInfo = session.getDeviceInfo();
        isCanon = deviceInfo.manufacturer() != null
                && deviceInfo.manufacturer().toLowerCase().contains("canon");

        if (isCanon) {
            setupCanonRemoteMode();
        }

        eventLoop.start();
        log.info("Camera initialized: {} {} (Canon: {})",
                deviceInfo.manufacturer(), deviceInfo.model(), isCanon);
    }

    private void setupCanonRemoteMode() {
        session.executeOperation(CanonConstants.OC_EOS_SET_REMOTE_MODE,
                CanonConstants.REMOTE_MODE_NORMAL);
        session.executeOperation(CanonConstants.OC_EOS_SET_EVENT_MODE, 1);
        log.debug("Canon remote mode enabled");
    }

    // ── Camera Information ──

    public PtpDeviceInfo getDeviceInfo() {
        return deviceInfo;
    }

    public CameraCapabilities getCapabilities() {
        if (deviceInfo == null) return CameraCapabilities.none();
        boolean hasLiveView = isCanon
                && deviceInfo.supportsOperation(CanonConstants.OC_EOS_GET_LIVEVIEW_DATA);
        boolean hasCapture = deviceInfo.supportsOperation(PtpConstants.OP_INITIATE_CAPTURE)
                || (isCanon && deviceInfo.supportsOperation(CanonConstants.OC_EOS_REMOTE_RELEASE_ON));
        boolean hasFocus = isCanon
                && deviceInfo.supportsOperation(CanonConstants.OC_EOS_AF_DRIVE);
        boolean hasPropsRead = deviceInfo.supportsOperation(PtpConstants.OP_GET_DEVICE_PROP_DESC);
        boolean hasPropsWrite = deviceInfo.supportsOperation(PtpConstants.OP_SET_DEVICE_PROP_VALUE);
        return new CameraCapabilities(hasLiveView, hasCapture, hasFocus, false,
                hasPropsRead, hasPropsWrite);
    }

    // ── Capture ──

    public ImageDownload capture() {
        if (isCanon) {
            CanonRemoteCapture remoteCapture = new CanonRemoteCapture(transport);
            remoteCapture.capture(session::nextTransactionId);
            // After capture, poll events for object-added
            // For now, return a placeholder; real implementation would poll GetEvent
            return new ImageDownload("capture", "capture.jpg", new byte[0]);
        }
        // Standard PTP capture
        PtpContainer response = session.executeOperation(PtpConstants.OP_INITIATE_CAPTURE, 0, 0);
        if (response.code() != PtpConstants.RC_OK) {
            throw new PtpException(response.code(),
                    "Capture failed: 0x%04X".formatted(response.code()));
        }
        return new ImageDownload("capture", "capture.jpg", new byte[0]);
    }

    public void captureToCard() {
        capture();
    }

    // ── Live View ──

    public void startLiveView() {
        if (!isCanon) {
            throw new PtpException("LiveView is only supported on Canon cameras");
        }
        session.executeOperation(CanonConstants.OC_EOS_SET_DEVICE_PROP,
                CanonConstants.DPC_EOS_EVF_OUTPUT, CanonConstants.EVF_OUTPUT_PC);
        log.info("LiveView started");
    }

    public byte[] getLiveViewFrame() {
        if (!isCanon) {
            throw new PtpException("LiveView is only supported on Canon cameras");
        }
        PtpTransport.DataResponse dr = session.executeReceiveData(
                CanonConstants.OC_EOS_GET_LIVEVIEW_DATA, 0x00100000);
        return CanonLiveViewDecoder.extractJpeg(dr.data());
    }

    public void stopLiveView() {
        if (!isCanon) return;
        session.executeOperation(CanonConstants.OC_EOS_SET_DEVICE_PROP,
                CanonConstants.DPC_EOS_EVF_OUTPUT, CanonConstants.EVF_OUTPUT_TFT);
        log.info("LiveView stopped");
    }

    // ── Focus ──

    public void driveFocusNear(int magnitude) {
        CanonFocusDrive drive = new CanonFocusDrive(transport);
        drive.driveNear(magnitude, session.nextTransactionId());
    }

    public void driveFocusFar(int magnitude) {
        CanonFocusDrive drive = new CanonFocusDrive(transport);
        drive.driveFar(magnitude, session.nextTransactionId());
    }

    // ── Properties ──

    public String getAperture() {
        if (isCanon) return CanonPropertyDecoder.decodeAperture(getCanonPropertyValue(CanonConstants.DPC_EOS_APERTURE));
        PtpDeviceProperty prop = session.getDevicePropertyDesc(CanonConstants.DPC_EOS_APERTURE);
        return String.valueOf(prop.currentValue());
    }

    public String getShutterSpeed() {
        if (isCanon) return CanonPropertyDecoder.decodeShutterSpeed(getCanonPropertyValue(CanonConstants.DPC_EOS_SHUTTER_SPEED));
        PtpDeviceProperty prop = session.getDevicePropertyDesc(CanonConstants.DPC_EOS_SHUTTER_SPEED);
        return String.valueOf(prop.currentValue());
    }

    public String getIso() {
        if (isCanon) return CanonPropertyDecoder.decodeIso(getCanonPropertyValue(CanonConstants.DPC_EOS_ISO));
        PtpDeviceProperty prop = session.getDevicePropertyDesc(CanonConstants.DPC_EOS_ISO);
        return String.valueOf(prop.currentValue());
    }

    public int getBatteryLevel() {
        if (isCanon) return CanonPropertyDecoder.decodeBatteryLevel(getCanonPropertyValue(CanonConstants.DPC_EOS_BATTERY_LEVEL));
        PtpDeviceProperty prop = session.getDevicePropertyDesc(CanonConstants.DPC_EOS_BATTERY_LEVEL);
        return prop.currentValue() instanceof Number n ? n.intValue() : 0;
    }

    private int getCanonPropertyValue(int propertyCode) {
        PtpDeviceProperty prop = session.getDevicePropertyDesc(propertyCode);
        return prop.currentValue() instanceof Number n ? n.intValue() : 0;
    }

    // ── Storage ──

    public List<StorageEntry> listImages() {
        List<StorageEntry> entries = new ArrayList<>();
        int[] storageIds = session.getStorageIds();
        for (int storageId : storageIds) {
            int[] handles = session.getObjectHandles(storageId);
            for (int handle : handles) {
                PtpObjectInfo info = session.getObjectInfo(handle);
                entries.add(new StorageEntry(
                        handle, info.filename(), info.objectFormat(),
                        info.compressedSize(), info.imagePixWidth(),
                        info.imagePixHeight(), info.captureDate()));
            }
        }
        return entries;
    }

    public byte[] downloadImage(int objectHandle) {
        return session.getObject(objectHandle);
    }

    public byte[] downloadThumbnail(int objectHandle) {
        return session.getThumb(objectHandle);
    }

    // ── Events ──

    public void setEventListener(PtpEventListener listener) {
        eventLoop.setListener(listener);
    }

    // ── Lifecycle ──

    public boolean isCanon() {
        return isCanon;
    }

    public PtpSession getSession() {
        return session;
    }

    @Override
    public void close() {
        eventLoop.stop();
        session.close();
        transport.close();
        log.info("Camera closed");
    }
}
