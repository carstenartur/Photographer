package com.photographer.focusstack.cr3.decode;

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

    @Test
    void automaticSelectionLeavesRoomForOtherHeapUse() {
        assertFalse(Cr3MemoryPolicy.useLowMemory(AUTO, 80 * MIB, 200 * MIB, 512 * MIB));
        assertTrue(Cr3MemoryPolicy.useLowMemory(AUTO, 180 * MIB, 200 * MIB, 512 * MIB));
        assertTrue(Cr3MemoryPolicy.useLowMemory(AUTO, 100 * MIB, 40 * MIB, 512 * MIB));
        assertTrue(Cr3MemoryPolicy.useLowMemory(AUTO, 1, 0, 128 * MIB));
        assertFalse(Cr3MemoryPolicy.useLowMemory(AUTO, MIB, Long.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test
    void explicitPolicyOverridesTheSnapshotWithoutChangingWorkerLimit() {
        assertFalse(Cr3MemoryPolicy.useLowMemory(FAST, 200 * MIB, 0, 128 * MIB));
        assertTrue(Cr3MemoryPolicy.useLowMemory(LOW_MEMORY, MIB, 512 * MIB, 512 * MIB));
        var options = Cr3DecodeOptions.rawProcessed().maxWorkers(4).memoryMode(LOW_MEMORY);
        var output = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        try (var error = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setErr(error);
            var selected = Cr3MemoryPolicy.apply(options, 100 * MIB);
            assertNotSame(options, selected);
            assertEquals(1, selected.maxWorkers());
            assertEquals(4, options.maxWorkers());
            assertEquals(LOW_MEMORY, selected.copy().memoryMode());
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
