package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.testutil.Cr3TestData;
import org.junit.jupiter.api.Test;

import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.DataBuffer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static com.photographer.focusstack.cr3.decode.Cr3DecodeOptions.MemoryMode.LOW_MEMORY;
import static org.junit.jupiter.api.Assertions.*;

class CanonCr3MemoryModeTest {
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
            assertEquals(Cr3DecodeOptions.BitDepth.EIGHT, options.bitDepth());
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
