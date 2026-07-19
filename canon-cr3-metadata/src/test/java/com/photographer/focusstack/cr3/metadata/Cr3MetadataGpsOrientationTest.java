package com.photographer.focusstack.cr3.metadata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for GPS and orientation fields in {@link Cr3Metadata}.
 */
class Cr3MetadataGpsOrientationTest {

    @Test
    @DisplayName("GPS fields default to null")
    void gpsFieldsDefault() {
        Cr3Metadata meta = new Cr3Metadata();
        assertThat(meta.gpsLatitude()).isNull();
        assertThat(meta.gpsLongitude()).isNull();
        assertThat(meta.gpsAltitude()).isNull();
        assertThat(meta.gpsTimestamp()).isNull();
    }

    @Test
    @DisplayName("GPS fields can be set and read")
    void setGpsFields() {
        Cr3Metadata meta = new Cr3Metadata();
        meta.setGpsLatitude(48.8566);
        meta.setGpsLongitude(2.3522);
        meta.setGpsAltitude(35.0);
        meta.setGpsTimestamp("12:34:56");

        assertThat(meta.gpsLatitude()).isEqualTo(48.8566);
        assertThat(meta.gpsLongitude()).isEqualTo(2.3522);
        assertThat(meta.gpsAltitude()).isEqualTo(35.0);
        assertThat(meta.gpsTimestamp()).isEqualTo("12:34:56");
    }

    @Test
    @DisplayName("Negative GPS values for south/west")
    void negativeGpsValues() {
        Cr3Metadata meta = new Cr3Metadata();
        meta.setGpsLatitude(-33.8688);  // Sydney (south)
        meta.setGpsLongitude(-70.6693); // Santiago (west)

        assertThat(meta.gpsLatitude()).isLessThan(0);
        assertThat(meta.gpsLongitude()).isLessThan(0);
    }

    @Test
    @DisplayName("Orientation defaults to 1 (normal)")
    void orientationDefault() {
        Cr3Metadata meta = new Cr3Metadata();
        assertThat(meta.orientation()).isEqualTo(1);
    }

    @Test
    @DisplayName("Orientation can be set to valid values")
    void setOrientation() {
        Cr3Metadata meta = new Cr3Metadata();
        meta.setOrientation(6);
        assertThat(meta.orientation()).isEqualTo(6);
    }

    @Test
    @DisplayName("WhiteBalance multipliers default to null")
    void wbDefault() {
        Cr3Metadata meta = new Cr3Metadata();
        assertThat(meta.whiteBalanceMultipliers()).isNull();
    }

    @Test
    @DisplayName("WhiteBalance multipliers can be set")
    void setWb() {
        Cr3Metadata meta = new Cr3Metadata();
        meta.setWhiteBalanceMultipliers(new double[]{1.8, 1.0, 1.5});
        assertThat(meta.whiteBalanceMultipliers()).containsExactly(1.8, 1.0, 1.5);
    }

    @Test
    @DisplayName("PerChannel black levels default to null")
    void blackLevelsDefault() {
        Cr3Metadata meta = new Cr3Metadata();
        assertThat(meta.perChannelBlackLevels()).isNull();
    }

    @Test
    @DisplayName("PerChannel black levels can be set")
    void setBlackLevels() {
        Cr3Metadata meta = new Cr3Metadata();
        meta.setPerChannelBlackLevels(new int[]{510, 512, 512, 514});
        assertThat(meta.perChannelBlackLevels()).containsExactly(510, 512, 512, 514);
    }

    @Test
    @DisplayName("MakerNote data defaults to null")
    void makerNoteDefault() {
        Cr3Metadata meta = new Cr3Metadata();
        assertThat(meta.makerNoteData()).isNull();
    }

    @Test
    @DisplayName("toString includes GPS when present")
    void toStringWithGps() {
        Cr3Metadata meta = new Cr3Metadata();
        meta.setCameraModel("Test");
        meta.setGpsLatitude(48.8566);
        meta.setGpsLongitude(2.3522);
        String str = meta.toString();
        assertThat(str).contains("GPS=");
    }

    @Test
    @DisplayName("toString includes WB when present")
    void toStringWithWb() {
        Cr3Metadata meta = new Cr3Metadata();
        meta.setCameraModel("Test");
        meta.setWhiteBalanceMultipliers(new double[]{1.8, 1.0, 1.5});
        String str = meta.toString();
        assertThat(str).contains("WB=");
    }
}
