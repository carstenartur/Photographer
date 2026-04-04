package com.photographer.focusstack.analysis.service;

import com.photographer.focusstack.analysis.strategy.MotionDetectionStrategy;
import com.photographer.focusstack.analysis.strategy.OpticalFlowStrategy;
import com.photographer.focusstack.analysis.strategy.PixelDiffStrategy;
import com.photographer.focusstack.common.analysis.AnalysisResult;
import com.photographer.focusstack.common.model.StackingParameters;
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

    public StackingAdvisor(SharpnessAnalyzer sharpnessAnalyzer,
                           MotionDetector motionDetector,
                           InformationGainEstimator informationGainEstimator,
                           ImageJProcessor imageJProcessor,
                           @Value("${analysis.motion.strategy:optical-flow}") String strategyName) {
        this.sharpnessAnalyzer = sharpnessAnalyzer;
        this.informationGainEstimator = informationGainEstimator;
        this.motionDetectionStrategy = "pixel-diff".equalsIgnoreCase(strategyName)
                ? new PixelDiffStrategy(motionDetector)
                : new OpticalFlowStrategy(imageJProcessor, motionDetector);
        log.info("StackingAdvisor initialized with '{}' motion detection strategy",
                this.motionDetectionStrategy.name());
    }

    /**
     * Analyzes a frame and makes a continue/stop recommendation.
     */
    public AnalysisResult analyzeFrame(byte[] imageData,
                                       byte[] previousFrame,
                                       List<AnalysisResult> history,
                                       StackingParameters params) {
        double sharpnessScore = sharpnessAnalyzer.calculateSharpness(imageData);
        double sharpRegionPercentage = estimateSharpRegion(sharpnessScore);

        MotionDetector.MotionResult motionResult;
        if (params.motionDetectionEnabled() && previousFrame != null) {
            motionResult = motionDetectionStrategy.detect(imageData, previousFrame, 0.15);
        } else {
            motionResult = new MotionDetector.MotionResult(0.0, false, 0.15);
        }

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
        // Abort if motion detected
        if (motionResult.motionDetected()) {
            return false;
        }
        // Abort if information gain below threshold
        if (infoGain < params.abortThreshold()) {
            return false;
        }
        return true;
    }

    private double estimateSharpRegion(double sharpnessScore) {
        return Math.min(1.0, sharpnessScore * 1.2);
    }
}
