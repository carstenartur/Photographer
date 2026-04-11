package com.photographer.ptp.usb;

import com.photographer.ptp.core.PtpConstants;
import com.photographer.ptp.core.PtpContainer;
import com.photographer.ptp.core.PtpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.usb.*;
import java.util.List;

/**
 * USB transport implementation for PTP using usb4java (javax.usb API).
 * Communicates with PTP/Still Image Class devices via bulk and interrupt endpoints.
 *
 * <p>This implementation requires a USB camera to be physically connected.
 * For testing without hardware, use {@code MockPtpTransport} instead.</p>
 */
public class UsbPtpTransport implements PtpTransport {
    private static final Logger log = LoggerFactory.getLogger(UsbPtpTransport.class);

    private final UsbDevice device;
    private UsbInterface usbInterface;
    private UsbEndpoint bulkIn;
    private UsbEndpoint bulkOut;
    private UsbEndpoint interruptIn;
    private volatile boolean open;

    public UsbPtpTransport(UsbDevice device) {
        this.device = device;
    }

    @Override
    public void open() throws PtpException {
        try {
            UsbConfiguration config = device.getActiveUsbConfiguration();
            if (config == null) {
                throw new PtpException("No active USB configuration on device "
                        + "(device may need to be configured first)");
            }
            usbInterface = findPtpInterface(config);
            usbInterface.claim();

            for (UsbEndpoint endpoint : (List<UsbEndpoint>) usbInterface.getUsbEndpoints()) {
                byte direction = endpoint.getDirection();
                byte type = endpoint.getType();
                if (type == UsbConst.ENDPOINT_TYPE_BULK && direction == UsbConst.ENDPOINT_DIRECTION_IN) {
                    bulkIn = endpoint;
                } else if (type == UsbConst.ENDPOINT_TYPE_BULK && direction == UsbConst.ENDPOINT_DIRECTION_OUT) {
                    bulkOut = endpoint;
                } else if (type == UsbConst.ENDPOINT_TYPE_INTERRUPT && direction == UsbConst.ENDPOINT_DIRECTION_IN) {
                    interruptIn = endpoint;
                }
            }

            if (bulkIn == null || bulkOut == null) {
                throw new PtpException("Required USB endpoints not found on PTP interface");
            }

            open = true;
            log.info("USB PTP transport opened for device: {}", device);
        } catch (UsbException e) {
            throw new PtpException("Failed to open USB PTP transport", e);
        }
    }

    @Override
    public PtpContainer execute(PtpContainer request) throws PtpException {
        checkOpen();
        sendContainer(request);
        return receiveContainer(bulkIn);
    }

    @Override
    public PtpContainer executeWithData(PtpContainer request, byte[] data) throws PtpException {
        checkOpen();
        sendContainer(request);

        // Send data phase
        PtpContainer dataContainer = PtpContainer.create(
                PtpConstants.CONTAINER_DATA, request.code(), request.transactionId(), data);
        sendContainer(dataContainer);

        return receiveContainer(bulkIn);
    }

    @Override
    public DataResponse executeReceiveData(PtpContainer request) throws PtpException {
        checkOpen();
        sendContainer(request);

        // Receive first container — may be DATA or RESPONSE (on error)
        PtpContainer firstContainer = receiveContainer(bulkIn);
        if (firstContainer.type() == PtpConstants.CONTAINER_RESPONSE) {
            // Error: camera returned response without data phase
            return new DataResponse(new byte[0], firstContainer);
        }
        byte[] data = firstContainer.payload();

        // Receive response
        PtpContainer response = receiveContainer(bulkIn);
        return new DataResponse(data, response);
    }

