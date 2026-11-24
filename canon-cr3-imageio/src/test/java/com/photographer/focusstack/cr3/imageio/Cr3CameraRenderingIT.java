package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions.BitDepth;
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions.MemoryMode;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import javax.imageio.stream.FileImageInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks the public ImageIO parameter on real captures with measured lens profiles. */
class Cr3CameraRenderingIT {
    private static final Rectangle WINDOW = new Rectangle(1127, 903, 43, 29);

    @ParameterizedTest
    @CsvSource({"sample-wide.cr3,SIXTEEN,6", "eosr-iso-low.cr3,EIGHT,1"})
    void requestedRegionUsesTheSameOrientedOpticalFieldAsTheFullWorkspace(
            String name, BitDepth depth, int orientation) throws Exception {
        var reader = new CanonCr3ImageReader(new CanonCr3ImageReaderSpi());
        try (var input = openFixture(name)) {
            reader.setInput(input);
            var meta = ((Cr3ImageMetadata) reader.getImageMetadata(0)).getCr3Metadata();
            meta.setOrientation(orientation);
            // Isolate lens correction so this test also detects missing camera/lens identification.
            var settings = Cr3DecodeOptions.cameraRendered().applyCameraTone(false).bitDepth(depth);
            int[] reference = readWindowFromFullWorkspace(reader, settings);

            var param = reader.getDefaultReadParam();
            param.setDecodeOptions(settings.copy().memoryMode(MemoryMode.LOW_MEMORY));
            param.setSourceRegion(WINDOW);
            param.setSourceSubsampling(3, 2, 0, 0);
            var sampled = reader.read(0, param);
            int bits = depth == BitDepth.SIXTEEN ? 16 : 8;
            assertEquals(bits, sampled.getSampleModel().getSampleSize(0));
            assertArrayEquals(reference, pixels(sampled));

            param.setDecodeOptions(settings.copy().applyLensCorrection(false));
            int[] neutral = pixels(reader.read(0, param));
            assertFalse(Arrays.equals(reference, neutral),
                    "expected the measured optical profile to affect this field of view");
            param.setDecodeOptions(settings.copy().applyLensCorrection(false).applyCameraTone(true));
            assertFalse(Arrays.equals(neutral, pixels(reader.read(0, param))),
                    "expected the capture's camera and picture style to select a measured tone curve");
        } finally {
            reader.dispose();
        }
    }

    private static int[] readWindowFromFullWorkspace(CanonCr3ImageReader reader, Cr3DecodeOptions settings)
            throws Exception {
        var param = reader.getDefaultReadParam();
        param.setDecodeOptions(settings);
        int w = reader.getWidth(0);
        int h = reader.getHeight(0);
        var target = settings.bitDepth() == BitDepth.EIGHT
                ? new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                : reader.getImageTypes(0).next().createBufferedImage(w, h);
        param.setDestination(target);
        param.setUseDestinationAsWorkspace(true);
        var developed = reader.read(0, param);
        assertSame(target, developed);
        var raster = developed.getRaster();
        int[] expected = new int[Math.ceilDiv(WINDOW.width, 3) * Math.ceilDiv(WINDOW.height, 2) * 3];
        int i = 0;
        for (int y = 0; y < WINDOW.height; y += 2) {
            for (int x = 0; x < WINDOW.width; x += 3) {
                for (int band = 0; band < 3; band++) {
                    expected[i++] = raster.getSample(WINDOW.x + x, WINDOW.y + y, band);
                }
            }
        }
        return expected;
    }

    @Test
    void shadingAmountBlendsLinearEosRValuesAtTheSameCorrectedCoordinates() throws Exception {
        var reader = new CanonCr3ImageReader(new CanonCr3ImageReaderSpi());
        try (var input = openFixture("eosr-iso-low.cr3")) {
            reader.setInput(input);
            var meta = ((Cr3ImageMetadata) reader.getImageMetadata(0)).getCr3Metadata();
            assertEquals("Canon EOS R", meta.cameraModel());
            assertEquals(12800, meta.isoSpeed());
            assertEquals(24, meta.focalLengthMm());
            assertEquals(4, meta.fNumber());
            var param = reader.getDefaultReadParam();
            param.setSourceRegion(WINDOW);
            var options = Cr3DecodeOptions.cameraRendered().applySrgbConversion(false);
            param.setDecodeOptions(options.lensShadingStrength(0));
            int[] geometryOnly = pixels(reader.read(0, param));
            options.lensShadingStrength(0.5);
            int[] half = pixels(reader.read(0, param));
            options.lensShadingStrength(1);
            int[] full = pixels(reader.read(0, param));
            // A different sampling position would move details as the correction amount changes.
            // On these unsaturated sites only the linear-light gain is allowed to interpolate.
            boolean brightened = false;
            for (int i = 0; i < full.length; i++) {
                if (geometryOnly[i] > 0 && full[i] < 65535) {
                    assertEquals((geometryOnly[i] + full[i]) / 2.0, half[i], 1.0);
                    brightened |= full[i] > geometryOnly[i] + 2;
                }
            }
            assertTrue(brightened, "the RF 24–105 mm profile should raise off-axis samples");
        } finally {
            reader.dispose();
        }
    }

    @Test
    void unsupportedLensAndStyleRetainNeutralPixelsAndLinearAndBayerReadsBypassDisplayTone()
            throws Exception {
        var reader = new CanonCr3ImageReader(new CanonCr3ImageReaderSpi());
        try (var input = openFixture("eosr-iso-low.cr3")) {
            reader.setInput(input);
            var meta = ((Cr3ImageMetadata) reader.getImageMetadata(0)).getCr3Metadata();
            var param = reader.getDefaultReadParam();
            param.setSourceRegion(WINDOW);
            var neutral = Cr3DecodeOptions.rawProcessed(BitDepth.SIXTEEN);
            param.setDecodeOptions(neutral.copy().applySrgbConversion(false));
            int[] linear = pixels(reader.read(0, param));
            int[] bayer = pixels(reader.readRaster(0, param));
            param.setDecodeOptions(neutral.copy().applySrgbConversion(false).applyCameraTone(true));
            assertArrayEquals(linear, pixels(reader.read(0, param)));
            meta.setLensModel("Unprofiled zoom");
            meta.setPictureStyle(-1);
            param.setDecodeOptions(neutral);
            int[] expected = pixels(reader.read(0, param));
            param.setDecodeOptions(Cr3DecodeOptions.cameraRendered());
            assertArrayEquals(expected, pixels(reader.read(0, param)));
            assertArrayEquals(bayer, pixels(reader.readRaster(0, param)));
        } finally {
            reader.dispose();
        }
    }

    private static int[] pixels(BufferedImage image) {
        return pixels(image.getRaster());
    }

    private static int[] pixels(Raster raster) {
        return raster.getPixels(raster.getMinX(), raster.getMinY(), raster.getWidth(), raster.getHeight(), (int[]) null);
    }

    private static FileImageInputStream openFixture(String name) throws Exception {
        var resource = Objects.requireNonNull(Cr3CameraRenderingIT.class.getResource("/samples/" + name));
        return new FileImageInputStream(Path.of(resource.toURI()).toFile());
    }
}
