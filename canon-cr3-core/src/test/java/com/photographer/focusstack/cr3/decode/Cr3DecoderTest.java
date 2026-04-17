package com.photographer.focusstack.cr3.decode;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.testutil.Cr3TestData;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class Cr3DecoderTest {

    @Nested
    class InputValidation {
        @Test
        void decodeWithoutInputThrowsIllegalStateException() {
            Cr3Decoder decoder = new Cr3Decoder();
            assertThrows(IllegalStateException.class,
                    () -> decoder.decode(Cr3DecodeOptions.defaults()));
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
