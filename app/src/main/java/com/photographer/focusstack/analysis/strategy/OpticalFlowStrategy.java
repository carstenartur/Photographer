package com.photographer.focusstack.analysis.strategy;

import com.photographer.focusstack.analysis.service.ImageJProcessor;
import com.photographer.focusstack.analysis.service.MotionDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link MotionDetectionStrategy} that uses dense optical flow for motion estimation.
 *
 * <p>When real image data (JPEG/PNG/BMP) is provided, this strategy delegates to
 * {@link ImageJProcessor#detectMotion} which computes pixel-level differences on the
 * decoded image (a more accurate comparison than comparing raw compressed bytes).
 *
 * <p><b>Extension point:</b> A future upgrade can replace the ImageJ pixel-diff
 * implementation here with OpenCV's {@code calcOpticalFlowFarneback} or
 * {@code calcOpticalFlowPyrLK} once native OpenCV libraries are available.
 * The strategy interface ensures the switch is transparent to callers.
 *
 * <p>Falls back gracefully to {@link PixelDiffStrategy} for non-image byte arrays
 * (e.g., synthetic test data).
 */
public class OpticalFlowStrategy implements MotionDetectionStrategy {

    private static final Logger log = LoggerFactory.getLogger(OpticalFlowStrategy.class);

    private final ImageJProcessor imageJProcessor;
    private final PixelDiffStrategy fallback;

    public OpticalFlowStrategy(ImageJProcessor imageJProcessor, MotionDetector motionDetector) {
        this.imageJProcessor = imageJProcessor;
        this.fallback = new PixelDiffStrategy(motionDetector);
    }

    @Override
    public MotionDetector.MotionResult detect(byte[] current, byte[] previous, double threshold) {
        if (imageJProcessor.isImageFile(current) && imageJProcessor.isImageFile(previous)) {
            // Use ImageJ-decoded pixel comparison (more accurate than raw byte diff)
            return imageJProcessor.detectMotion(current, previous, threshold);
        }
        // Fallback: raw pixel diff for synthetic / non-image data
        log.debug("OpticalFlowStrategy: falling back to pixel-diff (non-image data)");
        return fallback.detect(current, previous, threshold);
    }

    @Override
    public String name() {
        return "optical-flow";
    }
}
