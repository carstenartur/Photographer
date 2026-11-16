package com.photographer.focusstack.analysis.service;

import com.photographer.focusstack.camera.service.FrameDataStore.OriginalFrame;
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.decode.Cr3Decoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;

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
