package com.photographer.ptp.canon;

/**
 * Canon vendor-specific PTP constants.
 * Sources: Magic Lantern Wiki, miniPtp YAML, community reverse-engineering.
 * NOT from Canon proprietary documentation or libgphoto2 source code.
 */
public final class CanonConstants {
    private CanonConstants() {}

    // ── Canon Vendor Operation Codes ──
    public static final int OC_EOS_GET_DEVICE_INFO_EX = 0x9001;
    public static final int OC_EOS_SET_DEVICE_PROP    = 0x9110;
    public static final int OC_EOS_SET_REMOTE_MODE    = 0x9114;
    public static final int OC_EOS_SET_EVENT_MODE     = 0x9115;
    public static final int OC_EOS_GET_EVENT          = 0x9116;
    public static final int OC_EOS_TRANSFER_COMPLETE  = 0x9117;
    public static final int OC_EOS_GET_OBJECT_INFO_EX = 0x9109;
    public static final int OC_EOS_GET_OBJECT         = 0x9101;
    public static final int OC_EOS_REMOTE_RELEASE_ON  = 0x9128;
    public static final int OC_EOS_REMOTE_RELEASE_OFF = 0x9129;
    public static final int OC_EOS_AF_DRIVE           = 0x9155;
    public static final int OC_EOS_GET_LIVEVIEW_DATA  = 0x9153;
    public static final int OC_EOS_SET_LIVEVIEW_PARAMS = 0x9154;

    // ── Canon Device Property Codes ──
    public static final int DPC_EOS_APERTURE       = 0xD101;
    public static final int DPC_EOS_SHUTTER_SPEED  = 0xD102;
    public static final int DPC_EOS_ISO            = 0xD103;
    public static final int DPC_EOS_EXPOSURE_COMPENSATION = 0xD104;
    public static final int DPC_EOS_IMAGE_QUALITY  = 0xD108;
    public static final int DPC_EOS_FOCUS_MODE     = 0xD109;
    public static final int DPC_EOS_BATTERY_LEVEL  = 0xD111;
    public static final int DPC_EOS_EVF_MODE       = 0xD1B0;
    public static final int DPC_EOS_EVF_OUTPUT     = 0xD1B1;

    // ── Remote Release Parameter Values ──
    public static final int RELEASE_PRESS_HALF  = 0x01;
    public static final int RELEASE_PRESS_FULL  = 0x02;
    public static final int RELEASE_RELEASE     = 0x00;

    // ── Focus Drive Values ──
    public static final int FOCUS_NEAR_1 = 0x8001; // Near, small step
    public static final int FOCUS_NEAR_2 = 0x8002; // Near, medium step
    public static final int FOCUS_NEAR_3 = 0x8003; // Near, large step
    public static final int FOCUS_FAR_1  = 0x0001; // Far, small step
    public static final int FOCUS_FAR_2  = 0x0002; // Far, medium step
    public static final int FOCUS_FAR_3  = 0x0003; // Far, large step

    // ── Canon Event Types (in GetEvent response) ──
    public static final int EVENT_PROP_CHANGED    = 0xC189;
    public static final int EVENT_OBJ_ADDED       = 0xC181;
    public static final int EVENT_REQUEST_COMPLETE = 0xC18A;

    // ── EVF Output Device Values ──
    public static final int EVF_OUTPUT_TFT = 0x01;  // Camera LCD
    public static final int EVF_OUTPUT_PC  = 0x02;  // PC (LiveView to host)
    public static final int EVF_OUTPUT_BOTH = 0x03;  // LCD + PC

    // ── Remote Mode Values ──
    public static final int REMOTE_MODE_NORMAL = 0x01;
    public static final int REMOTE_MODE_SILENT = 0x02;
}
