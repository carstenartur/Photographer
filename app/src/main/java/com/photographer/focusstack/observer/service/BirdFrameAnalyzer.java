package com.photographer.focusstack.observer.service;

import com.photographer.focusstack.ai.VisionContext;
import com.photographer.focusstack.ai.VisionProvider;
import com.photographer.focusstack.ai.VisionResult;
import com.photographer.focusstack.observer.bird.BirdTriggerSettings;
import com.photographer.focusstack.observer.events.BoundingBox;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Pixel-based ROI preparation; the detector never sees pixels outside the selected rectangle. */
final class BirdFrameAnalyzer {
    static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;
    private final VisionProvider provider;

    BirdFrameAnalyzer(VisionProvider provider) {
        this.provider = provider;
    }

    record Analysis(byte[] previewJpeg, int width, int height, List<BoundingBox> boxes, VisionResult result) {}

    Analysis analyze(byte[] encoded, BirdTriggerSettings settings) throws IOException {
        BufferedImage full = decode(encoded);
        var roi = settings.roi();
        int left = Math.min(full.getWidth() - 1, (int) Math.floor(roi.x() * full.getWidth()));
        int top = Math.min(full.getHeight() - 1, (int) Math.floor(roi.y() * full.getHeight()));
        int right = Math.min(full.getWidth(), (int) Math.ceil((roi.x() + roi.width()) * full.getWidth()));
        int bottom = Math.min(full.getHeight(), (int) Math.ceil((roi.y() + roi.height()) * full.getHeight()));
        int width = Math.max(1, right - left);
        int height = Math.max(1, bottom - top);
        byte[] crop = encode(full.getSubimage(left, top, width, height), "png");
        VisionResult result = provider.detectObjects(crop, VisionContext.wildlife());
        if (result == null || !result.successful()) {
            throw new DetectionFailure("Local bird detection failed; no capture was requested");
        }
        List<BoundingBox> boxes = new ArrayList<>();
        if (result.boundingBoxes() != null) for (BoundingBox box : result.boundingBoxes()) {
            if (!qualifies(box, settings)) continue;
            boxes.add(new BoundingBox(
                    (left + box.x() * width) / full.getWidth(),
                    (top + box.y() * height) / full.getHeight(),
                    box.width() * width / full.getWidth(),
                    box.height() * height / full.getHeight(), "bird", box.confidence()));
        }
        BufferedImage preview = previewImage(full);
        return new Analysis(encode(preview, "jpg"), preview.getWidth(), preview.getHeight(), List.copyOf(boxes), result);
    }

    private static boolean qualifies(BoundingBox box, BirdTriggerSettings settings) {
        return box != null && "bird".equals(box.label())
                && Double.isFinite(box.confidence()) && box.confidence() >= settings.minConfidence()
                && box.confidence() <= 1 && Double.isFinite(box.x()) && Double.isFinite(box.y())
                && Double.isFinite(box.width()) && Double.isFinite(box.height())
                && box.x() >= 0 && box.y() >= 0 && box.width() > 0 && box.height() > 0
                && box.x() + box.width() <= 1.000000001 && box.y() + box.height() <= 1.000000001
                && box.width() * box.height() >= settings.minArea();
    }

    static BufferedImage decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_FRAME_BYTES) {
            throw new IOException("Live-view image is empty or exceeds the image budget");
        }
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Live view is not a supported JPEG/PNG image");
            var reader = readers.next();
            try {
                String format = reader.getFormatName();
                if (!"JPEG".equalsIgnoreCase(format) && !"PNG".equalsIgnoreCase(format)) {
                    throw new IOException("Live view is not a supported JPEG/PNG image");
                }
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 8192 || height > 8192
                        || (long) width * height > 16_777_216L) {
                    throw new IOException("Live-view dimensions exceed the image budget");
                }
                var warning = new java.util.concurrent.atomic.AtomicBoolean();
                reader.addIIOReadWarningListener((source, message) -> warning.set(true));
                BufferedImage image = reader.read(0);
                if (warning.get()) throw new IOException("Live-view image was only partially decoded");
                if (image == null) throw new IOException("Live-view image could not be decoded");
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    static byte[] jpegPreview(byte[] source) throws IOException {
        return encode(previewImage(decode(source)), "jpg");
    }

    private static BufferedImage previewImage(BufferedImage source) {
        double scale = Math.min(1, 1280.0 / Math.max(source.getWidth(), source.getHeight()));
        var image = new BufferedImage(Math.max(1, (int) Math.round(source.getWidth() * scale)),
                Math.max(1, (int) Math.round(source.getHeight() * scale)), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, image.getWidth(), image.getHeight(), null);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        var output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, output)) throw new IOException("Image encoder is unavailable");
        return output.toByteArray();
    }

    static final class DetectionFailure extends IllegalStateException {
        DetectionFailure(String message) { super(message); }
    }
}
