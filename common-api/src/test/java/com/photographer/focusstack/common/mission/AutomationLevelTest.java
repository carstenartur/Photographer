package com.photographer.focusstack.common.mission;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AutomationLevelTest {

    @Test
    void atLeast_reflectsOrdinalOrdering() {
        assertTrue(AutomationLevel.FULL_PIPELINE.atLeast(AutomationLevel.ADVISOR));
        assertTrue(AutomationLevel.AUTONOMOUS.atLeast(AutomationLevel.ASSISTED));
        assertTrue(AutomationLevel.ASSISTED.atLeast(AutomationLevel.ASSISTED));
        assertFalse(AutomationLevel.ADVISOR.atLeast(AutomationLevel.ASSISTED));
        assertFalse(AutomationLevel.ASSISTED.atLeast(AutomationLevel.AUTONOMOUS));
    }

    @Test
    void requiresUserConfirmation_trueForLowLevels() {
        assertTrue(AutomationLevel.ADVISOR.requiresUserConfirmation());
        assertTrue(AutomationLevel.ASSISTED.requiresUserConfirmation());
        assertFalse(AutomationLevel.AUTONOMOUS.requiresUserConfirmation());
        assertFalse(AutomationLevel.FULL_PIPELINE.requiresUserConfirmation());
    }

    @Test
    void hasFourLevels_inExpectedOrder() {
        AutomationLevel[] vals = AutomationLevel.values();
        assertEquals(4, vals.length);
        assertEquals(AutomationLevel.ADVISOR, vals[0]);
        assertEquals(AutomationLevel.ASSISTED, vals[1]);
        assertEquals(AutomationLevel.AUTONOMOUS, vals[2]);
        assertEquals(AutomationLevel.FULL_PIPELINE, vals[3]);
    }
}
