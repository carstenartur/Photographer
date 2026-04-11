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

    /** Default timeout for waiting for a captured image event (ms). */
    public static final long DEFAULT_CAPTURE_TIMEOUT_MS = 15_000;
    /** Default interval between GetEvent polls during capture (ms). */
    public static final long DEFAULT_CAPTURE_POLL_INTERVAL_MS = 500;

    /**
     * Capture and timing configuration for {@link PtpCamera}.
     * Use {@link #defaults()} for production, or {@link #zeroDelays()} in unit tests
     * to avoid real {@code Thread.sleep} calls.
     */
    public record Config(
            long captureTimeoutMs,
            long capturePollIntervalMs,
            long halfPressDelayMs,
            long fullPressDelayMs,
            long releaseDelayMs) {

        /** Production defaults matching Canon SDK timing recommendations. */
        public static Config defaults() {
            return new Config(
                    DEFAULT_CAPTURE_TIMEOUT_MS,
                    DEFAULT_CAPTURE_POLL_INTERVAL_MS,
                    CanonRemoteCapture.DELAY_AFTER_HALF_PRESS,
                    CanonRemoteCapture.DELAY_AFTER_FULL_PRESS,
                    CanonRemoteCapture.DELAY_AFTER_RELEASE);
        }

        /** Zero-delay configuration suitable for unit tests with a mock transport. */
        public static Config zeroDelays() {
            return new Config(5_000, 10, 0, 0, 0);
        }
    }

    private final PtpTransport transport;
    private final PtpSession session;
    private final EventLoop eventLoop;
    private final Config config;
    private PtpDeviceInfo deviceInfo;
    private boolean isCanon;

    public PtpCamera(PtpTransport transport) {
        this(transport, Config.defaults());
    }

    public PtpCamera(PtpTransport transport, Config config) {
        this.transport = transport;
        this.config = config;
        this.session = new PtpSession(transport);
        this.eventLoop = new EventLoop(session);
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

    /**
     * Opens a camera with the given transport and custom config (for testing).
     */
    public static PtpCamera open(PtpTransport transport, Config config) {
        transport.open();
        PtpCamera camera = new PtpCamera(transport, config);
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

        eventLoop.setCanonMode(isCanon);
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
        boolean hasRawDownload = isCanon
                && deviceInfo.supportsOperation(CanonConstants.OC_EOS_GET_OBJECT);
        return new CameraCapabilities(hasLiveView, hasCapture, hasFocus, false,
                hasPropsRead, hasPropsWrite, hasRawDownload);
    }

    // ── Capture ──

    /**
     * Triggers the shutter and downloads the resulting image.
     * <p>
     * For Canon cameras, the 3-phase shutter sequence is used and events are
     * polled via {@code GetEvent} (0x9116) to find the new object handle. The image
     * is then downloaded via {@code GetObject}.
     * <p>
     * For standard PTP cameras, {@code InitiateCapture} is sent and the method
     * waits for an {@code EC_CAPTURE_COMPLETE} event before listing and downloading
     * the most recently added object.
     * <p>
     * On timeout, a warning is logged and an {@link ImageDownload} with empty data
     * is returned (graceful degradation).
     *
     * @return the downloaded image, or an empty-data result on timeout
     */
    public ImageDownload capture() {
        if (isCanon) {
            eventLoop.suspend();
            try {
                CanonRemoteCapture remoteCapture = new CanonRemoteCapture(
                        transport,
                        config.halfPressDelayMs(),
                        config.fullPressDelayMs(),
                        config.releaseDelayMs());
                remoteCapture.capture(session::nextTransactionId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PtpException("Canon capture interrupted");
            } finally {
                eventLoop.resume();
            }
            return pollForCapturedImageCanon();
        }

        // Standard PTP capture
        PtpContainer response = session.executeOperation(PtpConstants.OP_INITIATE_CAPTURE, 0, 0);
        if (response.code() != PtpConstants.RC_OK) {
            throw new PtpException(response.code(),
                    "Capture failed: 0x%04X".formatted(response.code()));
        }
        return pollForCapturedImageStandard();
    }

    /**
     * Triggers the shutter and stores the image on the camera's SD card
     * WITHOUT downloading it to the host.
     * Useful for focus-stacking workflows where the host only needs to count frames.
     */
    public void captureToCard() {
        if (isCanon) {
            eventLoop.suspend();
            try {
                CanonRemoteCapture remoteCapture = new CanonRemoteCapture(
                        transport,
                        config.halfPressDelayMs(),
                        config.fullPressDelayMs(),
                        config.releaseDelayMs());
                remoteCapture.capture(session::nextTransactionId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PtpException("Canon capture interrupted");
            } finally {
                eventLoop.resume();
            }
        } else {
            PtpContainer response = session.executeOperation(PtpConstants.OP_INITIATE_CAPTURE, 0, 0);
            if (response.code() != PtpConstants.RC_OK) {
                throw new PtpException(response.code(),
                        "Capture failed: 0x%04X".formatted(response.code()));
            }
        }
    }

    /**
     * Polls Canon GetEvent (0x9116) until an {@code EVENT_OBJ_ADDED} event is
     * received, then downloads the image.
     */
    private ImageDownload pollForCapturedImageCanon() {
        long deadline = System.currentTimeMillis() + config.captureTimeoutMs();
        while (System.currentTimeMillis() < deadline) {
            try {
                PtpTransport.DataResponse dr =
                        session.executeReceiveData(CanonConstants.OC_EOS_GET_EVENT);
                List<CanonEventParser.CanonEvent> events = CanonEventParser.parse(dr.data());
                for (CanonEventParser.CanonEvent event : events) {
                    if (event.isObjectAdded()) {
                        int objectHandle = event.getObjectHandle();
                        // Acknowledge the object to the camera
                        session.executeOperation(
                                CanonConstants.OC_EOS_TRANSFER_COMPLETE, objectHandle);
                        // Download the image
                        byte[] data = session.getObject(objectHandle);
                        PtpObjectInfo info = session.getObjectInfo(objectHandle);
                        log.info("Canon capture: downloaded {} bytes as {}",
                                data.length, info.filename());
                        return new ImageDownload(
                                String.valueOf(objectHandle), info.filename(), data);
                    }
                }
            } catch (PtpException e) {
                log.debug("GetEvent poll error: {}", e.getMessage());
            }
            try {
                Thread.sleep(config.capturePollIntervalMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.warn("Canon capture timeout: no image received within {} ms",
                config.captureTimeoutMs());
        return new ImageDownload("capture", "capture.jpg", new byte[0]);
    }

    /**
     * Waits for an {@code EC_CAPTURE_COMPLETE} event on the interrupt endpoint,
     * then lists object handles and downloads the latest one.
     */
    private ImageDownload pollForCapturedImageStandard() {
        long deadline = System.currentTimeMillis() + config.captureTimeoutMs();
        outer:
        while (System.currentTimeMillis() < deadline) {
            try {
                PtpContainer event = transport.readEvent(config.capturePollIntervalMs());
                if (event != null && event.code() == PtpConstants.EC_CAPTURE_COMPLETE) {
                    break outer;
                }
            } catch (PtpException e) {
                // Timeout or no event — continue waiting
            }
        }
        // Find the latest object handle across all storages
        try {
            int[] storageIds = session.getStorageIds();
            int latestHandle = 0;
            for (int storageId : storageIds) {
                int[] handles = session.getObjectHandles(storageId);
                if (handles.length > 0) {
                    latestHandle = handles[handles.length - 1];
                }
            }
            if (latestHandle > 0) {
                byte[] data = session.getObject(latestHandle);
                PtpObjectInfo info = session.getObjectInfo(latestHandle);
                log.info("Standard PTP capture: downloaded {} bytes as {}",
                        data.length, info.filename());
                return new ImageDownload(
                        String.valueOf(latestHandle), info.filename(), data);
            }
        } catch (PtpException e) {
            log.warn("Failed to retrieve captured image: {}", e.getMessage());
        }
        log.warn("Standard PTP capture: no image found after capture");
        return new ImageDownload("capture", "capture.jpg", new byte[0]);
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
