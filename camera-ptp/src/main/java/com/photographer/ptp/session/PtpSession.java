package com.photographer.ptp.session;

import com.photographer.ptp.core.*;
import com.photographer.ptp.usb.PtpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages a PTP session lifecycle and provides low-level operation methods.
 * A session must be opened before any PTP operations can be performed.
 */
public class PtpSession implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PtpSession.class);

    private final PtpTransport transport;
    private final AtomicInteger transactionId = new AtomicInteger(1);
    private int sessionId;
    private boolean open;

    public PtpSession(PtpTransport transport) {
        this.transport = transport;
    }

    /** Opens a PTP session with the camera. */
    public void open() {
        sessionId = 1; // Standard: first session ID is 1
        PtpContainer request = PtpContainer.createCommand(
                PtpConstants.OP_OPEN_SESSION, 0, sessionId);
        PtpContainer response = transport.execute(request);
        if (response.code() != PtpConstants.RC_OK
                && response.code() != PtpConstants.RC_SESSION_ALREADY_OPEN) {
            throw new PtpException(response.code(),
                    "Failed to open session: 0x%04X".formatted(response.code()));
        }
        open = true;
        log.info("PTP session {} opened", sessionId);
    }

    /** Closes the PTP session. */
    @Override
    public void close() {
        if (open) {
            try {
                PtpContainer request = PtpContainer.createCommand(
                        PtpConstants.OP_CLOSE_SESSION, nextTransactionId());
                transport.execute(request);
            } catch (Exception e) {
                log.warn("Error closing PTP session", e);
            }
            open = false;
            log.info("PTP session {} closed", sessionId);
        }
    }

    /** Gets the next transaction ID. */
    public int nextTransactionId() {
        return transactionId.getAndIncrement();
    }

    /** Gets the PTP DeviceInfo. */
    public PtpDeviceInfo getDeviceInfo() {
        PtpContainer request = PtpContainer.createCommand(
                PtpConstants.OP_GET_DEVICE_INFO, nextTransactionId());
        PtpTransport.DataResponse dr = transport.executeReceiveData(request);
        PtpOperationResponse.from(dr.response()).checkOk();
        return PtpDeviceInfo.parse(dr.data());
    }

    /** Gets storage IDs. */
    public int[] getStorageIds() {
        PtpContainer request = PtpContainer.createCommand(
                PtpConstants.OP_GET_STORAGE_IDS, nextTransactionId());
        PtpTransport.DataResponse dr = transport.executeReceiveData(request);
        PtpOperationResponse.from(dr.response()).checkOk();
        PtpTypeReader reader = new PtpTypeReader(dr.data());
        return reader.readUInt32Array();
    }

    /** Gets storage info for a given storage ID. */
    public PtpStorageInfo getStorageInfo(int storageId) {
        PtpContainer request = PtpContainer.createCommand(
                PtpConstants.OP_GET_STORAGE_INFO, nextTransactionId(), storageId);
        PtpTransport.DataResponse dr = transport.executeReceiveData(request);
        PtpOperationResponse.from(dr.response()).checkOk();
        return PtpStorageInfo.parse(dr.data());
    }

    /** Gets object handles from a storage ID. */
    public int[] getObjectHandles(int storageId) {
        PtpContainer request = PtpContainer.createCommand(
                PtpConstants.OP_GET_OBJECT_HANDLES, nextTransactionId(),
                storageId, 0, 0); // all formats, root
        PtpTransport.DataResponse dr = transport.executeReceiveData(request);
        PtpOperationResponse.from(dr.response()).checkOk();
        PtpTypeReader reader = new PtpTypeReader(dr.data());
        return reader.readUInt32Array();
    }

    /** Gets object info for a given object handle. */
    public PtpObjectInfo getObjectInfo(int objectHandle) {
        PtpContainer request = PtpContainer.createCommand(
                PtpConstants.OP_GET_OBJECT_INFO, nextTransactionId(), objectHandle);
        PtpTransport.DataResponse dr = transport.executeReceiveData(request);
        PtpOperationResponse.from(dr.response()).checkOk();
        return PtpObjectInfo.parse(dr.data());
    }

    /** Downloads an object by handle. */
    public byte[] getObject(int objectHandle) {
        PtpContainer request = PtpContainer.createCommand(
                PtpConstants.OP_GET_OBJECT, nextTransactionId(), objectHandle);
        PtpTransport.DataResponse dr = transport.executeReceiveData(request);
        PtpOperationResponse.from(dr.response()).checkOk();
        return dr.data();
    }

    /** Downloads an object by handle using the Canon vendor-specific command (0x9101). */
    public byte[] getCanonObject(int objectHandle) {
        PtpContainer request = PtpContainer.createCommand(
                com.photographer.ptp.canon.CanonConstants.OC_EOS_GET_OBJECT, nextTransactionId(), objectHandle);
        PtpTransport.DataResponse dr = transport.executeReceiveData(request);
        PtpOperationResponse.from(dr.response()).checkOk();
        return dr.data();
    }

    /** Downloads a thumbnail by handle. */
    public byte[] getThumb(int objectHandle) {
        PtpContainer request = PtpContainer.createCommand(
                PtpConstants.OP_GET_THUMB, nextTransactionId(), objectHandle);
        PtpTransport.DataResponse dr = transport.executeReceiveData(request);
        PtpOperationResponse.from(dr.response()).checkOk();
        return dr.data();
    }

    /** Gets a device property value. */
    public PtpDeviceProperty getDevicePropertyDesc(int propertyCode) {
        PtpContainer request = PtpContainer.createCommand(
                PtpConstants.OP_GET_DEVICE_PROP_DESC, nextTransactionId(), propertyCode);
        PtpTransport.DataResponse dr = transport.executeReceiveData(request);
        PtpOperationResponse.from(dr.response()).checkOk();
        return PtpDeviceProperty.parse(dr.data());
    }

    /** Executes a generic PTP operation (no data phase). */
    public PtpContainer executeOperation(int opCode, int... params) {
        PtpContainer request = PtpContainer.createCommand(opCode, nextTransactionId(), params);
        return transport.execute(request);
    }

    /** Executes a generic PTP operation with data-write phase. */
    public PtpContainer executeOperationWithData(int opCode, byte[] data, int... params) {
        PtpContainer request = PtpContainer.createCommand(opCode, nextTransactionId(), params);
        return transport.executeWithData(request, data);
    }

    /** Executes a generic PTP operation with data receive phase. */
    public PtpTransport.DataResponse executeReceiveData(int opCode, int... params) {
        PtpContainer request = PtpContainer.createCommand(opCode, nextTransactionId(), params);
        return transport.executeReceiveData(request);
    }

    public PtpTransport getTransport() {
        return transport;
    }

    public int getSessionId() {
        return sessionId;
    }

    public boolean isOpen() {
        return open;
    }
}
