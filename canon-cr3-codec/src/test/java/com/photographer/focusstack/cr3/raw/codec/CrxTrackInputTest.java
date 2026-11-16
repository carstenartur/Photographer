package com.photographer.focusstack.cr3.raw.codec;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.Executors;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class CrxTrackInputTest {
    @Test
    void arrayWindowCannotReadBeforeItsSlice() {
        var window = CrxTrackInput.fromArray(new byte[16]).window(4, 8);
        assertThrows(IOException.class, () -> window.unsignedByte(-1));
        assertThrows(IOException.class, () -> window.word(5));
        assertThrows(IOException.class, () -> window.longWord(1));
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
    void readsBigEndianWordsAcrossAnUnalignedChannelWindow(@TempDir Path directory) throws IOException {
        int readAhead = 32 * 1024;
        int trackStart = 11;
        int sliceOffset = 6;
        int sliceStart = trackStart + sliceOffset;
        int sliceLength = readAhead + 11;
        byte[] file = new byte[sliceStart + sliceLength + 19];
        new Random(353).nextBytes(file);
        Path path = Files.write(directory.resolve("track.bin"), file);
        try (var channel = FileChannel.open(path)) {
            var window = CrxTrackInput.fromChannel(channel, trackStart, file.length - trackStart)
                    .window(sliceOffset, sliceLength);
            var expected = ByteBuffer.wrap(file);
            for (int position : new int[] {0, readAhead - 3, sliceLength - Long.BYTES}) {
                assertEquals(expected.getLong(sliceStart + position), window.longWord(position));
            }
            assertThrows(IOException.class, () -> window.longWord(sliceLength - Long.BYTES + 1));
        }
    }

    @Test
    void concurrentRefillsReadFromTheirOwnSubbandPositions() throws Exception {
        int length = 100_000;
        byte[] file = new byte[2 * length];
        new Random(2718).nextBytes(file);
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(file)) {
            @Override
            public void seek(long pos) throws IOException {
                super.seek(pos);
                Thread.yield();  // Give the other reader an opportunity to change the cursor.
            }
        }; var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var track = CrxTrackInput.fromStream(input, 0, file.length);
            var first = executor.submit(() -> checkRefills(track.window(0, length), file, 0));
            var second = executor.submit(() -> checkRefills(track.window(length, length), file, length));
            first.get();
            second.get();
        }
    }

    private static Void checkRefills(CrxTrackInput.Window window, byte[] file, int offset) throws IOException {
        for (int i = 0; i < 200; i++) {
            int pos = i % 2 == 0 ? i : window.length - i;
            assertEquals(Byte.toUnsignedInt(file[offset + pos]), window.unsignedByte(pos));
        }
        return null;
    }

    @Test
    void rejectsTruncatedTrackEvenIfItsLastPaddingByteWouldNeverBeDecoded() throws IOException {
        try (var in = new MemoryCacheImageInputStream(new ByteArrayInputStream(new byte[40]))) {
            assertThrows(IOException.class, () -> CrxTrackInput.fromStream(in, 10, 31));
        }
    }
}
