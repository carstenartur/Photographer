package com.photographer.ptp.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * PTP Container Format (ISO 15740, Section 9).
 * <pre>
 * ┌──────────┬──────────┬──────────┬──────────┬──────────────┐
 * │  Length   │   Type   │   Code   │ TransID  │   Payload    │
 * │  4 bytes  │  2 bytes │  2 bytes │  4 bytes │  variable    │
 * └──────────┴──────────┴──────────┴──────────┴──────────────┘
 * </pre>
 */
public record PtpContainer(
    int length,
    int type,
    int code,
    int transactionId,
    byte[] payload
) {
    /** Creates a container with auto-calculated length. */
    public static PtpContainer create(int type, int code, int transactionId, byte[] payload) {
        int len = PtpConstants.CONTAINER_HEADER_SIZE + (payload != null ? payload.length : 0);
        return new PtpContainer(len, type, code, transactionId,
                payload != null ? payload : new byte[0]);
    }

    /** Creates an operation request container with up to 5 parameters. */
    public static PtpContainer createCommand(int opCode, int transactionId, int... params) {
        byte[] payload = new byte[params.length * 4];
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        for (int p : params) {
            buf.putInt(p);
        }
        return create(PtpConstants.CONTAINER_COMMAND, opCode, transactionId, payload);
    }

    /** Creates a response container. */
    public static PtpContainer createResponse(int responseCode, int transactionId, int... params) {
        byte[] payload = new byte[params.length * 4];
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        for (int p : params) {
            buf.putInt(p);
        }
        return create(PtpConstants.CONTAINER_RESPONSE, responseCode, transactionId, payload);
    }

    /** Serializes this container to bytes for transmission. */
    public byte[] serialize() {
        ByteBuffer buf = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(length);
        buf.putShort((short) type);
        buf.putShort((short) code);
        buf.putInt(transactionId);
        if (payload != null && payload.length > 0) {
            buf.put(payload);
        }
        return buf.array();
    }

    /** Parses a PTP container from raw bytes. */
    public static PtpContainer parse(byte[] raw) {
        if (raw == null || raw.length < PtpConstants.CONTAINER_HEADER_SIZE) {
            throw new PtpException("Invalid container: too short (need at least "
                    + PtpConstants.CONTAINER_HEADER_SIZE + " bytes, got "
                    + (raw == null ? 0 : raw.length) + ")");
        }
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int length = buf.getInt();
        if (length < PtpConstants.CONTAINER_HEADER_SIZE || length > raw.length) {
            throw new PtpException("Invalid container length: " + length
                    + " (expected between " + PtpConstants.CONTAINER_HEADER_SIZE
                    + " and " + raw.length + ")");
        }
        int type = buf.getShort() & 0xFFFF;
        int code = buf.getShort() & 0xFFFF;
        int transactionId = buf.getInt();
        int payloadLength = length - PtpConstants.CONTAINER_HEADER_SIZE;
        byte[] payload;
        if (payloadLength > 0) {
            payload = new byte[payloadLength];
            buf.get(payload);
        } else {
            payload = new byte[0];
        }
        return new PtpContainer(length, type, code, transactionId, payload);
    }

    /** Extracts parameter values from the payload (each 4 bytes, little-endian). */
    public int[] getParams() {
        if (payload == null || payload.length == 0) {
            return new int[0];
        }
        int count = payload.length / 4;
        int[] params = new int[count];
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            params[i] = buf.getInt();
        }
        return params;
    }

    /** Returns the first parameter or 0 if no parameters. */
    public int getParam1() {
        int[] p = getParams();
        return p.length > 0 ? p[0] : 0;
    }

    /** Checks if this is a successful response. */
    public boolean isOk() {
        return type == PtpConstants.CONTAINER_RESPONSE && code == PtpConstants.RC_OK;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PtpContainer that)) return false;
        return length == that.length && type == that.type && code == that.code
                && transactionId == that.transactionId && Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        int result = Integer.hashCode(length);
        result = 31 * result + Integer.hashCode(type);
        result = 31 * result + Integer.hashCode(code);
        result = 31 * result + Integer.hashCode(transactionId);
        result = 31 * result + Arrays.hashCode(payload);
        return result;
    }

    @Override
    public String toString() {
        return "PtpContainer[length=%d, type=0x%04X, code=0x%04X, txId=%d, payloadLen=%d]"
                .formatted(length, type, code, transactionId,
                        payload != null ? payload.length : 0);
    }
}
