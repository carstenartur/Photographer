package com.photographer.focusstack.cr3.decode;

import com.photographer.focusstack.cr3.decode.Cr3MemoryPolicy.Estimate;
import com.photographer.focusstack.cr3.decode.Cr3MemoryPolicy.ImageBytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Rectangle;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static com.photographer.focusstack.cr3.decode.Cr3DecodeOptions.MemoryMode.*;
import static org.junit.jupiter.api.Assertions.*;

class Cr3MemoryPolicyTest {
    private static final long MIB = 1024 * 1024;

    // At four workers the normal path needs 84 MiB, the compact path needs 44 MiB.
    // Per worker: 4 MiB for decoding and 1 MiB for processing.
    private static final Estimate ESTIMATE = new Estimate(new ImageBytes(60 * MIB, 20 * MIB, 20 * MIB),
            4 * MIB, 4 * MIB, 4, MIB, 4);

    @Test
    void automaticSelectionUsesTheLargestCompactLimitThatKeepsTheReserve() {
        var options = Cr3DecodeOptions.rawProcessed();
        // A maximum of 100 MiB reserves 10 MiB. Zero means keep the normal path.
        assertEquals(0, ESTIMATE.compactWorkers(options, 4, 94 * MIB, 100 * MIB));
        assertEquals(4, ESTIMATE.compactWorkers(options, 4, 94 * MIB - 1, 100 * MIB));
        assertEquals(4, ESTIMATE.compactWorkers(options, 4, 54 * MIB, 100 * MIB));
        assertEquals(3, ESTIMATE.compactWorkers(options, 4, 54 * MIB - 1, 100 * MIB));
        assertEquals(2, ESTIMATE.compactWorkers(options, 4, 44 * MIB, 100 * MIB));
        assertEquals(1, ESTIMATE.compactWorkers(options, 4, 44 * MIB - 1, 100 * MIB));
        assertEquals(1, ESTIMATE.compactWorkers(options, 4, 0, 100 * MIB));
        assertEquals(1, ESTIMATE.compactWorkers(options, 4, Long.MIN_VALUE, 100 * MIB));
        assertEquals(0, ESTIMATE.compactWorkers(options.memoryMode(FAST), 4, 0, 100 * MIB));
    }

