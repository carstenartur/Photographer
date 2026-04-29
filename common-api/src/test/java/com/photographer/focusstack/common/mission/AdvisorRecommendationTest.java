package com.photographer.focusstack.common.mission;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdvisorRecommendationTest {

    @Test
    void advisoryFactory_setsSeverity() {
        AdvisorRecommendation r = AdvisorRecommendation.advisory(
                "iso", "1600", "low light", 0.8);
        assertEquals(AdvisorRecommendation.Severity.ADVISORY, r.severity());
        assertEquals(0.8, r.confidenceScore(), 1e-6);
    }

    @Test
    void confidenceOutOfRange_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new AdvisorRecommendation("iso", "1600", "x", 1.5,
                        AdvisorRecommendation.Severity.STRONG));
        assertThrows(IllegalArgumentException.class,
                () -> new AdvisorRecommendation("iso", "1600", "x", -0.01,
                        AdvisorRecommendation.Severity.STRONG));
    }

    @Test
    void nullArgs_rejected() {
        assertThrows(NullPointerException.class,
                () -> new AdvisorRecommendation(null, "v", "r", 0.5,
                        AdvisorRecommendation.Severity.ADVISORY));
        assertThrows(NullPointerException.class,
                () -> new AdvisorRecommendation("s", null, "r", 0.5,
                        AdvisorRecommendation.Severity.ADVISORY));
        assertThrows(NullPointerException.class,
                () -> new AdvisorRecommendation("s", "v", null, 0.5,
                        AdvisorRecommendation.Severity.ADVISORY));
        assertThrows(NullPointerException.class,
                () -> new AdvisorRecommendation("s", "v", "r", 0.5, null));
    }

    @Test
    void severityHasThreeLevels() {
        assertEquals(3, AdvisorRecommendation.Severity.values().length);
    }
}
