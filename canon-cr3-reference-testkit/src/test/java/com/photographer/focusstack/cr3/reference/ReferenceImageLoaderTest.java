package com.photographer.focusstack.cr3.reference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ReferenceImageLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsUnsigned16BitSamplesWithoutDisplayConversion() throws Exception {
        assumeTrue(ImageIO.getImageWritersByFormatName("TIFF").hasNext(),
                "No TIFF ImageIO writer is registered in this runtime");

        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_USHORT_GRAY);
        image.getRaster().setSamples(0, 0, 2, 2, 0,
                new int[]{0, 2_048, 32_768, 65_535});
        Path tiff = tempDir.resolve("reference.tiff");
        assertThat(ImageIO.write(image, "TIFF", tiff.toFile())).isTrue();

        ReferenceImage loaded = ReferenceImageLoader.load(tiff);

        assertThat(loaded.width()).isEqualTo(2);
        assertThat(loaded.height()).isEqualTo(2);
        assertThat(loaded.samples()).containsExactly(0, 2_048, 32_768, 65_535);
    }

    @Test
    void loadsGrayscalePngSamplesWithoutColorConversion() throws Exception {
        BufferedImage image = new BufferedImage(3, 1, BufferedImage.TYPE_BYTE_GRAY);
        image.getRaster().setSamples(0, 0, 3, 1, 0, new int[]{0, 127, 255});
        Path png = tempDir.resolve("reference.png");
        assertThat(ImageIO.write(image, "PNG", png.toFile())).isTrue();

        ReferenceImage loaded = ReferenceImageLoader.load(png);

        assertThat(loaded.width()).isEqualTo(3);
        assertThat(loaded.height()).isEqualTo(1);
        assertThat(loaded.samples()).containsExactly(0, 127, 255);
    }

    @Test
    void loadsFirstDecodedRasterBandWithoutImplicitLuminanceConversion() throws Exception {
        BufferedImage source = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        source.getRaster().setPixel(0, 0, new int[]{10, 20, 30});
        source.getRaster().setPixel(1, 0, new int[]{200, 150, 100});
        Path png = tempDir.resolve("rgb-reference.png");
        assertThat(ImageIO.write(source, "PNG", png.toFile())).isTrue();

        BufferedImage decoded = ImageIO.read(png.toFile());
        int[] expectedBandZero = decoded.getRaster().getSamples(
                0, 0, decoded.getWidth(), decoded.getHeight(), 0, (int[]) null);
        ReferenceImage loaded = ReferenceImageLoader.load(png);

        assertThat(loaded.width()).isEqualTo(decoded.getWidth());
        assertThat(loaded.height()).isEqualTo(decoded.getHeight());
        assertThat(loaded.samples()).containsExactly(expectedBandZero);
    }
}
