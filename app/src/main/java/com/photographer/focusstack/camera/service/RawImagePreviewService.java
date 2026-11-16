package com.photographer.focusstack.camera.service;

import com.photographer.focusstack.common.camera.CapturedImageFormat;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.decode.Cr3Decoder;
import com.photographer.focusstack.cr3.preview.Cr3PreviewExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Extracts browser-displayable JPEG preview images from Canon RAW files (CR3/CR2).
 *
 * <p>Canon RAW files contain embedded JPEG previews (~1620×1080 for CR3). This
 * service extracts those previews so the browser can display captured frames
 * without requiring a full RAW decoder on the client side.
 *
 * <p>The original RAW data is preserved in {@link FrameDataStore} for export
 * and processing. Browser previews can be read separately from the original.
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
        return CapturedImageFormat.isCanonRaw(fileName);
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
            return extractPreview(iis, originalFileName);
        } catch (IOException e) {
            log.debug("Failed to close preview input for {}", originalFileName, e);
            return null;
        }
    }

    /** Extracts a browser preview directly from the saved original. */
    public PreviewResult extractFilePreview(Path original, String originalFileName) {
        try (ImageInputStream iis = new FileImageInputStream(original.toFile())) {
            return extractPreview(iis, originalFileName);
        } catch (IOException e) {
            log.debug("Failed to open original frame {}", originalFileName, e);
            return null;
        }
    }

    private PreviewResult extractPreview(ImageInputStream iis, String originalFileName) {
        try {
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
            String baseName = originalFileName == null ? "frame" : originalFileName;
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

    /**
     * Decodes a full RAW-processed image from CR3 bytes for high-quality analysis pipelines.
     *
     * @param rawBytes CR3 bytes
     * @return decoded RGB image, or {@code null} if decoding fails
     */
    public BufferedImage decodeFullRaw(byte[] rawBytes) {
        if (rawBytes == null || rawBytes.length == 0) {
            return null;
        }
        try (ImageInputStream iis = new MemoryCacheImageInputStream(new ByteArrayInputStream(rawBytes))) {
            Cr3Decoder decoder = new Cr3Decoder();
            decoder.setInput(iis);
            return decoder.decode(Cr3DecodeOptions.rawProcessed());
        } catch (Exception e) {
            log.error("Failed to decode full CR3 RAW image: {}", e.getMessage(), e);
            return null;
        }
    }
}
