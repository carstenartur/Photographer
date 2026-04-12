package com.photographer.focusstack.observer.capture;

import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.camera.FocusCommand;
import com.photographer.focusstack.observer.events.CapturedImage;
import com.photographer.focusstack.observer.monitoring.SharpnessEvaluator;
import com.photographer.focusstack.observer.profile.ObserverProfile;
import com.photographer.focusstack.observer.rules.TriggerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates capture sequences for observation events.
 *
 * <p>Supports single capture, burst capture, and focus-bracketing sequences.
 * The mode is determined by the active {@link ObserverProfile}.</p>
 */
public class CaptureOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(CaptureOrchestrator.class);

    /** Delay between burst frames in milliseconds. */
    private static final int BURST_DELAY_MS = 200;
    /** Delay after focus adjustment in milliseconds. */
    private static final int FOCUS_SETTLE_MS = 150;

    private final CameraBackend camera;
    private final SharpnessEvaluator sharpnessEvaluator;
    private final ObserverProfile profile;

    public CaptureOrchestrator(CameraBackend camera, SharpnessEvaluator sharpnessEvaluator,
                                ObserverProfile profile) {
        this.camera = camera;
        this.sharpnessEvaluator = sharpnessEvaluator;
        this.profile = profile;
    }

    /**
     * Executes a capture series appropriate to the current profile mode.
     *
     * @param trigger the trigger context that initiated the capture
     * @return result containing all images and the selected best image
     */
    public CaptureSeriesResult captureSeries(TriggerContext trigger) {
        return switch (profile.mode()) {
            case "macro_pollinator" -> captureMacroSeries(trigger);
            default                 -> captureWildlifeSeries(trigger);
        };
    }

    private CaptureSeriesResult captureWildlifeSeries(TriggerContext trigger) {
        log.debug("Wildlife burst capture: {} frames", profile.burstCount());
        List<CapturedImage> images = new ArrayList<>();
        for (int i = 0; i < profile.burstCount(); i++) {
            CaptureResult result = camera.capture();
            double score = sharpnessEvaluator.evaluate(result.imageData(), trigger.roi());
            images.add(toCapturedImage(result, score));
            if (i < profile.burstCount() - 1) {
                sleep(BURST_DELAY_MS);
            }
        }
        return selectBest(images, "highest_sharpness");
    }

    private CaptureSeriesResult captureMacroSeries(TriggerContext trigger) {
        log.debug("Macro capture: {} frames, focusBracketing={}", profile.burstCount(), profile.focusBracketing());
        List<CapturedImage> images = new ArrayList<>();
        if (profile.focusBracketing()) {
            for (int step = 0; step < profile.focusBracketingSteps(); step++) {
                camera.setFocus(new FocusCommand(FocusCommand.Direction.FAR, 1));
                sleep(FOCUS_SETTLE_MS);
                CaptureResult result = camera.capture();
                double score = sharpnessEvaluator.evaluate(result.imageData(), trigger.roi());
                images.add(toCapturedImage(result, score));
            }
        } else {
            for (int i = 0; i < profile.burstCount(); i++) {
                CaptureResult result = camera.capture();
                double score = sharpnessEvaluator.evaluate(result.imageData(), trigger.roi());
                images.add(toCapturedImage(result, score));
                if (i < profile.burstCount() - 1) {
                    sleep(BURST_DELAY_MS);
                }
            }
        }
        return selectBest(images, "highest_sharpness");
    }

    private CaptureSeriesResult selectBest(List<CapturedImage> images, String reason) {
        if (images.isEmpty()) {
            throw new IllegalStateException("No images captured");
        }
        CapturedImage best = images.stream()
                .max(Comparator.comparingDouble(CapturedImage::sharpnessScore))
                .orElseThrow();
        List<CapturedImage> marked = images.stream()
                .map(img -> new CapturedImage(img.id(), img.fileName(), img.filePath(),
                        img.capturedAt(), img.fileSize(), img.sharpnessScore(),
                        img.id().equals(best.id()), img.cameraSettings()))
                .toList();
        CapturedImage markedBest = marked.stream().filter(CapturedImage::isBestShot).findFirst().orElse(best);
        return new CaptureSeriesResult(marked, markedBest, reason);
    }

    private CapturedImage toCapturedImage(CaptureResult result, double sharpnessScore) {
        return new CapturedImage(
                result.imageId() != null ? result.imageId() : UUID.randomUUID().toString(),
                result.fileName(),
                result.filePath(),
                result.capturedAt(),
                result.fileSize(),
                sharpnessScore,
                false,
                Map.of("focusPosition", String.valueOf(result.focusPosition()))
        );
    }

    private void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
