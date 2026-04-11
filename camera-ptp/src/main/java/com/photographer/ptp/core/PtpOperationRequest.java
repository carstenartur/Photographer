package com.photographer.ptp.core;

/**
 * Builder for PTP operation request containers.
 */
public class PtpOperationRequest {
    private final int opCode;
    private int transactionId;
    private int[] params = new int[0];

    public PtpOperationRequest(int opCode) {
        this.opCode = opCode;
    }

    public PtpOperationRequest transactionId(int id) {
        this.transactionId = id;
        return this;
    }

    public PtpOperationRequest params(int... params) {
        this.params = params;
        return this;
    }

    public PtpContainer toContainer() {
        return PtpContainer.createCommand(opCode, transactionId, params);
    }
}
