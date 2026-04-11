package com.photographer.ptp.canon;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CanonFocusDriveTest {

    @Test
    void mapNearValues() {
        assertEquals(CanonConstants.FOCUS_NEAR_1, CanonFocusDrive.mapNearValue(1));
        assertEquals(CanonConstants.FOCUS_NEAR_2, CanonFocusDrive.mapNearValue(2));
        assertEquals(CanonConstants.FOCUS_NEAR_3, CanonFocusDrive.mapNearValue(3));
    }

    @Test
    void mapFarValues() {
        assertEquals(CanonConstants.FOCUS_FAR_1, CanonFocusDrive.mapFarValue(1));
        assertEquals(CanonConstants.FOCUS_FAR_2, CanonFocusDrive.mapFarValue(2));
        assertEquals(CanonConstants.FOCUS_FAR_3, CanonFocusDrive.mapFarValue(3));
    }

    @Test
    void invalidMagnitudeThrows() {
        assertThrows(IllegalArgumentException.class, () -> CanonFocusDrive.mapNearValue(0));
        assertThrows(IllegalArgumentException.class, () -> CanonFocusDrive.mapNearValue(4));
        assertThrows(IllegalArgumentException.class, () -> CanonFocusDrive.mapFarValue(-1));
    }
}
