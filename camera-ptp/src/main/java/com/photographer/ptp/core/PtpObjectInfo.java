package com.photographer.ptp.core;

/**
 * PTP ObjectInfo dataset (ISO 15740).
 * Contains metadata about an object (file) on the camera.
 */
public record PtpObjectInfo(
    int storageId,
    int objectFormat,
    int protectionStatus,
    long compressedSize,
    int thumbFormat,
    long thumbCompressedSize,
    int thumbPixWidth,
    int thumbPixHeight,
    int imagePixWidth,
    int imagePixHeight,
    int imageBitDepth,
    int parentObject,
    int associationType,
    int associationDesc,
    int sequenceNumber,
    String filename,
    String captureDate,
    String modificationDate,
    String keywords
) {
    public static PtpObjectInfo parse(byte[] data) {
        PtpTypeReader reader = new PtpTypeReader(data);
        int storageId = reader.readInt32();
        int objectFormat = reader.readInt16();
        int protectionStatus = reader.readInt16();
        long compressedSize = reader.readUInt32();
        int thumbFormat = reader.readInt16();
        long thumbCompressedSize = reader.readUInt32();
        int thumbPixWidth = reader.readInt32();
        int thumbPixHeight = reader.readInt32();
        int imagePixWidth = reader.readInt32();
        int imagePixHeight = reader.readInt32();
        int imageBitDepth = reader.readInt32();
        int parentObject = reader.readInt32();
        int associationType = reader.readInt16();
        int associationDesc = reader.readInt32();
        int sequenceNumber = reader.readInt32();
        String filename = reader.readPtpString();
        String captureDate = reader.readPtpString();
        String modificationDate = reader.readPtpString();
        String keywords = reader.hasRemaining() ? reader.readPtpString() : "";
        return new PtpObjectInfo(
                storageId, objectFormat, protectionStatus, compressedSize,
                thumbFormat, thumbCompressedSize, thumbPixWidth, thumbPixHeight,
                imagePixWidth, imagePixHeight, imageBitDepth,
                parentObject, associationType, associationDesc, sequenceNumber,
                filename, captureDate, modificationDate, keywords);
    }
}
