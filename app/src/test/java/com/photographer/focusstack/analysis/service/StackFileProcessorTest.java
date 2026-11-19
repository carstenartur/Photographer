package com.photographer.focusstack.analysis.service;

import com.photographer.focusstack.camera.service.FrameDataStore.OriginalFrame;
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.decode.Cr3Decoder;
import com.photographer.focusstack.cr3.testutil.Cr3TestData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class StackFileProcessorTest {

    @TempDir
    Path directory;

    @Test
    void processesJpegOnlyStacksAndPreservesOriginalOrder() throws Exception {
        var processor = new StackFileProcessor(new RawDecodeBudget(2, 64L << 20));
        var first = new BufferedImage(13, 11, BufferedImage.TYPE_INT_RGB);
        var next = new BufferedImage(13, 11, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < first.getHeight(); y++) {
            for (int x = 0; x < first.getWidth(); x++) {
                first.setRGB(x, y, 0xff444444);
                next.setRGB(x, y, 0xffbbbbbb);
            }
        }
        Path original = directory.resolve("frame-0");
        Path second = directory.resolve("frame-1");
        ImageIO.write(first, "jpeg", original.toFile());
        ImageIO.write(next, "jpeg", second.toFile());

        var result = processor.merge(List.of(
                new OriginalFrame("IMG_0001.JPG", original),
                new OriginalFrame("IMG_missing.JPG", directory.resolve("missing")),
                new OriginalFrame("IMG_0002.JPG", second)));

        assertEquals(StackFileProcessor.Source.RASTER, result.source());
        assertEquals(2, result.frameCount());
        var image = ImageIO.read(new ByteArrayInputStream(result.png()));
        assertEquals(ImageIO.read(original.toFile()).getRGB(6, 5), image.getRGB(6, 5));
    }

    @Test
    void usesTheCameraPreviewWhenTheRawTrackIsUnavailable() throws Exception {
        byte[] jpeg = Cr3TestData.createMinimalJpeg();
        Path original = Files.write(directory.resolve("preview-only.cr3"),
                Cr3TestData.buildMinimalCr3WithJpeg(jpeg, jpeg));
        var processor = new StackFileProcessor(new RawDecodeBudget(1, 64L << 20));

        var result = processor.merge(List.of(new OriginalFrame("preview-only.cr3", original)));

        assertPreview(result, jpeg, 1);
    }

    @Test
    void usesPreviewDimensionsAndPixelsWhenTheRawBudgetIsTooSmall() throws Exception {
        byte[] jpeg = widerPreview();
        Path original = rawWithPreview(jpeg);
        // One tiny RAW frame plus the accumulator needs at least 16 MiB; its JPEG needs 12 MiB.
        var processor = new StackFileProcessor(new RawDecodeBudget(1, 14L << 20));

        var result = processor.merge(List.of(new OriginalFrame("raw-with-preview.cr3", original)));

        assertPreview(result, jpeg, 1);
    }

    @Test
    void switchesTheEntireStackToPreviewsWhenOneRawTrackIsUnavailable() throws Exception {
        byte[] jpeg = widerPreview();
        Path raw = rawWithPreview(jpeg);
        Path previewOnly = Files.write(directory.resolve("preview-only.cr3"),
                Cr3TestData.buildMinimalCr3WithJpeg(jpeg, jpeg));
        var processor = new StackFileProcessor(new RawDecodeBudget(2, 64L << 20));

        var result = processor.merge(List.of(
                new OriginalFrame("raw-with-preview.cr3", raw),
                new OriginalFrame("preview-only.cr3", previewOnly)));

        assertPreview(result, jpeg, 2);
    }

    private static byte[] widerPreview() throws Exception {
        var image = new BufferedImage(5, 3, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, (40 + 30 * x) << 16 | (50 + 45 * y) << 8 | 220 - 15 * x);
            }
        }
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", output);
        return output.toByteArray();
    }

    private Path rawWithPreview(byte[] jpeg) throws Exception {
        byte[] raw = Files.readAllBytes(Path.of(
                "../canon-cr3-imageio/src/test/resources/com/photographer/focusstack/cr3/imageio/four-sites.cr3"));
        var header = ByteBuffer.allocate(16 + jpeg.length).putInt(0).putShort((short) 1)
                .putShort((short) 5).putShort((short) 3).putShort((short) 1).putInt(jpeg.length).put(jpeg);
        // Append the existing PRVW container layout; all original RAW track offsets remain unchanged.
        byte[] preview = Cr3TestData.buildBox("uuid", Cr3TestData.concat(
                HexFormat.of().parseHex("eaf42b5e1c984b88b9fbb7dc406e4d16"), new byte[8],
                Cr3TestData.buildBox("PRVW", header.array())));
        return Files.write(directory.resolve("raw-with-preview.cr3"), Cr3TestData.concat(raw, preview));
    }

    private static void assertPreview(StackFileProcessor.Result result, byte[] jpeg, int frameCount) throws Exception {
        assertEquals(StackFileProcessor.Source.EMBEDDED_PREVIEW, result.source());
        assertEquals(frameCount, result.frameCount());
        BufferedImage expected = ImageIO.read(new ByteArrayInputStream(jpeg));
        BufferedImage actual = ImageIO.read(new ByteArrayInputStream(result.png()));
        assertEquals(expected.getWidth(), actual.getWidth());
        assertEquals(expected.getHeight(), actual.getHeight());
        int width = expected.getWidth();
        int height = expected.getHeight();
        assertArrayEquals(expected.getRGB(0, 0, width, height, null, 0, width),
                actual.getRGB(0, 0, width, height, null, 0, width));
    }

    @Test
    void developsTheOriginalSensorImageForACr3Stack() throws Exception {
        Path path = Path.of(System.getProperty("cr3.stack.test",
                "../canon-cr3-imageio/src/test/resources/samples/eosr-iso-low.cr3"));
        var processor = new StackFileProcessor(new RawDecodeBudget(1, 768L << 20));
        var frame = new OriginalFrame("IMG_0001.CR3", path);
        Dimension expected;
        try (var decoder = new Cr3Decoder()) {
            decoder.setInput(path);
            expected = decoder.getRawProcessedBounds(Cr3DecodeOptions.rawProcessed()).getSize();
        }

        var result = processor.merge(List.of(frame, frame));

        assertEquals(StackFileProcessor.Source.RAW, result.source());
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(result.png()))) {
            var reader = ImageIO.getImageReaders(input).next();
            try {
                reader.setInput(input);
                assertEquals(expected.width, reader.getWidth(0));
                assertEquals(expected.height, reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        }
    }
}
