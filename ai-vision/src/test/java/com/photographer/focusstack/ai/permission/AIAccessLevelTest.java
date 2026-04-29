package com.photographer.focusstack.ai.permission;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AIAccessLevelTest {

    @Test
    void disabled_doesNotAllowReadOnly() {
        assertFalse(AIAccessLevel.DISABLED.allows(AIAccessLevel.READ_ONLY));
    }

    @Test
    void disabled_allowsDisabled() {
        assertTrue(AIAccessLevel.DISABLED.allows(AIAccessLevel.DISABLED));
    }

    @Test
    void readOnly_allowsReadOnly() {
        assertTrue(AIAccessLevel.READ_ONLY.allows(AIAccessLevel.READ_ONLY));
    }

    @Test
    void readOnly_doesNotAllowRecommend() {
        assertFalse(AIAccessLevel.READ_ONLY.allows(AIAccessLevel.RECOMMEND));
    }

    @Test
    void autonomous_allowsAllLevels() {
        for (AIAccessLevel level : AIAccessLevel.values()) {
            assertTrue(AIAccessLevel.AUTONOMOUS.allows(level),
                    "AUTONOMOUS should allow " + level);
        }
    }

    @Test
    void recommend_allowsRecommendAndBelow() {
        assertTrue(AIAccessLevel.RECOMMEND.allows(AIAccessLevel.DISABLED));
        assertTrue(AIAccessLevel.RECOMMEND.allows(AIAccessLevel.READ_ONLY));
        assertTrue(AIAccessLevel.RECOMMEND.allows(AIAccessLevel.RECOMMEND));
        assertFalse(AIAccessLevel.RECOMMEND.allows(AIAccessLevel.APPLY_WITH_CONFIRMATION));
        assertFalse(AIAccessLevel.RECOMMEND.allows(AIAccessLevel.AUTONOMOUS));
    }
}
