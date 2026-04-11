package com.photographer.ptp.core;

import com.photographer.ptp.mock.MockPtpTransport;
import com.photographer.ptp.usb.PtpTransport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PtpDeviceInfoTest {

    @Test
    void parseFromMockTransport() {
        MockPtpTransport transport = new MockPtpTransport();
        transport.open();
        // Send open session
        PtpContainer openReq = PtpContainer.createCommand(PtpConstants.OP_OPEN_SESSION, 0, 1);
        transport.execute(openReq);

        // Get device info
        PtpContainer request = PtpContainer.createCommand(PtpConstants.OP_GET_DEVICE_INFO, 1);
        PtpTransport.DataResponse response = transport.executeReceiveData(request);
        PtpDeviceInfo info = PtpDeviceInfo.parse(response.data());

        assertEquals("Canon Inc.", info.manufacturer());
        assertEquals("Canon PowerShot G5 X Mark II", info.model());
        assertEquals("1.0.0", info.deviceVersion());
        assertEquals("MOCK12345678", info.serialNumber());
        assertTrue(info.operationsSupported().length > 0);
    }

    @Test
    void supportsOperationCheck() {
        MockPtpTransport transport = new MockPtpTransport();
        transport.open();
        PtpContainer openReq = PtpContainer.createCommand(PtpConstants.OP_OPEN_SESSION, 0, 1);
        transport.execute(openReq);

        PtpContainer request = PtpContainer.createCommand(PtpConstants.OP_GET_DEVICE_INFO, 1);
        PtpTransport.DataResponse response = transport.executeReceiveData(request);
        PtpDeviceInfo info = PtpDeviceInfo.parse(response.data());

        assertTrue(info.supportsOperation(PtpConstants.OP_GET_DEVICE_INFO));
        assertTrue(info.supportsOperation(PtpConstants.OP_INITIATE_CAPTURE));
        assertFalse(info.supportsOperation(0xFFFF)); // non-existent op
    }
}
