package com.photographer.ptp.core;

/**
 * Parsed PTP operation response.
 */
public record PtpOperationResponse(
    int responseCode,
    int transactionId,
    int[] params
) {
    public static PtpOperationResponse from(PtpContainer container) {
        if (container.type() != PtpConstants.CONTAINER_RESPONSE) {
            throw new PtpException("Expected response container, got type: " + container.type());
        }
        return new PtpOperationResponse(container.code(), container.transactionId(), container.getParams());
    }

    public boolean isOk() {
        return responseCode == PtpConstants.RC_OK;
    }

    public void checkOk() {
        if (!isOk()) {
            throw new PtpException(responseCode,
                    "PTP operation failed with response code: 0x%04X".formatted(responseCode));
        }
    }
}
