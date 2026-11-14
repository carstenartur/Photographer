package com.photographer.focusstack.cr3.raw.codec;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class BitReaderTest {
    @Test
    void readsEveryBitWidthAcrossWordAndSliceBoundaries() throws IOException {
        byte[] bytes = new byte[25];
        new Random(42).nextBytes(bytes);
        for (int length = 4; length <= 19; length++) {
            for (int startBit = 0; startBit < 32; startBit++) {
                for (int width = 0; width <= Math.min(32, length * 8 - startBit); width++) {
                    var bits = new BitReader(bytes, 3, length);
                    for (int i = 0; i < startBit; i++) bits.readBit();
                    int expected = 0;
                    for (int i = startBit; i < startBit + width; i++) {
                        expected = expected << 1 | (bytes[3 + i / 8] >> (7 - i % 8) & 1);
                    }
                    assertEquals(expected, bits.readBits(width));
                    assertEquals((startBit + width) / 8, bits.bytePosition());
                    assertEquals(Math.ceilDiv(startBit + width, 8), bits.consumedBytes());
                    assertEquals(startBit + width < length * 8, bits.hasMore());
                }
            }
        }
    }

    @Test
    void boundsUnaryCodesAcrossRefillsAndStopsAtTheSliceEnd() throws IOException {
        byte[] data = new byte[8];
        data[6] = 0x40;
        assertEquals(41, new BitReader(data, 1, 6).readUnary(41));
        assertThrows(IOException.class, () -> new BitReader(data, 1, 6).readUnary(40));
        assertThrows(IOException.class, () -> new BitReader(data, 1, 5).readUnary(41));
        assertThrows(IllegalArgumentException.class, () -> new BitReader(data, 2, Integer.MAX_VALUE));
        Arrays.fill(data, (byte) -1);
        var bits = new BitReader(data, 1, 2);
        assertEquals(0, bits.readUnary());
        assertThrows(IOException.class, () -> bits.readBits(32));
        assertFalse(bits.hasMore());
    }
}
