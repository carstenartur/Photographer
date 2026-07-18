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
}
