package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.processing.BayerDemosaicer;
import com.photographer.focusstack.cr3.testutil.Cr3TestData;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageTypeSpecifier;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Point;
import java.awt.color.ColorSpace;
import java.awt.image.BandedSampleModel;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBuffer;
import java.awt.image.MultiPixelPackedSampleModel;
import java.awt.image.SinglePixelPackedSampleModel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static com.photographer.focusstack.cr3.decode.Cr3DecodeOptions.MemoryMode.LOW_MEMORY;
import static org.junit.jupiter.api.Assertions.*;

class CanonCr3MemoryModeTest {
    @Test
    void copyBudgetIncludesRowPaddingChannelOffsetsAndSparseBanks() {
        assertEquals(44, Cr3DestinationMemory.storageBytes(new SinglePixelPackedSampleModel(DataBuffer.TYPE_INT,
                3, 2, 8, new int[]{0xff0000, 0xff00, 0xff})));
        assertEquals(204, Cr3DestinationMemory.storageBytes(new ComponentSampleModel(DataBuffer.TYPE_USHORT,
                3, 2, 4, 20, new int[]{2, 0, 1}, new int[]{5, 3, 1})));
        assertEquals(126, Cr3DestinationMemory.storageBytes(new BandedSampleModel(DataBuffer.TYPE_USHORT,
                3, 2, 8, new int[]{0, 2, 1}, new int[]{5, 0, 2})));
        assertEquals(Long.MAX_VALUE, Cr3DestinationMemory.storageBytes(new BandedSampleModel(DataBuffer.TYPE_USHORT,
                2, 2, 2, new int[]{0, 1, 1_000_000}, new int[]{0, 0, 0})));
        assertEquals(9, Cr3DestinationMemory.storageBytes(new MultiPixelPackedSampleModel(DataBuffer.TYPE_BYTE,
                3, 2, 1, 4, 5)));
    }

    @Test
    void standardOffsetDestinationsAreIncludedInTheLaterPeakEstimate() throws Exception {
        var reader = new CanonCr3ImageReader(new CanonCr3ImageReaderSpi());
        var messages = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        try (var error = new PrintStream(messages, true, StandardCharsets.UTF_8);
             var source = getClass().getResourceAsStream("four-sites.cr3");
             var input = new MemoryCacheImageInputStream(source)) {
            System.setErr(error);
            reader.setInput(input);
            for (boolean bayer : new boolean[]{false, true}) {
                var param = reader.getDefaultReadParam();
                var options = bayer ? Cr3DecodeOptions.rawSensor() : Cr3DecodeOptions.rawProcessed()
                        .bitDepth(Cr3DecodeOptions.BitDepth.SIXTEEN);
                param.setDecodeOptions(options.memoryMode(LOW_MEMORY).maxWorkers(1).postDecodeMemoryBytes(4096));
                param.setDestinationOffset(new Point(900, 900));
                var image = reader.read(0, param);
                assertEquals(902, image.getWidth());
                assertEquals(902, image.getHeight());
                int bands = bayer ? 1 : 3;
                // The decoded result, automatic target, transfer row and caller hint coexist
                // with the first-use encoder tables for sRGB16, but not for Bayer output.
                long shared = bayer ? 0 : BayerDemosaicer.estimateSrgb16TableBytes();
                long peak = (4L + 902L * 902) * 2 * bands + 2 * 4 * bands + 4096 + shared;
                assertTrue(messages.toString(StandardCharsets.UTF_8).contains("estimated normal-path heap " + peak
                        + " bytes"), messages::toString);
                assertEquals(1, warningCount(messages));
                assertEquals(4096, options.postDecodeMemoryBytes());
                assertSame(options, param.getDecodeOptions());
                assertNull(param.getDestination());
                messages.reset();
            }
        } finally {
            System.setErr(previous);
            reader.dispose();
        }
    }

    @Test
    void automaticDestinationFactoryRunsAfterCorePlanningAndPreservesCallerParameters() throws Exception {
        var reader = new CanonCr3ImageReader(new CanonCr3ImageReaderSpi());
        var messages = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        try (var error = new PrintStream(messages, true, StandardCharsets.UTF_8);
             var source = getClass().getResourceAsStream("four-sites.cr3");
             var input = new MemoryCacheImageInputStream(source)) {
            System.setErr(error);
            reader.setInput(input);
            for (boolean bayer : new boolean[]{false, true}) {
                var param = reader.getDefaultReadParam();
                var options = bayer ? Cr3DecodeOptions.rawSensor() : Cr3DecodeOptions.rawProcessed()
                        .bitDepth(Cr3DecodeOptions.BitDepth.SIXTEEN);
                param.setDecodeOptions(options.memoryMode(LOW_MEMORY).maxWorkers(1));
                var reference = reader.read(0, param);
                messages.reset();
                var type = bayer ? reader.getRawImageType(0)
                        : ImageTypeSpecifier.createInterleaved(ColorSpace.getInstance(ColorSpace.CS_sRGB),
                                new int[]{0, 1, 2}, DataBuffer.TYPE_USHORT, false, false);
                // A non-banded RGB16 layout or a positive Bayer offset requires a distinct target.
                var allocated = new BufferedImage[1];
                var requested = new ImageTypeSpecifier(type.getColorModel(), type.getSampleModel()) {
                    @Override
                    public BufferedImage createBufferedImage(int width, int height) {
                        assertEquals(1, warningCount(messages), "Defer destination creation until development completes");
                        assertNull(allocated[0], "Create only one destination");
                        return allocated[0] = super.createBufferedImage(width, height);
                    }
                };
                param.setDestinationType(requested);
                if (bayer) param.setDestinationOffset(new Point(1, 1));
                var image = reader.read(0, param);
                assertTrue(messages.toString(StandardCharsets.UTF_8).contains("estimated normal-path heap unknown"),
                        messages::toString);
                assertSame(allocated[0], image);
                assertArrayEquals(reference.getRaster().getPixels(0, 0, 2, 2, (int[]) null),
                        image.getRaster().getPixels(bayer ? 1 : 0, bayer ? 1 : 0, 2, 2, (int[]) null));
                assertEquals(1, warningCount(messages));
                assertSame(requested, param.getDestinationType());
                assertSame(options, param.getDecodeOptions());
                assertNull(param.getDestination());
                messages.reset();
            }
        } finally {
            System.setErr(previous);
            reader.dispose();
        }
    }

