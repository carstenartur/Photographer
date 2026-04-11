package com.photographer.ptp.core;

/**
 * PTP StorageInfo dataset (ISO 15740).
 */
public record PtpStorageInfo(
    int storageType,
    int filesystemType,
    int accessCapability,
    long maxCapacity,
    long freeSpaceInBytes,
    int freeSpaceInImages,
    String storageDescription,
    String volumeLabel
) {
    public static PtpStorageInfo parse(byte[] data) {
        PtpTypeReader reader = new PtpTypeReader(data);
        int storageType = reader.readInt16();
        int filesystemType = reader.readInt16();
        int accessCapability = reader.readInt16();
        long maxCapacity = reader.readInt64();
        long freeSpaceInBytes = reader.readInt64();
        int freeSpaceInImages = reader.readInt32();
        String storageDescription = reader.readPtpString();
        String volumeLabel = reader.readPtpString();
        return new PtpStorageInfo(
                storageType, filesystemType, accessCapability,
                maxCapacity, freeSpaceInBytes, freeSpaceInImages,
                storageDescription, volumeLabel);
    }
}
