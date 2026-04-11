package com.photographer.ptp.canon;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CanonEventParser}.
 */
class CanonEventParserTest {

    // ── Null / empty input ──

    @Test
    void parse_null_returnsEmptyList() {
        List<CanonEventParser.CanonEvent> events = CanonEventParser.parse(null);
        assertNotNull(events);
        assertTrue(events.isEmpty());
    }

    @Test
    void parse_emptyArray_returnsEmptyList() {
        List<CanonEventParser.CanonEvent> events = CanonEventParser.parse(new byte[0]);
        assertNotNull(events);
        assertTrue(events.isEmpty());
    }

    @Test
    void parse_tooShort_returnsEmptyList() {
        List<CanonEventParser.CanonEvent> events = CanonEventParser.parse(new byte[7]);
        assertNotNull(events);
        assertTrue(events.isEmpty());
    }

    // ── EVENT_OBJ_ADDED ──

    @Test
    void parse_objAdded_isObjectAddedTrue() {
        byte[] data = buildEvent(CanonConstants.EVENT_OBJ_ADDED, intBytes(0xABCD_1234));
        List<CanonEventParser.CanonEvent> events = CanonEventParser.parse(data);

        assertEquals(1, events.size());
        assertTrue(events.get(0).isObjectAdded());
        assertFalse(events.get(0).isPropertyChanged());
        assertFalse(events.get(0).isRequestComplete());
    }

    @Test
    void parse_objAdded_extractsObjectHandle() {
        int expectedHandle = 0x0042;
        byte[] data = buildEvent(CanonConstants.EVENT_OBJ_ADDED, intBytes(expectedHandle));
        List<CanonEventParser.CanonEvent> events = CanonEventParser.parse(data);

        assertEquals(1, events.size());
        assertEquals(expectedHandle, events.get(0).getObjectHandle());
    }

    @Test
    void parse_objAdded_largeHandle() {
        int expectedHandle = 0x1F4A8;
        byte[] data = buildEvent(CanonConstants.EVENT_OBJ_ADDED, intBytes(expectedHandle));
        List<CanonEventParser.CanonEvent> events = CanonEventParser.parse(data);

        assertEquals(expectedHandle, events.get(0).getObjectHandle());
    }

    // ── EVENT_PROP_CHANGED ──

    @Test
    void parse_propChanged_isPropertyChangedTrue() {
        byte[] data = buildEvent(CanonConstants.EVENT_PROP_CHANGED, intBytes(CanonConstants.DPC_EOS_ISO));
        List<CanonEventParser.CanonEvent> events = CanonEventParser.parse(data);

        assertEquals(1, events.size());
        assertTrue(events.get(0).isPropertyChanged());
        assertFalse(events.get(0).isObjectAdded());
    }

    @Test
    void parse_propChanged_extractsPropertyCode() {
        int expectedCode = CanonConstants.DPC_EOS_APERTURE;
        byte[] data = buildEvent(CanonConstants.EVENT_PROP_CHANGED, intBytes(expectedCode));
        List<CanonEventParser.CanonEvent> events = CanonEventParser.parse(data);

        assertEquals(1, events.size());
        assertEquals(expectedCode, events.get(0).getPropertyCode());
    }

    // ── EVENT_REQUEST_COMPLETE ──

    @Test
    void parse_requestComplete_isRequestCompleteTrue() {
        byte[] data = buildEvent(CanonConstants.EVENT_REQUEST_COMPLETE, intBytes(0));
        List<CanonEventParser.CanonEvent> events = CanonEventParser.parse(data);

        assertEquals(1, events.size());
        assertTrue(events.get(0).isRequestComplete());
    }

    // ── Multiple events ──

    @Test
    void parse_multipleEvents_allParsed() {
        byte[] event1 = buildEvent(CanonConstants.EVENT_OBJ_ADDED, intBytes(100));
        byte[] event2 = buildEvent(CanonConstants.EVENT_PROP_CHANGED, intBytes(CanonConstants.DPC_EOS_ISO));
        byte[] event3 = buildEvent(CanonConstants.EVENT_REQUEST_COMPLETE, intBytes(0));
        byte[] combined = concatenate(event1, event2, event3);

        List<CanonEventParser.CanonEvent> events = CanonEventParser.parse(combined);

        assertEquals(3, events.size());
        assertTrue(events.get(0).isObjectAdded());
        assertEquals(100, events.get(0).getObjectHandle());
        assertTrue(events.get(1).isPropertyChanged());
        assertEquals(CanonConstants.DPC_EOS_ISO, events.get(1).getPropertyCode());
        assertTrue(events.get(2).isRequestComplete());
    }

    @Test
    void parse_multipleObjAdded_allHandlesExtracted() {
        byte[] event1 = buildEvent(CanonConstants.EVENT_OBJ_ADDED, intBytes(10));
        byte[] event2 = buildEvent(CanonConstants.EVENT_OBJ_ADDED, intBytes(20));
        byte[] combined = concatenate(event1, event2);

        List<CanonEventParser.CanonEvent> events = CanonEventParser.parse(combined);

        assertEquals(2, events.size());
        assertEquals(10, events.get(0).getObjectHandle());
        assertEquals(20, events.get(1).getObjectHandle());
    }

    // ── Edge cases ──

    @Test
    void parse_zeroLengthEntry_stopsEarly() {
        // entryLength=0 is <= 8, so parser should stop
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0);  // entryLength = 0
        buf.putInt(CanonConstants.EVENT_OBJ_ADDED);
        List<CanonEventParser.CanonEvent> events = CanonEventParser.parse(buf.array());
        assertTrue(events.isEmpty(), "Zero-length entry should stop parsing");
    }

    @Test
    void parse_payloadWithNoHandle_returnsZero() {
        // Build an OBJ_ADDED event with empty payload
        byte[] data = buildEvent(CanonConstants.EVENT_OBJ_ADDED, new byte[0]);
        List<CanonEventParser.CanonEvent> events = CanonEventParser.parse(data);

        // The event is parsed (entryLength=8 would be <= 8, so actually it stops early)
        // entryLength of 8 with no payload means length <= 8, parser stops
        assertTrue(events.isEmpty(), "Event with entryLength=8 (no payload) causes early stop");
    }

    // ── Helpers ──

    /**
     * Builds a single event blob: entryLength(4) + eventType(4) + payload(N).
     * entryLength = 8 + payload.length
     */
    private static byte[] buildEvent(int eventType, byte[] payload) {
        int entryLength = 8 + payload.length;
        ByteBuffer buf = ByteBuffer.allocate(entryLength).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(entryLength);
        buf.putInt(eventType);
        buf.put(payload);
        return buf.array();
    }

    private static byte[] intBytes(int value) {
        ByteBuffer buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(value);
        return buf.array();
    }

    private static byte[] concatenate(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, result, offset, a.length);
            offset += a.length;
        }
        return result;
    }
}
