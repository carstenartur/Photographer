package com.photographer.focusstack.cr3.raw.codec;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Random;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CrxTrackInputTest {
    @Test
    void arrayWindowCannotReadBeforeItsSlice() {
        var window = CrxTrackInput.fromArray(new byte[16]).window(4, 8);
        assertThrows(IOException.class, () -> window.unsignedByte(-1));
        assertThrows(IOException.class, () -> window.word(5));
    }

    @Test
    void interleavedSubbandsRefillIndependentlyAndStopAtTheirOwnEnds() throws IOException {
        int length = 152_011;
        byte[] file = new byte[2 * length + 23];
        new Random(314159).nextBytes(file);
        try (var in = new MemoryCacheImageInputStream(new ByteArrayInputStream(file))) {
            var track = CrxTrackInput.fromStream(in, 19, 2 * length);
            var first = new BitReader(track.window(0, length));
            var second = new BitReader(track.window(length, length));
            var expectedFirst = new BitReader(file, 19, length);
            var expectedSecond = new BitReader(file, 19 + length, length);
            for (int bits = length * 8; bits > 0;) {
                int step = Math.min(29, bits);
                assertEquals(expectedFirst.readBits(step), first.readBits(step));
                assertEquals(expectedSecond.readBits(step), second.readBits(step));
                bits -= step;
            }
            assertEquals(length, first.bytePosition());
            assertFalse(second.hasMore());
            assertThrows(IOException.class, first::readBit);
            assertThrows(IllegalArgumentException.class, () -> track.window(length, length + 1));
        }
    }

    @Test
    void rejectsTruncatedTrackEvenIfItsLastPaddingByteWouldNeverBeDecoded() throws IOException {
        try (var in = new MemoryCacheImageInputStream(new ByteArrayInputStream(new byte[40]))) {
            assertThrows(IOException.class, () -> CrxTrackInput.fromStream(in, 10, 31));
        }
    }
}
