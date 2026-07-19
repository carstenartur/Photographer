package com.photographer.focusstack.cr3.metadata;

import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.testutil.Cr3TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link Cr3MetadataReader}.
 */
class Cr3MetadataReaderTest {

    @Test
    @DisplayName("read should extract basic metadata from CR3 container")
    void readBasicMetadata() throws Exception {
        byte[] cr3Data = Cr3TestData.buildMinimalCr3WithJpeg();
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(cr3Data));
        Cr3Container container = Cr3Parser.parse(input);

        Cr3Metadata metadata = Cr3MetadataReader.read(input, container);

        assertThat(metadata).isNotNull();
        assertThat(metadata.cameraMake()).isEqualTo("Canon");
    }

    @Test
    @DisplayName("read should default bit depth to 14")
    void readDefaultBitDepth() throws Exception {
        byte[] cr3Data = Cr3TestData.buildMinimalCr3WithJpeg();
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(cr3Data));
        Cr3Container container = Cr3Parser.parse(input);

        Cr3Metadata metadata = Cr3MetadataReader.read(input, container);

        assertThat(metadata.bitDepth()).isEqualTo(14);
    }

    @Test
    @DisplayName("read should return non-null metadata even without CMT boxes")
    void readWithoutCmtBoxes() throws Exception {
        byte[] cr3Data = Cr3TestData.buildMinimalCr3WithJpeg();
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(cr3Data));
        Cr3Container container = Cr3Parser.parse(input);

        Cr3Metadata metadata = Cr3MetadataReader.read(input, container);

        // Should not throw, should return valid metadata with defaults
        assertThat(metadata.cameraModel()).isEmpty();
        assertThat(metadata.shutterSpeed()).isEmpty();
        assertThat(metadata.aperture()).isEmpty();
    }
}
