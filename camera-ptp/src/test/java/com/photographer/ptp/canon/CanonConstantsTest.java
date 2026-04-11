package com.photographer.ptp.canon;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CanonConstantsTest {

    @Test
    void vendorOpcodeRanges() {
        // Canon vendor opcodes should be in 0x9xxx range
        assertTrue(CanonConstants.OC_EOS_GET_DEVICE_INFO_EX >= 0x9000);
        assertTrue(CanonConstants.OC_EOS_SET_REMOTE_MODE >= 0x9000);
        assertTrue(CanonConstants.OC_EOS_GET_EVENT >= 0x9000);
        assertTrue(CanonConstants.OC_EOS_REMOTE_RELEASE_ON >= 0x9000);
        assertTrue(CanonConstants.OC_EOS_AF_DRIVE >= 0x9000);
    }

    @Test
    void propertyCodeRanges() {
        // Canon device property codes should be in 0xDxxx range
        assertTrue(CanonConstants.DPC_EOS_APERTURE >= 0xD000);
        assertTrue(CanonConstants.DPC_EOS_SHUTTER_SPEED >= 0xD000);
        assertTrue(CanonConstants.DPC_EOS_ISO >= 0xD000);
        assertTrue(CanonConstants.DPC_EOS_BATTERY_LEVEL >= 0xD000);
    }

    @Test
    void focusDriveValues() {
        // Near values should have high bit set (0x8xxx)
        assertTrue(CanonConstants.FOCUS_NEAR_1 > 0x8000 || CanonConstants.FOCUS_NEAR_1 == 0x8001);
        assertTrue(CanonConstants.FOCUS_NEAR_2 > 0x8000 || CanonConstants.FOCUS_NEAR_2 == 0x8002);
        assertTrue(CanonConstants.FOCUS_NEAR_3 > 0x8000 || CanonConstants.FOCUS_NEAR_3 == 0x8003);

        // Far values should be small positive
        assertEquals(1, CanonConstants.FOCUS_FAR_1);
        assertEquals(2, CanonConstants.FOCUS_FAR_2);
        assertEquals(3, CanonConstants.FOCUS_FAR_3);
    }

    @Test
    void releaseValues() {
        assertEquals(0x00, CanonConstants.RELEASE_RELEASE);
        assertEquals(0x01, CanonConstants.RELEASE_PRESS_HALF);
        assertEquals(0x02, CanonConstants.RELEASE_PRESS_FULL);
    }
}
