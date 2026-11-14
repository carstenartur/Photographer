package com.photographer.focusstack.cr3.raw.codec;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class CrxRunLengthTest {
    private static final int[] WIDTHS = {
            0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3,
            4, 4, 5, 5, 6, 6, 7, 7, 8, 9, 10, 11, 12, 13, 14, 15
    };

    @Test
    void prefixAndTailMatchTheScalarStateAcrossRefillsRowsAndTruncation() throws IOException {
        byte[] data = new byte[263];
        new Random(8457).nextBytes(data);
        for (int offset = 0; offset < 32; offset++) {
            verifyStream(data, offset);
        }
        Arrays.fill(data, (byte) 0xff);
        verifyStream(data, 0); // Cross buffer boundaries and saturate the adaptive index.
        assertEquals(1, new CrxEntropyState(new BitReader(new byte[0]), 0).readRunLength(1));
    }

    private static void verifyStream(byte[] data, int offset) throws IOException {
        var expected = new BitReader(data, 2, data.length - 3);
        var actual = new BitReader(data, 2, data.length - 3);
        expected.readBits(offset);
        actual.readBits(offset);
        var decoder = new CrxEntropyState(actual, 0);
        var random = new Random(offset);
        int index = 0;
        while (expected.hasMore()) {
            int remaining = switch (random.nextInt(4)) {
                case 0 -> 2;
                case 1 -> 1 + (1 << WIDTHS[index]);
                case 2 -> Integer.MAX_VALUE;
                default -> 1 + random.nextInt(4096);
            };
            long copies = 1;
            try {
                while (copies < remaining) {
                    int width = WIDTHS[index];
                    if (expected.readBit() == 0) {
                        copies += expected.readBits(width);
                        index = Math.max(0, index - 1);
                        break;
                    }
                    copies += 1 << width;
                    if (copies <= remaining) {
                        index = Math.min(WIDTHS.length - 1, index + 1);
                    }
                }
            } catch (IOException end) {
                assertThrows(IOException.class, () -> decoder.readRunLength(remaining));
                assertFalse(actual.hasMore());
                return;
            }
            assertEquals(Math.min(copies, remaining), decoder.readRunLength(remaining));
            assertEquals(expected.bytePosition(), actual.bytePosition());
            if (expected.hasMore()) {
                assertEquals(expected.readBit(), actual.readBit()); // Next symbol starts at the same bit.
            }
        }
        assertFalse(actual.hasMore());
    }

    @Test
    void peekingAContinuedRunLeavesTheSliceUntouched() throws IOException {
        var bits = new BitReader(new byte[]{(byte) 0xff});
        assertEquals(-1, bits.peekRunPrefix());
        assertEquals(0, bits.bytePosition());
        assertEquals(255, bits.readBits(8));
        assertThrows(IOException.class, bits::peekRunPrefix);
    }
}
