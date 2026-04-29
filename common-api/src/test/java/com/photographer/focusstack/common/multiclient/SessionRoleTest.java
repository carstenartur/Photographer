package com.photographer.focusstack.common.multiclient;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionRoleTest {

    @Test
    void atLeast_reflectsOrdering() {
        assertTrue(SessionRole.DIRECTOR.atLeast(SessionRole.OBSERVER));
        assertTrue(SessionRole.DIRECTOR.atLeast(SessionRole.CONTROLLER));
        assertTrue(SessionRole.CONTROLLER.atLeast(SessionRole.OBSERVER));
        assertTrue(SessionRole.OBSERVER.atLeast(SessionRole.OBSERVER));
        assertFalse(SessionRole.OBSERVER.atLeast(SessionRole.CONTROLLER));
        assertFalse(SessionRole.CONTROLLER.atLeast(SessionRole.DIRECTOR));
    }

    @Test
    void canControlCamera_isFalseForObserver() {
        assertFalse(SessionRole.OBSERVER.canControlCamera());
        assertTrue(SessionRole.CONTROLLER.canControlCamera());
        assertTrue(SessionRole.DIRECTOR.canControlCamera());
    }

    @Test
    void canManageSession_isOnlyDirector() {
        assertFalse(SessionRole.OBSERVER.canManageSession());
        assertFalse(SessionRole.CONTROLLER.canManageSession());
        assertTrue(SessionRole.DIRECTOR.canManageSession());
    }

    @Test
    void hasThreeRoles() {
        assertEquals(3, SessionRole.values().length);
    }
}
