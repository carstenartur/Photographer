package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.testutil.Cr3TestData;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import javax.imageio.IIOException;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class CanonCr3DestinationTest {

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void standardParametersCanSelectEveryAdvertisedType(boolean defaultParam, boolean suppliedImage) throws Exception {
        var reader = (CanonCr3ImageReader) new CanonCr3ImageReaderSpi().createReaderInstance();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(fourSites()))) {
            reader.setInput(input);
            reader.getImageMetadata(0);
            var metadata = reader.getDecoder().getMetadata();
            metadata.setCameraModel("Unknown");
            metadata.setPerChannelBlackLevels(new int[]{90, 101, 120, 140});
            metadata.setWhiteBalanceMultipliers(new double[]{1.25, 2.5, 0.7501});

            var types = reader.getImageTypes(0);
            while (types.hasNext()) {
                var type = types.next();
                ImageReadParam param = defaultParam ? reader.getDefaultReadParam() : new ImageReadParam();
                BufferedImage target = selectDestination(param, type, suppliedImage);

                BufferedImage image = reader.read(0, param);

                if (suppliedImage) {
                    assertSame(target, image);
                }
                assertEquals(type.getColorModel(), image.getColorModel());
                assertEquals(type.getSampleModel(image.getWidth(), image.getHeight()), image.getSampleModel());
                assertEquals(type.getSampleModel().getDataType(), image.getRaster().getDataBuffer().getDataType());
                if (image.getRaster().getTransferType() == DataBuffer.TYPE_USHORT) {
                    // Known four-site development with fractional gains; see Cr3DecoderTest's numeric derivation.
                    int[] expected = type.getColorModel().getColorSpace().isCS_sRGB()
                            ? new int[]{44128, 65535, 31528} : new int[]{26933, 65535, 12907};
                    assertArrayEquals(expected, image.getRaster().getPixel(0, 0, (int[]) null));
                }
                if (param instanceof CanonCr3ImageReadParam cr3) {
                    assertEquals(Cr3DecodeOptions.BitDepth.SIXTEEN, cr3.getDecodeOptions().bitDepth());
                    assertTrue(cr3.getDecodeOptions().applySrgbConversion());
                }
            }
        } finally {
            reader.dispose();
        }
    }

    @Test
    void malformedFinalPlaneNeverWritesIntoACustomFactorysCallerHeldRaster() throws Exception {
        byte[] bytes = fourSites();
        // Keep the directory and first three planes intact, but damage the final plane's opening prefix.
        Arrays.fill(bytes, bytes.length - 2, bytes.length, (byte) 0xff);
        var reader = (CanonCr3ImageReader) new CanonCr3ImageReaderSpi().createReaderInstance();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            reader.setInput(input);
            var param = reader.getDefaultReadParam();
            param.getDecodeOptions().maxWorkers(1);
            var type = reader.getImageTypes(0).next();
            var held = type.createBufferedImage(2, 2);
            int[] before = {11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22};
            held.getRaster().setPixels(0, 0, 2, 2, before);
            var requested = new ImageTypeSpecifier(type.getColorModel(), type.getSampleModel()) {
                @Override
                public BufferedImage createBufferedImage(int width, int height) {
                    return new BufferedImage(held.getColorModel(), held.getRaster(), false, null);
                }
            };
            param.setDestinationType(requested);
            assertThrows(IIOException.class, () -> reader.read(0, param));
            assertArrayEquals(before, held.getRaster().getPixels(0, 0, 2, 2, (int[]) null));
            assertNull(param.getDestination());
            assertSame(requested, param.getDestinationType());
        } finally {
            reader.dispose();
        }
    }

    @Test
    void validatesTheRawDirectoryBeforeAllocatingAnAutomaticFallbackDestination() throws Exception {
        var reader = (CanonCr3ImageReader) new CanonCr3ImageReaderSpi().createReaderInstance();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(damagedRaw()))) {
            reader.setInput(input);
            var param = reader.getDefaultReadParam();
            param.setDecodeOptions(Cr3DecodeOptions.rawProcessed().bitDepth(Cr3DecodeOptions.BitDepth.SIXTEEN));
            var interleaved = ImageTypeSpecifier.createInterleaved(ColorSpace.getInstance(ColorSpace.CS_sRGB),
                    new int[]{0, 1, 2}, DataBuffer.TYPE_USHORT, false, false);
            param.setDestinationType(new ImageTypeSpecifier(interleaved.getColorModel(), interleaved.getSampleModel()) {
                @Override
                public BufferedImage createBufferedImage(int width, int height) {
                    throw new AssertionError("The CRX directory is malformed");
                }
            });

            assertNotNull(assertThrows(IIOException.class, () -> reader.read(0, param)).getCause());
            assertNull(param.getDestination());
        } finally {
            reader.dispose();
        }
    }

    @Test
    void aDifferentRgb16DestinationLayoutStillReceivesAllSamples() throws Exception {
        var reader = (CanonCr3ImageReader) new CanonCr3ImageReaderSpi().createReaderInstance();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(fourSites()))) {
            reader.setInput(input);
            var param = reader.getDefaultReadParam();
            param.setDecodeOptions(Cr3DecodeOptions.rawProcessed().bitDepth(Cr3DecodeOptions.BitDepth.SIXTEEN)
                    .applyBlackLevel(false).applyWhiteBalance(false).applyColorMatrix(false).applySrgbConversion(false));
            var interleaved = ImageTypeSpecifier.createInterleaved(ColorSpace.getInstance(ColorSpace.CS_LINEAR_RGB),
                    new int[]{0, 1, 2}, DataBuffer.TYPE_USHORT, false, false);
            param.setDestinationType(interleaved);

            BufferedImage image = reader.read(0, param);

            assertEquals(interleaved.getSampleModel(2, 2), image.getSampleModel());
            assertArrayEquals(new int[]{
                    32770, 32770, 32778, 32770, 32774, 32778,
                    32770, 32765, 32778, 32770, 32770, 32778},
                    image.getRaster().getPixels(0, 0, 2, 2, (int[]) null));
        } finally {
            reader.dispose();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void destinationNegotiationPreservesMutableProcessingSettings(boolean suppliedImage) throws Exception {
        var reader = (CanonCr3ImageReader) new CanonCr3ImageReaderSpi().createReaderInstance();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(fourSites()))) {
            reader.setInput(input);
            reader.getImageMetadata(0);
            var metadata = reader.getDecoder().getMetadata();
            metadata.setActiveAreaLeft(1);
            metadata.setActiveAreaRight(2);
            metadata.setActiveAreaBottom(2);
            metadata.setPerChannelBlackLevels(new int[]{90, 101, 120, 140});
            metadata.setWhiteBalanceMultipliers(new double[]{1.25, 2.5, 0.7501});
            var param = reader.getDefaultReadParam();
            var settings = param.getDecodeOptions().cropToActiveArea(false).maxWorkers(1)
                    .applyBlackLevel(false).applyWhiteBalance(false).applyColorMatrix(false);
            selectDestination(param, rgb16(false), suppliedImage);

            BufferedImage image = reader.read(0, param);

            assertEquals(2, image.getWidth());
            assertEquals(2, image.getHeight());
            assertArrayEquals(new int[]{
                    32770, 32770, 32778, 32770, 32774, 32778,
                    32770, 32765, 32778, 32770, 32770, 32778},
                    image.getRaster().getPixels(0, 0, 2, 2, (int[]) null));
            assertSame(settings, param.getDecodeOptions());
            assertEquals(Cr3DecodeOptions.BitDepth.SIXTEEN, settings.bitDepth());
            assertTrue(settings.applySrgbConversion());
            assertFalse(settings.cropToActiveArea());
            assertEquals(1, settings.maxWorkers());
        } finally {
            reader.dispose();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitEightBitOptionsRejectSixteenBitTargetsBeforeReadingRaw(boolean suppliedImage) throws Exception {
        var reader = (CanonCr3ImageReader) new CanonCr3ImageReaderSpi().createReaderInstance();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(damagedRaw()))) {
            reader.setInput(input);
            for (boolean srgb : new boolean[]{true, false}) {
                var param = reader.getDefaultReadParam();
                var settings = Cr3DecodeOptions.rawProcessed();
                param.setDecodeOptions(settings);
                BufferedImage target = selectDestination(param, rgb16(srgb), suppliedImage);
                if (target != null) {
                    target.getRaster().setPixel(0, 0, new int[]{1111, 2222, 3333});
                }

                IIOException failure = assertThrows(IIOException.class, () -> reader.read(0, param));

                assertNull(failure.getCause(), "Reject the destination before entering the damaged RAW payload");
                assertSame(settings, param.getDecodeOptions());
                assertEquals(Cr3DecodeOptions.BitDepth.EIGHT, settings.bitDepth());
                if (target != null) {
                    assertArrayEquals(new int[]{1111, 2222, 3333}, target.getRaster().getPixel(0, 0, (int[]) null));
                }
            }
        } finally {
            reader.dispose();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mutableDepthAndEncodingChangesKeepPriorityOverDestinationNegotiation(boolean suppliedImage) throws Exception {
        var reader = (CanonCr3ImageReader) new CanonCr3ImageReaderSpi().createReaderInstance();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(damagedRaw()))) {
            reader.setInput(input);
            var sixteenSrgb = reader.getDefaultReadParam();
            sixteenSrgb.getDecodeOptions().bitDepth(Cr3DecodeOptions.BitDepth.SIXTEEN);
            selectDestination(sixteenSrgb, rgb16(false), suppliedImage);
            assertNull(assertThrows(IIOException.class, () -> reader.read(0, sixteenSrgb)).getCause());

            var param = reader.getDefaultReadParam();
            param.getDecodeOptions().bitDepth(Cr3DecodeOptions.BitDepth.SIXTEEN).applySrgbConversion(false);
            selectDestination(param, rgb16(true), suppliedImage);
            assertNull(assertThrows(IIOException.class, () -> reader.read(0, param)).getCause());

            var linearEight = reader.getDefaultReadParam();
            linearEight.getDecodeOptions().applySrgbConversion(false);
            selectDestination(linearEight, rgb16(true), suppliedImage);
            assertNull(assertThrows(IIOException.class, () -> reader.read(0, linearEight)).getCause());
        } finally {
            reader.dispose();
        }
    }

    @Test
    void mutableSensorModeStillCopiesNativeCodesIntoASelectedDestinationBand() throws Exception {
        var reader = (CanonCr3ImageReader) new CanonCr3ImageReaderSpi().createReaderInstance();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(fourSites()))) {
            reader.setInput(input);
            var param = reader.getDefaultReadParam();
            param.getDecodeOptions().outputMode(Cr3DecodeOptions.OutputMode.RAW_SENSOR);
            var target = rgb16(true).createBufferedImage(2, 2);
            param.setDestination(target);
            param.setSourceBands(new int[]{0});
            param.setDestinationBands(new int[]{1});

            assertSame(target, reader.read(0, param));
            assertArrayEquals(new int[]{0, 8192, 0}, target.getRaster().getPixel(0, 0, (int[]) null));
            assertEquals(Cr3DecodeOptions.OutputMode.RAW_SENSOR, param.getDecodeOptions().outputMode());
        } finally {
            reader.dispose();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void previewSelectionStillUsesTheEmbeddedJpeg(boolean suppliedImage) throws Exception {
        var reader = (CanonCr3ImageReader) new CanonCr3ImageReaderSpi().createReaderInstance();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(Cr3TestData.buildMinimalCr3WithJpeg()))) {
            reader.setInput(input);
            var param = reader.getDefaultReadParam();
            param.setPreviewOnly(true);
            selectDestination(param, ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB), suppliedImage);

            BufferedImage image = reader.read(0, param);

            assertEquals(2, image.getWidth());
            assertEquals(2, image.getHeight());
            assertEquals(BufferedImage.TYPE_INT_RGB, image.getType());
            assertEquals(Cr3DecodeOptions.OutputMode.PREVIEW, param.getDecodeOptions().outputMode());
        } finally {
            reader.dispose();
        }
    }

    private static BufferedImage selectDestination(ImageReadParam param, ImageTypeSpecifier type, boolean supplied) {
        if (supplied) {
            var image = type.createBufferedImage(2, 2);
            param.setDestination(image);
            return image;
        }
        param.setDestinationType(type);
        return null;
    }

    private static ImageTypeSpecifier rgb16(boolean srgb) {
        return ImageTypeSpecifier.createBanded(ColorSpace.getInstance(srgb ? ColorSpace.CS_sRGB : ColorSpace.CS_LINEAR_RGB),
                new int[]{0, 1, 2}, new int[3], DataBuffer.TYPE_USHORT, false, false);
    }

    private static byte[] fourSites() throws Exception {
        try (var source = CanonCr3DestinationTest.class.getResourceAsStream("four-sites.cr3")) {
            return source.readAllBytes();
        }
    }

    private static byte[] damagedRaw() throws Exception {
        byte[] bytes = fourSites();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            int offset = Math.toIntExact(Cr3Parser.parse(input).tracks().getFirst().dataOffset());
            Arrays.fill(bytes, offset, bytes.length, (byte) 0);
        }
        return bytes;
    }
}
