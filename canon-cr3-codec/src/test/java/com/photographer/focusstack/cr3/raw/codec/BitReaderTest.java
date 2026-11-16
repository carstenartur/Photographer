package com.photographer.focusstack.cr3.raw.codec;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class BitReaderTest {
    @Test
    void riceSymbolsMatchSeparatePrefixAndRemainderReads() throws IOException {
        var random = new Random(1719);
        byte[] bytes = new byte[2048];
        random.nextBytes(bytes);
        for (int k = 0; k <= 15; k++) {
            var expected = new BitReader(bytes);
            var actual = new BitReader(bytes);
            while (expected.hasMore()) {
                int value;
                try {
                    int quotient = expected.readUnary(41);
                    value = quotient == 41 ? expected.readBits(21) : (quotient << k) | expected.readBits(k);
                } catch (IOException end) {
                    int width = k;
                    assertThrows(IOException.class, () -> actual.readRice(width, 41, 21));
                    break;
                }
                assertEquals(value, actual.readRice(k, 41, 21));
                assertEquals(expected.bytePosition(), actual.bytePosition());
            }
        }
        byte[] escaped = {0, 0, 0, 0, 0, 0x7f, (byte) 0xff, (byte) 0xfe};
        assertEquals((1 << 21) - 1, new BitReader(escaped).readRice(0, 41, 21));
        assertThrows(IOException.class, () -> new BitReader(new byte[9]).readRice(0, 41, 21));
    }

    @Test
    void readsDetailRiceWithOptionalRunFlagsAcrossReservoirBoundaries() throws IOException {
        var random = new Random(726);
        byte[] bytes = new byte[2048];
        random.nextBytes(bytes);
        for (int k = 0; k <= 15; k++) {
            var expected = new BitReader(bytes, 1, bytes.length - 2);
            var actual = new BitReader(bytes, 1, bytes.length - 2);
            while (expected.hasMore()) {
                boolean hasRunFlag = random.nextBoolean();
                int expectedCode;
                try {
                    expectedCode = readSeparateDetailCode(expected, k, hasRunFlag);
                } catch (IOException end) {
                    int width = k;
                    var actualEnd = assertThrows(IOException.class, () -> actual.readDetailRice(width, hasRunFlag));
                    assertEquals(end.getMessage(), actualEnd.getMessage());
                    break;
                }
                assertEquals(expectedCode, actual.readDetailRice(k, hasRunFlag));
                assertEquals(expected.bytePosition(), actual.bytePosition());
                assertEquals(expected.consumedBytes(), actual.consumedBytes());
                if (expected.hasMore()) {
                    assertEquals(expected.readBit(), actual.readBit());
                }
            }
        }
    }

    @Test
    void readsDetailRiceEscapeAndRejectsTruncationAndAnExcessiveQuotient() throws IOException {
        byte[] escapedAfterFlag = {0, 0, 0, 0, 0, 0x3f, (byte) 0xff, (byte) 0xff};
        var escaped = new BitReader(escapedAfterFlag);
        int literal = (1 << 21) - 1;
        assertEquals((literal << 4) | 15, escaped.readDetailRice(15, true));
        assertFalse(escaped.hasMore());

        byte[] escapedZero = {0, 0, 0, 0, 0, 0x20, 0, 0};
        assertEquals(14, new BitReader(escapedZero).readDetailRice(15, true));

        assertThrows(IOException.class, () -> new BitReader(escapedAfterFlag, 0, 7).readDetailRice(15, true));
        assertThrows(IOException.class, () -> new BitReader(new byte[9]).readDetailRice(0, true));
        assertThrows(IOException.class, () -> new BitReader(new byte[0]).readDetailRice(0, true));
    }

    private static int readSeparateDetailCode(BitReader bits, int k, boolean hasRunFlag) throws IOException {
        if (hasRunFlag && bits.readBit() == 1) {
            return BitReader.POSITIVE_RUN;
        }
        int quotient = bits.readUnary(41);
        int value = quotient == 41 ? bits.readBits(21) : (quotient << k) | bits.readBits(k);
        return (value << 4) | CrxEntropyState.adaptK(k, value);
    }

    @Test
    void readsEveryBitWidthAcrossWordAndSliceBoundaries() throws IOException {
        byte[] bytes = new byte[25];
        new Random(42).nextBytes(bytes);
        for (int length = 4; length <= 19; length++) {
            for (int startBit = 0; startBit < Math.min(96, length * 8); startBit++) {
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
    void consumesExactlyOneWordOfUnaryBits() throws IOException {
        byte[] data = new byte[17];
        data[8] = 1;
        var bits = new BitReader(data, 1, 16);
        assertEquals(63, bits.readUnary());
        assertEquals(8, bits.bytePosition());
        assertEquals(0, bits.readBits(32));
        assertEquals(0, bits.readBits(32));
        assertFalse(bits.hasMore());

        data[8] = 0;
        data[9] = 0x40;
        assertEquals(65, new BitReader(data, 1, 9).readUnary());
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
