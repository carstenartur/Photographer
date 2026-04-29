package com.photographer.focusstack.ai.permission;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AIPrivacyScopeTest {

    @Test
    void metadataOnly_doesNotAllowThumbnails() {
        assertFalse(AIPrivacyScope.METADATA_ONLY.allows(AIPrivacyScope.THUMBNAILS));
    }

    @Test
    void metadataOnly_allowsMetadataOnly() {
        assertTrue(AIPrivacyScope.METADATA_ONLY.allows(AIPrivacyScope.METADATA_ONLY));
    }

    @Test
    void thumbnails_allowsThumbnailsAndBelow() {
        assertTrue(AIPrivacyScope.THUMBNAILS.allows(AIPrivacyScope.METADATA_ONLY));
        assertTrue(AIPrivacyScope.THUMBNAILS.allows(AIPrivacyScope.THUMBNAILS));
        assertFalse(AIPrivacyScope.THUMBNAILS.allows(AIPrivacyScope.FULL_IMAGES));
    }

    @Test
    void fullImages_allowsAllScopes() {
        for (AIPrivacyScope scope : AIPrivacyScope.values()) {
            assertTrue(AIPrivacyScope.FULL_IMAGES.allows(scope),
                    "FULL_IMAGES should allow " + scope);
        }
    }
}
