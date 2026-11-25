package com.photographer.focusstack.cr3.processing;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageTypeSpecifier;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Fixed output of the original scalar sampler, before shading shared source tiles. */
class OpticalCorrectionRegressionTest {
    // SHA-256 of row-major RGB, each unsigned code stored low byte first (including RGB8).
    // Captured from baebb9c69eadf2a36cb99c4c52e9703a291967a7, not recomputed by the sampler under test.
    private static final String[] GOLDEN = {
            "58b6ace5e3d80470051c36664d5043c8ac3b61800f613b2af74893e2f6f80f9f",
            "989135ae5fc74e9b9dee9036b6f39eb12ba52504f3b702d0114b2fb41bdf24c4",
            "f348fad7ac8732ee46b4336862b2b83bf38acdcf4a9d1a499efa18cb7af53891",
            "0dec0c92a7272cdd26cd8f315c7492b4182e63430894327fbeb373952d784347",
            "955259034ea8ef7b432688b2b4e4f38c574d098d8d5e11cbb1b0078d418a8535",
            "44c9c1bc68ed1629fe4d8c1b835e2653bb473c4056b8311588001824b3623f3a",
            "6cd2c056d630c165efe555b692fe2e71b8bb39c19de838e7c4f5665cc79e702b",
            "eb26070791ec991b94a296f27c96f7aed7367198932c917c173dd5a1b8e01933",
            "e1ba56dfde63856d957e4963f30156dc8323f4b22f7065257d92a819efba137d",
            "5a7fa4c4066ccf571d4d45948142d87e9a205c6bc07823bb3fb364abe930665e"
    };

    @Test
    void preservesEveryCodeAcrossTransferFunctionsShadingAndWorkerCounts() {
        // Opposite distortion signs cover repeated cells, adjacent cells, skipped source
        // columns and changing source rows. The large cases use multiple stripes whose
        // boundaries do not align with the tile grid of the one-worker run.
        for (int fixture = 0; fixture < GOLDEN.length; fixture++) {
            for (int workers : new int[]{1, 4}) {
                int width = fixture < 6 ? 79 : 1024;
                int height = fixture < 6 ? 61 : 773;
                BufferedImage image = image(width, height, bits(fixture), srgb(fixture));
                fill(image);
                assertSame(image, correction(fixture).applyToOwned(image, srgb(fixture), workers,
                        strength(fixture)));
                assertEquals(GOLDEN[fixture], hash(image), "fixture " + fixture + ", workers " + workers);
            }
        }
    }

    @Test
    void preservesEveryCodeInInterleavedAndTranslatedRasters() {
        for (int fixture = 0; fixture < 6; fixture++) {
            var model = image(1, 1, bits(fixture), srgb(fixture)).getColorModel();
            var parent = new BufferedImage(model, model.createCompatibleWritableRaster(91, 73), false, null);
            var image = parent.getSubimage(5, 7, 79, 61);
            fill(image);
            correction(fixture).applyToOwned(image, srgb(fixture), 4, strength(fixture));
            assertEquals(GOLDEN[fixture], hash(image), "translated fixture " + fixture);
            assertEquals(0, parent.getRaster().getSample(4, 7, 0));
            assertEquals(0, parent.getRaster().getSample(84, 7, 0));
            assertEquals(0, parent.getRaster().getSample(5, 6, 0));
            assertEquals(0, parent.getRaster().getSample(5, 68, 0));
        }
    }

    private static OpticalCorrection correction(int fixture) {
        if (fixture < 6) return new OpticalCorrection(.03, -.12, .02, 1.001, .999, -.6, .05, -.01);
        if (fixture < 8) return new OpticalCorrection(-.04, .12, -.03, .997, 1.003, -.4, .04, .01);
        // Strong outward curvature also exercises source footprints too large for the tile buffer.
        return new OpticalCorrection(.99, 0, 0, 1.005, .995, -.2, .02, 0);
    }

    private static double strength(int fixture) {
        return fixture < 2 || fixture > 5 ? 1 : fixture < 4 ? .35 : 0;
    }

    private static boolean srgb(int fixture) {
        return (fixture & 1) == 0;
    }

    private static int bits(int fixture) {
        return fixture < 4 || fixture > 5 ? 16 : 8;
    }

    private static BufferedImage image(int width, int height, int bits, boolean srgb) {
        if (bits == 8) return new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var colorSpace = ColorSpace.getInstance(srgb ? ColorSpace.CS_sRGB : ColorSpace.CS_LINEAR_RGB);
        return ImageTypeSpecifier.createBanded(colorSpace, new int[]{0, 1, 2}, new int[3],
                DataBuffer.TYPE_USHORT, false, false).createBufferedImage(width, height);
    }

    private static void fill(BufferedImage image) {
        int bits = image.getSampleModel().getSampleSize(0);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                for (int band = 0; band < 3; band++) {
                    int value = (x * 1747 + y * 17231 + band * 19913 + x * y * 31) & 65535;
                    if ((x + 3 * y + 5 * band) % 31 == 0) value = 0;
                    if ((x + 7 * y + 2 * band) % 37 == 0) value = 65535;
                    image.getRaster().setSample(x, y, band, bits == 16 ? value : value >>> 8);
                }
            }
        }
    }

    private static String hash(BufferedImage image) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
        int[] samples = new int[image.getWidth() * 3];
        byte[] row = new byte[samples.length * 2];
        for (int y = 0; y < image.getHeight(); y++) {
            image.getRaster().getPixels(0, y, image.getWidth(), 1, samples);
            for (int i = 0; i < samples.length; i++) {
                row[2 * i] = (byte) samples[i];
                row[2 * i + 1] = (byte) (samples[i] >>> 8);
            }
            digest.update(row);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
