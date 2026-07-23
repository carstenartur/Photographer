package com.photographer.focusstack.common.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AstroSessionProgressTest {

    @Test
    void oldConstructorDefaultsCompletedSessionToOneHundredPercent() {
        AstroSession session = oldSession(AstroSessionStatus.COMPLETED);

        assertEquals(100, session.stackingProgressPercent());
    }

    @Test
    void oldConstructorDefaultsNonCompletedSessionToZeroPercent() {
        AstroSession session = oldSession(AstroSessionStatus.STACKING);

        assertEquals(0, session.stackingProgressPercent());
    }

    @Test
    void rejectsProgressOutsidePublicRange() {
        assertThrows(IllegalArgumentException.class, () -> sessionWithProgress(-1));
        assertThrows(IllegalArgumentException.class, () -> sessionWithProgress(101));
    }

    private static AstroSession oldSession(AstroSessionStatus status) {
        return new AstroSession(
                "session",
                status,
                Instant.EPOCH,
                null,
                null,
                AstroCaptureConfig.defaults(),
                AstroFocusConfig.manualFixed(),
                "out",
                List.of(),
                null,
                null,
                null);
    }

    private static AstroSession sessionWithProgress(int progress) {
        return new AstroSession(
                "session",
                AstroSessionStatus.STACKING,
                Instant.EPOCH,
                null,
                null,
                AstroCaptureConfig.defaults(),
                AstroFocusConfig.manualFixed(),
                "out",
                List.of(),
                null,
                null,
                progress,
                null);
    }
}
