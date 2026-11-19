package com.photographer.focusstack.cr3.decode;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.processing.BayerDemosaicer;
import com.photographer.focusstack.cr3.processing.ColorMatrixProcessor;
import com.photographer.focusstack.cr3.processing.ExifOrientationTransformer;
import com.photographer.focusstack.cr3.processing.SrgbConverter;
import com.photographer.focusstack.cr3.processing.WhiteBalanceProcessor;
import com.photographer.focusstack.cr3.testutil.Cr3TestData;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Rectangle;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class Cr3DecoderTest {

    @Test
    void developsNativeCodesIntoLinearUnsignedShortRgb() throws Exception {
        try (var source = Cr3DecoderTest.class.getResourceAsStream("four-sites.cr3");
             var input = new MemoryCacheImageInputStream(source);
             var decoder = new Cr3Decoder()) {
            decoder.setInput(input);
            var options = Cr3DecodeOptions.rawProcessed().bitDepth(Cr3DecodeOptions.BitDepth.SIXTEEN)
                    .applyBlackLevel(false).applyWhiteBalance(false).applyColorMatrix(false).applySrgbConversion(false);
            var packed = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            var image = decoder.decodeRawProcessedRegion(options, null, packed);
            var raster = image.getRaster();
            assertEquals(DataBuffer.TYPE_USHORT, raster.getTransferType());
            assertArrayEquals(new int[]{16, 16, 16}, raster.getSampleModel().getSampleSize());
            assertEquals(3, raster.getDataBuffer().getNumBanks());
            assertSame(ColorSpace.getInstance(ColorSpace.CS_LINEAR_RGB), image.getColorModel().getColorSpace());
            // RGGB sites 8192, 8193, 8191, 8194, normalized by 16383 and rounded once.
            assertArrayEquals(new int[]{
                    32770, 32770, 32778, 32770, 32774, 32778,
                    32770, 32765, 32778, 32770, 32770, 32778}, raster.getPixels(0, 0, 2, 2, (int[]) null));
            assertEquals(0xff000000, packed.getRGB(0, 0));
        }
    }

    @Test
    void preservesFractionalGainsAndUnclippedHighlightsThroughTheColorMatrix() throws Exception {
        try (var source = Cr3DecoderTest.class.getResourceAsStream("four-sites.cr3");
             var input = new MemoryCacheImageInputStream(source);
             var decoder = new Cr3Decoder()) {
            decoder.setInput(input);
            var metadata = decoder.getMetadata();
            metadata.setCameraModel("Unknown");
            metadata.setPerChannelBlackLevels(new int[]{90, 101, 120, 140});
            metadata.setWhiteBalanceMultipliers(new double[]{1.25, 2.5, 0.7501});
            var options = Cr3DecodeOptions.rawProcessed().bitDepth(Cr3DecodeOptions.BitDepth.SIXTEEN)
                    .applySrgbConversion(false);
            // At the red site the two green neighbors average to 8081.5 after pedestal subtraction.
            // Green then exceeds nominal white. Its negative matrix coefficients still reduce red and blue.
            var linear = decoder.decode(options);
            assertArrayEquals(new int[]{26933, 65535, 12907}, linear.getRaster().getPixel(0, 0, (int[]) null));
            var srgb = decoder.decode(options.applySrgbConversion(true));
            assertTrue(srgb.getColorModel().getColorSpace().isCS_sRGB());
            assertArrayEquals(new int[]{44128, 65535, 31528}, srgb.getRaster().getPixel(0, 0, (int[]) null));
        }
    }

    @Test
    void sixteenBitWindowsPreserveCropPhaseAndOrientedSamples() throws Exception {
        try (var source = Cr3DecoderTest.class.getResourceAsStream("four-sites.cr3");
             var input = new MemoryCacheImageInputStream(source);
             var decoder = new Cr3Decoder()) {
            decoder.setInput(input);
            var metadata = decoder.getMetadata();
            var options = Cr3DecodeOptions.rawProcessed().bitDepth(Cr3DecodeOptions.BitDepth.SIXTEEN)
                    .applyColorMatrix(false).applySrgbConversion(false);
            metadata.setActiveAreaLeft(1);
            metadata.setActiveAreaRight(2);
            metadata.setActiveAreaBottom(2);
            metadata.setPerChannelBlackLevels(new int[]{1, 4096, 4000, 2048});
            metadata.setWhiteBalanceMultipliers(new double[]{2, 1, 0.5});
            for (int orientation = 1; orientation <= 8; orientation++) {
                metadata.setOrientation(orientation);
                var full = decoder.decode(options);
                assertArrayEquals(new int[]{32780, 24587, 0}, full.getRaster().getPixel(0, 0, (int[]) null));
                var window = new Rectangle(full.getWidth() - 1, full.getHeight() - 1, 1, 1);
                var patch = decoder.decodeRawProcessedRegion(options, window);
                assertArrayEquals(full.getRaster().getPixel(window.x, window.y, (int[]) null),
                        patch.getRaster().getPixel(0, 0, (int[]) null));
            }
        }
    }

    @Test
    void preparedRawReproducesDefaultColorDevelopment() throws Exception {
        try (var source = Cr3DecoderTest.class.getResourceAsStream("four-sites.cr3");
             var input = new MemoryCacheImageInputStream(source);
             var decoder = new Cr3Decoder()) {
            decoder.setInput(input);
            decoder.getMetadata().setOrientation(6);
            var prepared = decoder.prepareRawDevelopment(1);
            assertEquals(6, prepared.orientation());
            assertArrayEquals(new int[] {8192, 8193, 8191, 8194}, prepared.image().data());

            WhiteBalanceProcessor.process(prepared.image(), prepared.whiteBalance(), prepared.blackLevel(),
                    prepared.sensorX(), prepared.sensorY(), 1);
            var rgb = BayerDemosaicer.demosaicInPlace(prepared.image(), prepared.whiteLevel(), 1);
            ColorMatrixProcessor.process(rgb, prepared.colorMatrix(), 1);
            SrgbConverter.process(rgb, 1);
            rgb = ExifOrientationTransformer.applyToOwnedImage(rgb, prepared.orientation());
            var expected = decoder.decode(Cr3DecodeOptions.rawProcessed().maxWorkers(1));
            assertArrayEquals(expected.getRGB(0, 0, 2, 2, null, 0, 2), rgb.getRGB(0, 0, 2, 2, null, 0, 2));
        }
    }

    @Test
    void developsIntoAnOwnedRgbDestinationWithInPlaceOrientation() throws Exception {
        try (var source = Cr3DecoderTest.class.getResourceAsStream("four-sites.cr3");
             var input = new MemoryCacheImageInputStream(source);
             var decoder = new Cr3Decoder()) {
            decoder.setInput(input);
            var options = Cr3DecodeOptions.rawProcessed().maxWorkers(1);
            var destination = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            for (int orientation = 1; orientation <= 4; orientation++) {
                decoder.getMetadata().setOrientation(orientation);
                BufferedImage expected = decoder.decode(options);
                assertSame(destination, decoder.decodeRawProcessedRegion(options, null, destination));
                assertArrayEquals(expected.getRGB(0, 0, 2, 2, null, 0, 2),
                        destination.getRGB(0, 0, 2, 2, null, 0, 2));
            }

            decoder.getMetadata().setOrientation(6);
            assertNotSame(destination, decoder.decodeRawProcessedRegion(options, null, destination));

            decoder.getMetadata().setOrientation(1);
            var parent = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
            var view = parent.getSubimage(1, 1, 2, 2);
            assertNotSame(view, decoder.decodeRawProcessedRegion(options, null, view));
            assertEquals(0xff000000, parent.getRGB(1, 1));
        }
    }

    @Nested
    class InputValidation {
        @Test
        void decodeWithoutInputThrowsIllegalStateException() {
            Cr3Decoder decoder = new Cr3Decoder();
            assertThrows(IllegalStateException.class,
                    () -> decoder.decode(Cr3DecodeOptions.defaults()));
        }

        @Test
        void replacingPathInputClearsTheOwnedFileAndPreservesBorrowedStreams(@TempDir Path directory)
                throws Exception {
            Path raw = directory.resolve("sample.cr3");
            try (var fixture = Cr3DecoderTest.class.getResourceAsStream("four-sites.cr3")) {
                Files.copy(fixture, raw);
            }
            Path missing = directory.resolve("missing.cr3");
            try (var decoder = new Cr3Decoder();
                 var source = Files.newInputStream(raw);
                 var stream = new MemoryCacheImageInputStream(source)) {
                decoder.setInput(raw);
                var sensor = decoder.decode(Cr3DecodeOptions.rawSensor());
                assertThrows(IOException.class, () -> decoder.setInput(missing));
                assertNull(decoder.getContainer());
                assertThrows(IllegalStateException.class, () -> decoder.decode(Cr3DecodeOptions.rawSensor()));
                decoder.setInput(stream);
                var fromStream = decoder.decode(Cr3DecodeOptions.rawSensor().maxWorkers(1));
                assertArrayEquals(sensor.getRaster().getSamples(0, 0, 2, 2, 0, (int[]) null),
                        fromStream.getRaster().getSamples(0, 0, 2, 2, 0, (int[]) null));
                decoder.reset();
                stream.seek(0);
                assertTrue(stream.read() >= 0);
            }
        }

        @Test
        void resetClearsState() throws Exception {
            byte[] cr3Data = Cr3TestData.buildMinimalCr3WithJpeg();
            Cr3Decoder decoder = new Cr3Decoder();
            try (var iis = new MemoryCacheImageInputStream(new ByteArrayInputStream(cr3Data))) {
                decoder.setInput(iis);
                assertNotNull(decoder.getContainer());
                assertNotNull(decoder.getMetadata());
                decoder.reset();
                assertNull(decoder.getContainer());
                assertNull(decoder.getMetadata());
            }
        }
    }

    @Nested
    class PreviewDecoding {
        @Test
        void decodesPreviewFromSyntheticCr3() throws Exception {
            byte[] cr3Data = Cr3TestData.buildMinimalCr3WithJpeg();
            Cr3Decoder decoder = new Cr3Decoder();
            try (var iis = new MemoryCacheImageInputStream(new ByteArrayInputStream(cr3Data))) {
                decoder.setInput(iis);
                BufferedImage preview = decoder.decode(Cr3DecodeOptions.defaults());
                assertNotNull(preview);
                assertTrue(preview.getWidth() > 0);
                assertTrue(preview.getHeight() > 0);
            }
        }

        @Test
        void decodesThumbnailFromSyntheticCr3() throws Exception {
            byte[] cr3Data = Cr3TestData.buildMinimalCr3WithJpeg();
            Cr3Decoder decoder = new Cr3Decoder();
            try (var iis = new MemoryCacheImageInputStream(new ByteArrayInputStream(cr3Data))) {
                decoder.setInput(iis);
                Cr3DecodeOptions options = Cr3DecodeOptions.defaults()
                        .outputMode(Cr3DecodeOptions.OutputMode.THUMBNAIL);
                BufferedImage thumb = decoder.decode(options);
                assertNotNull(thumb);
                assertTrue(thumb.getWidth() > 0);
            }
        }
    }

    @Test
    void readsEachCfaPositionThroughRawLinear() throws Exception {
        // Synthetic 2x2 CR3: one V1 tile, four 8-byte planes, a 108-byte CMP1 directory.
        // Opening residual codes 0, 2, 1, 4 give 8192, 8193, 8191, 8194 from the 14-bit midpoint.
        try (var source = Cr3DecoderTest.class.getResourceAsStream("four-sites.cr3");
             var input = new MemoryCacheImageInputStream(source);
             var decoder = new Cr3Decoder()) {
            decoder.setInput(input);
            var image = decoder.decode(Cr3DecodeOptions.rawLinear().cropToActiveArea(false).applyBlackLevel(false));
            assertEquals(BufferedImage.TYPE_USHORT_GRAY, image.getType());
            assertEquals(2, image.getWidth());
            assertEquals(2, image.getHeight());
            assertArrayEquals(new int[] {32769, 32773, 32765, 32777},
                    image.getRaster().getSamples(0, 0, 2, 2, 0, (int[]) null));
        }
    }

    @Test
    void returnsUnscaledSensorCodesEvenWhenProcessingOptionsAreSet() throws Exception {
        try (var source = Cr3DecoderTest.class.getResourceAsStream("four-sites.cr3");
             var input = new MemoryCacheImageInputStream(source);
             var decoder = new Cr3Decoder()) {
            decoder.setInput(input);
            var options = Cr3DecodeOptions.rawSensor().cropToActiveArea(true).applyBlackLevel(true);
            var image = decoder.decode(options);
            assertEquals(BufferedImage.TYPE_USHORT_GRAY, image.getType());
            assertArrayEquals(new int[] {8192, 8193, 8191, 8194},
                    image.getRaster().getSamples(0, 0, 2, 2, 0, (int[]) null));
            var linear = decoder.decode(Cr3DecodeOptions.rawLinear());
            assertArrayEquals(new int[] {30721, 30725, 30717, 30729},
                    linear.getRaster().getSamples(0, 0, 2, 2, 0, (int[]) null));
        }
    }

    @Test
    void processedOddCropRetainsSensorBlackAndCropLocalWhiteBalancePhases() throws Exception {
        try (var source = Cr3DecoderTest.class.getResourceAsStream("four-sites.cr3");
             var input = new MemoryCacheImageInputStream(source);
             var decoder = new Cr3Decoder()) {
            decoder.setInput(input);
            var metadata = decoder.getMetadata();
            // Crop the right column. Black levels use sensor Gr and B, and WB uses local R and G.
            metadata.setActiveAreaLeft(1);
            metadata.setActiveAreaRight(2);
            metadata.setActiveAreaBottom(2);
            metadata.setPerChannelBlackLevels(new int[]{1, 4096, 4000, 2048});
            metadata.setWhiteBalanceMultipliers(new double[]{2, 1, 0.5});
            metadata.setOrientation(6);
            var options = Cr3DecodeOptions.rawProcessed().applyColorMatrix(false).applySrgbConversion(false);
            var image = decoder.decode(options);
            assertEquals(2, image.getWidth());
            assertEquals(1, image.getHeight());
            assertArrayEquals(new int[]{0xff7f5f00, 0xff7f5f00}, image.getRGB(0, 0, 2, 1, null, 0, 2));

            metadata.setActiveAreaTop(1);
            assertEquals(0xffbf0000, decoder.decode(options).getRGB(0, 0));
            metadata.setActiveAreaTop(0);

            // Fixed research pedestal takes precedence over MakerNote levels.
            var fixed = decoder.decode(options.experimentalPedestal(4096));
            assertArrayEquals(new int[]{0xffaa5500, 0xffaa5500}, fixed.getRGB(0, 0, 2, 1, null, 0, 2));
        }
    }

    @Test
    void placesSubsampledSensorSitesAtTheCorrectParity() throws Exception {
        try (var source = Cr3DecoderTest.class.getResourceAsStream("four-sites.cr3");
             var input = new MemoryCacheImageInputStream(source);
             var decoder = new Cr3Decoder()) {
            decoder.setInput(input);
            var options = Cr3DecodeOptions.rawSensor();
            assertEquals(new Rectangle(2, 2), decoder.getRawBounds(options));
            var right = decoder.decodeRawRegion(options, new Rectangle(1, 0, 1, 2), Integer.MAX_VALUE, 1);
            assertArrayEquals(new int[] {8193, 8194}, right.getRaster().getSamples(0, 0, 1, 2, 0, (int[]) null));
            var bottom = decoder.decodeRawRegion(options, new Rectangle(0, 1, 2, 1), 1, 2);
            assertArrayEquals(new int[] {8191, 8194}, bottom.getRaster().getSamples(0, 0, 2, 1, 0, (int[]) null));
            assertThrows(IllegalArgumentException.class,
                    () -> decoder.decodeRawRegion(options, new Rectangle(1, 1, 2, 2), 1, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> decoder.decodeRawRegion(options, new Rectangle(2, 2), 0, 1));
        }
    }

    @Test
    void developsARegionInOrientedOutputCoordinates() throws Exception {
        try (var source = Cr3DecoderTest.class.getResourceAsStream("four-sites.cr3");
             var input = new MemoryCacheImageInputStream(source);
             var decoder = new Cr3Decoder()) {
            decoder.setInput(input);
            var metadata = decoder.getMetadata();
            metadata.setActiveAreaLeft(1);
            metadata.setActiveAreaRight(2);
            metadata.setActiveAreaBottom(2);
            metadata.setPerChannelBlackLevels(new int[]{16, 4096, 512, 2048});
            var options = Cr3DecodeOptions.rawProcessed();
            for (int orientation = 1; orientation <= 8; orientation++) {
                metadata.setOrientation(orientation);
                var whole = decoder.decode(options);
                var bounds = decoder.getRawProcessedBounds(options);
                assertEquals(new Rectangle(whole.getWidth(), whole.getHeight()), bounds);
                var region = new Rectangle(bounds.width - 1, bounds.height - 1, 1, 1);
                var cropped = decoder.decodeRawProcessedRegion(options, region);
                assertEquals(1, cropped.getWidth());
                assertEquals(1, cropped.getHeight());
                assertEquals(whole.getRGB(region.x, region.y), cropped.getRGB(0, 0),
                        "orientation " + orientation);
            }
        }
    }

    @Test
    void writesBayerSamplesIntoAnOffsetUshortSubimage() throws Exception {
        try (var source = Cr3DecoderTest.class.getResourceAsStream("four-sites.cr3");
             var input = new MemoryCacheImageInputStream(source);
             var decoder = new Cr3Decoder()) {
            decoder.setInput(input);
            var backing = new BufferedImage(5, 4, BufferedImage.TYPE_USHORT_GRAY);
            var dest = backing.getSubimage(1, 1, 3, 3).getRaster();
            backing.getRaster().setSample(0, 0, 0, 123);
            decoder.decodeRawRegionInto(Cr3DecodeOptions.rawSensor(), new Rectangle(0, 0, 2, 2),
                    2, 1, dest, 1, 1);
            var pixels = backing.getRaster();
            assertEquals(8192, pixels.getSample(2, 2, 0));
            assertEquals(8191, pixels.getSample(2, 3, 0));
            assertEquals(0, pixels.getSample(3, 2, 0));
            assertEquals(123, pixels.getSample(0, 0, 0));
        }
    }

    @Nested
    class OptionsFactoryMethods {
        @Test
        void rawProcessedCreatesCorrectOptions() {
            Cr3DecodeOptions opts = Cr3DecodeOptions.rawProcessed();
            assertEquals(Cr3DecodeOptions.OutputMode.RAW_PROCESSED, opts.outputMode());
            assertTrue(opts.applyBlackLevel());
            assertTrue(opts.applyWhiteBalance());
        }

        @Test
        void rawLinearCreatesCorrectOptions() {
            Cr3DecodeOptions opts = Cr3DecodeOptions.rawLinear();
            assertEquals(Cr3DecodeOptions.OutputMode.RAW_LINEAR, opts.outputMode());
            assertTrue(opts.applyBlackLevel());
            assertFalse(opts.applyWhiteBalance());
            assertFalse(opts.applyColorMatrix());
            assertFalse(opts.applySrgbConversion());
        }
    }

    @Nested
    class MetadataAccess {
        @Test
        void metadataAvailableAfterSetInput() throws Exception {
            byte[] cr3Data = Cr3TestData.buildMinimalCr3WithJpeg();
            Cr3Decoder decoder = new Cr3Decoder();
            try (var iis = new MemoryCacheImageInputStream(new ByteArrayInputStream(cr3Data))) {
                decoder.setInput(iis);
                assertNotNull(decoder.getMetadata());
                assertEquals("Canon", decoder.getMetadata().cameraMake());
            }
        }

        @Test
        void containerAvailableAfterSetInput() throws Exception {
            byte[] cr3Data = Cr3TestData.buildMinimalCr3WithJpeg();
            Cr3Decoder decoder = new Cr3Decoder();
            try (var iis = new MemoryCacheImageInputStream(new ByteArrayInputStream(cr3Data))) {
                decoder.setInput(iis);
                assertNotNull(decoder.getContainer());
                assertTrue(decoder.getContainer().trackCount() > 0);
            }
        }
    }
}
