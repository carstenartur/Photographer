package com.photographer.ptp.core;

/**
 * Standard PTP constants from ISO 15740 (public standard).
 */
public final class PtpConstants {
    private PtpConstants() {}

    // Standard Operation Codes
    public static final int OP_GET_DEVICE_INFO       = 0x1001;
    public static final int OP_OPEN_SESSION          = 0x1002;
    public static final int OP_CLOSE_SESSION         = 0x1003;
    public static final int OP_GET_STORAGE_IDS       = 0x1004;
    public static final int OP_GET_STORAGE_INFO      = 0x1005;
    public static final int OP_GET_NUM_OBJECTS       = 0x1006;
    public static final int OP_GET_OBJECT_HANDLES    = 0x1007;
    public static final int OP_GET_OBJECT_INFO       = 0x1008;
    public static final int OP_GET_OBJECT            = 0x1009;
    public static final int OP_GET_THUMB             = 0x100A;
    public static final int OP_DELETE_OBJECT          = 0x100B;
    public static final int OP_INITIATE_CAPTURE      = 0x100E;
    public static final int OP_GET_DEVICE_PROP_DESC  = 0x1014;
    public static final int OP_GET_DEVICE_PROP_VALUE = 0x1015;
    public static final int OP_SET_DEVICE_PROP_VALUE = 0x1016;

    // Container Types
    public static final int CONTAINER_COMMAND  = 1;
    public static final int CONTAINER_DATA     = 2;
    public static final int CONTAINER_RESPONSE = 3;
    public static final int CONTAINER_EVENT    = 4;

    // Standard Response Codes
    public static final int RC_UNDEFINED                 = 0x2000;
    public static final int RC_OK                        = 0x2001;
    public static final int RC_GENERAL_ERROR             = 0x2002;
    public static final int RC_SESSION_NOT_OPEN          = 0x2003;
    public static final int RC_INVALID_TRANSACTION_ID    = 0x2004;
    public static final int RC_OPERATION_NOT_SUPPORTED   = 0x2005;
    public static final int RC_PARAMETER_NOT_SUPPORTED   = 0x2006;
    public static final int RC_INCOMPLETE_TRANSFER       = 0x2007;
    public static final int RC_INVALID_STORAGE_ID        = 0x2008;
    public static final int RC_INVALID_OBJECT_HANDLE     = 0x2009;
    public static final int RC_DEVICE_PROP_NOT_SUPPORTED = 0x200A;
    public static final int RC_STORE_FULL                = 0x200C;
    public static final int RC_STORE_NOT_AVAILABLE       = 0x2013;
    public static final int RC_SPECIFICATION_BY_FORMAT_UNSUPPORTED = 0x2014;
    public static final int RC_NO_VALID_OBJECT_INFO      = 0x2015;
    public static final int RC_DEVICE_BUSY               = 0x2019;
    public static final int RC_SESSION_ALREADY_OPEN      = 0x201E;

    // Standard Event Codes
    public static final int EC_OBJECT_ADDED   = 0x4002;
    public static final int EC_OBJECT_REMOVED = 0x4003;
    public static final int EC_STORE_ADDED    = 0x4004;
    public static final int EC_STORE_REMOVED  = 0x4005;
    public static final int EC_DEVICE_PROP_CHANGED = 0x4006;
    public static final int EC_CAPTURE_COMPLETE    = 0x400D;

    // Object Format Codes
    public static final int OFC_UNDEFINED  = 0x3000;
    public static final int OFC_ASSOCIATION = 0x3001;
    public static final int OFC_EXIF_JPEG  = 0x3801;
    public static final int OFC_TIFF_EP    = 0x380D;
    public static final int OFC_PNG        = 0x380B;

    // Container header size: length(4) + type(2) + code(2) + transactionId(4) = 12 bytes
    public static final int CONTAINER_HEADER_SIZE = 12;
}
