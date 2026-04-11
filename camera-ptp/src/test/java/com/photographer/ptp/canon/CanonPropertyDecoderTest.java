package com.photographer.ptp.canon;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CanonPropertyDecoderTest {

    @Test
    void decodeAperture() {
        assertEquals("f/2.8", CanonPropertyDecoder.decodeAperture(0x23));
        assertEquals("f/4.0", CanonPropertyDecoder.decodeAperture(0x2B));
        assertEquals("f/5.6", CanonPropertyDecoder.decodeAperture(0x33));
        assertEquals("f/8.0", CanonPropertyDecoder.decodeAperture(0x3B));
    }

    @Test
    void decodeShutterSpeed() {
        assertEquals("1/60", CanonPropertyDecoder.decodeShutterSpeed(0x68));
        assertEquals("1/125", CanonPropertyDecoder.decodeShutterSpeed(0x70));
        assertEquals("1/250", CanonPropertyDecoder.decodeShutterSpeed(0x78));
        assertEquals("1/1000", CanonPropertyDecoder.decodeShutterSpeed(0x88));
    }

    @Test
    void decodeIso() {
        assertEquals("Auto", CanonPropertyDecoder.decodeIso(0x00));
        assertEquals("100", CanonPropertyDecoder.decodeIso(0x48));
        assertEquals("400", CanonPropertyDecoder.decodeIso(0x58));
        assertEquals("1600", CanonPropertyDecoder.decodeIso(0x68));
    }

    @Test
    void decodeBatteryLevel() {
        assertEquals(0, CanonPropertyDecoder.decodeBatteryLevel(0));
        assertEquals(50, CanonPropertyDecoder.decodeBatteryLevel(2));
        assertEquals(100, CanonPropertyDecoder.decodeBatteryLevel(4));
    }

    @Test
    void unknownValuesReturnFallback() {
        String result = CanonPropertyDecoder.decodeAperture(0xFF);
        assertTrue(result.contains("Unknown"));
    }
}
