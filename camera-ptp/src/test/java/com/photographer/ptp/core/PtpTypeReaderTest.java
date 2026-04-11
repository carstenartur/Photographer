package com.photographer.ptp.core;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class PtpTypeReaderTest {

    @Test
    void readInt8() {
        PtpTypeReader reader = new PtpTypeReader(new byte[] { (byte) 0xFF });
        assertEquals(255, reader.readInt8());
    }

    @Test
    void readInt16LittleEndian() {
        // 0x0201 in little-endian = bytes [01, 02]
        PtpTypeReader reader = new PtpTypeReader(new byte[] { 0x01, 0x02 });
        assertEquals(0x0201, reader.readInt16());
    }

    @Test
    void readInt32LittleEndian() {
        // 0x04030201 in little-endian = bytes [01, 02, 03, 04]
        PtpTypeReader reader = new PtpTypeReader(new byte[] { 0x01, 0x02, 0x03, 0x04 });
        assertEquals(0x04030201, reader.readInt32());
    }

    @Test
    void readUInt32() {
        // 0xFFFFFFFF = 4294967295 unsigned
        PtpTypeReader reader = new PtpTypeReader(new byte[] {
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF });
        assertEquals(4294967295L, reader.readUInt32());
    }

    @Test
    void readPtpString() {
        // PTP String: length=4 (3 chars + null), then 3 UCS-2LE chars + null
        ByteBuffer buf = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 4); // length including null
        buf.putShort((short) 'A');
        buf.putShort((short) 'B');
        buf.putShort((short) 'C');
        buf.putShort((short) 0); // null
        PtpTypeReader reader = new PtpTypeReader(buf.array());
        assertEquals("ABC", reader.readPtpString());
    }

    @Test
    void readEmptyPtpString() {
        PtpTypeReader reader = new PtpTypeReader(new byte[] { 0x00 });
        assertEquals("", reader.readPtpString());
    }

    @Test
    void readUInt16Array() {
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(2); // count
        buf.putShort((short) 0x1001);
        buf.putShort((short) 0x1002);
        PtpTypeReader reader = new PtpTypeReader(buf.array());
        int[] arr = reader.readUInt16Array();
        assertEquals(2, arr.length);
        assertEquals(0x1001, arr[0]);
        assertEquals(0x1002, arr[1]);
    }

    @Test
    void readUInt32Array() {
        ByteBuffer buf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(2); // count
        buf.putInt(0x00010001);
        buf.putInt(0x00020001);
        PtpTypeReader reader = new PtpTypeReader(buf.array());
        int[] arr = reader.readUInt32Array();
        assertEquals(2, arr.length);
        assertEquals(0x00010001, arr[0]);
        assertEquals(0x00020001, arr[1]);
    }

    @Test
    void remainingAndPosition() {
        PtpTypeReader reader = new PtpTypeReader(new byte[10]);
        assertEquals(10, reader.remaining());
        assertEquals(0, reader.position());
        reader.readInt8();
        assertEquals(9, reader.remaining());
        assertEquals(1, reader.position());
    }

    @Test
    void skipBytes() {
        PtpTypeReader reader = new PtpTypeReader(new byte[] { 1, 2, 3, 4, 5 });
        reader.skip(3);
        assertEquals(4, reader.readInt8());
    }

    @Test
    void readBytes() {
        PtpTypeReader reader = new PtpTypeReader(new byte[] { 10, 20, 30, 40, 50 });
        byte[] read = reader.readBytes(3);
        assertArrayEquals(new byte[] { 10, 20, 30 }, read);
        assertEquals(2, reader.remaining());
    }
}
