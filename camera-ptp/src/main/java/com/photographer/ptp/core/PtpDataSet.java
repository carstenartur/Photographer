package com.photographer.ptp.core;

/**
 * Generic PTP dataset parser. Wraps PtpTypeReader for convenience.
 */
public class PtpDataSet {
    private final PtpTypeReader reader;

    public PtpDataSet(byte[] data) {
        this.reader = new PtpTypeReader(data);
    }

    public PtpTypeReader getReader() {
        return reader;
    }

    public int readUInt8() {
        return reader.readInt8();
    }

    public int readUInt16() {
        return reader.readInt16();
    }

    public int readInt32() {
        return reader.readInt32();
    }

    public long readUInt32Long() {
        return reader.readUInt32();
    }

    public String readString() {
        return reader.readPtpString();
    }

    public int[] readUInt16Array() {
        return reader.readUInt16Array();
    }

    public int[] readUInt32Array() {
        return reader.readUInt32Array();
    }

    public boolean hasRemaining() {
        return reader.hasRemaining();
    }
}
