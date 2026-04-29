package com.photographer.focusstack.common.mission;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MissionScenarioTest {

    @Test
    void astro_includesMoonAndSun() {
        assertTrue(MissionScenario.ASTRO.relevantContextSources().contains(ContextSource.MOON));
        assertTrue(MissionScenario.ASTRO.relevantContextSources().contains(ContextSource.SUN));
    }

    @Test
    void wildlife_includesGpsAndWind() {
        assertTrue(MissionScenario.WILDLIFE.relevantContextSources().contains(ContextSource.GPS));
        assertTrue(MissionScenario.WILDLIFE.relevantContextSources().contains(ContextSource.WIND));
    }

    @Test
    void macro_doesNotIncludeWeather() {
        assertFalse(MissionScenario.MACRO_FOCUS_STACK.relevantContextSources()
                .contains(ContextSource.WEATHER));
    }

    @Test
    void relevantContextSources_isImmutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> MissionScenario.BIRDHOUSE.relevantContextSources().clear());
    }
}