    @Test
    void rasterModeConversionPreservesMemoryPreferenceAndCallerOptions() throws Exception {
        var reader = new CanonCr3ImageReader(new CanonCr3ImageReaderSpi());
        var messages = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        try (var error = new PrintStream(messages, true, StandardCharsets.UTF_8);
             var source = getClass().getResourceAsStream("four-sites.cr3");
             var input = new MemoryCacheImageInputStream(source)) {
            System.setErr(error);
            reader.setInput(input);
            var param = reader.getDefaultReadParam();
            var options = param.getDecodeOptions().memoryMode(LOW_MEMORY).maxWorkers(4).applyBlackLevel(false);
            var raster = reader.readRaster(0, param);
            assertArrayEquals(new int[]{32769, 32773, 32765, 32777}, raster.getPixels(0, 0, 2, 2, (int[]) null));
            assertEquals(1, warningCount(messages));
            assertSame(options, param.getDecodeOptions());
            assertEquals(Cr3DecodeOptions.OutputMode.RAW_PROCESSED, options.outputMode());
            assertEquals(4, options.maxWorkers());
        } finally {
            System.setErr(previous);
            reader.dispose();
        }
    }

    @Test
    void destinationNegotiationRetainsMemoryModeWithoutChangingRequestedDepth() throws Exception {
        var reader = new CanonCr3ImageReader(new CanonCr3ImageReaderSpi());
        var messages = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        try (var error = new PrintStream(messages, true, StandardCharsets.UTF_8);
             var source = getClass().getResourceAsStream("four-sites.cr3");
             var input = new MemoryCacheImageInputStream(source)) {
            System.setErr(error);
            reader.setInput(input);
            var param = reader.getDefaultReadParam();
            var options = param.getDecodeOptions().memoryMode(LOW_MEMORY).maxWorkers(4);
            var types = reader.getImageTypes(0);
            types.next();
            var requested = types.next();
            param.setDestinationType(requested);
            var image = reader.read(0, param);
            assertEquals(DataBuffer.TYPE_USHORT, image.getRaster().getTransferType());
            assertArrayEquals(new int[]{16, 16, 16}, image.getSampleModel().getSampleSize());
            assertEquals(requested.getColorModel(), image.getColorModel());
            assertEquals(1, warningCount(messages));
            assertSame(options, param.getDecodeOptions());
            assertEquals(Cr3DecodeOptions.BitDepth.SIXTEEN, options.bitDepth());
            assertEquals(LOW_MEMORY, options.memoryMode());
            assertEquals(4, options.maxWorkers());
        } finally {
            System.setErr(previous);
            reader.dispose();
        }
    }

    @Test
    void previewReadsAreQuietAndKeepTheRawMemoryPreferenceForLater() throws Exception {
        var reader = new CanonCr3ImageReader(new CanonCr3ImageReaderSpi());
        var messages = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        try (var error = new PrintStream(messages, true, StandardCharsets.UTF_8);
             var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(Cr3TestData.buildMinimalCr3WithJpeg()))) {
            System.setErr(error);
            reader.setInput(input);
            var param = reader.getDefaultReadParam();
            var options = param.getDecodeOptions().memoryMode(LOW_MEMORY).maxWorkers(4);
            param.setPreviewOnly(true);
            assertEquals(LOW_MEMORY, param.getDecodeOptions().memoryMode());
            assertNotNull(reader.read(0, param));
            assertEquals(0, warningCount(messages));
            param.setPreviewOnly(false);
            assertSame(options, param.getDecodeOptions());
            assertEquals(LOW_MEMORY, options.memoryMode());
            assertEquals(4, options.maxWorkers());
        } finally {
            System.setErr(previous);
            reader.dispose();
        }
    }

    private static long warningCount(ByteArrayOutputStream messages) {
        return messages.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> line.startsWith("CR3 low-memory:")).count();
    }
}
