package com.photographer.ptp.session;

import com.photographer.ptp.canon.*;
import com.photographer.ptp.core.*;
import com.photographer.ptp.usb.PtpTransport;
import com.photographer.ptp.usb.UsbDeviceScanner;
import com.photographer.ptp.wpd.WpdDeviceScanner;
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
     * <p>
     * On <b>Windows</b>, this method first tries the native libusb transport
     * (requires WinUSB driver).  If that fails (e.g. the default MTP driver is
     * loaded), it automatically falls back to the <b>Windows Portable Devices
     * (WPD)</b> API, which works with the standard MTP driver — no driver
     * changes required.
     * <p>
     * On Linux / macOS, libusb is used directly.
     */
    public static PtpCamera openUsb() {
        PtpException usbError = null;

        // Try libusb first (works on all platforms if driver is correct)
        try {
            PtpTransport transport = UsbDeviceScanner.findCanon();
            return openInitializedCamera(transport, Config.defaults());
        } catch (PtpException e) {
            usbError = e;
            log.debug("libusb transport failed: {}", e.getMessage());
        }

        // On Windows: fall back to WPD (uses the standard MTP driver)
        if (WpdDeviceScanner.isAvailable()) {
            log.info("Falling back to Windows Portable Devices (WPD) transport");
            try {
                PtpTransport transport = WpdDeviceScanner.wakeAndFindCanon();
                return openInitializedCamera(transport, Config.defaults());
            } catch (PtpException wpdError) {
                log.error("WPD transport also failed: {}", wpdError.getMessage());
                usbError.addSuppressed(wpdError);
            }
        }

        throw usbError;
    }

    /**
     * Opens a camera with the given transport (for testing/mock).
     */
    public static PtpCamera open(PtpTransport transport) {
        return openInitializedCamera(transport, Config.defaults());
    }

    /**
     * Opens a camera with the given transport and custom config (for testing).
     */
    public static PtpCamera open(PtpTransport transport, Config config) {
        return openInitializedCamera(transport, config);
    }

    private static PtpCamera openInitializedCamera(PtpTransport transport, Config config) {
        PtpCamera camera = null;
        try {
            transport.open();
            camera = new PtpCamera(transport, config);
            camera.initialize();
            return camera;
        } catch (RuntimeException | Error e) {
            try {
                if (camera != null) {
                    camera.close();
                } else {
                    transport.close();
                }
            } catch (RuntimeException | Error closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    private void initialize() {
        session.open();
        deviceInfo = session.getDeviceInfo();
        isCanon = deviceInfo.manufacturer() != null
                && deviceInfo.manufacturer().toLowerCase().contains("canon");

        if (isCanon) {
            setupCanonRemoteMode();
            seedPropertyCache();
        }

        eventLoop.setCanonMode(isCanon);
        eventLoop.start();
        log.info("Camera initialized: {} {} (Canon: {})",
                deviceInfo.manufacturer(), deviceInfo.model(), isCanon);
    }

    /**
     * Performs an initial GetEvent call to populate the property cache before the
     * event loop starts.  Canon cameras deliver all current property values in
     * the first GetEvent response after SetRemoteMode/SetEventMode.
     */
    private void seedPropertyCache() {
        try {
            PtpTransport.DataResponse dr = session.executeReceiveData(CanonConstants.OC_EOS_GET_EVENT);
            if (dr.response().code() == PtpConstants.RC_OK && dr.data() != null) {
                List<CanonEventParser.CanonEvent> events = CanonEventParser.parse(dr.data());
                int cached = 0;
                for (CanonEventParser.CanonEvent event : events) {
                    if (event.isPropertyChanged()) {
                        eventLoop.cacheProperty(event.getPropertyCode(), event.getPropertyValue());
                        cached++;
                    }
                }
                log.debug("Seeded property cache with {} values from initial GetEvent", cached);
            }
        } catch (Exception e) {
            log.debug("Initial GetEvent for property seeding failed: {}", e.getMessage());
        }
    }

    private void setupCanonRemoteMode() {
        PtpContainer remoteModeResponse = session.executeOperation(
                CanonConstants.OC_EOS_SET_REMOTE_MODE,
                CanonConstants.REMOTE_MODE_NORMAL);
        requireOkResponse("Canon remote mode", remoteModeResponse);

        PtpContainer eventModeResponse = session.executeOperation(
                CanonConstants.OC_EOS_SET_EVENT_MODE, 1);
        requireOkResponse("Canon event mode", eventModeResponse);

        log.debug("Canon remote mode enabled");
    }

    private void requireOkResponse(String operationName, PtpContainer response) {
        if (response == null) {
            throw new PtpException(operationName + " failed: null response");
        }
        if (response.code() != PtpConstants.RC_OK) {
            throw new PtpException(response.code(),
                    operationName + " failed with response code 0x"
                            + Integer.toHexString(response.code()));
        }
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
        boolean hasPropsWrite = deviceInfo.supportsOperation(PtpConstants.OP_SET_DEVICE_PROP_VALUE)
                || (isCanon && deviceInfo.supportsOperation(CanonConstants.OC_EOS_SET_DEVICE_PROP));
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
                return pollForCapturedImageCanon();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PtpException("Canon capture interrupted");
            } finally {
                eventLoop.resume();
            }
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
     * Polls Canon GetEvent (0x9116) until an {@code EVENT_OBJ_ADDED} or
     * {@code EVENT_OBJ_INFO_EX} event is received, then downloads the image.
     *
     * <p>On EOS R-series cameras, a single capture produces two events (CR3 + JPEG
     * sidecar).  We collect all object handles from the batch and return the
     * largest download (the CR3).
     */
    private ImageDownload pollForCapturedImageCanon() {
        long deadline = System.currentTimeMillis() + config.captureTimeoutMs();
        while (System.currentTimeMillis() < deadline) {
            try {
                PtpTransport.DataResponse dr =
                        session.executeReceiveData(CanonConstants.OC_EOS_GET_EVENT);
                PtpOperationResponse.from(dr.response()).checkOk();
                List<CanonEventParser.CanonEvent> events = CanonEventParser.parse(dr.data());

                // Collect all object handles from this event batch
                List<Integer> objectHandles = new java.util.ArrayList<>();
                for (CanonEventParser.CanonEvent event : events) {
                    log.debug("Canon event: type=0x{}, payloadLen={}",
                            String.format("%04X", event.type()), event.payload().length);
                    if (event.isObjectAdded()) {
                        int handle = event.getObjectHandle();
                        log.info("Canon object-added event: handle=0x{}, eventType=0x{}",
                                Integer.toHexString(handle), String.format("%04X", event.type()));
                        objectHandles.add(handle);
                    }
                }

                if (!objectHandles.isEmpty()) {
                    // Download all objects, pick the largest (typically the CR3)
                    ImageDownload best = null;
                    for (int objectHandle : objectHandles) {
                        try {
                            // Try Canon vendor command first, fall back to standard PTP
                            byte[] data = null;
                            try {
                                data = session.getCanonObject(objectHandle);
                            } catch (PtpException e) {
                                log.debug("Canon GetObject failed for 0x{}, trying standard PTP: {}",
                                        Integer.toHexString(objectHandle), e.getMessage());
                            }
                            if (data == null || data.length <= 8) {
                                log.debug("Canon GetObject returned {} bytes, trying standard PTP GetObject",
                                        data != null ? data.length : 0);
                                data = session.getObject(objectHandle);
                            }
                            String filename = "object_" + Integer.toHexString(objectHandle);
                            try {
                                PtpObjectInfo info = session.getObjectInfo(objectHandle);
                                filename = info.filename();
                            } catch (PtpException e) {
                                log.debug("Could not get ObjectInfo for 0x{}: {}",
                                        Integer.toHexString(objectHandle), e.getMessage());
                            }
                            log.info("Canon capture: downloaded {} bytes as {}",
                                    data.length, filename);

                            // Acknowledge the object to the camera
                            try {
                                session.executeOperation(
                                        CanonConstants.OC_EOS_TRANSFER_COMPLETE, objectHandle);
                            } catch (PtpException e) {
                                log.debug("TransferComplete for 0x{}: {}",
                                        Integer.toHexString(objectHandle), e.getMessage());
                            }

                            if (best == null || data.length > best.size()) {
                                best = new ImageDownload(
                                        String.valueOf(objectHandle), filename, data);
                            }
                        } catch (PtpException e) {
                            log.warn("Failed to download object 0x{}: {}",
                                    Integer.toHexString(objectHandle), e.getMessage());
                        }
                    }
                    if (best != null && best.data().length > 0) {
                        return best;
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
        eventLoop.suspend();
        try {
            // Canon EOS requires enabling EVF mode first, then routing output to PC
            setCanonProperty(CanonConstants.DPC_EOS_EVF_MODE, 1);
            setCanonProperty(CanonConstants.DPC_EOS_EVF_OUTPUT, CanonConstants.EVF_OUTPUT_PC);
        } finally {
            eventLoop.resume();
        }
        log.info("LiveView started");
    }

    public byte[] getLiveViewFrame() {
        if (!isCanon) {
            throw new PtpException("LiveView is only supported on Canon cameras");
        }
        eventLoop.suspend();
        try {
            PtpTransport.DataResponse dr = session.executeReceiveData(
                    CanonConstants.OC_EOS_GET_LIVEVIEW_DATA, 0x00100000);
            return CanonLiveViewDecoder.extractJpeg(dr.data());
        } finally {
            eventLoop.resume();
        }
    }

    public void stopLiveView() {
        if (!isCanon) return;
        eventLoop.suspend();
        try {
            setCanonProperty(CanonConstants.DPC_EOS_EVF_OUTPUT, CanonConstants.EVF_OUTPUT_TFT);
            setCanonProperty(CanonConstants.DPC_EOS_EVF_MODE, 0);
        } finally {
            eventLoop.resume();
        }
        log.info("LiveView stopped");
    }

    // ── Focus ──

    private void ensureCanonFocusDriveSupported() {
        if (!isCanon) {
            throw new PtpException("Focus drive is only supported on Canon cameras");
        }
    }

    public void driveFocusNear(int magnitude) {
        ensureCanonFocusDriveSupported();
        eventLoop.suspend();
        try {
            CanonFocusDrive drive = new CanonFocusDrive(transport);
            drive.driveNear(magnitude, session.nextTransactionId());
        } finally {
            eventLoop.resume();
        }
    }

    public void driveFocusFar(int magnitude) {
        ensureCanonFocusDriveSupported();
        eventLoop.suspend();
        try {
            CanonFocusDrive drive = new CanonFocusDrive(transport);
            drive.driveFar(magnitude, session.nextTransactionId());
        } finally {
            eventLoop.resume();
        }
    }

    // ── Properties ──

    public String getAperture() {
        if (isCanon) return CanonPropertyDecoder.decodeAperture(getCanonPropertyValue(CanonConstants.DPC_EOS_APERTURE));
        return "Unsupported";
    }

    public int getApertureRaw() {
        if (isCanon) return getCanonPropertyValue(CanonConstants.DPC_EOS_APERTURE);
        return 0;
    }

    public String getShutterSpeed() {
        if (isCanon) return CanonPropertyDecoder.decodeShutterSpeed(getCanonPropertyValue(CanonConstants.DPC_EOS_SHUTTER_SPEED));
        return "Unsupported";
    }

    public int getShutterSpeedRaw() {
        if (isCanon) return getCanonPropertyValue(CanonConstants.DPC_EOS_SHUTTER_SPEED);
        return 0;
    }

    public String getIso() {
        if (isCanon) return CanonPropertyDecoder.decodeIso(getCanonPropertyValue(CanonConstants.DPC_EOS_ISO));
        return "Unsupported";
    }

    public int getIsoRaw() {
        if (isCanon) return getCanonPropertyValue(CanonConstants.DPC_EOS_ISO);
        return 0;
    }

    public String getExposureCompensation() {
        if (isCanon) return CanonPropertyDecoder.decodeExposureCompensation(
                getCanonPropertyValue(CanonConstants.DPC_EOS_EXPOSURE_COMPENSATION));
        return "0";
    }

    public int getExposureCompensationRaw() {
        if (isCanon) return getCanonPropertyValue(CanonConstants.DPC_EOS_EXPOSURE_COMPENSATION);
        return 0;
    }

    // ── Property setters ──

    /** Sets the aperture (Canon EOS raw value). */
    public void setAperture(int rawValue) {
        if (!isCanon) throw new PtpException("Setting aperture not supported on non-Canon cameras");
        setCanonProperty(CanonConstants.DPC_EOS_APERTURE, rawValue);
        log.info("Aperture set to {} (raw=0x{} )", CanonPropertyDecoder.decodeAperture(rawValue),
                Integer.toHexString(rawValue));
    }

    /** Sets the shutter speed (Canon EOS raw value). */
    public void setShutterSpeed(int rawValue) {
        if (!isCanon) throw new PtpException("Setting shutter speed not supported on non-Canon cameras");
        setCanonProperty(CanonConstants.DPC_EOS_SHUTTER_SPEED, rawValue);
        log.info("Shutter speed set to {} (raw=0x{})", CanonPropertyDecoder.decodeShutterSpeed(rawValue),
                Integer.toHexString(rawValue));
    }

    /** Sets the ISO (Canon EOS raw value). */
    public void setIso(int rawValue) {
        if (!isCanon) throw new PtpException("Setting ISO not supported on non-Canon cameras");
        setCanonProperty(CanonConstants.DPC_EOS_ISO, rawValue);
        log.info("ISO set to {} (raw=0x{})", CanonPropertyDecoder.decodeIso(rawValue),
                Integer.toHexString(rawValue));
    }

    public int getBatteryLevel() {
        if (isCanon) return CanonPropertyDecoder.decodeBatteryLevel(getCanonPropertyValue(CanonConstants.DPC_EOS_BATTERY_LEVEL));
        return -1;
    }

    private int getCanonPropertyValue(int propertyCode) {
        // Canon EOS cameras deliver properties through GetEvent (0x9116).
        // The EventLoop caches property values in the background — read from there.
        return eventLoop.getPropertyValue(propertyCode);
    }

    /**
     * Reads a Canon EOS device property's last-known value from the
     * background event-loop cache. Returns {@code 0} if the camera has not
     * yet emitted that property in a GetEvent (0x9116) batch.
     *
     * <p>Public so research/diagnostics integration tests can inspect
     * properties that do not yet have a typed accessor (e.g.
     * {@link CanonConstants#DPC_EOS_IMAGE_QUALITY 0xD108 ImageQuality}).</p>
     */
    public int getCanonPropertyRaw(int propertyCode) {
        if (!isCanon) return 0;
        return getCanonPropertyValue(propertyCode);
    }

    /**
     * Sets a Canon EOS device property via the data-write phase.
     * Canon's SetDevicePropValueEx (0x9110) sends a data payload with format:
     * {@code [4-byte totalSize][4-byte propCode][4-byte value]}
     * where totalSize includes itself (= 0x0C for a single UINT32 property).
     *
     * <p>Public so callers (e.g. integration tests) can set arbitrary EOS
     * properties such as image quality, white balance, etc.</p>
     */
    public void setCanonProperty(int propertyCode, int value) {
        byte[] data = new byte[12];
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(data)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buf.putInt(12);            // totalSize (includes this field)
        buf.putInt(propertyCode);
        buf.putInt(value);
        // Suspend the background event loop so its GetEvent (0x9116) poll does
        // not collide with our SetDevicePropValueEx and trigger Device_Busy
        // (0x2019). The event loop processes a single transaction at a time,
        // and the camera rejects overlapping vendor commands.
        eventLoop.suspend();
        try {
            PtpContainer response = session.executeOperationWithData(
                    CanonConstants.OC_EOS_SET_DEVICE_PROP, data);
            requireOkResponse("SetCanonProperty 0x" + Integer.toHexString(propertyCode), response);
        } finally {
            eventLoop.resume();
        }
    }

    // ── Storage ──

    public List<StorageEntry> listImages() {
        eventLoop.suspend();
        try {
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
        } finally {
            eventLoop.resume();
        }
    }

    public byte[] downloadImage(int objectHandle) {
        eventLoop.suspend();
        try {
            return session.getObject(objectHandle);
        } finally {
            eventLoop.resume();
        }
    }

    public byte[] downloadThumbnail(int objectHandle) {
        eventLoop.suspend();
        try {
            return session.getThumb(objectHandle);
        } finally {
            eventLoop.resume();
        }
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
