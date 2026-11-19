package com.photographer.focusstack.observer.service;

import com.photographer.focusstack.ai.VisionContext;
import com.photographer.focusstack.ai.VisionProvider;
import com.photographer.focusstack.ai.VisionResult;
import com.photographer.focusstack.observer.bird.BirdTriggerSettings;
import com.photographer.focusstack.observer.events.BoundingBox;
import com.photographer.focusstack.observer.events.RegionOfInterest;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BirdFrameAnalyzerTest {
    @Test
    void cropsActualOffCentrePixelsBeforeInferenceAndMapsBoxesToFullImage() throws Exception {
        VisionProvider provider = mock(VisionProvider.class);
        when(provider.detectObjects(any(), any())).thenAnswer(invocation -> {
            BufferedImage crop = ImageIO.read(new ByteArrayInputStream(invocation.getArgument(0)));
            assertEquals(100, crop.getWidth());
            assertEquals(100, crop.getHeight());
            assertEquals(Color.RED.getRGB(), crop.getRGB(50, 50));
            return detected(new BoundingBox(.1, .2, .5, .4, "bird", .9));
        });
        BufferedImage frame = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
        var graphics = frame.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(200, 50, 100, 100);
        graphics.dispose();
        var settings = new BirdTriggerSettings(new RegionOfInterest(.5, .25, .25, .5, "perch"),
                .5, .19, 500, 5, 3);
        var result = new BirdFrameAnalyzer(provider).analyze(png(frame), settings);
        var box = result.boxes().getFirst();
        assertEquals(.525, box.x(), 1e-9);
        assertEquals(.35, box.y(), 1e-9);
        assertEquals(.125, box.width(), 1e-9);
        assertEquals(.2, box.height(), 1e-9);
        assertEquals(400, ImageIO.read(new ByteArrayInputStream(result.previewJpeg())).getWidth());
    }

    @Test
    void filtersConfidenceAreaOtherClassesAndMalformedBoxes() throws Exception {
        VisionProvider provider = mock(VisionProvider.class);
        when(provider.detectObjects(any(), any())).thenReturn(detected(
                new BoundingBox(0, 0, .1, .1, "bird", .2),
                new BoundingBox(0, 0, .001, .001, "bird", .9),
                new BoundingBox(0, 0, .5, .5, "cat", .9),
                new BoundingBox(Double.NaN, 0, .5, .5, "bird", .9)));
        assertTrue(new BirdFrameAnalyzer(provider).analyze(jpeg(1), BirdTriggerSettings.defaults()).boxes().isEmpty());
    }

    @Test
    void malformedInputAndProviderFailureAreNotSuccessfulEmptyFrames() {
        VisionProvider provider = mock(VisionProvider.class);
        BirdFrameAnalyzer analyzer = new BirdFrameAnalyzer(provider);
        assertThrows(Exception.class, () -> analyzer.analyze(new byte[]{1, 2, 3}, BirdTriggerSettings.defaults()));
        verifyNoInteractions(provider);
        when(provider.detectObjects(any(), any())).thenReturn(VisionResult.error("local-bird", "object_detection", "failed"));
        assertThrows(Exception.class, () -> analyzer.analyze(jpeg(2), BirdTriggerSettings.defaults()));
    }

    @Test
    void rejectsTruncatedJpegBeforeReencodingCanHideDecoderWarnings() throws Exception {
        byte[] complete = jpeg(4);
        byte[] truncated = java.util.Arrays.copyOf(complete, complete.length - 2);
        assertThrows(java.io.IOException.class, () -> BirdFrameAnalyzer.decode(truncated));
    }

    static VisionResult detected(BoundingBox... boxes) {
        return new VisionResult("local-bird", "test-model", "object_detection", "bird", List.of(), .9,
                "Bird, species unknown", List.of(boxes), null, 1, true, null);
    }

    static byte[] jpeg(int marker) throws Exception {
        BufferedImage image = new BufferedImage(80, 60, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(marker % 255, (marker * 40) % 255, 100));
        graphics.fillRect(0, 0, 80, 60);
        graphics.dispose();
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", bytes);
        return bytes.toByteArray();
    }

    private static byte[] png(BufferedImage image) throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }
}
