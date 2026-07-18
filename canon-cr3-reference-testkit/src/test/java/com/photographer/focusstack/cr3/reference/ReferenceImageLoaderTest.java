package com.photographer.focusstack.cr3.reference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReferenceImageLoaderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsUnsignedSixteenBitSamplesInRowMajorOrder() throws Exception {
        BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_USHORT_GRAY);
        WritableRaster raster = image.getRaster();
        int[] expected = {0, 1, 32768, 65535, 2048, 16383};
        raster.setSamples(0, 0, 3, 2, 0, expected);

        Path tiff = temporaryDirectory.resolve("fixture.reference.tiff");
        assertThat(ImageIO.write(image, "TIFF", tiff.toFile())).isTrue();

        ReferenceImage loaded = ReferenceImageLoader.load(tiff);

        assertThat(loaded.width()).isEqualTo(3);
        assertThat(loaded.height()).isEqualTo(2);
        assertThat(loaded.samples()).containsExactly(expected);
    }

    @Test
    void reportsMissingReferenceFile() {
        Path missing = temporaryDirectory.resolve("missing.reference.tiff");

        assertThatThrownBy(() -> ReferenceImageLoader.load(missing))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("does not exist");
    }
}
