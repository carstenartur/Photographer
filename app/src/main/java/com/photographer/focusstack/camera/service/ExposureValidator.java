package com.photographer.focusstack.camera.service;

import com.photographer.focusstack.common.camera.ExposureAssessment;
import com.photographer.focusstack.common.camera.ExposureAssessment.Verdict;
import com.photographer.focusstack.common.camera.ExposureInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

/**
 * Analyses captured JPEG frames for exposure problems.
 *
 * <p>This service provides two complementary checks:
 * <ol>
 *   <li><b>Image brightness analysis</b> — examines actual pixel data to detect
 *       over/underexposure regardless of camera mode or metering.</li>
 *   <li><b>Settings plausibility</b> — logs the current camera settings so the
 *       operator can spot obviously wrong values (e.g. ISO 12800 in daylight).</li>
 * </ol>
 *
 * <p>The first captured frame is analysed <em>before</em> the remaining stack
 * frames are taken, so the job can abort early instead of filling the SD card
 * with useless images.
 */
@Service
public class ExposureValidator {

    private static final Logger log = LoggerFactory.getLogger(ExposureValidator.class);

    /** Pixels with luminance above this (0–255 scale) are "highlight-clipped". */
    private static final int HIGHLIGHT_THRESHOLD = 250;
    /** Pixels with luminance below this (0–255 scale) are "shadow-clipped". */
    private static final int SHADOW_THRESHOLD = 5;
    /** Mean brightness above this → WARN overexposed. */
    private static final double MEAN_BRIGHT_WARN = 0.80;
    /** Mean brightness above this → CRITICAL overexposed. */
    private static final double MEAN_BRIGHT_CRITICAL = 0.92;
    /** Mean brightness below this → WARN underexposed. */
    private static final double MEAN_DARK_WARN = 0.15;
    /** Mean brightness below this → CRITICAL underexposed. */
    private static final double MEAN_DARK_CRITICAL = 0.06;
    /** Percentage of clipped highlights that triggers WARN. */
    private static final double CLIP_WARN_PCT = 10.0;
    /** Percentage of clipped highlights that triggers CRITICAL. */
    private static final double CLIP_CRITICAL_PCT = 40.0;

    /**
     * Analyses the brightness distribution of a JPEG frame.
     *
     * @param jpegData raw JPEG bytes (as returned by {@code CaptureResult.imageData()})
     * @return exposure assessment; never null
     */
    public ExposureAssessment analyzeFrame(byte[] jpegData) {
        if (jpegData == null || jpegData.length == 0) {
            return new ExposureAssessment(0, 0, 0, Verdict.OK,
                    "No image data available for exposure analysis");
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpegData));
            if (image == null) {
                return new ExposureAssessment(0, 0, 0, Verdict.OK,
                        "Could not decode image for exposure analysis");
            }
            return analyzeImage(image);
        } catch (Exception e) {
            log.debug("Exposure analysis failed: {}", e.getMessage());
            return new ExposureAssessment(0, 0, 0, Verdict.OK,
                    "Exposure analysis error: " + e.getMessage());
        }
    }

    private ExposureAssessment analyzeImage(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();

        // Sample every Nth pixel for performance on large images
        int step = Math.max(1, (width * height) / 100_000);
        long totalLuminance = 0;
        long highlightClipped = 0;
        long shadowClipped = 0;
        long sampleCount = 0;

        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                // ITU-R BT.601 luminance
                int lum = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                totalLuminance += lum;
                if (lum >= HIGHLIGHT_THRESHOLD) highlightClipped++;
                if (lum <= SHADOW_THRESHOLD) shadowClipped++;
                sampleCount++;
            }
        }

        double meanBrightness = (totalLuminance / (double) sampleCount) / 255.0;
        double overPct = (highlightClipped * 100.0) / sampleCount;
        double underPct = (shadowClipped * 100.0) / sampleCount;

        // Determine verdict
        Verdict verdict = Verdict.OK;
        StringBuilder msg = new StringBuilder();

        if (meanBrightness >= MEAN_BRIGHT_CRITICAL || overPct >= CLIP_CRITICAL_PCT) {
            verdict = Verdict.CRITICAL;
            msg.append(String.format("SEVERELY OVEREXPOSED — mean brightness %.0f%%, %.1f%% highlights clipped. ",
                    meanBrightness * 100, overPct));
            msg.append("Images will be blown out. Consider reducing ISO, using a faster shutter speed, or closing the aperture.");
        } else if (meanBrightness <= MEAN_DARK_CRITICAL) {
            verdict = Verdict.CRITICAL;
            msg.append(String.format("SEVERELY UNDEREXPOSED — mean brightness only %.0f%%, %.1f%% in deep shadow. ",
                    meanBrightness * 100, underPct));
            msg.append("Images will be mostly black. Consider increasing ISO, using a slower shutter speed, or opening the aperture.");
        } else if (meanBrightness >= MEAN_BRIGHT_WARN || overPct >= CLIP_WARN_PCT) {
            verdict = Verdict.WARN;
            msg.append(String.format("Slightly overexposed — mean brightness %.0f%%, %.1f%% highlights clipped.",
                    meanBrightness * 100, overPct));
        } else if (meanBrightness <= MEAN_DARK_WARN) {
            verdict = Verdict.WARN;
            msg.append(String.format("Slightly underexposed — mean brightness %.0f%%, %.1f%% in deep shadow.",
                    meanBrightness * 100, underPct));
        } else {
            msg.append(String.format("Exposure OK — mean brightness %.0f%%, highlights clipped %.1f%%, shadows clipped %.1f%%.",
                    meanBrightness * 100, overPct, underPct));
        }

        log.info("Exposure analysis: mean={}%, over={}%, under={}% → {}",
                String.format("%.1f", meanBrightness * 100),
                String.format("%.1f", overPct),
                String.format("%.1f", underPct), verdict);

        return new ExposureAssessment(meanBrightness, overPct, underPct, verdict, msg.toString());
    }

    /**
     * Logs the current camera exposure settings for diagnostic purposes.
     */
    public void logExposureSettings(ExposureInfo info) {
        log.info("Camera exposure settings: aperture={}, shutter={}, ISO={}, EV={}",
                info.aperture(), info.shutterSpeed(), info.iso(), info.exposureCompensation());
    }
}
