package com.photographer.ptp.core;

/**
 * PTP DeviceInfo dataset (ISO 15740).
 * Parsed from the response to GetDeviceInfo (0x1001).
 */
public record PtpDeviceInfo(
    int standardVersion,
    int vendorExtensionId,
    int vendorExtensionVersion,
    String vendorExtensionDesc,
    int functionalMode,
    int[] operationsSupported,
    int[] eventsSupported,
    int[] devicePropertiesSupported,
    int[] captureFormats,
    int[] imageFormats,
    String manufacturer,
    String model,
    String deviceVersion,
    String serialNumber
) {
    /**
     * Parses DeviceInfo from raw PTP data bytes.
     */
    public static PtpDeviceInfo parse(byte[] data) {
        PtpTypeReader reader = new PtpTypeReader(data);
        int standardVersion = reader.readInt16();
        int vendorExtensionId = reader.readInt32();
        int vendorExtensionVersion = reader.readInt16();
        String vendorExtensionDesc = reader.readPtpString();
        int functionalMode = reader.readInt16();
        int[] operationsSupported = reader.readUInt16Array();
        int[] eventsSupported = reader.readUInt16Array();
        int[] devicePropertiesSupported = reader.readUInt16Array();
        int[] captureFormats = reader.readUInt16Array();
        int[] imageFormats = reader.readUInt16Array();
        String manufacturer = reader.readPtpString();
        String model = reader.readPtpString();
        String deviceVersion = reader.readPtpString();
        String serialNumber = reader.readPtpString();
        return new PtpDeviceInfo(
                standardVersion, vendorExtensionId, vendorExtensionVersion,
                vendorExtensionDesc, functionalMode,
                operationsSupported, eventsSupported, devicePropertiesSupported,
                captureFormats, imageFormats,
                manufacturer, model, deviceVersion, serialNumber);
    }

    /** Checks if the given operation code is supported. */
    public boolean supportsOperation(int opCode) {
        for (int op : operationsSupported) {
            if (op == opCode) return true;
        }
        return false;
    }

    /** Checks if the given device property code is supported. */
    public boolean supportsProperty(int propCode) {
        for (int prop : devicePropertiesSupported) {
            if (prop == propCode) return true;
        }
        return false;
    }
}
