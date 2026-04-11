package com.photographer.ptp.core;

/**
 * PTP DevicePropDesc dataset (ISO 15740).
 * Describes a device property including its current and default values.
 */
public record PtpDeviceProperty(
    int propertyCode,
    int dataType,
    boolean writable,
    Object defaultValue,
    Object currentValue
) {
    // PTP Data Type Codes
    public static final int TYPE_INT8    = 0x0001;
    public static final int TYPE_UINT8   = 0x0002;
    public static final int TYPE_INT16   = 0x0003;
    public static final int TYPE_UINT16  = 0x0004;
    public static final int TYPE_INT32   = 0x0005;
    public static final int TYPE_UINT32  = 0x0006;
    public static final int TYPE_INT64   = 0x0007;
    public static final int TYPE_UINT64  = 0x0008;
    public static final int TYPE_STRING  = 0xFFFF;

    /**
     * Reads a value of the given PTP data type from the reader.
     */
    public static Object readValue(PtpTypeReader reader, int dataType) {
        return switch (dataType) {
            case TYPE_INT8, TYPE_UINT8 -> reader.readInt8();
            case TYPE_INT16, TYPE_UINT16 -> reader.readInt16();
            case TYPE_INT32, TYPE_UINT32 -> reader.readInt32();
            case TYPE_INT64, TYPE_UINT64 -> reader.readInt64();
            case TYPE_STRING -> reader.readPtpString();
            default -> reader.readInt32(); // fallback
        };
    }

    /**
     * Parses a DevicePropDesc from raw PTP data.
     */
    public static PtpDeviceProperty parse(byte[] data) {
        PtpTypeReader reader = new PtpTypeReader(data);
        int propertyCode = reader.readInt16();
        int dataType = reader.readInt16();
        int getSet = reader.readInt8(); // 0=Get, 1=Get/Set
        Object defaultValue = readValue(reader, dataType);
        Object currentValue = readValue(reader, dataType);
        return new PtpDeviceProperty(propertyCode, dataType, getSet == 1,
                defaultValue, currentValue);
    }
}
