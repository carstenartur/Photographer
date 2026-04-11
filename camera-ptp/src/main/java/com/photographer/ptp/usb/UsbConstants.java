package com.photographer.ptp.usb;

/**
 * USB constants for PTP/Still Image Class devices.
 * Values from USB Still Image Capture Device Definition (publicly available from USB-IF).
 */
public final class UsbConstants {
    private UsbConstants() {}

    /** USB Interface Class: Still Image (0x06) */
    public static final byte INTERFACE_CLASS_STILL_IMAGE = 0x06;

    /** USB Interface Subclass: PTP (0x01) */
    public static final byte INTERFACE_SUBCLASS_PTP = 0x01;

    /** USB Interface Protocol: PTP (0x01) */
    public static final byte INTERFACE_PROTOCOL_PTP = 0x01;

    // Canon vendor ID
    public static final short VENDOR_ID_CANON = 0x04A9;

    // Common Canon camera product IDs (community-documented)
    public static final short PRODUCT_ID_EOS_R5  = 0x3262;
    public static final short PRODUCT_ID_EOS_R6  = 0x3263;
    public static final short PRODUCT_ID_G5X_II  = 0x3272;

    /** USB endpoint direction: IN */
    public static final byte ENDPOINT_DIR_IN = (byte) 0x80;

    /** USB endpoint direction: OUT */
    public static final byte ENDPOINT_DIR_OUT = 0x00;

    /** USB transfer type: Bulk */
    public static final byte TRANSFER_TYPE_BULK = 0x02;

    /** USB transfer type: Interrupt */
    public static final byte TRANSFER_TYPE_INTERRUPT = 0x03;

    /** Default USB timeout in milliseconds */
    public static final int DEFAULT_TIMEOUT_MS = 5000;

    /** Maximum USB bulk transfer size (512 KB) */
    public static final int MAX_BULK_TRANSFER_SIZE = 512 * 1024;
}
