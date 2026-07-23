package com.photographer.focusstack.astro.entity;

import com.photographer.focusstack.common.model.AstroCaptureConfig;
import com.photographer.focusstack.common.model.AstroFocusConfig;
import com.photographer.focusstack.common.model.AstroSessionStatus;
import com.photographer.focusstack.common.model.AstroStackingConfig;
import com.photographer.focusstack.common.model.AstroStackingMode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AstroSessionEntityStackingTest {

    @Test
    void explicitStackingConfigurationRoundTripsThroughEntity() {
        AstroStackingConfig stacking = AstroStackingConfig.sigmaClipped(1.7, 5, 6);
        AstroCaptureConfig capture = new AstroCaptureConfig(
                "camera", 8, 1000, 800, "f/4", 0,
                "PNG", false, true, false, stacking);

        AstroSessionEntity entity = AstroSessionEntity.create(
                capture, AstroFocusConfig.manualFixed());

        assertEquals(stacking, entity.resolvedStackingConfig());
        assertEquals(stacking, entity.toRecord().captureConfig().stackingConfig());
    }

    @Test
    void legacyRowWithoutStackingColumnsNormalizesToHistoricalMean() throws Exception {
        AstroSessionEntity entity = new AstroSessionEntity();
        set(entity, "id", "legacy");
        set(entity, "status", AstroSessionStatus.CREATED);
        set(entity, "createdAt", Instant.EPOCH);
        set(entity, "frameCount", 1);
        set(entity, "exposureTimeMs", 1000L);
        set(entity, "iso", 800);
        set(entity, "delayBetweenFramesMs", 0L);
        set(entity, "imageFormat", "PNG");

        assertEquals(AstroStackingConfig.defaults(), entity.resolvedStackingConfig());
        assertEquals(AstroStackingMode.MEAN,
                entity.toRecord().captureConfig().stackingConfig().mode());
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
