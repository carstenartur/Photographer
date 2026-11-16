package com.photographer.focusstack.analysis.service;

import com.photographer.focusstack.analysis.strategy.MotionDetectionStrategy;
import com.photographer.focusstack.analysis.strategy.OpticalFlowStrategy;
import com.photographer.focusstack.analysis.strategy.PixelDiffStrategy;
import com.photographer.focusstack.analysis.strategy.StructuralSimilarityStrategy;
import com.photographer.focusstack.common.analysis.AnalysisResult;
import com.photographer.focusstack.common.model.StackingParameters;
import ij.process.ByteProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class StackingAdvisor {

    private static final Logger log = LoggerFactory.getLogger(StackingAdvisor.class);

    private final SharpnessAnalyzer sharpnessAnalyzer;
    private final InformationGainEstimator informationGainEstimator;
    private final MotionDetectionStrategy motionDetectionStrategy;
    private final ImageJProcessor imageJProcessor;

    public record RawFrameAnalysis(AnalysisResult result, ByteProcessor pixels) {}

    public StackingAdvisor(SharpnessAnalyzer sharpnessAnalyzer,
                           MotionDetector motionDetector,
                           InformationGainEstimator informationGainEstimator,
                           ImageJProcessor imageJProcessor,
                           @Value("${analysis.motion.strategy:structural-similarity}") String strategyName) {
        this.sharpnessAnalyzer = sharpnessAnalyzer;
        this.informationGainEstimator = informationGainEstimator;
        this.imageJProcessor = imageJProcessor;
        this.motionDetectionStrategy = switch (strategyName.toLowerCase()) {
            case "pixel-diff" -> new PixelDiffStrategy(motionDetector);
            case "optical-flow" -> new OpticalFlowStrategy(imageJProcessor, motionDetector);
            default -> new StructuralSimilarityStrategy(imageJProcessor, motionDetector);
        };
        log.info("StackingAdvisor initialized with '{}' motion detection strategy",
                this.motionDetectionStrategy.name());
    }

    /**
     * Analyzes a frame and makes a continue/stop recommendation.
     *
     * <p>For decodable image files (JPEG, PNG, BMP) the sharpness is computed via
     * {@link ImageJProcessor} which decodes the image properly before applying the
     * Laplacian kernel.  For raw/synthetic byte arrays (e.g. unit-test data) the
     * fallback {@link SharpnessAnalyzer} is used.
     */
    public AnalysisResult analyzeFrame(byte[] imageData,
                                       byte[] previousFrame,
                                       List<AnalysisResult> history,
                                       StackingParameters params) {
        double sharpnessScore = computeSharpness(imageData);
        MotionDetector.MotionResult motionResult;
        if (params.motionDetectionEnabled() && previousFrame != null) {
            motionResult = motionDetectionStrategy.detect(imageData, previousFrame, 0.15);
        } else {
            motionResult = new MotionDetector.MotionResult(0.0, false, 0.15);
        }

        return completeAnalysis(sharpnessScore, motionResult, history, params);
    }

    /** Prepares each captured RAW frame once and keeps only its reduced grayscale pixels. */
    public RawFrameAnalysis analyzeRawFrame(
            byte[] imageData,
            byte[] previousEncoded,
            RawFrameAnalysis previousRaw,
            List<AnalysisResult> history,
            StackingParameters params) {
        ByteProcessor current = imageJProcessor.readForStacking(imageData);
        MotionDetector.MotionResult motion = new MotionDetector.MotionResult(0.0, false, 0.15);
        if (params.motionDetectionEnabled() && current != null) {
            ByteProcessor previous = previousRaw == null
                    ? imageJProcessor.readForStacking(previousEncoded) : previousRaw.pixels();
            motion = motionDetectionStrategy instanceof StructuralSimilarityStrategy
                    ? imageJProcessor.detectDecodedStructuralMotion(current, previous, 0.15)
                    : imageJProcessor.detectDecodedMotion(current, previous, 0.15);
        }
        AnalysisResult result = completeAnalysis(
                imageJProcessor.calculateDecodedSharpness(current), motion, history, params);
        return new RawFrameAnalysis(result, current);
    }

    private AnalysisResult completeAnalysis(
            double sharpnessScore,
            MotionDetector.MotionResult motionResult,
            List<AnalysisResult> history,
            StackingParameters params) {
        double sharpRegionPercentage = estimateSharpRegion(sharpnessScore);
        // Build a preliminary result for gain estimation
        AnalysisResult preliminary = new AnalysisResult(
                UUID.randomUUID().toString(),
                sharpnessScore,
                sharpRegionPercentage,
                motionResult.motionDetected(),
                motionResult.motionScore(),
                0.0,
                true
        );

        double infoGain = informationGainEstimator.estimateGain(history, preliminary);

        boolean recommendContinue = shouldContinue(
                infoGain, motionResult, history, params);

        return new AnalysisResult(
                preliminary.imageId(),
                sharpnessScore,
                sharpRegionPercentage,
                motionResult.motionDetected(),
                motionResult.motionScore(),
                infoGain,
                recommendContinue
        );
    }

    private boolean shouldContinue(double infoGain,
                                   MotionDetector.MotionResult motionResult,
                                   List<AnalysisResult> history,
                                   StackingParameters params) {
        // Abort if max frames reached
        if (history != null && history.size() >= params.maxFrames()) {
            return false;
        }
        // Never recommend stopping before the minimum number of frames —
        // a focus stack with 1-2 frames is useless.
        if (history == null || history.size() < InformationGainEstimator.MIN_FRAMES_BEFORE_STOP) {
            return true;
        }
        // Abort if motion detected (the structural-similarity strategy is
        // blur-invariant, so a positive here represents real subject motion)
        if (motionResult.motionDetected()) {
            log.info("Recommending stop: motion detected (score={})", motionResult.motionScore());
            return false;
        }
        // Abort if information gain below threshold
        if (infoGain < params.abortThreshold()) {
            log.info("Recommending stop: information gain {}<{} (threshold)",
                    infoGain, params.abortThreshold());
            return false;
        }
        return true;
    }

    private double estimateSharpRegion(double sharpnessScore) {
        return Math.min(1.0, sharpnessScore * 1.2);
    }

    /**
     * Computes a sharpness score for the given byte array.
     * Uses {@link ImageJProcessor} for decodable image files (JPEG/PNG/BMP), which
     * correctly decodes the image before applying the Laplacian kernel.
     * Falls back to {@link SharpnessAnalyzer} for raw/synthetic byte arrays.
     */
    private double computeSharpness(byte[] imageData) {
        if (imageData != null && imageJProcessor.isImageFile(imageData)) {
            return imageJProcessor.calculateSharpness(imageData);
        }
        return sharpnessAnalyzer.calculateSharpness(imageData);
    }
}
