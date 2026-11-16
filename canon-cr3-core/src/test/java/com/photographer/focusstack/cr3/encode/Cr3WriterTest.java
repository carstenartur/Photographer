package com.photographer.focusstack.cr3.encode;

import com.photographer.focusstack.cr3.container.Cr3Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.FileImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Cr3WriterTest {
    @Test
    void embedsTheSamePreviewJpegAsPackedIntRgb(@TempDir Path directory) throws Exception {
        var profile = Cr3CameraProfile.POWERSHOT_G5_X_MARK_II;
        var crop = profile.raw().crop();
        int width = profile.sensorWidth();
        var sensor = Raster.createInterleavedRaster(DataBuffer.TYPE_USHORT, width, profile.sensorHeight(), 1, null);
        int[] alternatingMaximum = new int[width];
        for (int x = 0; x < width; x += 2) {
            alternatingMaximum[x] = 16383;
        }
        int midpoint = crop.top() + crop.height() / 2;
        // Top Bayer cells have maximum red and one maximum green site. Bottom cells have maximum blue.
        for (int y = crop.top(); y < midpoint; y++) {
            sensor.setSamples(0, y, width, 1, 0, alternatingMaximum);
        }
        for (int y = midpoint + 1; y <= crop.bottom(); y += 2) {
            for (int x = crop.left() + 1; x <= crop.right(); x += 2) {
                sensor.setSample(x, y, 0, 16383);
            }
        }

        Path file = directory.resolve("preview.cr3");
        try (var output = new FileImageOutputStream(file.toFile())) {
            Cr3Writer.write(output, sensor, profile);
        }
        try (var input = new FileImageInputStream(file.toFile())) {
            var preview = Cr3Parser.parse(input).prvwBox();
            assertArrayEquals(referenceJpeg(profile.previewWidth(), profile.previewHeight()), preview.jpegData());
        }
    }

    private static byte[] referenceJpeg(int width, int height) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[width];
        Arrays.fill(row, 0xffbc00); // Half linear green maps to 188 in sRGB.
        for (int y = 0; y < height / 2; y++) {
            image.getRaster().setDataElements(0, y, width, 1, row);
        }
        Arrays.fill(row, 0x0000ff);
        for (int y = height / 2; y < height; y++) {
            image.getRaster().setDataElements(0, y, width, 1, row);
        }
        var bytes = new ByteArrayOutputStream();
        try (var output = new MemoryCacheImageOutputStream(bytes)) {
            assertTrue(ImageIO.write(image, "JPEG", output));
        }
        return bytes.toByteArray();
    }
}
