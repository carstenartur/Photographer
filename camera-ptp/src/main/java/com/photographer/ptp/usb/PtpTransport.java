package com.photographer.ptp.usb;

import com.photographer.ptp.core.PtpContainer;
import com.photographer.ptp.core.PtpException;

/**
 * Transport interface for PTP communication.
 * Abstracts the underlying transport (USB, IP, mock) to enable testing without hardware.
 */
public interface PtpTransport extends AutoCloseable {

    /** Opens the connection to the camera. */
    void open() throws PtpException;

    /** Sends an operation request and receives a response. */
    PtpContainer execute(PtpContainer request) throws PtpException;

    /** Sends an operation request with data phase, receives a response. */
    PtpContainer executeWithData(PtpContainer request, byte[] data) throws PtpException;

    /** Sends an operation request and receives data + response. */
    DataResponse executeReceiveData(PtpContainer request) throws PtpException;

    /** Reads an event from the interrupt endpoint (blocking with timeout). */
    PtpContainer readEvent(long timeoutMs) throws PtpException;

    /** Returns true if the transport is connected and open. */
    boolean isOpen();

    /** Closes the transport connection. */
    @Override
    void close();

    /** Response that includes both data and the response container. */
    record DataResponse(byte[] data, PtpContainer response) {}
}