    @Test
    void rotatedPartialBudgetUsesTheLargerOfTransposeAndCopyPeaks() {
        // 40 MiB of haloed packed storage, a 36 MiB rotated output, and a separate
        // 42 MiB destination. Only the detached 36 MiB result survives to the copy.
        var pixels = ImageBytes.rgb(false, 10 * MIB, 9 * MIB, true, 6, false);
        var rotated = new Estimate(pixels, MIB, MIB / 4, 4, 0, 0);
        var caller = Cr3DecodeOptions.rawProcessed().postDecodeMemoryBytes(42 * MIB);
        // Four-worker development and subsequent copy both peak at 78 MiB.
        assertEquals(0, rotated.compactWorkers(caller, 4, 88 * MIB, 100 * MIB));
        assertEquals(1, rotated.compactWorkers(caller, 4, 88 * MIB - 1, 100 * MIB));
        // The nonrotated subimage retains its 40 MiB parent, requiring 82 MiB at copy time.
        var view = new Estimate(ImageBytes.rgb(false, 10 * MIB, 9 * MIB, true, 1, false),
                MIB, MIB / 4, 4, 0, 0);
        assertEquals(1, view.compactWorkers(caller, 4, 91 * MIB, 100 * MIB));
        assertEquals(0, view.compactWorkers(caller, 4, 92 * MIB, 100 * MIB));
        assertEquals(42 * MIB, caller.copy().postDecodeMemoryBytes());
        assertThrows(IllegalArgumentException.class, () -> caller.postDecodeMemoryBytes(-1));
        assertEquals(1, rotated.compactWorkers(caller.postDecodeMemoryBytes(Long.MAX_VALUE),
                4, Long.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test
    void explicitCompactHonorsWorkerCapsAndReserveBounds() {
        var options = Cr3DecodeOptions.rawProcessed().memoryMode(LOW_MEMORY);
        for (int limit : new int[]{1, 2, 3}) {
            assertEquals(limit, ESTIMATE.compactWorkers(options.maxWorkers(limit), 4, 100 * MIB, 100 * MIB));
        }
        assertEquals(2, ESTIMATE.compactWorkers(options.maxWorkers(99), 2, 100 * MIB, 100 * MIB));
        assertEquals(4, ESTIMATE.compactWorkers(options.maxWorkers(0), 8, 100 * MIB, 100 * MIB));
        assertEquals(3, ESTIMATE.compactWorkers(options, 4, 52 * MIB - 1, 64 * MIB)); // 8 MiB floor
        assertEquals(4, ESTIMATE.compactWorkers(options, 4, 108 * MIB, 1024 * MIB)); // 64 MiB ceiling
    }

    @Test
    void pixelEstimatesFollowBufferOwnershipAndRoundVisitedBitsToWords() {
        // 65 sites use two BitSet words.
        assertEquals(new ImageBytes(260, 260, 260), ImageBytes.rgb(false, 65, 65, false, 1, false));
        assertEquals(new ImageBytes(520, 276, 260), ImageBytes.rgb(false, 65, 65, false, 6, false));
        assertEquals(new ImageBytes(390, 390, 390), ImageBytes.rgb(true, 65, 65, false, 1, false));
        assertEquals(new ImageBytes(520, 406, 390), ImageBytes.rgb(true, 65, 65, false, 6, false));
        // A partial RGB16 keeps its native Bayer halo alongside the output banks.
        assertEquals(new ImageBytes(298, 298, 168), ImageBytes.rgb(true, 65, 28, true, 1, false));
        assertEquals(new ImageBytes(298, 298, 168), ImageBytes.rgb(true, 65, 28, true, 6, false));
        // A transposed packed subimage needs a separate destination.
        assertEquals(new ImageBytes(372, 372, 112), ImageBytes.rgb(false, 65, 28, true, 6, false));
        assertEquals(new ImageBytes(0, 0, 0), ImageBytes.rgb(false, 65, 65, false, 2, true));
        assertEquals(new ImageBytes(130, 130, 130), ImageBytes.samples(65, Short.BYTES));
        assertEquals(new ImageBytes(0, 0, 0), ImageBytes.samples(0, Short.BYTES));
    }

    @Test
    void budgetsCodecWindowsProcessingRowsAndOpticalBlackEvenForCompactReads() {
        // Two tiles, four Bayer phases each. At limit 11 only eight codec workers need scratch.
        var pixels = new ImageBytes(100, 80, 80);
        var raw = Estimate.forTrack(pixels, 1024, 600, 512, false, 120);
        var craw = Estimate.forTrack(pixels, 1024, 600, 512, true, 120);
        long fixed = MIB + 32L * 600;
        assertEquals(80 + fixed + 8 * (32768 + 8 * 256) + 11 * 40 * 122, raw.bytes(80, 11));
        assertEquals(80 + fixed + 8 * (163840 + 64 * 272) + 11 * 40 * 122, craw.bytes(80, 11));
        var nativeRaw = Estimate.forTrack(pixels, 1024, 600, 512, false, 0);
        assertEquals(80 + fixed + 8 * (32768 + 8 * 256), nativeRaw.bytes(80, 11));
    }

    @Test
    void invalidOrOverflowingEstimatesCannotCreateHeadroom() {
        var options = Cr3DecodeOptions.rawProcessed();
        var negative = new Estimate(new ImageBytes(-1, -1, -1), 0, 1, 8, 0, 0);
        assertEquals(1, negative.compactWorkers(options, 8, Long.MAX_VALUE, Long.MAX_VALUE));
        var oversized = ImageBytes.rgb(true, Long.MAX_VALUE, Long.MAX_VALUE, false, 6, false);
        assertEquals(new ImageBytes(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE), oversized);
        var overflow = new Estimate(new ImageBytes(10, 10, 10), 2, Long.MAX_VALUE / 2, 8, 0, 0);
        assertEquals(Long.MAX_VALUE, overflow.bytes(10, 4));
        assertEquals(1, overflow.compactWorkers(options, 8, Long.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test
    void explicitPolicyCopiesTheOptionsAndWarnsOnceWithTheSelectedWorkerLimit() {
        var options = Cr3DecodeOptions.rawProcessed().maxWorkers(4).memoryMode(LOW_MEMORY)
                .bitDepth(Cr3DecodeOptions.BitDepth.SIXTEEN).cropToActiveArea(false).experimentalPedestal(347);
        var output = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        try (var error = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setErr(error);
            var estimate = Estimate.forTrack(new ImageBytes(1024, 1024, 1024), 128, 128, 128, false, 128);
            var selected = Cr3MemoryPolicy.apply(options, estimate);
            assertNotSame(options, selected);
            assertEquals(Math.min(4, Runtime.getRuntime().availableProcessors()), selected.maxWorkers());
            assertEquals(4, options.maxWorkers());
            assertEquals(LOW_MEMORY, selected.memoryMode());
            assertEquals(Cr3DecodeOptions.BitDepth.SIXTEEN, selected.bitDepth());
            assertFalse(selected.cropToActiveArea());
            assertEquals(347, selected.experimentalPedestalLevel());
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("worker limit " + selected.maxWorkers()));
            assertEquals(1, output.toString(StandardCharsets.UTF_8).lines()
                    .filter(line -> line.startsWith("CR3 low-memory:")).count());
        } finally {
            System.setErr(previous);
        }
        assertThrows(NullPointerException.class, () -> options.memoryMode(null));
    }

    @ParameterizedTest
    @EnumSource(Cr3DecodeOptions.BitDepth.class)
    void compactReadsPreserveEveryOrientedSampleAndCallerOptions(Cr3DecodeOptions.BitDepth depth) throws Exception {
        var output = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        try (var error = new PrintStream(output, true, StandardCharsets.UTF_8);
             var source = Cr3DecoderTest.class.getResourceAsStream("four-sites.cr3");
             var input = new MemoryCacheImageInputStream(source);
             var decoder = new Cr3Decoder()) {
            System.setErr(error);
            decoder.setInput(input);
            decoder.getMetadata().setWhiteBalanceMultipliers(new double[]{1.37, 0.91, 0.73});
            var fast = Cr3DecodeOptions.rawProcessed().bitDepth(depth).memoryMode(FAST).maxWorkers(4);
            var compact = fast.copy().memoryMode(LOW_MEMORY);
            for (int orientation = 1; orientation <= 8; orientation++) {
                decoder.getMetadata().setOrientation(orientation);
                for (boolean srgb : new boolean[]{false, true}) {
                    fast.applySrgbConversion(srgb);
                    compact.applySrgbConversion(srgb);
                    var expected = decoder.decode(fast);
                    assertTrue(output.toString(StandardCharsets.UTF_8).isEmpty());
                    var actual = decoder.decode(compact);
                    assertEquals(expected.getColorModel(), actual.getColorModel());
                    assertEquals(expected.getWidth(), actual.getWidth());
                    assertEquals(expected.getHeight(), actual.getHeight());
                    assertArrayEquals(expected.getRaster().getPixels(0, 0, 2, 2, (int[]) null),
                            actual.getRaster().getPixels(0, 0, 2, 2, (int[]) null));
                    assertEquals(1, output.toString(StandardCharsets.UTF_8).lines().count());
                    output.reset();
                    var patch = decoder.decodeRawProcessedRegion(compact, new Rectangle(1, 0, 1, 2));
                    assertArrayEquals(expected.getRaster().getPixels(1, 0, 1, 2, (int[]) null),
                            patch.getRaster().getPixels(0, 0, 1, 2, (int[]) null));
                    assertEquals(1, output.toString(StandardCharsets.UTF_8).lines().count());
                    output.reset();
                    assertEquals(4, compact.maxWorkers());
                    assertEquals(LOW_MEMORY, compact.memoryMode());
                }
            }
        } finally {
            System.setErr(previous);
        }
    }

    @Test
    void nativeAndLinearReadsApplyThePolicyOnceAndPreserveSampleUnits() throws Exception {
        var output = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        try (var error = new PrintStream(output, true, StandardCharsets.UTF_8);
             var source = Cr3DecoderTest.class.getResourceAsStream("four-sites.cr3");
             var input = new MemoryCacheImageInputStream(source);
             var decoder = new Cr3Decoder()) {
            System.setErr(error);
            decoder.setInput(input);
            for (var options : new Cr3DecodeOptions[]{Cr3DecodeOptions.rawSensor(), Cr3DecodeOptions.rawLinear()}) {
                var expected = decoder.decode(options.memoryMode(FAST));
                var actual = decoder.decode(options.memoryMode(LOW_MEMORY).maxWorkers(4));
                assertArrayEquals(expected.getRaster().getPixels(0, 0, 2, 2, (int[]) null),
                        actual.getRaster().getPixels(0, 0, 2, 2, (int[]) null));
                assertEquals(1, output.toString(StandardCharsets.UTF_8).lines().count());
                output.reset();
                decoder.decodeRawRegionInto(options, null, 1, 1, actual.getRaster(), 0, 0);
                assertEquals(1, output.toString(StandardCharsets.UTF_8).lines().count());
                output.reset();
                assertEquals(4, options.maxWorkers());
                decoder.getRawBounds(options);
                assertEquals("", output.toString(StandardCharsets.UTF_8));
            }
        } finally {
            System.setErr(previous);
        }
    }
}
