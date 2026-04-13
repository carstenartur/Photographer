package com.photographer.focusstack.camera.service;

import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.preview.Cr3PreviewExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;

/**
 * Extracts browser-displayable JPEG preview images from Canon RAW files (CR3/CR2).
 *
 * <p>Canon RAW files contain embedded JPEG previews (~1620×1080 for CR3). This
 * service extracts those previews so the browser can display captured frames
 * without requiring a full RAW decoder on the client side.
 *
 * <p>The original RAW data is preserved in {@link FrameDataStore} for export
 * and processing; only the HTTP image endpoint uses the extracted preview.
 */
@Service
public class RawImagePreviewService {

    private static final Logger log = LoggerFactory.getLogger(RawImagePreviewService.class);

    /**
     * Result of a preview extraction attempt.
     *
     * @param jpegBytes  the JPEG preview bytes, or {@code null} if extraction failed
     * @param fileName   a display filename for the preview (e.g. {@code IMG_1234.preview.jpg})
     */
    public record PreviewResult(byte[] jpegBytes, String fileName) {}

    /**
     * Returns {@code true} if the filename indicates a Canon RAW file that may
     * contain an embedded JPEG preview.
     */
    public boolean isRawFile(String fileName) {
        if (fileName == null) return false;
        String upper = fileName.toUpperCase();
        return upper.endsWith(".CR3") || upper.endsWith(".CR2");
    }

    /**
     * Attempts to extract the embedded JPEG preview from a CR3 file.
     *
     * @param rawBytes the raw CR3 file bytes
     * @param originalFileName the original filename (used for logging and the result filename)
     * @return a {@link PreviewResult} with the JPEG bytes, or {@code null} if extraction fails
     */
    public PreviewResult extractPreview(byte[] rawBytes, String originalFileName) {
        if (rawBytes == null || rawBytes.length == 0) {
            return null;
        }
        try (ImageInputStream iis = new MemoryCacheImageInputStream(new ByteArrayInputStream(rawBytes))) {
            Cr3Container container = Cr3Parser.parse(iis);
            if (!Cr3PreviewExtractor.hasPreview(container)) {
                log.warn("CR3 file {} has no embedded JPEG preview", originalFileName);
                return null;
            }
            byte[] jpegBytes = Cr3PreviewExtractor.extractPreviewBytes(iis, container);
            if (jpegBytes == null || jpegBytes.length == 0) {
                log.warn("CR3 file {} — preview extraction returned empty data", originalFileName);
                return null;
            }
            // Build a display filename: IMG_1234.CR3 → IMG_1234.preview.jpg
            String baseName = originalFileName;
            int dotIdx = baseName.lastIndexOf('.');
            if (dotIdx > 0) {
                baseName = baseName.substring(0, dotIdx);
            }
            String previewName = baseName + ".preview.jpg";
            log.info("Extracted {} byte JPEG preview from {} → {}",
                    jpegBytes.length, originalFileName, previewName);
            return new PreviewResult(jpegBytes, previewName);
        } catch (Exception e) {
            log.error("Failed to extract JPEG preview from {}: {}", originalFileName, e.getMessage(), e);
            return null;
        }
    }
}
