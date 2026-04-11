package com.photographer.ptp.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PtpContainerTest {

    @Test
    void serializeAndParseRoundTrip() {
        PtpContainer original = PtpContainer.createCommand(
                PtpConstants.OP_GET_DEVICE_INFO, 1);
        byte[] serialized = original.serialize();
        PtpContainer parsed = PtpContainer.parse(serialized);

        assertEquals(original.length(), parsed.length());
        assertEquals(original.type(), parsed.type());
        assertEquals(original.code(), parsed.code());
        assertEquals(original.transactionId(), parsed.transactionId());
    }

    @Test
    void createCommandWithParams() {
        PtpContainer cmd = PtpContainer.createCommand(
                PtpConstants.OP_OPEN_SESSION, 0, 1);

        assertEquals(PtpConstants.CONTAINER_COMMAND, cmd.type());
        assertEquals(PtpConstants.OP_OPEN_SESSION, cmd.code());
        assertEquals(0, cmd.transactionId());

        int[] params = cmd.getParams();
        assertEquals(1, params.length);
        assertEquals(1, params[0]);
    }

    @Test
    void createResponse() {
        PtpContainer resp = PtpContainer.createResponse(PtpConstants.RC_OK, 5);
        assertEquals(PtpConstants.CONTAINER_RESPONSE, resp.type());
        assertEquals(PtpConstants.RC_OK, resp.code());
        assertEquals(5, resp.transactionId());
        assertTrue(resp.isOk());
    }

    @Test
    void containerHeaderSize() {
        PtpContainer cmd = PtpContainer.createCommand(PtpConstants.OP_CLOSE_SESSION, 1);
        assertEquals(PtpConstants.CONTAINER_HEADER_SIZE, cmd.length());
        assertEquals(12, cmd.serialize().length);
    }

    @Test
    void parseInvalidDataThrows() {
        assertThrows(PtpException.class, () -> PtpContainer.parse(new byte[4]));
        assertThrows(PtpException.class, () -> PtpContainer.parse(null));
    }

    @Test
    void multipleParamsRoundTrip() {
        PtpContainer cmd = PtpContainer.createCommand(
                PtpConstants.OP_GET_OBJECT_HANDLES, 10, 0x00010001, 0x3801, 0);

        byte[] serialized = cmd.serialize();
        PtpContainer parsed = PtpContainer.parse(serialized);

        int[] params = parsed.getParams();
        assertEquals(3, params.length);
        assertEquals(0x00010001, params[0]);
        assertEquals(0x3801, params[1]);
        assertEquals(0, params[2]);
    }

    @Test
    void getParam1ReturnsFirstOrZero() {
        PtpContainer withParams = PtpContainer.createCommand(
                PtpConstants.OP_OPEN_SESSION, 0, 42);
        assertEquals(42, withParams.getParam1());

        PtpContainer noParams = PtpContainer.createCommand(
                PtpConstants.OP_CLOSE_SESSION, 0);
        assertEquals(0, noParams.getParam1());
    }

    @Test
    void toStringContainsHexCodes() {
        PtpContainer cmd = PtpContainer.createCommand(PtpConstants.OP_GET_DEVICE_INFO, 1);
        String str = cmd.toString();
        assertTrue(str.contains("0x1001"));
        assertTrue(str.contains("0x0001"));
    }
}