    @Override
    public PtpContainer readEvent(long timeoutMs) throws PtpException {
        checkOpen();
        if (interruptIn == null) {
            throw new PtpException("No interrupt endpoint available for events");
        }
        try {
            byte[] buffer = new byte[64]; // Event containers are small
            UsbPipe pipe = interruptIn.getUsbPipe();
            if (!pipe.isOpen()) {
                pipe.open();
            }
            // Use an IRP with async submit + wait to honour the timeout
            UsbIrp irp = pipe.createUsbIrp();
            irp.setData(buffer);
            pipe.asyncSubmit(irp);
            irp.waitUntilComplete(timeoutMs);
            if (!irp.isComplete()) {
                irp.setComplete(true);
                throw new PtpException("Event read timed out after " + timeoutMs + " ms");
            }
            if (irp.isUsbException()) {
                throw new PtpException("Failed to read event", irp.getUsbException());
            }
            int received = irp.getActualLength();
            if (received >= PtpConstants.CONTAINER_HEADER_SIZE) {
                byte[] actual = new byte[received];
                System.arraycopy(buffer, 0, actual, 0, received);
                return PtpContainer.parse(actual);
            }
            throw new PtpException("Event data too short: " + received + " bytes");
        } catch (UsbException e) {
            throw new PtpException("Failed to read event", e);
        }
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        if (usbInterface != null) {
            try {
                usbInterface.release();
            } catch (UsbException e) {
                log.warn("Error releasing USB interface", e);
            }
        }
        open = false;
    }

    private void checkOpen() {
        if (!open) {
            throw new PtpException("Transport is not open");
        }
    }

    private void sendContainer(PtpContainer container) throws PtpException {
        try {
            byte[] data = container.serialize();
            UsbPipe pipe = bulkOut.getUsbPipe();
            if (!pipe.isOpen()) {
                pipe.open();
            }
            pipe.syncSubmit(data);
        } catch (UsbException e) {
            throw new PtpException("Failed to send PTP container", e);
        }
    }

    private PtpContainer receiveContainer(UsbEndpoint endpoint) throws PtpException {
        try {
            UsbPipe pipe = endpoint.getUsbPipe();
            if (!pipe.isOpen()) {
                pipe.open();
            }
            // First read: get at least the 12-byte header
            byte[] headerBuf = new byte[UsbConstants.MAX_BULK_TRANSFER_SIZE];
            int received = pipe.syncSubmit(headerBuf);
            if (received < PtpConstants.CONTAINER_HEADER_SIZE) {
                throw new PtpException("Received data too short: " + received + " bytes");
            }
            // Parse declared length from the header (little-endian, first 4 bytes)
            int declaredLength = (headerBuf[0] & 0xFF)
                    | ((headerBuf[1] & 0xFF) << 8)
                    | ((headerBuf[2] & 0xFF) << 16)
                    | ((headerBuf[3] & 0xFF) << 24);
            if (declaredLength <= received) {
                // Complete container received in one transfer
                byte[] actual = new byte[received];
                System.arraycopy(headerBuf, 0, actual, 0, received);
                return PtpContainer.parse(actual);
            }
            // Multi-transfer: container is larger than one bulk read
            byte[] fullData = new byte[declaredLength];
            System.arraycopy(headerBuf, 0, fullData, 0, received);
            int totalReceived = received;
            while (totalReceived < declaredLength) {
                byte[] chunk = new byte[UsbConstants.MAX_BULK_TRANSFER_SIZE];
                int chunkSize = pipe.syncSubmit(chunk);
                if (chunkSize <= 0) {
                    throw new PtpException("USB read returned 0 bytes, expected "
                            + (declaredLength - totalReceived) + " more");
                }
                int toCopy = Math.min(chunkSize, declaredLength - totalReceived);
                System.arraycopy(chunk, 0, fullData, totalReceived, toCopy);
                totalReceived += toCopy;
            }
            return PtpContainer.parse(fullData);
        } catch (UsbException e) {
            throw new PtpException("Failed to receive PTP container", e);
        }
    }

    @SuppressWarnings("unchecked")
    private UsbInterface findPtpInterface(UsbConfiguration config) throws PtpException {
        for (UsbInterface iface : (List<UsbInterface>) config.getUsbInterfaces()) {
            UsbInterfaceDescriptor desc = iface.getUsbInterfaceDescriptor();
            if (desc.bInterfaceClass() == UsbConstants.INTERFACE_CLASS_STILL_IMAGE
                    && desc.bInterfaceSubClass() == UsbConstants.INTERFACE_SUBCLASS_PTP) {
                return iface;
            }
        }
        throw new PtpException("No PTP interface found on USB device");
    }
}
