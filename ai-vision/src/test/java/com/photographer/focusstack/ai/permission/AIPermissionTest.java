package com.photographer.focusstack.ai.permission;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AIPermissionTest {

    @Test
    void disabled_doesNotAllowReadOnlyFullImages() {
        assertFalse(AIPermission.DISABLED.allows(AIAccessLevel.READ_ONLY, AIPrivacyScope.FULL_IMAGES));
    }

    @Test
    void disabled_allowsDisabledMetadata() {
        assertTrue(AIPermission.DISABLED.allows(AIAccessLevel.DISABLED, AIPrivacyScope.METADATA_ONLY));
    }

    @Test
    void readOnlyFull_allowsReadOnlyFullImages() {
        assertTrue(AIPermission.READ_ONLY_FULL.allows(AIAccessLevel.READ_ONLY, AIPrivacyScope.FULL_IMAGES));
    }

    @Test
    void readOnlyFull_doesNotAllowAutonomous() {
        assertFalse(AIPermission.READ_ONLY_FULL.allows(AIAccessLevel.AUTONOMOUS, AIPrivacyScope.FULL_IMAGES));
    }

    @Test
    void autonomousFull_allowsEverything() {
        for (AIAccessLevel level : AIAccessLevel.values()) {
            for (AIPrivacyScope scope : AIPrivacyScope.values()) {
                assertTrue(AIPermission.AUTONOMOUS_FULL.allows(level, scope),
                        "AUTONOMOUS_FULL should allow " + level + " / " + scope);
            }
        }
    }

    @Test
    void customPermission_thumbnailsReadOnly_doesNotAllowFullImages() {
        AIPermission p = new AIPermission(AIAccessLevel.READ_ONLY, AIPrivacyScope.THUMBNAILS);
        assertFalse(p.allows(AIAccessLevel.READ_ONLY, AIPrivacyScope.FULL_IMAGES));
    }

    @Test
    void customPermission_thumbnailsReadOnly_allowsThumbnails() {
        AIPermission p = new AIPermission(AIAccessLevel.READ_ONLY, AIPrivacyScope.THUMBNAILS);
        assertTrue(p.allows(AIAccessLevel.READ_ONLY, AIPrivacyScope.THUMBNAILS));
    }
}
