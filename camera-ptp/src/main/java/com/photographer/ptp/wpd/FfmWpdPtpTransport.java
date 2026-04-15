package com.photographer.ptp.wpd;

import com.photographer.ptp.core.PtpConstants;
import com.photographer.ptp.core.PtpContainer;
import com.photographer.ptp.core.PtpException;
import com.photographer.ptp.usb.PtpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * PTP transport via the Windows Portable Devices (WPD) COM API, implemented
 * with the <b>Foreign Function &amp; Memory API</b> (JEP 454, Java 22+).
 * <p>
 * Replaces the former JNA-based {@link WpdPtpTransport}. Key improvements:
 * <ul>
 *   <li>No JNA dependency — uses only {@code java.lang.foreign} from the JDK</li>
 *   <li>Deterministic native memory management with {@link java.lang.foreign.Arena}</li>
 *   <li>Type-safe COM vtable calls via {@link java.lang.invoke.MethodHandle}</li>
 * </ul>
 * <p>
 * This transport uses the <b>existing MTP driver</b> ({@code wpdmtp.inf}) that
 * Windows loads for PTP cameras by default — <b>no Zadig or WinUSB driver
 * installation required</b>.
 */
public class FfmWpdPtpTransport implements PtpTransport {

    private static final Logger log = LoggerFactory.getLogger(FfmWpdPtpTransport.class);

    private final String deviceId;
    private MemorySegment pDevice;   // IPortableDevice*
    private volatile boolean open;
    private final BlockingQueue<PtpContainer> eventQueue = new LinkedBlockingQueue<>();

    public FfmWpdPtpTransport(String deviceId) {
        this.deviceId = deviceId;
    }

    @Override
    public void open() throws PtpException {
        try {
            pDevice = FfmWpdNative.openDevice(deviceId);
            open = true;
            log.info("FFM WPD PTP transport opened for device: {}", deviceId);
        } catch (Exception e) {
            throw new PtpException("Failed to open FFM WPD transport for " + deviceId, e);
        }
    }

    @Override
    public PtpContainer execute(PtpContainer request) throws PtpException {
        checkOpen();
        byte[] responseBytes = FfmWpdNative.sendCommand(pDevice, request.code(),
                extractParams(request), null, false);
        return parseWpdResponse(responseBytes);
    }

    @Override
    public PtpContainer executeWithData(PtpContainer request, byte[] data) throws PtpException {
        checkOpen();
        byte[] responseBytes = FfmWpdNative.sendCommand(pDevice, request.code(),
                extractParams(request), data, false);
        return parseWpdResponse(responseBytes);
    }

    @Override
    public DataResponse executeReceiveData(PtpContainer request) throws PtpException {
        checkOpen();
        byte[] result = FfmWpdNative.sendCommand(pDevice, request.code(),
                extractParams(request), null, true);

        // FfmWpdNative returns: [4-byte response code][4-byte param count][params...][data...]
        if (result == null || result.length < 8) {
            PtpContainer resp = PtpContainer.createResponse(PtpConstants.RC_GENERAL_ERROR, 0);
            return new DataResponse(new byte[0], resp);
        }

        ByteBuffer buf = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        int responseCode = buf.getInt();
        int paramCount = buf.getInt();
        int[] params = new int[paramCount];
        for (int i = 0; i < paramCount; i++) {
            params[i] = buf.getInt();
        }
        byte[] data = new byte[buf.remaining()];
        buf.get(data);

        PtpContainer resp = PtpContainer.createResponse(responseCode, 0, params);
        return new DataResponse(data, resp);
    }

    @Override
    public PtpContainer readEvent(long timeoutMs) throws PtpException {
        checkOpen();
        try {
            PtpContainer event = eventQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (event == null) {
                throw new PtpException("Event read timed out after " + timeoutMs + " ms");
            }
            return event;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PtpException("Event read interrupted", e);
        }
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        if (pDevice != null) {
            try {
                FfmWpdNative.closeDevice(pDevice);
            } catch (Exception e) {
                log.warn("Error closing FFM WPD device", e);
            }
            pDevice = null;
        }
        open = false;
    }

    /**
     * Injects an event into the event queue (used by the event callback).
     */
    public void injectEvent(PtpContainer event) {
        eventQueue.offer(event);
    }

    private void checkOpen() {
        if (!open) {
            throw new PtpException("FFM WPD transport is not open");
        }
    }

    private int[] extractParams(PtpContainer request) {
        return request.getParams();
    }

    private PtpContainer parseWpdResponse(byte[] responseBytes) {
        if (responseBytes == null || responseBytes.length < 4) {
            return PtpContainer.createResponse(PtpConstants.RC_GENERAL_ERROR, 0);
        }
        ByteBuffer buf = ByteBuffer.wrap(responseBytes).order(ByteOrder.LITTLE_ENDIAN);
        int responseCode = buf.getInt();
        int paramCount = buf.remaining() / 4;
        int[] params = new int[paramCount];
        for (int i = 0; i < paramCount; i++) {
            params[i] = buf.getInt();
        }
        return PtpContainer.createResponse(responseCode, 0, params);
    }
}
