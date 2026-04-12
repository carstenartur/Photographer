package com.photographer.focusstack.observer.profile;

import com.photographer.focusstack.observer.events.RegionOfInterest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ObserverProfileTest {

    @Test
    void birdhouseProfileIsValid() {
        ObserverProfile p = ObserverProfile.birdhouse();
        assertNotNull(p.name());
        assertEquals("wildlife", p.mode());
        assertTrue(p.previewIntervalMs() > 0);
        assertTrue(p.burstCount() > 0);
        assertTrue(p.cooldownSeconds() > 0);
        assertNotNull(p.defaultRoi());
        assertFalse(p.focusBracketing());
    }

    @Test
    void macroFlowerProfileHasFocusBracketing() {
        ObserverProfile p = ObserverProfile.macroFlower();
        assertEquals("macro_pollinator", p.mode());
        assertTrue(p.focusBracketing());
        assertTrue(p.focusBracketingSteps() > 0);
        assertTrue(p.previewIntervalMs() < 300);
    }

    @Test
    void allProfilesHaveValidRoi() {
        for (ObserverProfile profile : new ObserverProfile[]{
                ObserverProfile.birdhouse(),
                ObserverProfile.birdbath(),
                ObserverProfile.macroFlower(),
                ObserverProfile.insectHotel(),
                ObserverProfile.gardenWildlife()
        }) {
            RegionOfInterest roi = profile.defaultRoi();
            assertNotNull(roi, profile.name() + " has null ROI");
            assertTrue(roi.width() > 0 && roi.height() > 0,
                    profile.name() + " has invalid ROI dimensions");
        }
    }

    @Test
    void birdbathProfileIsValid() {
        ObserverProfile p = ObserverProfile.birdbath();
        assertEquals("wildlife", p.mode());
        assertNotNull(p.preferredAiProvider());
    }

    @Test
    void insectHotelProfileIsValid() {
        ObserverProfile p = ObserverProfile.insectHotel();
        assertEquals("macro_pollinator", p.mode());
        assertTrue(p.burstCount() >= 1);
    }
}
