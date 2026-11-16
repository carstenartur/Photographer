package com.photographer.focusstack.cr3.raw.codec;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CrxSensorDecoderTest {
    private static final int HEADER = 2 * (12 + 4 * 24);
    private static final Cmp1Box CMP = new Cmp1Box((short) 1, 4, 2, 2, 2,
            14, 4, 0, 0, 0, false, HEADER, false);
    private static final CrxHeader.CodecVersion V1 = CrxHeader.CodecVersion.V1;

    @Test
    void serialOverloadsKeepDirectoryOrderAndParallelWorkersDeliverEverySample() throws Exception {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(track()))) {
            var decoder = CrxSensorDecoder.prepare(input, 0, HEADER + 64, 4, 2, 14, V1, CMP);
            var order = new ArrayList<Integer>();
            CrxSensorDecoder.RowConsumer record = (x, y, row) -> order.add(y * 4 + x);
            decoder.decodeRows(record);
            assertEquals(List.of(5, 0, 4, 1, 3, 6, 2, 7), order);
            order.clear();
            decoder.decodeRows(record, 1);
            assertEquals(List.of(5, 0, 4, 1, 3, 6, 2, 7), order);
            for (int workers : new int[]{0, 2, 3, 4}) {
                assertArrayEquals(new int[]{8192, 8193, 8194, 8195, 8196, 8197, 8198, 8199},
                        pixels(decoder, workers));
            }
        }
    }

    @Test
    void callbacksForDifferentBayerPositionsCanOverlapWhileTilesStayOrdered() throws Exception {
        assumeTrue(Runtime.getRuntime().availableProcessors() >= 2);
        var decoder = CrxSensorDecoder.prepare(track(), 4, 2, 14, V1, CMP);
        var callers = ConcurrentHashMap.<Thread>newKeySet();
        var firstPlanes = new CountDownLatch(2);
        int[] nextTile = new int[4];
        decoder.decodeRows((x, y, row) -> {
            if (callers.add(Thread.currentThread())) {
                firstPlanes.countDown();
                try {
                    assertTrue(firstPlanes.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }
            assertEquals(nextTile[2 * y + (x & 1)]++, x / 2);
        }, 2);
        assertArrayEquals(new int[]{2, 2, 2, 2}, nextTile);
    }

    @Test
    void positionedDecodeUsesTrackOffsetWithoutChangingTheSharedFileCursor(@TempDir Path temp) throws Exception {
        byte[] track = track();
        Path file = temp.resolve("offset.bin");
        Files.write(file, ByteBuffer.allocate(11 + track.length).position(11).put(track).array());
        try (FileChannel input = FileChannel.open(file)) {
            input.position(5);
            var decoder = CrxSensorDecoder.prepare(input, 11, track.length, 4, 2, 14, V1, CMP);
            assertArrayEquals(new int[]{8192, 8193, 8194, 8195, 8196, 8197, 8198, 8199}, pixels(decoder, 4));
            assertEquals(5, input.position());
            assertThrows(IOException.class,
                    () -> CrxSensorDecoder.prepare(input, 12, track.length, 4, 2, 14, V1, CMP));
        }
    }

    @Test
    void preservesLosslessPayloadFailureTypeAndCause() throws Exception {
        byte[] track = track();
        track[HEADER + 8] = 1; // Corrupt the opening prefix of the second physical plane.
        var decoder = CrxSensorDecoder.prepare(track, 4, 2, 14, V1, CMP);
        for (int workers : new int[]{1, 4}) {
            var failure = assertThrows(Cr3Exception.class, () -> pixels(decoder, workers));
            assertInstanceOf(IOException.class, failure.getCause());
        }
    }

    @Test
    void selectedPlanePrefixAvoidsUnrequestedPayload() throws Exception {
        byte[] track = track();
        track[HEADER + 8] = 1; // An invalid opening outside the selection.
        // Each plane has a valid first row and no bytes for the second row.
        var twoRows = new Cmp1Box((short) 1, 4, 4, 2, 4,
                14, 4, 0, 0, 0, false, HEADER, false);
        var decoder = CrxSensorDecoder.prepare(track, 4, 4, 14, V1, twoRows);
        int[] output = new int[2];
        decoder.decodeSelectedRows((x, y, row) -> output[x - 2] = row[0], 2,
                (x, y, columns, rows) -> x >= 2 && y == 0 ? 1 : 0, true);
        assertArrayEquals(new int[]{8194, 8195}, output);
        assertThrows(Cr3Exception.class, () -> decoder.decodeRows((x, y, row) -> {}));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 8})
    void independentTilesCanUseTheAvailableProcessors(int maxWorkers) throws Exception {
        int workers = Math.min(Runtime.getRuntime().availableProcessors(), 8);
        assumeTrue(workers > 4);
        var decoder = CrxSensorDecoder.prepare(track(), 4, 2, 14, V1, CMP);
        var active = new CountDownLatch(workers);
        int[] pixels = new int[8];
        decoder.decodeSelectedRows((x, y, row) -> {
            active.countDown();
            try {
                assertTrue(active.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            pixels[4 * y + x] = row[0];
        }, maxWorkers, CrxSensorDecoder.PlaneRowLimit.ALL_ROWS, true);
        assertArrayEquals(new int[]{8192, 8193, 8194, 8195, 8196, 8197, 8198, 8199}, pixels);
    }

    @Test
    void selectedTilesOfOneBayerPhaseCanUseTwoWorkers() throws Exception {
        assumeTrue(Runtime.getRuntime().availableProcessors() >= 2);
        var decoder = CrxSensorDecoder.prepare(track(), 4, 2, 14, V1, CMP);
        var active = new CountDownLatch(2);
        int[] pixels = new int[2];
        decoder.decodeSelectedRows((x, y, row) -> {
            active.countDown();
            try {
                assertTrue(active.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            pixels[x / 2] = row[0];
        }, 2, (x, y, columns, rows) -> (x & 1) == 0 && y == 0 ? rows : 0, true);
        assertArrayEquals(new int[]{8192, 8194}, pixels);
    }

    @Test
    void existingDecoderLambdasCanServeTheNewOverload() throws Exception {
        CrxSensorDecoder custom = consumer -> consumer.write(0, 0, new int[]{77});
        assertEquals(77, pixels(custom, 4)[0]);
        assertThrows(IllegalArgumentException.class, () -> pixels(custom, -1));
    }

    private static int[] pixels(CrxSensorDecoder decoder, int workers) throws Cr3Exception, IOException {
        int[] pixels = new int[8];
        decoder.decodeRows((x, y, row) -> pixels[4 * y + x] = row[0], workers);
        return pixels;
    }

    // Each tile contains four one-sample planes. Directory order differs from CFA order and between tiles.
    private static byte[] track() {
        var track = ByteBuffer.allocate(HEADER + 64);
        var payload = track.duplicate().position(HEADER);
        int[][] order = {{3, 0, 2, 1}, {1, 2, 0, 3}};
        for (int tile = 0; tile < 2; tile++) {
            record(track, 0xff01, 32, tile << 4);
            for (int plane : order[tile]) {
                record(track, 0xff02, 8, plane << 4 | 8);
                record(track, 0xff03, 8, 0);
                int pixel = (plane / 2) * 4 + 2 * tile + (plane & 1);
                payload.putLong(0x200000L + 2 * pixel);
            }
        }
        return track.array();
    }

    private static void record(ByteBuffer bytes, int marker, int size, int flags) {
        bytes.putShort((short) marker).putShort((short) 8).putInt(size).putInt(flags << 24);
    }
}
