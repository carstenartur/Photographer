package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.bmff.PrvwBox;
import com.photographer.focusstack.cr3.container.Cr3Container;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Cr3CameraJpegProbeTest {
    @Test
    void embeddedFullSizeJpegAndPublicRgb16ImportOfTheSamePhoto() throws Exception {
        var resource = Objects.requireNonNull(getClass().getResource("/samples/sample.cr3"));
        var result = Cr3CameraJpegProbe.measure(Path.of(resource.toURI()).toFile());
        assertEquals("ok", result.status());
        assertEquals("JPEG_BIG", result.camera().source());
        assertEquals(5472, result.camera().width());
        assertEquals(3648, result.camera().height());
        assertEquals(5496, result.rawWidth());
        assertEquals(3668, result.rawHeight());
        // A self-comparison would give zero. This fixture has different camera and RAW tone curves.
        assertTrue(result.metrics().medianE() > 0);
        assertTrue(Double.isFinite(result.metrics().p90E()) && result.metrics().p90E() >= result.metrics().medianE());
    }

    @Test
    void mapsOrientedTilesAndPreservesDifferencesSmallerThanOneEightBitCode() {
        var camera = new BufferedImage(64, 48, BufferedImage.TYPE_3BYTE_BGR);
        var model = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB), false, false,
                Transparency.OPAQUE, DataBuffer.TYPE_USHORT);
        var raw = new BufferedImage(model, model.createCompatibleWritableRaster(48, 64), false, null);
        for (int y = 0; y < 48; y++) {
            for (int x = 0; x < 64; x++) {
                int r = 24 + 2 * x, g = 48 + y, b = 40 + x + y;
                camera.getRaster().setPixel(x, y, new int[]{r, g, b});
                raw.getRaster().setPixel(47 - y, x, new int[]{257 * r, 257 * g, 257 * b});
            }
        }
        var reference = Cr3CameraJpegProbe.grid(camera, 6, 257);
        var exact = Cr3CameraJpegProbe.compare(Cr3CameraJpegProbe.grid(raw, 1, 1), reference);
        assertEquals(0, exact.medianE(), 1e-11);
        assertEquals(0, exact.p90E(), 1e-11);
        // Add less than half of one camera code to red only. An RGB8 conversion would erase it.
        for (int y = 0; y < raw.getHeight(); y++) {
            for (int x = 0; x < raw.getWidth(); x++) {
                var raster = raw.getRaster();
                raster.setSample(x, y, 0, raster.getSample(x, y, 0) + 32);
            }
        }
        var shifted = Cr3CameraJpegProbe.compare(Cr3CameraJpegProbe.grid(raw, 1, 1), reference);
        assertTrue(shifted.medianE() > 0 && shifted.medianA() > 0 && shifted.medianL() > 0);
        assertTrue(Cr3CameraJpegProbe.compatible(5496, 3668, 5472, 3648));
        assertFalse(Cr3CameraJpegProbe.compatible(4000, 3000, 3000, 2000));
        assertFalse(Cr3CameraJpegProbe.compatible(1, 1, 1, 1));
    }

    @Test
    void usesJpegPrvwFallbackButSkipsCiszAndReportsDamagedPreviews() throws IOException {
        var jpeg = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB), "jpeg", jpeg);
        var fallback = preview(jpeg.toByteArray());
        assertEquals("PRVW", fallback.source());
        assertEquals(64, fallback.width());
        assertFalse(fallback.comparable()); // No JPEG or container color declaration on this fixture.
        byte[] hevc = {0, 0, 0, 20, 'C','I','S','Z', 0,0,0,0, 0,0,6,-128, 0,0,4,56,
                0,0,0,-80, 'h','v','c','C'};
        assertNull(preview(hevc));
        assertThrows(IOException.class, () -> preview(new byte[]{1, 2, 3}));
    }

    private static Cr3CameraJpegProbe.Camera preview(byte[] bytes) throws IOException {
        var container = new Cr3Container(null, null, null, List.of(), List.of(), null, null, null, null,
                new PrvwBox(64, 48, bytes));
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(new byte[0]))) {
            return Cr3CameraJpegProbe.readCamera(stream, container);
        }
    }

    @Test
    void usesEvenMedianAndNearestRankP90WithRawMinusJpegSigns() {
        var camera = new double[48][3];
        var raw = new double[48][3];
        for (int i = 0; i < raw.length; i++) {
            camera[i] = new double[]{50, 0, 0};
            raw[i] = new double[]{50, 0, 0};
        }
        for (int i = 24; i < 48; i++) raw[i] = new double[]{55, 2, -3};
        for (int i = 44; i < 48; i++) raw[i] = new double[]{90, 5, -5};
        var expectedP90 = ImageQualityHelper.deltaE2000(raw[43], camera[43]);
        var result = Cr3CameraJpegProbe.compare(raw, camera);
        assertEquals(expectedP90 / 2, result.medianE());
        assertEquals(expectedP90, result.p90E());
        assertEquals(2.5, result.medianL());
        assertEquals(1.0, result.medianA());
        assertEquals(-1.5, result.medianB());
        assertEquals(50.0, result.jpegMedianL());
    }
}
