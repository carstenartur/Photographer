package com.photographer.focusstack.observer.service;

import com.photographer.focusstack.camera.service.RawImagePreviewService;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.camera.CapturedImageFormat;
import com.photographer.focusstack.observer.events.CapturedImage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Durable, generated filenames: camera-provided paths are never used as local paths. */
@Service
public class BirdCaptureStore {
    private final Path directory;
    private final RawImagePreviewService rawPreviews;

    public BirdCaptureStore(@Value("${observer.local-bird.output-directory:./data/observer}") String directory,
                            RawImagePreviewService rawPreviews) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        this.rawPreviews = rawPreviews;
    }

    public record StoredCapture(CapturedImage original, String previewPath, String previewSource) {}

    StoredCapture save(String eventId, CaptureResult capture, byte[] triggerPreview) throws IOException {
        UUID.fromString(eventId);
        if (capture == null || capture.imageData() == null || capture.imageData().length == 0) {
            throw new IOException("Camera captured an image but did not return its original bytes");
        }
        byte[] original = capture.imageData();
        if (original.length > 256 * 1024 * 1024) throw new IOException("Captured original exceeds the storage budget");
        Files.createDirectories(directory);
        String extension = extension(capture.fileName(), original);
        String fileName = eventId + extension;
        Path originalPath = directory.resolve(fileName);
        // Preserve original first, even if preview generation or database persistence fails afterwards.
        Files.write(originalPath, original, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        byte[] preview = null;
        String source = "capture";
        if (CapturedImageFormat.isCanonRaw(fileName)) {
            var extracted = rawPreviews.extractPreview(original, fileName);
            if (extracted != null) preview = extracted.jpegBytes();
        } else {
            preview = original;
        }
        try {
            if (preview != null) preview = BirdFrameAnalyzer.jpegPreview(preview);
        } catch (IOException | RuntimeException invalidPreview) {
            preview = null;
        }
        if (preview == null) {
            preview = triggerPreview;
            source = "live-view-at-trigger";
        }
        Path previewPath = directory.resolve(eventId + ".preview.jpg");
        Files.write(previewPath, preview, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        var image = new CapturedImage(capture.imageId() == null ? eventId : capture.imageId(), fileName,
                originalPath.toString(), capture.capturedAt() == null ? Instant.now() : capture.capturedAt(),
                original.length, 0, true, Map.of());
        return new StoredCapture(image, previewPath.toString(), source);
    }

    private static String extension(String name, byte[] bytes) {
        if (CapturedImageFormat.hasCr3Header(bytes)) return ".cr3";
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (String extension : new String[]{".cr3", ".cr2", ".jpg", ".jpeg", ".png"}) {
            if (lower.endsWith(extension)) return extension;
        }
        if (bytes.length > 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8) return ".jpg";
        return ".bin";
    }
}
