package com.photographer.focusstack.cr3.container;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SensorCropGeometry}.
 */
class SensorCropGeometryTest {

    @Nested
    @DisplayName("Dimension calculations")
    class DimensionCalculations {

        @Test
        @DisplayName("activeWidth and activeHeight should be computed correctly")
        void activeDimensions() {
            var geom = new SensorCropGeometry(5472, 3648, 100, 50, 5400, 3600, 0, 0, 0, 0);
            assertThat(geom.activeWidth()).isEqualTo(5300);
            assertThat(geom.activeHeight()).isEqualTo(3550);
        }

        @Test
        @DisplayName("defaultCropWidth and defaultCropHeight should be computed correctly")
        void defaultCropDimensions() {
            var geom = new SensorCropGeometry(5472, 3648, 0, 0, 5472, 3648, 120, 80, 5352, 3568);
            assertThat(geom.defaultCropWidth()).isEqualTo(5232);
            assertThat(geom.defaultCropHeight()).isEqualTo(3488);
        }
    }

    @Nested
    @DisplayName("Presence checks")
    class PresenceChecks {

        @Test
        @DisplayName("hasActiveArea should return true when active area is defined")
        void hasActiveAreaTrue() {
            var geom = SensorCropGeometry.withActiveArea(5472, 3648, 100, 50, 5400, 3600);
            assertThat(geom.hasActiveArea()).isTrue();
        }

        @Test
        @DisplayName("hasActiveArea should return false when active area has zero dimensions")
        void hasActiveAreaFalseZero() {
            var geom = new SensorCropGeometry(5472, 3648, 0, 0, 0, 0, 0, 0, 0, 0);
            assertThat(geom.hasActiveArea()).isFalse();
        }

        @Test
        @DisplayName("hasDefaultCrop should return true when crop is defined")
        void hasDefaultCropTrue() {
            var geom = new SensorCropGeometry(5472, 3648, 0, 0, 5472, 3648, 120, 80, 5352, 3568);
            assertThat(geom.hasDefaultCrop()).isTrue();
        }

        @Test
        @DisplayName("hasDefaultCrop should return false when crop is zero")
        void hasDefaultCropFalse() {
            var geom = SensorCropGeometry.sensorOnly(5472, 3648);
            assertThat(geom.hasDefaultCrop()).isFalse();
        }
    }

    @Nested
    @DisplayName("bestOutputDimensions")
    class BestOutput {

        @Test
        @DisplayName("Should prefer default crop when available")
        void preferDefaultCrop() {
            var geom = new SensorCropGeometry(5472, 3648, 100, 50, 5400, 3600, 120, 80, 5352, 3568);
            int[] dims = geom.bestOutputDimensions();
            assertThat(dims).containsExactly(5232, 3488);
        }

        @Test
        @DisplayName("Should fall back to active area when no default crop")
        void fallbackToActiveArea() {
            var geom = SensorCropGeometry.withActiveArea(5472, 3648, 100, 50, 5400, 3600);
            int[] dims = geom.bestOutputDimensions();
            assertThat(dims).containsExactly(5300, 3550);
        }

        @Test
        @DisplayName("Should fall back to sensor size when no active area or crop")
        void fallbackToSensor() {
            var geom = SensorCropGeometry.sensorOnly(5472, 3648);
            int[] dims = geom.bestOutputDimensions();
            assertThat(dims).containsExactly(5472, 3648);
        }
    }

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethods {

        @Test
        @DisplayName("sensorOnly should set active area to full sensor")
        void sensorOnlyFactory() {
            var geom = SensorCropGeometry.sensorOnly(1920, 1080);
            assertThat(geom.sensorWidth()).isEqualTo(1920);
            assertThat(geom.sensorHeight()).isEqualTo(1080);
            assertThat(geom.activeLeft()).isEqualTo(0);
            assertThat(geom.activeTop()).isEqualTo(0);
            assertThat(geom.activeRight()).isEqualTo(1920);
            assertThat(geom.activeBottom()).isEqualTo(1080);
            assertThat(geom.hasDefaultCrop()).isFalse();
        }

        @Test
        @DisplayName("withActiveArea should set active area without default crop")
        void withActiveAreaFactory() {
            var geom = SensorCropGeometry.withActiveArea(5472, 3648, 10, 20, 5462, 3628);
            assertThat(geom.activeWidth()).isEqualTo(5452);
            assertThat(geom.activeHeight()).isEqualTo(3608);
            assertThat(geom.hasDefaultCrop()).isFalse();
        }
    }
}
